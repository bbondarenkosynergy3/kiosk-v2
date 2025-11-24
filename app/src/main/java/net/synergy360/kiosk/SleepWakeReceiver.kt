package net.synergy360.kiosk

import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.util.Log

class SleepWakeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.d("SCHEDULE", "SleepWakeReceiver onReceive: action=$action")

        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val admin = ComponentName(context, MyDeviceAdminReceiver::class.java)

        when (action) {

            "kiosk.sleep" -> {
                try {
                    if (dpm != null && dpm.isAdminActive(admin)) {
                        try {
                            dpm.setKeyguardDisabled(admin, false)
                        } catch (_: Exception) { }
                        dpm.lockNow()
                        Log.d("SCHEDULE", "Device locked by schedule (kiosk.sleep)")
                    } else {
                        Log.w("SCHEDULE", "Device owner not active → kiosk.sleep ignored")
                    }
                } catch (e: Exception) {
                    Log.e("SCHEDULE", "sleep failed: ${e.message}")
                }
            }

            "kiosk.wake" -> {
                try {
                    val pm = context.getSystemService(PowerManager::class.java)
                    @Suppress("DEPRECATION")
                    val wl = pm.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                        "kiosk:alarmWake"
                    )
                    wl.acquire(3000)
                    wl.release()
                    Log.d("SCHEDULE", "Device woken from alarm (kiosk.wake)")
                } catch (e: Exception) {
                    Log.e("SCHEDULE", "wake failed: ${e.message}")
                }
            }
        }
    }
}
