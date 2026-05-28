package dev.hushyari.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.tencent.mmkv.MMKV
import timber.log.Timber

/**
 * Boot completion receiver that optionally restarts the foreground service
 * when the device boots up, enabling auto-resume of the agent after reboot.
 *
 * Checks user preferences to determine whether auto-start is enabled.
 * Respects the user's choice — does nothing if auto-start is disabled.
 *
 * **Mechanics:**
 * - PokeClaw: Boot persistence — allows long-running game automation to
 *   survive device reboots.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val PREF_AUTO_START = "auto_start_on_boot"
        private const val PREF_AUTO_START_ENABLED = "auto_start_enabled"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        Timber.d("BootReceiver: BOOT_COMPLETED received")

        val prefs = MMKV.mmkvWithID("hushyari_settings")

        // Check if user has enabled auto-start
        val autoStartEnabled = prefs.decodeBool(PREF_AUTO_START_ENABLED, false)
        if (!autoStartEnabled) {
            Timber.d("Auto-start on boot is disabled, skipping")
            return
        }

        try {
            val serviceIntent = Intent(context, HushyariForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
            Timber.i("Foreground service started on boot")
        } catch (e: Exception) {
            Timber.e(e, "Failed to start foreground service on boot")
        }
    }
}
