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

        val prefs = context.getSharedPreferences("kiosk_prefs", Context.MODE_PRIVATE)
        val company = prefs.getString("company", null) ?: return

        val today = java.time.LocalDate.now()
            .dayOfWeek
            .name
            .lowercase(Locale.US)

        FirebaseFirestore.getInstance()
            .collection("company")
            .document(company)
            .collection("settings")
            .document("config")
            .get()
            .addOnSuccessListener { snap ->

                val root = snap.data ?: run {
                    Log.e("DAY_SWITCH", "❌ Config missing in Firestore")
                    return@addOnSuccessListener
                }

                val kiosk = root["kiosk"] as? Map<*, *> ?: run {
                    Log.e("DAY_SWITCH", "❌ kiosk section missing")
                    return@addOnSuccessListener
                }

                val schedule = kiosk["sleepWakeSchedule"] as? Map<*, *> ?: run {
                    Log.e("DAY_SWITCH", "❌ sleepWakeSchedule missing")
                    return@addOnSuccessListener
                }

                val dayCfg = schedule[today] as? Map<*, *> ?: run {
                    Log.e("DAY_SWITCH", "❌ No config for day=$today")
                    return@addOnSuccessListener
                }

                val enabled = dayCfg["enabled"] as? Boolean ?: false
                if (!enabled) {
                    Log.d("DAY_SWITCH", "⏳ Today ($today) disabled → skipping")
                    return@addOnSuccessListener
                }

                val sleep = dayCfg["sleep"] as? String ?: run {
                    Log.e("DAY_SWITCH", "❌ sleep missing")
                    return@addOnSuccessListener
                }

                val wake = dayCfg["wake"] as? String ?: run {
                    Log.e("DAY_SWITCH", "❌ wake missing")
                    return@addOnSuccessListener
                }

                Log.d("DAY_SWITCH", "Today's schedule → sleep=$sleep wake=$wake")

                ScheduleManager.applySchedule(context, sleep, wake)
            }
            .addOnFailureListener {
                Log.e("DAY_SWITCH", "❌ Firestore error: ${it.message}")
            }
    }
}
