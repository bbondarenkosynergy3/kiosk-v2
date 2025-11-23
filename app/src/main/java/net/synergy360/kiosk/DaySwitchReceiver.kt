package net.synergy360.kiosk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore

class DaySwitchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Log.d("SCHEDULE", "DaySwitchReceiver → refreshing schedule directly")

        try {
            val prefs = context.getSharedPreferences("kiosk_prefs", Context.MODE_PRIVATE)
            val company = prefs.getString("company", "synergy3") ?: return
            val db = FirebaseFirestore.getInstance()

            db.collection("company")
                .document(company)
                .collection("settings")
                .document("config")
                .get()
                .addOnSuccessListener { snap ->
                    val kiosk = snap.get("kiosk") as? Map<*, *> ?: return@addOnSuccessListener
                    val sched = kiosk["sleepWakeSchedule"] as? Map<*, *> ?: return@addOnSuccessListener

                    val day = java.time.LocalDate.now().dayOfWeek.name.lowercase()
                    val today = sched[day] as? Map<*, *> ?: return@addOnSuccessListener

                    val enabled = today["enabled"] as? Boolean ?: false
                    if (!enabled) {
                        ScheduleManager.applySchedule(context, "25:00", "26:00")
                        return@addOnSuccessListener
                    }

                    val sleep = today["sleep"] as? String ?: return@addOnSuccessListener
                    val wake = today["wake"] as? String ?: return@addOnSuccessListener

                    ScheduleManager.applySchedule(context, sleep, wake)
                }
        } catch (e: Exception) {
            Log.e("SCHEDULE", "Day switch error: ${e.message}")
        }
    }
}
