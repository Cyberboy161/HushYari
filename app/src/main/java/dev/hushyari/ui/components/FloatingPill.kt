package dev.hushyari.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.hushyari.ui.theme.AgentRunning
import dev.hushyari.ui.theme.AgentStopped
import dev.hushyari.ui.theme.AgentPaused
import dev.hushyari.ui.theme.AgentThinking

enum class AgentStatus { RUNNING, PAUSED, STOPPED, THINKING }

@Composable
fun FloatingPill(
    status: AgentStatus,
    progress: Float = 0f,
    stepCount: Int = 0,
    elapsedSeconds: Long = 0,
    expanded: Boolean = false,
    onToggleExpand: () -> Unit = {},
    onStop: () -> Unit = {},
    onPause: () -> Unit = {},
    onResume: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val statusColor by animateColorAsState(
        targetValue = when (status) {
            AgentStatus.RUNNING -> AgentRunning
            AgentStatus.PAUSED -> AgentPaused
            AgentStatus.STOPPED -> AgentStopped
            AgentStatus.THINKING -> AgentThinking
        },
        animationSpec = tween(400),
    )

    val progressAngle by animateFloatAsState(
        targetValue = progress * 360f,
        animationSpec = tween(300),
    )

    Box(
        modifier = modifier
            .size(if (expanded) 200.dp else 56.dp)
            .shadow(8.dp, CircleShape)
            .clip(if (expanded) RoundedCornerShape(28.dp) else CircleShape)
            .background(MaterialTheme.colorScheme.surface)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onToggleExpand() },
                    onLongPress = { onStop() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        if (expanded) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(16.dp),
            ) {
                Text(
                    text = when (status) {
                        AgentStatus.RUNNING -> "Running"
                        AgentStatus.PAUSED -> "Paused"
                        AgentStatus.STOPPED -> "Idle"
                        AgentStatus.THINKING -> "Thinking"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = statusColor,
                )

                Text(
                    text = "Step $stepCount",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Text(
                    text = formatElapsed(elapsedSeconds),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    when (status) {
                        AgentStatus.RUNNING -> {
                            Icon(
                                Icons.Default.Pause,
                                contentDescription = "Pause",
                                modifier = Modifier
                                    .size(28.dp)
                                    .clickable { onPause() },
                                tint = AgentPaused,
                            )
                        }
                        AgentStatus.PAUSED -> {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = "Resume",
                                modifier = Modifier
                                    .size(28.dp)
                                    .clickable { onResume() },
                                tint = AgentRunning,
                            )
                        }
                        AgentStatus.STOPPED -> {
                            Icon(
                                Icons.Default.PlayArrow,
                                contentDescription = "Start",
                                modifier = Modifier
                                    .size(28.dp)
                                    .clickable { onResume() },
                                tint = AgentRunning,
                            )
                        }
                        AgentStatus.THINKING -> {}
                    }

                    Icon(
                        Icons.Default.Stop,
                        contentDescription = "Stop",
                        modifier = Modifier
                            .size(28.dp)
                            .clickable { onStop() },
                        tint = AgentStopped,
                    )
                }
            }
        } else {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokeWidth = 4.dp.toPx()
                val radius = (size.minDimension / 2) - strokeWidth

                drawArc(
                    color = statusColor,
                    startAngle = -90f,
                    sweepAngle = progressAngle,
                    useCenter = false,
                    topLeft = Offset(strokeWidth, strokeWidth),
                    size = Size(radius * 2, radius * 2),
                    style = Stroke(width = strokeWidth),
                )

                drawCircle(
                    color = statusColor,
                    radius = radius * 0.35f,
                )
            }
        }
    }
}

private fun formatElapsed(totalSeconds: Long): String {
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}
