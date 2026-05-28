package dev.hushyari.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Monitors notifications from the game package and detects in-game events
 * such as attack alerts, upgrade completions, and resource-full notifications.
 *
 * Emits structured [GameNotificationEvent]s via [SharedFlow] for consumption
 * by the agent system (e.g., [dev.hushyari.agent.Watcher] or [dev.hushyari.agent.Notetaker]).
 *
 * **Mechanics:**
 * - PokeClaw: Notification-based event detection — allows the agent to react
 *   to server-side game events without polling the screen.
 */
@AndroidEntryPoint
class NotificationMonitorService : NotificationListenerService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val _events = MutableSharedFlow<GameNotificationEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<GameNotificationEvent> = _events.asSharedFlow()

    /**
     * The monitored game package name. Set externally before or during agent setup.
     */
    @Volatile
    var targetPackageName: String = ""

    /**
     * Whether this service is actively monitoring.
     */
    @Volatile
    var isMonitoring: Boolean = false

    companion object {
        /** Event categories detected from notification text patterns. */
        private val EVENT_PATTERNS = mapOf(
            "attack" to listOf("under attack", "being attacked", "attack alert", "enemy attack", "scout detected"),
            "upgrade_complete" to listOf("upgrade complete", "upgrade finished", "level up", "research complete", "research finished"),
            "resource_full" to listOf("full", "storage full", "inventory full", "warehouse full", "capacity"),
            "building_complete" to listOf("building complete", "construction complete", "construction finished", "building finished"),
            "troops_ready" to listOf("troops ready", "army ready", "training complete", "reinforcements arrived"),
            "event_started" to listOf("event started", "event begins", "special event", "limited time event"),
            "reward_available" to listOf("reward available", "claim reward", "daily reward", "reward ready"),
        )
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        isMonitoring = true
        Timber.d("NotificationMonitorService connected, monitoring: $targetPackageName")
    }

    override fun onListenerDisconnected() {
        isMonitoring = false
        Timber.d("NotificationMonitorService disconnected")
        super.onListenerDisconnected()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || !isMonitoring) return

        val packageName = sbn.packageName
        if (targetPackageName.isNotEmpty() && packageName != targetPackageName) return

        val extras = sbn.notification.extras
        val title = extras.getString(android.app.Notification.EXTRA_TITLE) ?: ""
        val text = extras.getString(android.app.Notification.EXTRA_TEXT) ?: ""
        val subText = extras.getString(android.app.Notification.EXTRA_SUB_TEXT) ?: ""
        val combined = "$title $text $subText"

        val category = detectEventCategory(combined)
        val event = GameNotificationEvent(
            packageName = packageName,
            title = title,
            text = text,
            category = category,
            timestamp = sbn.postTime,
            notificationId = sbn.id,
        )

        scope.launch {
            _events.emit(event)
        }

        Timber.d("Notification: [$category] $title — $text")
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // Notifications dismissed by the game are tracked for state awareness
        if (sbn != null && (targetPackageName.isEmpty() || sbn.packageName == targetPackageName)) {
            val event = GameNotificationEvent(
                packageName = sbn.packageName,
                title = "Dismissed",
                text = "",
                category = "dismissed",
                timestamp = System.currentTimeMillis(),
                notificationId = sbn.id,
            )
            scope.launch {
                _events.emit(event)
            }
        }
    }

    /**
     * Detect the category of a game event from its notification text.
     */
    private fun detectEventCategory(combined: String): String {
        val lower = combined.lowercase()
        for ((category, patterns) in EVENT_PATTERNS) {
            for (pattern in patterns) {
                if (lower.contains(pattern)) {
                    return category
                }
            }
        }
        return "general"
    }
}

/**
 * Structured notification event emitted by [NotificationMonitorService].
 *
 * **Mechanics:**
 * - PokeClaw: Typed notification events enabling agent reaction to push-driven game events.
 */
data class GameNotificationEvent(
    val packageName: String,
    val title: String,
    val text: String,
    val category: String,
    val timestamp: Long,
    val notificationId: Int,
)
