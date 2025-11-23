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

    private const val REQ_SLEEP = 1001
    private const val REQ_WAKE = 1002
    private const val REQ_DAY_SWITCH = 1003

    /** Сохраняем полное расписание (JSON со всеми днями) в prefs */
    fun saveFullSchedule(context: Context, json: String) {
        try {
            JSONObject(json) // валидация
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            prefs.edit().putString(KEY_FULL_SCHEDULE, json).apply()
            Log.d("SCHEDULE", "Full schedule saved to prefs")
        } catch (e: Exception) {
            Log.e("SCHEDULE", "Failed to save schedule JSON: ${e.message}")
        }
    }

    /** Применяем расписание на сегодня */
    fun applyTodayFromPrefs(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_FULL_SCHEDULE, null)

        if (json.isNullOrEmpty()) {
            Log.w("SCHEDULE", "No full_schedule_json in prefs → nothing to apply")
            return
        }

        val today = getTodayKey()
        Log.d("SCHEDULE", "applyTodayFromPrefs() for day=$today")

        try {
            val root = JSONObject(json)

            if (!root.has(today)) {
                Log.w("SCHEDULE", "No config for day=$today in schedule JSON")
                cancelAll(context)
                scheduleDaySwitch(context)
                return
            }

            val dayObj = root.getJSONObject(today)
            val enabled = dayObj.optBoolean("enabled", false)

            if (!enabled) {
                Log.d("SCHEDULE", "Day $today disabled → only day-switch alarm")
                cancelAll(context)
                scheduleDaySwitch(context)
                return
            }

            val sleep = dayObj.optString("sleep", null)
            val wake = dayObj.optString("wake", null)

            if (sleep.isNullOrEmpty() || wake.isNullOrEmpty()) {
                Log.w("SCHEDULE", "Day $today missing sleep/wake")
                cancelAll(context)
                scheduleDaySwitch(context)
                return
            }

            Log.d("SCHEDULE", "Today=$today → sleep=$sleep wake=$wake")
            applySchedule(context, sleep, wake)

        } catch (e: Exception) {
            Log.e("SCHEDULE", "applyTodayFromPrefs error: ${e.message}")
        }
    }

    private fun getTodayKey(): String {
        return when (Calendar.getInstance().get(Calendar.DAY_OF_WEEK)) {
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

    /** Основная функция: ставим sleep/wake + day_switch */
    fun applySchedule(context: Context, sleep: String, wake: String) {
        Log.d("SCHEDULE", "Applying schedule sleep=$sleep wake=$wake")
        cancelAll(context)
        setAlarm(context, sleep, "sleep", REQ_SLEEP)
        setAlarm(context, wake, "wake", REQ_WAKE)
        scheduleDaySwitch(context)
    }

    /** Будильник на 00:00:05 */
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
            REQ_DAY_SWITCH,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)

        Log.d("SCHEDULE", "Day-switch alarm scheduled for ${cal.time}")
    }

    /** Отмена всех будильников */
    private fun cancelAll(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        fun cancel(req: Int, action: String) {
            val pi = PendingIntent.getBroadcast(
                context,
                req,
                Intent(action),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            am.cancel(pi)
        }

        cancel(REQ_SLEEP, "kiosk.sleep")
        cancel(REQ_WAKE, "kiosk.wake")
        cancel(REQ_DAY_SWITCH, "kiosk.day_switch")

        Log.d("SCHEDULE", "All alarms canceled")
    }

    /** Ставим будильник sleep/wake */
    private fun setAlarm(context: Context, time: String, type: String, req: Int) {
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
            req,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)

        Log.d("SCHEDULE", "Alarm set for $type at ${cal.time}")
    }
}
