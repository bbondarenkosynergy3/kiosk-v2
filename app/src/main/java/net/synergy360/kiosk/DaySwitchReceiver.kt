package net.synergy360.kiosk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class DaySwitchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("SCHEDULE", "DaySwitchReceiver → requesting schedule refresh")

        try {
            val svc = Intent(context, MyFirebaseService::class.java)
            svc.action = "kiosk.day_switch"
            context.startService(svc)
        } catch (e: Exception) {
            Log.e("SCHEDULE", "Day switch error: ${e.message}")
        }
    }
}
