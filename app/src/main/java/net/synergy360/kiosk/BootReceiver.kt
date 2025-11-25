package net.synergy360.kiosk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {

        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {

            Log.d("BOOT", "BOOT_COMPLETED received → starting MainActivity")

            // ---------------------------------------------------------
            // 1) СТАРТУЕМ ForegroundService (НОВАЯ ВАЖНАЯ СТРОКА)
            // ---------------------------------------------------------
            try {
                val fg = Intent(context, ForegroundService::class.java)
                context.startForegroundService(fg)
                Log.d("BOOT", "ForegroundService started")
            } catch (e: Exception) {
                

            // 3. Запуск MainActivity
            val i = Intent(context, MainActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)

            // 4. Восстановление расписания из local prefs
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
