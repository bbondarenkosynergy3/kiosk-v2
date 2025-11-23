package net.synergy360.kiosk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore

class DaySwitchReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        Log.d("DAY_SWITCH", "Day switched → reloading schedule")

        try {
            val prefs = context.getSharedPreferences("kiosk_prefs", Context.MODE_PRIVATE)
            val company = prefs.getString("company", null) ?: return

            val deviceId = prefs.getString("device_id", null) ?: return

            FirebaseFirestore.getInstance()
                .collection("company")
                .document(company)
                .collection("devices")
                .document(deviceId)
                .get()
                .addOnSuccessListener { snap ->
                    val cfg = snap.get("schedule") as? Map<*, *> ?: return@addOnSuccessListener
                    val sleep = cfg["sleep"] as? String ?: return@addOnSuccessListener
                    val wake = cfg["wake"] as? String ?: return@addOnSuccessListener

                    ScheduleManager.applySchedule(context, sleep, wake)
                }
        } catch (_: Exception) { }
    }
}
