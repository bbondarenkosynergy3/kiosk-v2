package net.synergy360.kiosk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log

class WakeAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.d("ALARM", "WakeAlarmReceiver triggered")

        try {
            val pm = context.getSystemService(PowerManager::class.java)

            @Suppress("DEPRECATION")
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "kiosk:wake"
            )

            wl.acquire(3000)
            wl.release()
        } catch (e: Exception) {
            Log.e("ALARM", "wake failed: ${e.message}")
        }
    }
}
