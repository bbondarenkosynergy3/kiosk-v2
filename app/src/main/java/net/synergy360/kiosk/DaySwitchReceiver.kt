package net.synergy360.kiosk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class DaySwitchReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.d("DAY_SWITCH", "🔄 DaySwitchReceiver triggered: action=${intent.action}")
        // Просто применяем расписание для сегодняшнего дня из prefs
        ScheduleManager.applyTodayFromPrefs(context)
    }
}
