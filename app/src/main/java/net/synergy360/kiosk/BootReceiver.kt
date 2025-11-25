package net.synergy360.kiosk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.os.PowerManager

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {

        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {

            Log.d("BOOT", "BOOT_COMPLETED received")

            // ---------------------------------------------------------
            // 0) WAKELOCK — включаем CPU, чтобы сервис гарантированно поднялся
            // ---------------------------------------------------------
            try {
                val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
                val wl = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK or
                            PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "kiosk:boot_wakelock"
                )
                wl.acquire(3000)
                Log.d("BOOT", "WakeLock acquired before starting services")
            } catch (e: Exception) {
                Log.e("BOOT", "WakeLock error: ${e.message}")
            }

            // ---------------------------------------------------------
            // 1) СТАРТ ForegroundService
            // ---------------------------------------------------------
            try {
                val fg = Intent(context, ForegroundService::class.java)
                context.startForegroundService(fg)
                Log.d("BOOT", "ForegroundService started")
            } catch (e: Exception) {
                Log.e("BOOT", "Failed to start ForegroundService: ${e.message}")
            }

            // ---------------------------------------------------------
            // 2) Запуск MainActivity
            // ---------------------------------------------------------
            val i = Intent(context, MainActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)

            // ---------------------------------------------------------
            // 3) Восстановление расписания
            // ---------------------------------------------------------
            restoreSchedule(context)
        }
    }

    private fun restoreSchedule(context: Context) {
        Log.d("BOOT", "Restoring schedule from prefs")

        try {
            ScheduleManager.applyTodayFromPrefs(context)
            Log.d("BOOT", "Schedule applied successfully")
        } catch (e: Exception) {
            Log.e("BOOT", "Failed to restore schedule: ${e.message}")
        }
    }
}
