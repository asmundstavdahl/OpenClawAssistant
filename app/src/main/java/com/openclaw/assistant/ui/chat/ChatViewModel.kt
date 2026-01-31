package com.openclaw.assistant.ui.chat

import android.app.Application
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.openclaw.assistant.api.OpenClawClient
import com.openclaw.assistant.data.SettingsRepository
import com.openclaw.assistant.speech.SpeechRecognizerManager
import com.openclaw.assistant.speech.SpeechResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.Locale
import java.util.UUID
import kotlin.coroutines.resume

private const val TAG = "ChatViewModel"

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val isUser: Boolean,
    val timestamp: Long = System.currentTimeMillis()
)

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isListening: Boolean = false,
    val isThinking: Boolean = false,
    val isSpeaking: Boolean = false,
    val error: String? = null,
    val partialText: String = "" // For real-time speech transcription
)

class ChatViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private val settings = SettingsRepository.getInstance(application)
    private val apiClient = OpenClawClient()
    private val speechManager = SpeechRecognizerManager(application)
    
    // TTS will be set from Activity
    private var tts: TextToSpeech? = null
    private var isTTSReady = false

    /**
     * ActivityからTTSを設定する
     */
    fun setTTS(textToSpeech: TextToSpeech) {
        Log.e(TAG, "setTTS called")
        tts = textToSpeech
        isTTSReady = true
    }

    fun sendMessage(text: String) {
        if (text.isBlank()) return

        // Add user message
        addMessage(text, isUser = true)
        _uiState.update { it.copy(isThinking = true) }

        viewModelScope.launch {
            try {
                val result = apiClient.sendMessage(
                    webhookUrl = settings.webhookUrl,
                    message = text,
                    sessionId = settings.sessionId,
                    authToken = settings.authToken.takeIf { it.isNotBlank() }
                )

                result.fold(
                    onSuccess = { response ->
                        val responseText = response.getResponseText() ?: "No response"
                        addMessage(responseText, isUser = false)
                        _uiState.update { it.copy(isThinking = false) }
                        speak(responseText)
                    },
                    onFailure = { error ->
                        _uiState.update { it.copy(isThinking = false, error = error.message) }
                        addMessage("Error: ${error.message}", isUser = false)
                    }
                )
            } catch (e: Exception) {
                _uiState.update { it.copy(isThinking = false, error = e.message) }
            }
        }
    }

    private var lastInputWasVoice = false
    private var listeningJob: kotlinx.coroutines.Job? = null

    fun startListening() {
        Log.e(TAG, "startListening() called, isListening=${_uiState.value.isListening}")
        if (_uiState.value.isListening) return

        lastInputWasVoice = true // Mark as voice input

        // Cancel existing and wait for cleanup
        listeningJob?.cancel()

        // Stop TTS if speaking
        tts?.stop()

        // Wait for TTS resource release before starting mic
        viewModelScope.launch {
            kotlinx.coroutines.delay(500) // Wait for TTS to release resources

            Log.e(TAG, "Setting isListening = true")
            _uiState.update { it.copy(isListening = true, partialText = "") }

            listeningJob = viewModelScope.launch {
                Log.e(TAG, "Starting speechManager.startListening()")
                speechManager.startListening("ja-JP").collect { result ->
                    Log.e(TAG, "SpeechResult: $result")
                    when (result) {
                        is SpeechResult.PartialResult -> {
                            _uiState.update { it.copy(partialText = result.text) }
                        }
                        is SpeechResult.Result -> {
                            _uiState.update { it.copy(isListening = false, partialText = "") }
                            sendMessage(result.text)
                        }
                        is SpeechResult.Error -> {
                            _uiState.update { it.copy(isListening = false, error = result.message) }
                            lastInputWasVoice = false
                        }
                        else -> {}
                    }
                }
            }
        }
    }

    fun stopListening() {
        lastInputWasVoice = false // User manually stopped
        listeningJob?.cancel()
        _uiState.update { it.copy(isListening = false) }
    }

    private fun speak(text: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isSpeaking = true) }
            
            val success = if (isTTSReady && tts != null) {
                speakWithTTS(text)
            } else {
                Log.e(TAG, "TTS not ready, skipping speech")
                false
            }
            
            _uiState.update { it.copy(isSpeaking = false) }

            // If it was a voice conversation, continue listening
            if (success && lastInputWasVoice) {
                // Explicit cleanup and wait for TTS to fully release audio focus
                speechManager.destroy()
                kotlinx.coroutines.delay(1000) // Increased from 800ms for more reliable cleanup

                // Restart listening
                startListening()
            }
        }
    }

    private suspend fun speakWithTTS(text: String): Boolean = suspendCancellableCoroutine { continuation ->
        val utteranceId = UUID.randomUUID().toString()
        
        val listener = object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            
            override fun onDone(utteranceId: String?) {
                if (continuation.isActive) {
                    continuation.resume(true)
                }
            }
            
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                if (continuation.isActive) {
                    continuation.resume(false)
                }
            }
            
            override fun onError(utteranceId: String?, errorCode: Int) {
                Log.e(TAG, "TTS error: $errorCode")
                if (continuation.isActive) {
                    continuation.resume(false)
                }
            }
        }
        
        tts?.setOnUtteranceProgressListener(listener)
        val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        Log.e(TAG, "TTS speak result: $result")
        
        if (result != TextToSpeech.SUCCESS) {
            Log.e(TAG, "TTS speak failed immediately")
            if (continuation.isActive) {
                continuation.resume(false)
            }
        }
        
        continuation.invokeOnCancellation {
            tts?.stop()
        }
    }
    
    fun stopSpeaking() {
        lastInputWasVoice = false // Stop loop if manually stopped
        tts?.stop()
        _uiState.update { it.copy(isSpeaking = false) }
    }

    private fun addMessage(text: String, isUser: Boolean) {
        val message = ChatMessage(text = text, isUser = isUser)
        _uiState.update { state ->
            state.copy(messages = state.messages + message)
        }
    }

    override fun onCleared() {
        super.onCleared()
        speechManager.destroy()
        // Don't shutdown TTS here - Activity owns it
    }
}
