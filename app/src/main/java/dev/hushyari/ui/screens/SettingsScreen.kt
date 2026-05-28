package dev.hushyari.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.hushyari.llm.LlmProvider
import dev.hushyari.ui.viewmodel.SettingsViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var showKeyInput by remember { mutableStateOf(false) }
    var currentKeyProvider by remember { mutableStateOf<LlmProvider?>(null) }
    var keyValue by remember { mutableStateOf("") }
    var keyVisible by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text(
                text = "API Configuration",
                style = MaterialTheme.typography.titleSmall,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    var providerExpanded by remember { mutableStateOf(false) }

                    Text(
                        text = "Provider",
                        style = MaterialTheme.typography.labelMedium,
                    )

                    Box {
                        OutlinedTextField(
                            value = uiState.selectedProvider.name,
                            onValueChange = {},
                            readOnly = true,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = false,
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        listOf(LlmProvider.GOOGLE, LlmProvider.OPENAI, LlmProvider.ANTHROPIC, LlmProvider.LOCAL).forEach { provider ->
                            OutlinedButton(
                                onClick = { viewModel.selectProvider(provider) },
                                modifier = Modifier.weight(1f),
                                colors = if (uiState.selectedProvider == provider)
                                    androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    )
                                else
                                    androidx.compose.material3.ButtonDefaults.outlinedButtonColors(),
                            ) {
                                Text(
                                    text = when (provider) {
                                        LlmProvider.GOOGLE -> "Gemini"
                                        LlmProvider.OPENAI -> "OpenAI"
                                        LlmProvider.ANTHROPIC -> "Claude"
                                        LlmProvider.LOCAL -> "Local"
                                        else -> provider.name
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "API Keys",
                        style = MaterialTheme.typography.labelMedium,
                    )

                    uiState.apiKeys.forEach { apiKeyState ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.Key,
                                contentDescription = null,
                                tint = if (apiKeyState.isMasked) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )

                            Column(
                                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
                            ) {
                                Text(
                                    text = apiKeyState.provider.name,
                                    style = MaterialTheme.typography.labelMedium,
                                )
                                if (apiKeyState.testResult != null) {
                                    Text(
                                        text = apiKeyState.testResult!!,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (apiKeyState.testResult?.startsWith("OK") == true)
                                            MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.error,
                                    )
                                }
                            }

                            if (apiKeyState.isMasked) {
                                TextButton(onClick = { viewModel.testApiKey(apiKeyState.provider) }) {
                                    Text("Test")
                                }
                            }

                            OutlinedButton(
                                onClick = {
                                    currentKeyProvider = apiKeyState.provider
                                    keyValue = ""
                                    showKeyInput = true
                                },
                            ) {
                                Text(if (apiKeyState.isMasked) "Change" else "Set")
                            }

                            if (apiKeyState.isMasked) {
                                TextButton(onClick = {
                                    viewModel.deleteApiKey(apiKeyState.provider)
                                }) {
                                    Text("Delete", color = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Model Settings",
                style = MaterialTheme.typography.titleSmall,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = uiState.modelName,
                        onValueChange = { viewModel.setModelName(it) },
                        label = { Text("Model Name") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("Use Local Model", style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = uiState.useLocalModel,
                            onCheckedChange = { viewModel.setUseLocalModel(it) },
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Performance",
                style = MaterialTheme.typography.titleSmall,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    var captureExpanded by remember { mutableStateOf(false) }

                    Text(
                        text = "Capture Mode",
                        style = MaterialTheme.typography.labelMedium,
                    )

                    Box {
                        OutlinedTextField(
                            value = uiState.captureMode,
                            onValueChange = {},
                            readOnly = true,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = false,
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        listOf("FAST", "VISUAL", "FULL").forEach { mode ->
                            OutlinedButton(
                                onClick = { viewModel.setCaptureMode(mode) },
                                modifier = Modifier.weight(1f),
                                colors = if (uiState.captureMode == mode)
                                    androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                                    )
                                else
                                    androidx.compose.material3.ButtonDefaults.outlinedButtonColors(),
                            ) {
                                Text(mode, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "LLM Escalation Threshold: ${uiState.llmEscalationThreshold}",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Slider(
                        value = uiState.llmEscalationThreshold.toFloat(),
                        onValueChange = { viewModel.setLlmEscalationThreshold(it.toInt()) },
                        valueRange = 1f..10f,
                        steps = 8,
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Safety & Limits",
                style = MaterialTheme.typography.titleSmall,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("External Automation", style = MaterialTheme.typography.bodyMedium)
                        Switch(
                            checked = uiState.externalAutomationEnabled,
                            onCheckedChange = { viewModel.toggleExternalAutomation() },
                        )
                    }
                    Text(
                        text = "Allow Tasker/MacroDroid to control HushYari",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Play Time Limit: ${if (uiState.playTimeLimitMinutes > 0) "${uiState.playTimeLimitMinutes} min" else "No limit"}",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Slider(
                        value = uiState.playTimeLimitMinutes.toFloat(),
                        onValueChange = { viewModel.setPlayTimeLimit(it.toInt()) },
                        valueRange = 0f..480f,
                        steps = 16,
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "Daily Spending Limit: ${uiState.dailySpendingLimitCents} cents",
                        style = MaterialTheme.typography.labelMedium,
                    )
                    Slider(
                        value = uiState.dailySpendingLimitCents.toFloat(),
                        onValueChange = { viewModel.setDailySpendingLimit(it.toInt()) },
                        valueRange = 0f..1000f,
                        steps = 20,
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Permissions",
                style = MaterialTheme.typography.titleSmall,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Lock, contentDescription = null, modifier = Modifier.size(20.dp))
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text("Accessibility Service", style = MaterialTheme.typography.bodyMedium)
                                Text(
                                    text = if (uiState.accessibilityGranted) "Granted" else "Not granted",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (uiState.accessibilityGranted) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.error,
                                )
                            }
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "About",
                style = MaterialTheme.typography.titleSmall,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "HushYari v0.1.0",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Text(
                        text = "AI-powered game automation for Android",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = "Built with Jetpack Compose, Kotlin, and LLMs",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }

    if (showKeyInput && currentKeyProvider != null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { showKeyInput = false },
            title = { Text("API Key — ${currentKeyProvider!!.name}") },
            text = {
                Column {
                    OutlinedTextField(
                        value = keyValue,
                        onValueChange = { keyValue = it },
                        label = { Text("API Key") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = if (keyVisible) VisualTransformation.None
                        else PasswordVisualTransformation(),
                        trailingIcon = {
                            IconButton(onClick = { keyVisible = !keyVisible }) {
                                Icon(
                                    if (keyVisible) Icons.Default.VisibilityOff
                                    else Icons.Default.Visibility,
                                    contentDescription = null,
                                )
                            }
                        },
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    viewModel.setApiKey(currentKeyProvider!!, keyValue)
                    showKeyInput = false
                    keyValue = ""
                }) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showKeyInput = false
                    keyValue = ""
                }) {
                    Text("Cancel")
                }
            },
        )
    }
}
