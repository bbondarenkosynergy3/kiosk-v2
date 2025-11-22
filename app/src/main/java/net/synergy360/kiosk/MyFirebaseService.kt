package net.synergy360.kiosk

import android.os.Build
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class MyFirebaseService : FirebaseMessagingService() {

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        val data = remoteMessage.data
        if (data.isEmpty()) {
            Log.d("FCM", "📩 Empty FCM message received")
            return
        }

        val cmd = data["cmd"] ?: data["command"] ?: return
        val cmdId = data["cmdId"]
        Log.d("FCM", "🔥 FCM command received: $cmd id=$cmdId payload=$data")

        val prefs = getSharedPreferences("kiosk_prefs", MODE_PRIVATE)

        when (cmd) {
    "sleep" -> {
        val dpm = getSystemService(android.app.admin.DevicePolicyManager::class.java)
        try {
            dpm.lockNow()
            Log.d("FCM", "✅ sleep via lockNow()")
        } catch (e: Exception) {
            Log.e("FCM", "sleep failed: ${e.message}")
        }
    }

            "wake" -> {
                try {
                    val pm = getSystemService(android.os.PowerManager::class.java)
            
                    @Suppress("DEPRECATION")
                    val wl = pm.newWakeLock(
                        android.os.PowerManager.SCREEN_BRIGHT_WAKE_LOCK or android.os.PowerManager.ACQUIRE_CAUSES_WAKEUP,
                        "kiosk:fcm_wake"
                    )
                    wl.acquire(3000)
                    wl.release()
            
                    Log.d("FCM", "✅ wake via wakelock")
                } catch (e: Exception) {
                    Log.e("FCM", "wake failed: ${e.message}")
                }
            }

            "reload" -> {
                prefs.edit().putBoolean("pending_reload", true).apply()
            }

            "update_now", "update" -> {
                val url = data["url"]
                if (!url.isNullOrEmpty()) {
                    UpdateHelper(this).startUpdate(url)
                } else {
                    Log.e("FCM", "update command without URL")
                }
            }

            "open_url" -> {
                val url = data["url"]
                if (!url.isNullOrEmpty()) {
                    prefs.edit().putString("pending_open_url", url).apply()
                }
            }
        }
    }

    override fun onNewToken(token: String) {
        Log.d("FCM", "🔄 New token: $token")

        val db = FirebaseFirestore.getInstance()
        val prefs = getSharedPreferences("kiosk_prefs", MODE_PRIVATE)
        val id = prefs.getString("device_id", null)
        val company = prefs.getString("company", "pierce") ?: " unknowncompany"

        if (id == null) {
            // Приложение ещё не создало deviceId — просто сохраним токен, MainActivity подхватит
            prefs.edit().putString("pending_token", token).apply()
            return
        }

        val update = mapOf(
            "token" to token,
            "timestamp" to System.currentTimeMillis()
        )

        db.collection("company").document(company).collection("devices").document(id)
            .set(update, com.google.firebase.firestore.SetOptions.merge())
            .addOnSuccessListener { Log.d("FIRESTORE", "✅ token updated for $id (company=$company)") }
            .addOnFailureListener { e -> Log.e("FIRESTORE", "❌ token update fail", e) }
    }
}
