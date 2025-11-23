package net.synergy360.kiosk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import java.util.Locale

class DaySwitchReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.d("DAY_SWITCH", "🔄 Day changed → loading schedule for today")

        try {
            val prefs = context.getSharedPreferences("kiosk_prefs", Context.MODE_PRIVATE)
            val company = prefs.getString("company", null) ?: return

            val today = java.time.LocalDate.now()
                .dayOfWeek
                .name
                .lowercase(Locale.US)   // monday, tuesday …

            val configRef = FirebaseFirestore.getInstance()
                .collection("company")
                .document(company)
                .collection("settings")
                .document("config")

            configRef.get().addOnSuccessListener { snap ->

                val kiosk = snap.get("kiosk") as? Map<*, *> ?: return@addOnSuccessListener
                val schedule = kiosk["sleepWakeSchedule"] as? Map<*, *> ?: return@addOnSuccessListener
                val dayCfg = schedule[today] as? Map<*, *> ?: return@addOnSuccessListener

                val enabled = dayCfg["enabled"] as? Boolean ?: false
                if (!enabled) {
                    Log.d("DAY_SWITCH", "Today ($today) disabled → skipping")
                    return@addOnSuccessListener
                }

                val sleep = dayCfg["sleep"] as? String ?: return@addOnSuccessListener
                val wake = dayCfg["wake"] as? String ?: return@addOnSuccessListener

                Log.d("DAY_SWITCH", "Today's schedule → sleep=$sleep wake=$wake")

                ScheduleManager.applySchedule(context, sleep, wake)
            }

        } catch (e: Exception) {
            Log.e("DAY_SWITCH", "Error: ${e.message}")
        }
    }
}
