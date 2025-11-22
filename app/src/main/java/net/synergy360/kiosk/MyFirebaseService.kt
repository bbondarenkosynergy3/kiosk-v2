package net.synergy360.kiosk

import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import android.os.PowerManager

class MyFirebaseService : FirebaseMessagingService() {

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        val data = remoteMessage.data
        if (data.isEmpty()) {
            Log.d("FCM", "📩 Empty FCM message received")
            return
        }

        val command = data["command"] ?: data["cmd"] ?: return
        val cmdId = data["commandId"] ?: data["cmdId"] ?: System.currentTimeMillis().toString()

        Log.d("FCM", "🔥 FCM command received: $command id=$cmdId payload=$data")

        val prefs = getSharedPreferences("kiosk_prefs", MODE_PRIVATE)

        when (command) {

            /* -----------------------------
               🛏 SLEEP — Device Owner lock
               ----------------------------- */
            "sleep" -> {
                val dpm = getSystemService(android.app.admin.DevicePolicyManager::class.java)
                try {
                    dpm.lockNow()
                    Log.d("FCM", "✅ sleep via lockNow()")
                    ack(cmdId, true, "screen locked")
                } catch (e: Exception) {
                    Log.e("FCM", "sleep failed: ${e.message}")
                    ack(cmdId, false, e.message ?: "")
                }
            }

            /* -----------------------------
               🌅 WAKE — wakelock fallback
               ----------------------------- */
            "wake" -> {
                try {
                    val pm = getSystemService(PowerManager::class.java)

                    @Suppress("DEPRECATION")
                    val wl = pm.newWakeLock(
                        PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                        "kiosk:fcm_wake"
                    )

                    wl.acquire(3000)
                    wl.release()

                    Log.d("FCM", "✅ wake via wakelock")
                    ack(cmdId, true, "screen waked")
                } catch (e: Exception) {
                    Log.e("FCM", "wake failed: ${e.message}")
                    ack(cmdId, false, e.message ?: "")
                }
            }

            /* -----------------------------
               🔄 RELOAD
               ----------------------------- */
            "reload" -> {
                prefs.edit().putBoolean("pending_reload", true).apply()
                ack(cmdId, true, "scheduled")
            }

            /* -----------------------------
               📡 UPDATE (OTA update)
               ----------------------------- */
            "update_now", "update" -> {
                val url = data["url"]
                if (!url.isNullOrEmpty()) {
                    try {
                        UpdateHelper(this).startUpdate(url)
                        ack(cmdId, true, "update started")
                    } catch (e: Exception) {
                        ack(cmdId, false, e.message ?: "")
                    }
                } else {
                    ack(cmdId, false, "missing url")
                }
            }

            /* -----------------------------
               🌐 OPEN URL
               ----------------------------- */
            "open_url" -> {
                val url = data["url"]
                if (!url.isNullOrEmpty()) {
                    prefs.edit().putString("pending_open_url", url).apply()
                    ack(cmdId, true, "scheduled")
                } else {
                    ack(cmdId, false, "missing url")
                }
            }
        }
    }

    /* --------------------------------------------------
       🔵 ACK COMMAND — синхронизация с Firestore
       -------------------------------------------------- */
    private fun ack(cmdId: String, ok: Boolean, msg: String) {
        val prefs = getSharedPreferences("kiosk_prefs", MODE_PRIVATE)
        val deviceId = prefs.getString("device_id", null)
        val company = prefs.getString("company", "pierce") ?: "unknowncompany"

        if (deviceId == null) {
            Log.e("FCM", "ACK failed: deviceId missing")
            return
        }

        val update = mapOf(
            "command" to "idle",
            "lastCommandId" to cmdId,
            "lastCommandStatus" to if (ok) "ok" else "error",
            "lastCommandMessage" to msg,
            "lastCommandAt" to System.currentTimeMillis()
        )

        FirebaseFirestore.getInstance()
            .collection("company")
            .document(company)
            .collection("devices")
            .document(deviceId)
            .set(update, com.google.firebase.firestore.SetOptions.merge())
            .addOnSuccessListener {
                Log.d("FCM", "ACK sent for $cmdId ($msg)")
            }
            .addOnFailureListener { e ->
                Log.e("FCM", "ACK failed", e)
            }
    }

    /* --------------------------------------------------
       🔵 UPDATE TOKEN
       -------------------------------------------------- */
    override fun onNewToken(token: String) {
        Log.d("FCM", "🔄 New token: $token")

        val db = FirebaseFirestore.getInstance()
        val prefs = getSharedPreferences("kiosk_prefs", MODE_PRIVATE)
        val id = prefs.getString("device_id", null)
        val company = prefs.getString("company", "pierce") ?: "unknowncompany"

        if (id == null) {
            prefs.edit().putString("pending_token", token).apply()
            return
        }

        val update = mapOf(
            "token" to token,
            "timestamp" to System.currentTimeMillis()
        )

        db.collection("company").document(company).collection("devices").document(id)
            .set(update, com.google.firebase.firestore.SetOptions.merge())
            .addOnSuccessListener {
                Log.d("FIRESTORE", "✅ token updated for $id (company=$company)")
            }
            .addOnFailureListener { e ->
                Log.e("FIRESTORE", "❌ token update fail", e)
            }
    }
}
