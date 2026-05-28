package dev.hushyari

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import com.tencent.mmkv.MMKV
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

@HiltAndroidApp
class HushyariApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this

        MMKV.initialize(this)

        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        val manager = getSystemService(NotificationManager::class.java)

        val serviceChannel = NotificationChannel(
            NOTIFICATION_CHANNEL_SERVICE,
            getString(R.string.notification_channel_service),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.notification_channel_service_desc)
            setShowBadge(false)
        }

        val alertsChannel = NotificationChannel(
            NOTIFICATION_CHANNEL_ALERTS,
            getString(R.string.notification_channel_alerts),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = getString(R.string.notification_channel_alerts_desc)
        }

        manager.createNotificationChannel(serviceChannel)
        manager.createNotificationChannel(alertsChannel)
    }

    companion object {
        const val NOTIFICATION_CHANNEL_SERVICE = "hushyari_service"
        const val NOTIFICATION_CHANNEL_ALERTS = "hushyari_alerts"
        const val NOTIFICATION_ID_FOREGROUND = 1001
        const val NOTIFICATION_ID_ALERT = 2001

        lateinit var instance: HushyariApp
            private set
    }
}
