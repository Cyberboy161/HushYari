package dev.hushyari.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.hushyari.data.model.AgentEvent
import dev.hushyari.data.model.LogLevel
import dev.hushyari.ui.theme.LogDebug
import dev.hushyari.ui.theme.LogError
import dev.hushyari.ui.theme.LogInfo
import dev.hushyari.ui.theme.LogWarning
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogEntry(
    val timestamp: Long,
    val level: LogLevel,
    val message: String,
)

@Composable
fun LogViewer(
    entries: List<LogEntry>,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    autoScroll: Boolean = true,
) {
    var filterLevel by remember { mutableStateOf<LogLevel?>(null) }
    var filterExpanded by remember { mutableStateOf(false) }

    if (autoScroll && entries.isNotEmpty()) {
        LaunchedEffect(entries.size) {
            listState.animateScrollToItem(entries.size - 1)
        }
    }

    Box(modifier = modifier) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Agent Log",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )

            Box {
                IconButton(onClick = { filterExpanded = true }) {
                    Icon(
                        Icons.Default.FilterList,
                        contentDescription = "Filter",
                        tint = if (filterLevel != null) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                DropdownMenu(
                    expanded = filterExpanded,
                    onDismissRequest = { filterExpanded = false },
                ) {
                    DropdownMenuItem(
                        text = { Text("All levels") },
                        onClick = {
                            filterLevel = null
                            filterExpanded = false
                        },
                    )
                    LogLevel.entries.forEach { level ->
                        DropdownMenuItem(
                            text = { Text(level.name) },
                            onClick = {
                                filterLevel = level
                                filterExpanded = false
                            },
                        )
                    }
                }
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 36.dp),
        ) {
            val filtered = if (filterLevel != null) {
                entries.filter { it.level == filterLevel }
            } else {
                entries
            }

            items(filtered, key = { "${it.timestamp}_${it.message.hashCode()}" }) { entry ->
                LogEntryRow(entry)
            }
        }
    }
}

@Composable
private fun LogEntryRow(entry: LogEntry) {
    val timeFormat = remember { SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault()) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            text = timeFormat.format(Date(entry.timestamp)),
            style = MaterialTheme.typography.labelSmall.copy(
                fontFamily = FontFamily.Monospace,
            ),
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
        )

        Spacer(modifier = Modifier.width(8.dp))

        Box(
            modifier = Modifier
                .size(8.dp)
                .padding(top = 4.dp)
                .clip(CircleShape)
                .background(levelColor(entry.level)),
        )

        Spacer(modifier = Modifier.width(8.dp))

        Text(
            text = entry.message,
            style = MaterialTheme.typography.bodySmall.copy(
                fontFamily = FontFamily.Monospace,
            ),
            color = levelColor(entry.level),
        )
    }
}

private fun levelColor(level: LogLevel) = when (level) {
    LogLevel.DEBUG -> LogDebug
    LogLevel.INFO -> LogInfo
    LogLevel.WARNING -> LogWarning
    LogLevel.ERROR -> LogError
}
