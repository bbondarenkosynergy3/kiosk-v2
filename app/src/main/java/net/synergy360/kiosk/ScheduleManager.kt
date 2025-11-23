package net.synergy360.kiosk

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import org.json.JSONObject
import java.util.Calendar

object ScheduleManager {

    private const val PREFS = "schedule_prefs"

    fun saveFullSchedule(context: Context, json: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString("full_schedule", json)
            .apply()

        Log.d("SCHEDULE", "Full schedule saved to prefs")
    }

    fun applyTodayFromPrefs(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString("full_schedule", null) ?: return

        val obj = JSONObject(json)

        val days = listOf(
            "sunday","monday","tuesday",
            "wednesday","thursday","friday","saturday"
        )

        val cal = Calendar.getInstance()
        val today = days[cal.get(Calendar.DAY_OF_WEEK) - 1]

        if (!obj.has(today)) {
            Log.e("SCHEDULE", "No schedule for today=$today")
            return
        }

        val dayObj = obj.getJSONObject(today)
        val enabled = dayObj.optBoolean("enabled", false)

        if (!enabled) {
            Log.d("SCHEDULE", "Schedule disabled for $today")
            return
        }

        val sleep = dayObj.optString("sleep", null)
        val wake = dayObj.optString("wake", null)

        Log.d("SCHEDULE", "applyToday: $today sleep=$sleep wake=$wake")

        applySchedule(context, sleep, wake)
    }

    fun applySchedule(context: Context, sleep: String?, wake: String?) {
        cancel(context)

        if (sleep != null) setAlarm(context, sleep, "sleep")
        if (wake != null) setAlarm(context, wake, "wake")

        Log.d("SCHEDULE", "Schedule applied sleep=$sleep wake=$wake")
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        listOf("sleep", "wake").forEach {
            val intent = Intent("kiosk.$it")
            val pi = PendingIntent.getBroadcast(
                context,
                it.hashCode(),
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            am.cancel(pi)
        }
        Log.d("SCHEDULE", "All schedule alarms cancelled")
    }

    private fun setAlarm(context: Context, time: String, type: String) {
        val (hour, min) = time.split(":").map { it.toInt() }

        val cal = Calendar.getInstance().apply {
            timeInMillis = System.currentTimeMillis()
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, min)
            set(Calendar.SECOND, 0)

            if (before(Calendar.getInstance())) add(Calendar.DAY_OF_MONTH, 1)
        }

        val intent = Intent("kiosk.$type")

        val pi = PendingIntent.getBroadcast(
            context,
            type.hashCode(),
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

        // SAFE — без SCHEDULE_EXACT_ALARM
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
        } else {
            am.set(AlarmManager.RTC_WAKEUP, cal.timeInMillis, pi)
        }

        Log.d("SCHEDULE", "Alarm set for $type at ${cal.time}")
    }
}
