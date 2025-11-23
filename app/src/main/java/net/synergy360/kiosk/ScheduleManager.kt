package net.synergy360.kiosk

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import org.json.JSONObject
import java.util.Calendar

object ScheduleManager {

    private const val PREFS_NAME = "kiosk_prefs"
    private const val KEY_FULL_SCHEDULE = "full_schedule_json"

    /** Сохраняем полное расписание (JSON со всеми днями) в prefs */
    fun saveFullSchedule(context: Context, json: String) {
        try {
            // Легкая валидация
            JSONObject(json) // бросит исключение, если мусор
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_FULL_SCHEDULE, json).apply()
            Log.d("SCHEDULE", "Full schedule saved to prefs")
        } catch (e: Exception) {
            Log.e("SCHEDULE", "Failed to save schedule JSON: ${e.message}")
        }
    }

    /** Применяем расписание на СЕГОДНЯ, читая его из prefs */
    fun applyTodayFromPrefs(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_FULL_SCHEDULE, null)

        if (json.isNullOrEmpty()) {
            Log.w("SCHEDULE", "No full_schedule_json in prefs → nothing to apply")
            return
        }

        val todayKey = getTodayKey()
        Log.d("SCHEDULE", "applyTodayFromPrefs() for day=$todayKey")

        try {
            val root = JSONObject(json)

            if (!root.has(todayKey)) {
                Log.w("SCHEDULE", "No config for day=$todayKey in schedule JSON")
                cancelAll(context)
                scheduleDaySwitch(context)
                return
            }

            val dayObj = root.getJSONObject(todayKey)

            val enabled = dayObj.optBoolean("enabled", false)
            if (!enabled) {
                Log.d("SCHEDULE", "Day $todayKey disabled → cancel alarms, only day-switch")
                cancelAll(context)
                scheduleDaySwitch(context)
                return
            }

            val sleep = dayObj.optString("sleep", null)
            val wake = dayObj.optString("wake", null)

            if (sleep.isNullOrEmpty() || wake.isNullOrEmpty()) {
                Log.w("SCHEDULE", "Day $todayKey config missing sleep/wake → skipping")
                cancelAll(context)
                scheduleDaySwitch(context)
                return
            }

            Log.d("SCHEDULE", "Today=$todayKey → sleep=$sleep wake=$wake")
            applySchedule(context, sleep, wake)

        } catch (e: Exception) {
            Log.e("SCHEDULE", "applyTodayFromPrefs error: ${e.message}")
        }
    }

    /** Ключ дня недели в виде "monday", "tuesday", ... */
    private fun getTodayKey(): String {
        val cal = Calendar.getInstance()
        return when (cal.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> "monday"
            Calendar.TUESDAY -> "tuesday"
            Calendar.WEDNESDAY -> "wednesday"
            Calendar.THURSDAY -> "thursday"
            Calendar.FRIDAY -> "friday"
            Calendar.SATURDAY -> "saturday"
            Calendar.SUNDAY -> "sunday"
            else -> "monday"
        }
    }

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

    /** Очищаем все PendingIntent'ы для sleep/wake/day_switch */
    private fun cancelAll(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        listOf("sleep", "wake", "day_switch").forEach { type ->
            val action = if (type == "day_switch") "kiosk.day_switch" else "kiosk.$type"
            val intent = Intent(action)
            val pi = PendingIntent.getBroadcast(
                context,
                type.hashCode(),
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            am.cancel(pi)
        }

        Log.d("SCHEDULE", "All alarms canceled")
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
