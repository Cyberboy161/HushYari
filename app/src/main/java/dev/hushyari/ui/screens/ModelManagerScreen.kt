package dev.hushyari.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.hushyari.llm.LlmProvider
import dev.hushyari.ui.viewmodel.SettingsViewModel

data class LocalModelInfo(
    val name: String,
    val displayName: String = "",
    val sizeBytes: Long = 0,
    val status: ModelStatus = ModelStatus.AVAILABLE,
    val progress: Float = 0f,
)

enum class ModelStatus {
    DOWNLOADED, AVAILABLE, DOWNLOADING, ERROR
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelManagerScreen(
    onNavigateBack: () -> Unit,
    settingsViewModel: SettingsViewModel = hiltViewModel(),
) {
    val uiState by settingsViewModel.uiState.collectAsState()

    val models = remember {
        listOf(
            LocalModelInfo(
                name = "gemma4_e2b",
                displayName = "Gemma 4 E2B",
                sizeBytes = 2_200_000_000,
                status = ModelStatus.AVAILABLE,
            ),
            LocalModelInfo(
                name = "gemma4_9b",
                displayName = "Gemma 4 9B",
                sizeBytes = 5_500_000_000,
                status = ModelStatus.AVAILABLE,
            ),
            LocalModelInfo(
                name = "llama3_8b",
                displayName = "Llama 3 8B",
                sizeBytes = 4_700_000_000,
                status = ModelStatus.AVAILABLE,
            ),
        )
    }

    var downloadingModel by remember { mutableStateOf<String?>(null) }
    var downloadProgress by remember { mutableStateOf(0f) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Model Manager") },
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
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Storage,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(32.dp),
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "Storage",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        Text(
                            text = "Free: 2.4 GB",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Local Models",
                style = MaterialTheme.typography.titleSmall,
            )

            Spacer(modifier = Modifier.height(8.dp))

            models.forEach { model ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = model.displayName,
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                text = "${model.name} — ${formatSize(model.sizeBytes)}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                text = when (model.status) {
                                    ModelStatus.DOWNLOADED -> "Downloaded"
                                    ModelStatus.AVAILABLE -> "Available"
                                    ModelStatus.DOWNLOADING -> "Downloading... ${(model.progress * 100).toInt()}%"
                                    ModelStatus.ERROR -> "Error"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = when (model.status) {
                                    ModelStatus.DOWNLOADED -> MaterialTheme.colorScheme.primary
                                    ModelStatus.AVAILABLE -> MaterialTheme.colorScheme.onSurfaceVariant
                                    ModelStatus.DOWNLOADING -> MaterialTheme.colorScheme.tertiary
                                    ModelStatus.ERROR -> MaterialTheme.colorScheme.error
                                },
                            )
                        }

                        when (model.status) {
                            ModelStatus.AVAILABLE -> {
                                Button(
                                    onClick = {
                                        downloadingModel = model.name
                                        downloadProgress = 0f
                                    },
                                ) {
                                    Icon(Icons.Default.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Download")
                                }
                            }
                            ModelStatus.DOWNLOADING -> {
                                CircularProgressIndicator(
                                    progress = { downloadProgress },
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                            ModelStatus.DOWNLOADED -> {
                                OutlinedButton(onClick = { }) {
                                    Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("Delete")
                                }
                            }
                            ModelStatus.ERROR -> {
                                Button(onClick = { }) {
                                    Text("Retry")
                                }
                            }
                        }
                    }

                    if (model.status == ModelStatus.DOWNLOADING) {
                        LinearProgressIndicator(
                            progress = { downloadProgress },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Cloud Providers",
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
                            Icon(
                                Icons.Default.Cloud,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Google Gemini", style = MaterialTheme.typography.bodyMedium)
                        }
                        Text(
                            text = if (uiState.apiKeys.any { it.provider == LlmProvider.GOOGLE && it.isMasked }) "Configured" else "Not configured",
                            style = MaterialTheme.typography.labelSmall,
                            color = if (uiState.apiKeys.any { it.provider == LlmProvider.GOOGLE && it.isMasked })
                                MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private fun formatSize(bytes: Long): String {
    return when {
        bytes >= 1_000_000_000 -> String.format("%.1f GB", bytes / 1_000_000_000.0)
        bytes >= 1_000_000 -> String.format("%.1f MB", bytes / 1_000_000.0)
        bytes >= 1_000 -> String.format("%.1f KB", bytes / 1_000.0)
        else -> "$bytes B"
    }
}
