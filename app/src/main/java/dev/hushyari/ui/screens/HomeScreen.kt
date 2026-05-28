package dev.hushyari.ui.screens

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SportsEsports
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import dev.hushyari.data.repository.AppInfo
import dev.hushyari.ui.components.GameSelector
import dev.hushyari.ui.components.PermissionGate
import dev.hushyari.ui.components.PermissionState
import dev.hushyari.ui.components.SkillCard
import dev.hushyari.ui.theme.PermissionDenied
import dev.hushyari.ui.theme.PermissionGranted
import dev.hushyari.ui.theme.PermissionPartial
import dev.hushyari.ui.viewmodel.HomeViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToGame: (String) -> Unit,
    onNavigateToSkills: () -> Unit,
    onNavigateToHistory: () -> Unit,
    onNavigateToSettings: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()
    var showGameSelector by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "HushYari",
                        fontWeight = FontWeight.Bold,
                    )
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
                text = "Welcome to HushYari",
                style = MaterialTheme.typography.headlineMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Text(
                text = "Your AI game assistant",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Spacer(modifier = Modifier.height(20.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showGameSelector = true },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                ),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.SportsEsports,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(48.dp),
                    )
                    Spacer(modifier = Modifier.width(16.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = uiState.selectedGame?.appName ?: "Select a game to play",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                        if (uiState.selectedGame != null) {
                            Text(
                                text = uiState.selectedGame!!.packageName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (uiState.agentRunning) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                    ),
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                    ) {
                        Text(
                            text = "Agent Active",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                        Text(
                            text = "Status: ${uiState.agentStatus} | Step ${uiState.stepCount}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            OutlinedButton(onClick = { viewModel.pauseAgent() }) {
                                Text("Pause")
                            }
                            Button(onClick = { viewModel.stopAgent() }) {
                                Icon(Icons.Default.Stop, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("Stop")
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (!uiState.agentRunning) {
                    Button(
                        onClick = {
                            viewModel.startAgent()
                            uiState.selectedGame?.packageName?.let { onNavigateToGame(it) }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = uiState.selectedGame != null,
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Start Agent")
                    }
                }

                OutlinedButton(
                    onClick = { showGameSelector = true },
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Select Game")
                }
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "Permissions",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PermissionDot(
                    label = "Accessibility",
                    granted = uiState.accessibilityGranted,
                )
                PermissionDot(
                    label = "Notifications",
                    granted = uiState.notificationGranted,
                )
                PermissionDot(
                    label = "Overlay",
                    granted = uiState.overlayGranted,
                )
            }

            val missingPermissions = buildList {
                add(PermissionState(
                    name = "Accessibility Service",
                    description = "Needed to read game screens and perform taps",
                    isGranted = uiState.accessibilityGranted,
                    icon = Icons.Default.Accessibility,
                    settingsAction = "android.settings.ACCESSIBILITY_SETTINGS",
                ))
                add(PermissionState(
                    name = "Notifications",
                    description = "Needed to monitor game events and alerts",
                    isGranted = uiState.notificationGranted,
                    icon = Icons.Default.Notifications,
                    settingsAction = "android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS",
                ))
                add(PermissionState(
                    name = "Overlay",
                    description = "Needed to show floating controls during gameplay",
                    isGranted = uiState.overlayGranted,
                    icon = Icons.Default.Cast,
                    settingsAction = "android.settings.action.MANAGE_OVERLAY_PERMISSION",
                ))
            }

            if (missingPermissions.any { !it.isGranted }) {
                Spacer(modifier = Modifier.height(8.dp))
                PermissionGate(
                    permissions = missingPermissions,
                    onDismiss = { viewModel.checkPermissions() },
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = "Quick Actions",
                style = MaterialTheme.typography.titleSmall,
            )

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                QuickActionCard(
                    label = "Skills",
                    modifier = Modifier.weight(1f),
                    onClick = onNavigateToSkills,
                )
                QuickActionCard(
                    label = "History",
                    modifier = Modifier.weight(1f),
                    onClick = onNavigateToHistory,
                )
                QuickActionCard(
                    label = "Settings",
                    modifier = Modifier.weight(1f),
                    onClick = onNavigateToSettings,
                )
            }
        }
    }

    if (showGameSelector) {
        val density = LocalDensity.current
        val sheetState = remember { androidx.compose.material3.SheetState(skipPartiallyExpanded = false, density = density) }
        GameSelector(
            games = uiState.installedGames,
            selectedPackage = uiState.selectedGame?.packageName,
            onGameSelected = { game ->
                viewModel.selectGame(game)
                showGameSelector = false
            },
            onDismiss = { showGameSelector = false },
            sheetState = sheetState,
        )
    }
}

@Composable
private fun PermissionDot(
    label: String,
    granted: Boolean,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .clip(CircleShape)
                .background(
                    if (granted) PermissionGranted else PermissionDenied,
                    CircleShape,
                ),
        )

        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

@Composable
private fun QuickActionCard(
    label: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit = {},
) {
    Card(
        modifier = modifier.clickable { onClick() },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                textAlign = TextAlign.Center,
            )
        }
    }
}
