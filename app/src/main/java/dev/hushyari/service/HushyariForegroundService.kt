package dev.hushyari.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import dev.hushyari.HushyariApp
import dev.hushyari.R
import dev.hushyari.agent.AgentLoop
import timber.log.Timber
import javax.inject.Inject

/**
 * Foreground service that keeps the agent alive with a persistent notification.
 * Shows "HushYari is playing..." with start/stop controls.
 *
 * Creates the notification channel on create and holds a reference to [AgentLoop]
 * so the agent process survives even when the UI is killed.
 *
 * **Mechanics:**
 * - PokeClaw: Persistent foreground service pattern ensuring agent survival.
 * - Android 14+: Uses foregroundServiceType="specialUse" for API compatibility.
 */
@AndroidEntryPoint
class HushyariForegroundService : Service() {

    @Inject
    lateinit var agentLoop: AgentLoop

    private var isAgentActive: Boolean = false

    companion object {
        const val ACTION_STOP_AGENT = "dev.hushyari.action.STOP_AGENT"
        const val ACTION_PAUSE_AGENT = "dev.hushyari.action.PAUSE_AGENT"
        const val ACTION_RESUME_AGENT = "dev.hushyari.action.RESUME_AGENT"
    }

    override fun onCreate() {
        super.onCreate()
        Timber.d("HushyariForegroundService created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_AGENT -> {
                agentLoop.stop()
                isAgentActive = false
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                Timber.d("Agent stopped from notification")
                return START_NOT_STICKY
            }
            ACTION_PAUSE_AGENT -> {
                agentLoop.pause()
                isAgentActive = true
                updateNotification()
                Timber.d("Agent paused from notification")
            }
            ACTION_RESUME_AGENT -> {
                agentLoop.resume()
                isAgentActive = true
                updateNotification()
                Timber.d("Agent resumed from notification")
            }
        }

        startForeground(HushyariApp.NOTIFICATION_ID_FOREGROUND, buildNotification())
        isAgentActive = true
        Timber.d("HushyariForegroundService started in foreground")

        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isAgentActive = false
        Timber.d("HushyariForegroundService destroyed")
        super.onDestroy()
    }

    /**
     * Build the persistent foreground notification with agent control actions.
     */
    private fun buildNotification(): Notification {
        createNotificationChannel()

        val stopIntent = Intent(this, HushyariForegroundService::class.java).apply {
            action = ACTION_STOP_AGENT
        }
        val stopPendingIntent = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val pauseIntent = Intent(this, HushyariForegroundService::class.java).apply {
            action = if (isAgentActive && !agentLoop.isRunning) {
                ACTION_RESUME_AGENT
            } else {
                ACTION_PAUSE_AGENT
            }
        }
        val pausePendingIntent = PendingIntent.getService(
            this, 1, pauseIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val statusText = when {
            !isAgentActive -> "Starting..."
            !agentLoop.isRunning -> "Paused"
            else -> "Playing"
        }

        return NotificationCompat.Builder(this, HushyariApp.NOTIFICATION_CHANNEL_SERVICE)
            .setContentTitle("HushYari")
            .setContentText("HushYari is $statusText...")
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(
                R.drawable.ic_stop,
                "Stop",
                stopPendingIntent,
            )
            .addAction(
                if (agentLoop.isRunning) R.drawable.ic_pause else R.drawable.ic_play,
                if (agentLoop.isRunning) "Pause" else "Resume",
                pausePendingIntent,
            )
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    /**
     * Update the existing notification without recreating.
     */
    private fun updateNotification() {
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.notify(HushyariApp.NOTIFICATION_ID_FOREGROUND, buildNotification())
    }

    /**
     * Create the foreground service notification channel.
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                HushyariApp.NOTIFICATION_CHANNEL_SERVICE,
                getString(R.string.notification_channel_service),
                android.app.NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notification_channel_service_desc)
                setShowBadge(false)
            }
            val manager = getSystemService(android.app.NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}
