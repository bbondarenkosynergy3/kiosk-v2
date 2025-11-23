package net.synergy360.kiosk

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.*

object ScheduleManager {

    /** Ставим будильники sleep/wake И будильник смены дня */
    fun applySchedule(context: Context, sleep: String, wake: String) {
        Log.d("SCHEDULE", "Applying schedule sleep=$sleep wake=$wake")

        cancelAll(context)

        setAlarm(context, sleep, "sleep")
        setAlarm(context, wake, "wake")

        scheduleDaySwitch(context)
    }

    /** Будильник на 00:00 — вызывает DaySwitchReceiver */
    fun scheduleDaySwitch(context: Context) {

        val cal = Calendar.getInstance().apply {
            timeInMillis = System.currentTimeMillis()
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 5)
            if (before(Calendar.getInstance())) {
                add(Calendar.DAY_OF_MONTH, 1)
            }
        }

        val intent = Intent("kiosk.day_switch")
        val pi = PendingIntent.getBroadcast(
            context,
            "day_switch".hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            cal.timeInMillis,
            pi
        )

        Log.d("SCHEDULE", "Day-switch alarm scheduled for ${cal.time}")
    }

    /** Очищаем корректные PendingIntent'ы */
    private fun cancelAll(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        listOf("sleep", "wake", "day_switch").forEach { type ->
            val intent = Intent("kiosk.$type")
            val pi = PendingIntent.getBroadcast(
                context,
                type.hashCode(),
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            am.cancel(pi)
        }
    }

    /** Ставим будильники sleep/wake */
    private fun setAlarm(context: Context, time: String, type: String) {
        val (hour, min) = time.split(":").map { it.toInt() }

        val cal = Calendar.getInstance().apply {
            timeInMillis = System.currentTimeMillis()
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, min)
            set(Calendar.SECOND, 0)

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
            cal.timeInMillis,
            pi
        )

        Log.d("SCHEDULE", "Alarm set for $type at ${cal.time}")
    }
}
