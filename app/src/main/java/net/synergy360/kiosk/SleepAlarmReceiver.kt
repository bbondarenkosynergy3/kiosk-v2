package net.synergy360.kiosk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.admin.DevicePolicyManager
import android.util.Log

class SleepAlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("ALARM", "SleepAlarmReceiver triggered")

        try {
            val dpm = context.getSystemService(DevicePolicyManager::class.java)
            dpm.lockNow()
        } catch (e: Exception) {
            Log.e("ALARM", "Failed lock: ${e.message}")
        }
    }
}
