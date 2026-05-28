package dev.hushyari.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.hushyari.ui.theme.AgentRunning
import dev.hushyari.ui.theme.AgentStopped
import dev.hushyari.ui.theme.AgentPaused
import dev.hushyari.ui.theme.AgentThinking

enum class AgentBarStatus { RUNNING, PAUSED, STOPPED, THINKING }

@Composable
fun StatusBar(
    status: AgentBarStatus,
    label: String = "",
    stepCount: Int = 0,
    modifier: Modifier = Modifier,
) {
    val statusColor by animateColorAsState(
        targetValue = when (status) {
            AgentBarStatus.RUNNING -> AgentRunning
            AgentBarStatus.PAUSED -> AgentPaused
            AgentBarStatus.STOPPED -> AgentStopped
            AgentBarStatus.THINKING -> AgentThinking
        },
        animationSpec = tween(400),
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(48.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(statusColor),
        )

        Text(
            text = when (status) {
                AgentBarStatus.RUNNING -> "Agent Running"
                AgentBarStatus.PAUSED -> "Agent Paused"
                AgentBarStatus.STOPPED -> "Agent Stopped"
                AgentBarStatus.THINKING -> "Agent Thinking"
            },
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
        )

        if (label.isNotEmpty()) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (stepCount > 0) {
            Text(
                text = "Step $stepCount",
                style = MaterialTheme.typography.labelSmall,
                color = statusColor,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}
