package com.openclaw.assistant

import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.openclaw.assistant.api.OpenClawClient
import com.openclaw.assistant.data.SettingsRepository
import com.openclaw.assistant.ui.theme.OpenClawAssistantTheme
import kotlinx.coroutines.launch

class SettingsActivity : ComponentActivity() {

    private lateinit var settings: SettingsRepository

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        settings = SettingsRepository.getInstance(this)

        setContent {
            OpenClawAssistantTheme {
                SettingsScreen(
                    settings = settings,
                    onSave = { 
                        Toast.makeText(this, "Saved", Toast.LENGTH_SHORT).show()
                        finish()
                    },
                    onBack = { finish() }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: SettingsRepository,
    onSave: () -> Unit,
    onBack: () -> Unit
) {
    var webhookUrl by remember { mutableStateOf(settings.webhookUrl) }
    var authToken by remember { mutableStateOf(settings.authToken) }
    var ttsEnabled by remember { mutableStateOf(settings.ttsEnabled) }
    var continuousMode by remember { mutableStateOf(settings.continuousMode) }
    
    var showAuthToken by remember { mutableStateOf(false) }
    
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val apiClient = remember { OpenClawClient() }
    
    var isTesting by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<TestResult?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    TextButton(
                        onClick = {
                            settings.webhookUrl = webhookUrl
                            settings.authToken = authToken
                            settings.ttsEnabled = ttsEnabled
                            settings.continuousMode = continuousMode
                            onSave()
                        },
                        enabled = webhookUrl.isNotBlank() && !isTesting
                    ) {
                        Text("Save")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            // Webhook URL (required)
            OutlinedTextField(
                value = webhookUrl,
                onValueChange = { 
                    webhookUrl = it
                    testResult = null
                },
                label = { Text("Webhook URL *") },
                placeholder = { Text("https://your-server/hooks/voice") },
                leadingIcon = { Icon(Icons.Default.Link, contentDescription = null) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                isError = webhookUrl.isBlank(),
                supportingText = {
                    if (webhookUrl.isBlank()) {
                        Text("Required", color = MaterialTheme.colorScheme.error)
                    }
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Auth Token (optional)
            OutlinedTextField(
                value = authToken,
                onValueChange = { 
                    authToken = it
                    testResult = null
                },
                label = { Text("Auth Token") },
                placeholder = { Text("Optional") },
                leadingIcon = { Icon(Icons.Default.Key, contentDescription = null) },
                trailingIcon = {
                    IconButton(onClick = { showAuthToken = !showAuthToken }) {
                        Icon(
                            if (showAuthToken) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = "Toggle visibility"
                        )
                    }
                },
                visualTransformation = if (showAuthToken) VisualTransformation.None else PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Voice Settings
            Text(
                text = "Voice Settings",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(bottom = 8.dp)
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Voice Output", style = MaterialTheme.typography.bodyLarge)
                            Text("Read AI responses aloud", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                        Switch(checked = ttsEnabled, onCheckedChange = { ttsEnabled = it })
                    }
                    
                    HorizontalDivider(modifier = Modifier.padding(vertical = 12.dp), thickness = 0.5.dp)
                    
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Continuous Conversation", style = MaterialTheme.typography.bodyLarge)
                            Text("Auto-start mic after AI speaks", style = MaterialTheme.typography.bodySmall, color = Color.Gray)
                        }
                        Switch(checked = continuousMode, onCheckedChange = { continuousMode = it })
                    }
                }
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Test Connection Button (v2 layout with improved feedback)
            Button(
                onClick = {
                    if (webhookUrl.isBlank()) return@Button
                    scope.launch {
                        try {
                            isTesting = true
                            testResult = null
                            val result = apiClient.testConnection(webhookUrl, authToken)
                            result.fold(
                                onSuccess = {
                                    testResult = TestResult(success = true, message = "Connection Successful!")
                                    // Also mark as verified in repository
                                    settings.webhookUrl = webhookUrl
                                    settings.authToken = authToken
                                    settings.isVerified = true
                                },
                                onFailure = {
                                    testResult = TestResult(success = false, message = "Connection Failed: ${it.message}")
                                    Toast.makeText(context, "Error: ${it.message}", Toast.LENGTH_LONG).show()
                                }
                            )
                        } catch (e: Exception) {
                            testResult = TestResult(success = false, message = "Error: ${e.message}")
                        } finally {
                            isTesting = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = when {
                        testResult?.success == true -> Color(0xFF4CAF50)
                        testResult?.success == false -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.primary
                    }
                ),
                enabled = webhookUrl.isNotBlank() && !isTesting
            ) {
                if (isTesting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Testing...")
                } else {
                    val icon = when {
                        testResult == null -> Icons.Default.NetworkCheck
                        testResult?.success == true -> Icons.Default.Check
                        else -> Icons.Default.Error
                    }
                    Icon(icon, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(testResult?.message ?: "Test Connection")
                }
            }

            // Detailed Test Result Card
            testResult?.let { result ->
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (result.success) Color(0xFFE8F5E9) else Color(0xFFFFEBEE)
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = if (result.success) Icons.Default.CheckCircle else Icons.Default.Error,
                            contentDescription = null,
                            tint = if (result.success) Color(0xFF4CAF50) else Color(0xFFF44336)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = result.message,
                            fontSize = 14.sp,
                            color = if (result.success) Color(0xFF2E7D32) else Color(0xFFC62828)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            // Tips
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Tips",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "• Enter your OpenClaw webhook URL\n" +
                               "• Auth Token is optional (depends on server config)\n" +
                               "• Use Test Connection to verify settings",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                        lineHeight = 18.sp
                    )
                }
            }
        }
    }
}

data class TestResult(
    val success: Boolean,
    val message: String
)
