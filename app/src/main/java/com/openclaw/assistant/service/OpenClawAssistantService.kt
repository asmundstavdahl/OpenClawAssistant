package com.openclaw.assistant.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.util.Log

/**
 * Voice Interaction Service
 * ホームボタン長押しでシステムアシスタントとして起動
 */
class OpenClawAssistantService : VoiceInteractionService() {

    companion object {
        private const val TAG = "OpenClawAssistantSvc"
        const val ACTION_DEBUG_SHOW_SESSION = "com.openclaw.assistant.DEBUG_SHOW_SESSION"
    }

    private val debugReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            Log.d(TAG, "Debug receiver triggered: ${intent?.action}")
            if (intent?.action == ACTION_DEBUG_SHOW_SESSION) {
                try {
                    showSession(Bundle(), 0)
                    Log.d(TAG, "showSession() called via debug trigger")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to call showSession via debug trigger", e)
                }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "VoiceInteractionService onCreate")
        val filter = IntentFilter(ACTION_DEBUG_SHOW_SESSION)
        registerReceiver(debugReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
    }

    override fun onReady() {
        super.onReady()
        Log.d(TAG, "VoiceInteractionService onReady")
    }

    override fun onShutdown() {
        super.onShutdown()
        Log.d(TAG, "VoiceInteractionService onShutdown")
        unregisterReceiver(debugReceiver)
    }
}

/**
 * Voice Interaction Session Service
 */
class OpenClawAssistantSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession {
        return OpenClawSession(this)
    }
}
