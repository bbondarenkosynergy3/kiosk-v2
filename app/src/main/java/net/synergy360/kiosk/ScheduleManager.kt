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
        val json = prefs.getString("full_schedule", null)

        if (json == null) {
            Log.d("SCHEDULE", "No full_schedule in prefs")
            return
        }

        val obj = JSONObject(json)

        val days = listOf(
            "sunday", "monday", "tuesday",
            "wednesday", "thursday", "friday", "saturday"
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
            // на всякий случай снимем любые старые будильники
            cancel(context)
            return
        }

        val sleep = dayObj.optString("sleep", null)
        val wake = dayObj.optString("wake", null)

        Log.d("SCHEDULE", "applyToday: $today sleep=$sleep wake=$wake")

        // 1) Ставим будильники на этот день (без переносов на завтра)
        applySchedule(context, sleep, wake)

        // 2) Немедленно применяем состояние на сейчас (sleep/wake),
        // чтобы не ждать будильник, если время уже прошло
        evaluateAndApplyNow(context, sleep, wake)
    }

    fun applySchedule(context: Context, sleep: String?, wake: String?) {
        cancel(context)

        if (sleep != null) setAlarm(context, sleep, "sleep")
        if (wake != null) setAlarm(context, wake, "wake")

        Log.d("SCHEDULE", "Schedule applied sleep=$sleep wake=$wake")
    }

    fun cancel(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        listOf("sleep", "wake").forEach { type ->
            val intent = Intent("kiosk.$type")
            val pi = PendingIntent.getBroadcast(
                context,
                type.hashCode(),
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            am.cancel(pi)
        }
        Log.d("SCHEDULE", "All schedule alarms cancelled")
    }

    /**
     * Ставим AlarmManager на указанное время ТЕКУЩЕГО дня.
     *
     * ВАЖНО:
     *  - Больше НЕ переносим на следующий день, если время в прошлом.
     *  - Если время уже прошло, AlarmManager должен сработать почти сразу.
     */
    private fun setAlarm(context: Context, time: String, type: String) {
        val (hour, min) = try {
            time.split(":").map { it.toInt() }
        } catch (e: Exception) {
            Log.e("SCHEDULE", "Invalid time format for '$type': '$time'")
            return
        }

        val now = Calendar.getInstance()

        val cal = Calendar.getInstance().apply {
            timeInMillis = System.currentTimeMillis()
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, min)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            // БЕЗ add(Calendar.DAY_OF_MONTH, 1)
        }

        val inPast = cal.before(now)

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

        Log.d(
            "SCHEDULE",
            "Alarm set for $type at ${cal.time} (now=${now.time}, inPast=$inPast)"
        )
    }

    /**
     * Считаем, что должно быть ПРЯМО СЕЙЧАС (sleep / wake) и шлём broadcast.
     * Логика такая же, как в Cloud Functions (evaluateAction).
     */
    private fun evaluateAndApplyNow(context: Context, sleep: String?, wake: String?) {
        if (sleep.isNullOrBlank() || wake.isNullOrBlank()) {
            Log.d("SCHEDULE", "evaluateNow: missing sleep or wake → skip")
            return
        }

        val cal = Calendar.getInstance()
        val hh = cal.get(Calendar.HOUR_OF_DAY)
        val mm = cal.get(Calendar.MINUTE)
        val nowStr = String.format("%02d:%02d", hh, mm)

        val action = evaluateAction(sleep, wake, nowStr)
        Log.d(
            "SCHEDULE",
            "evaluateNow: now=$nowStr sleep=$sleep wake=$wake → action=$action"
        )

        if (action == null) return

        val intent = Intent("kiosk.$action")
        context.sendBroadcast(intent)

        Log.d("SCHEDULE", "Broadcast sent: kiosk.$action")
    }

    /**
     * Копия evaluateAction из index.ts:
     * - если sleep > wake → интервал через полночь
     * - иначе обычный интервал
     */
    private fun evaluateAction(sleep: String, wake: String, now: String): String? {
        fun toMin(t: String): Int {
            val parts = t.split(":")
            if (parts.size != 2) return 0
            return parts[0].toIntOrNull()?.times(60)?.plus(parts[1].toIntOrNull() ?: 0) ?: 0
        }

        val S = toMin(sleep)
        val W = toMin(wake)
        val N = toMin(now)

        return if (S > W) {
            // переход через полночь (21:00 → 07:00)
            if (N >= S || N < W) "sleep" else "wake"
        } else {
            // обычный случай (02:00 → 23:00)
            if (N >= S && N < W) "sleep" else "wake"
        }
    }
}
