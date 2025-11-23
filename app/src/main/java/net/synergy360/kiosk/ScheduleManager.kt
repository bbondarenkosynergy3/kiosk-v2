package net.synergy360.kiosk

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.*

object ScheduleManager {

    fun applySchedule(context: Context, sleep: String, wake: String) {
        Log.d("SCHEDULE", "Applying schedule sleep=$sleep wake=$wake")

        cancelAll(context)
        setAlarm(context, sleep, "sleep")
        setAlarm(context, wake, "wake")
    }

    private fun cancelAll(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        listOf("sleep", "wake").forEach { type ->
            val pi = PendingIntent.getBroadcast(
                context,
                type.hashCode(),
                Intent("kiosk.$type"),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            am.cancel(pi)
        }
    }

    private fun setAlarm(context: Context, time: String, type: String) {
        val (hour, min) = time.split(":").map { it.toInt() }

        val calendar = Calendar.getInstance().apply {
            timeInMillis = System.currentTimeMillis()
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, min)
            set(Calendar.SECOND, 0)

            // Если время уже прошло — ставим на завтра
            if (before(Calendar.getInstance())) {
                add(Calendar.DAY_OF_MONTH, 1)
            }
        }

        val intent = Intent("kiosk.$type")
        val pi = PendingIntent.getBroadcast(
            context,
            type.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            calendar.timeInMillis,
            pi
        )

        Log.d("SCHEDULE", "Alarm set for $type at ${calendar.time}")
    }
}
