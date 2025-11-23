package net.synergy360.kiosk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {

        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {

            Log.d("BOOT", "BOOT_COMPLETED received → starting MainActivity")

            // 1. Запуск MainActivity
            val i = Intent(context, MainActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)

            // 2. Восстановление расписания
            restoreSchedule(context)
        }
    }

    private fun restoreSchedule(context: Context) {
        val prefs = context.getSharedPreferences("kiosk_prefs", Context.MODE_PRIVATE)

        val company = prefs.getString("company", "synergy3") ?: return
        val deviceId = prefs.getString("device_id", null) ?: return

        Log.d("BOOT", "Restoring schedule for company=$company device=$deviceId")

        val db = FirebaseFirestore.getInstance()

        db.collection("company")
            .document(company)
            .collection("settings")
            .document("config")
            .get()
            .addOnSuccessListener { snap ->
                val cfg = snap.data ?: return@addOnSuccessListener
                val sk = cfg["kiosk"] as? Map<*, *> ?: return@addOnSuccessListener
                val sched = sk["sleepWakeSchedule"] as? Map<*, *> ?: return@addOnSuccessListener

                // Определяем текущий день недели
                val day = java.time.LocalDate.now()
                    .dayOfWeek
                    .name
                    .lowercase()

                val today = sched[day] as? Map<*, *> ?: return@addOnSuccessListener
                val enabled = today["enabled"] as? Boolean ?: false
                if (!enabled) return@addOnSuccessListener

                val sleep = today["sleep"] as? String ?: return@addOnSuccessListener
                val wake = today["wake"] as? String ?: return@addOnSuccessListener

                Log.d("BOOT", "Applying restored schedule: sleep=$sleep wake=$wake")

                ScheduleManager.applySchedule(context, sleep, wake)

                // Schedule daily midnight day switch
                try {
                    ScheduleManager.scheduleDaySwitch(context)
                    Log.d("BOOT", "Day-switch alarm scheduled")
                } catch (e: Exception) {
                    Log.e("BOOT", "Failed to schedule day-switch: ${e.message}")
                }
            }
            .addOnFailureListener {
                Log.e("BOOT", "Failed to restore schedule: ${it.message}")
            }
    }
}
