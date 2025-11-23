package net.synergy360.kiosk

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import java.util.*

object ScheduleManager {

    /** Основная функция — ставит 2 будильника sleep/wake */
    fun applySchedule(context: Context, sleep: String, wake: String) {
        Log.d("SCHEDULE", "Applying schedule sleep=$sleep wake=$wake")

        cancelAll(context)
        setAlarm(context, sleep, "sleep")
        setAlarm(context, wake, "wake")
    }

    /** ❗ Новый: будильник на полночь для смены дня */
    fun scheduleDaySwitch(context: Context) {

        val cal = Calendar.getInstance().apply {
            timeInMillis = System.currentTimeMillis()
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 5)   // чуть-чуть после полуночи
            if (before(Calendar.getInstance())) {
                add(Calendar.DAY_OF_MONTH, 1)
            }
        }

        val intent = Intent(context, MyFirebaseService::class.java).apply {
            action = "kiosk.day_switch"
        }

        val pi = PendingIntent.getService(
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

        Log.d("SCHEDULE", "Day-switch alarm set for ${cal.time}")
    }

    /** Сбрасываем старые sleep/wake будильники */
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

    /** Ставим будильник sleep/wake */
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
