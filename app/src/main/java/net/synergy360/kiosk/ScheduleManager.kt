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
    private const val KEY_FULL = "full_json"

    // -------------------------------------------------------
    // Public API
    // -------------------------------------------------------

    fun saveFullSchedule(context: Context, json: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_FULL, json).apply()
        Log.d("SCHEDULE", "Full schedule saved to prefs")

        applyTodayFromPrefs(context)
    }

    /**
     * Вызывается:
     *  - после FCM команды set_full_schedule
     *  - после BOOT_COMPLETED
     *  - после смены дня (DaySwitchReceiver)
     */
    fun applyTodayFromPrefs(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_FULL, null)

        if (json.isNullOrBlank()) {
            Log.d("SCHEDULE", "No schedule in prefs → cancel all + wake")
            cancelAll(context)
            sendActionBroadcast(context, "wake")
            return
        }

        val all = JSONObject(json)
        val nowCal = Calendar.getInstance()
        val dayKey = dayKey(nowCal)

        val today = all.optJSONObject(dayKey)
        if (today == null || !today.optBoolean("enabled", false)) {
            Log.d("SCHEDULE", "Day $dayKey disabled or missing → cancel + wake")
            cancelAll(context)
            sendActionBroadcast(context, "wake")
            return
        }

        val sleep = today.optString("sleep", "").trim()
        val wake = today.optString("wake", "").trim()

        if (sleep.isBlank() || wake.isBlank()) {
            Log.d("SCHEDULE", "Day $dayKey has empty sleep/wake → cancel + wake")
            cancelAll(context)
            sendActionBroadcast(context, "wake")
            return
        }

        // 1) Ставим новые будильники
        cancelAll(context)
        scheduleAlarms(context, sleep, wake, nowCal)

        // 2) Определяем, что должно происходить прямо сейчас
        val nowStr = String.format("%02d:%02d",
            nowCal.get(Calendar.HOUR_OF_DAY),
            nowCal.get(Calendar.MINUTE)
        )
        val action = getCurrentAction(sleep, wake, nowStr)

        Log.d(
            "SCHEDULE",
            "applyToday: $dayKey sleep=$sleep wake=$wake now=$nowStr → action=$action"
        )

        sendActionBroadcast(context, action)
    }

    // -------------------------------------------------------
    // Alarm helpers
    // -------------------------------------------------------

    private fun cancelAll(context: Context) {
        val am = context.getSystemService(AlarmManager::class.java)

        am.cancel(pending(context, "kiosk.sleep", 1))
        am.cancel(pending(context, "kiosk.wake", 2))

        Log.d("SCHEDULE", "All schedule alarms cancelled")
    }

    private fun scheduleAlarms(
        context: Context,
        sleep: String,
        wake: String,
        now: Calendar
    ) {
        val am = context.getSystemService(AlarmManager::class.java)

        val sleepCal = timeToCalendar(now, sleep)
        val wakeCal = timeToCalendar(now, wake)

        // гарантируем, что оба времени лежат в будущем
        if (!sleepCal.after(now)) sleepCal.add(Calendar.DATE, 1)
        if (!wakeCal.after(now))  wakeCal.add(Calendar.DATE, 1)

        setExact(am, sleepCal.timeInMillis, pending(context, "kiosk.sleep", 1))
        setExact(am, wakeCal.timeInMillis, pending(context, "kiosk.wake", 2))

        Log.d(
            "SCHEDULE",
            "Alarm set for sleep at ${sleepCal.time} (now=${now.time})"
        )
        Log.d(
            "SCHEDULE",
            "Alarm set for wake at ${wakeCal.time} (now=${now.time})"
        )
    }

    private fun pending(context: Context, action: String, requestCode: Int): PendingIntent {
        val i = Intent(context, SleepWakeReceiver::class.java).apply {
            this.action = action
        }

        val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0

        return PendingIntent.getBroadcast(context, requestCode, i, flags)
    }

    private fun setExact(am: AlarmManager, triggerAt: Long, pi: PendingIntent) {
        if (Build.VERSION.SDK_INT >= 23) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } else {
            am.setExact(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    private fun sendActionBroadcast(context: Context, action: String) {
        val intent = Intent(
            if (action == "sleep") "kiosk.sleep" else "kiosk.wake"
        ).apply { setPackage(context.packageName) }

        context.sendBroadcast(intent)
        Log.d("SCHEDULE", "Broadcast sent: ${intent.action}")
    }

    // -------------------------------------------------------
    // Time helpers
    // -------------------------------------------------------

    private fun dayKey(cal: Calendar): String =
        when (cal.get(Calendar.DAY_OF_WEEK)) {
            Calendar.MONDAY -> "monday"
            Calendar.TUESDAY -> "tuesday"
            Calendar.WEDNESDAY -> "wednesday"
            Calendar.THURSDAY -> "thursday"
            Calendar.FRIDAY -> "friday"
            Calendar.SATURDAY -> "saturday"
            else -> "sunday"
        }

    private fun timeToCalendar(base: Calendar, hhmm: String): Calendar {
        val parts = hhmm.split(":")
        val h = parts.getOrNull(0)?.toIntOrNull() ?: 0
        val m = parts.getOrNull(1)?.toIntOrNull() ?: 0

        return (base.clone() as Calendar).apply {
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            set(Calendar.HOUR_OF_DAY, h)
            set(Calendar.MINUTE, m)
        }
    }

    /**
     * Логика "спим / бодрствуем" на основе трёх строк HH:mm
     */
    fun getCurrentAction(sleep: String, wake: String, now: String): String {

        fun toMin(t: String): Int {
            val p = t.split(":")
            val h = p.getOrNull(0)?.toIntOrNull() ?: 0
            val m = p.getOrNull(1)?.toIntOrNull() ?: 0
            return h * 60 + m
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
