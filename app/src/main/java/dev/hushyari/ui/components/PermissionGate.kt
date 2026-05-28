package dev.hushyari.ui.components

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Accessibility
import androidx.compose.material.icons.filled.Cast
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import dev.hushyari.ui.theme.PermissionDenied
import dev.hushyari.ui.theme.PermissionGranted
import dev.hushyari.ui.theme.PermissionPartial

data class PermissionState(
    val name: String,
    val description: String,
    val isGranted: Boolean,
    val icon: ImageVector,
    val settingsAction: String? = null,
)

@Composable
fun PermissionGate(
    permissions: List<PermissionState>,
    onDismiss: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val missingPermissions = permissions.filter { !it.isGranted }

    if (missingPermissions.isEmpty()) return

    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                )
                Text(
                    text = "Permissions Required",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            missingPermissions.forEach { perm ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        imageVector = perm.icon,
                        contentDescription = null,
                        tint = PermissionDenied,
                        modifier = Modifier.size(24.dp),
                    )

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .padding(start = 12.dp),
                    ) {
                        Text(
                            text = perm.name,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                        Text(
                            text = perm.description,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.7f),
                        )
                    }

                    if (perm.settingsAction != null) {
                        OutlinedButton(
                            onClick = {
                                val intent = Intent(perm.settingsAction).apply {
                                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                }
                                context.startActivity(intent)
                            },
                            modifier = Modifier.padding(start = 8.dp),
                        ) {
                            Text("Open", style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                TextButton(onClick = onDismiss) {
                    Text("Dismiss")
                }
                Button(onClick = {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                }) {
                    Text("Open Settings")
                }
            }
        }
    }
}

private val accessibilityPermissions = listOf(
    PermissionState(
        name = "Accessibility Service",
        description = "Needed to read game screens and perform taps",
        isGranted = false,
        icon = Icons.Default.Accessibility,
        settingsAction = Settings.ACTION_ACCESSIBILITY_SETTINGS,
    ),
    PermissionState(
        name = "Notifications",
        description = "Needed to monitor game events and alerts",
        isGranted = false,
        icon = Icons.Default.Notifications,
        settingsAction = Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS,
    ),
    PermissionState(
        name = "Overlay",
        description = "Needed to show floating controls during gameplay",
        isGranted = false,
        icon = Icons.Default.Cast,
        settingsAction = Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
    ),
)
