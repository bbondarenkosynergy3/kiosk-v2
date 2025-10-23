package net.synergy360.kiosk

import android.os.Build
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class MyFirebaseService : FirebaseMessagingService() {

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        Log.d("FCM", "📩 Message received: ${remoteMessage.data}")
    }

    override fun onNewToken(token: String) {
        Log.d("FCM", "🔄 New token: $token")

        val db = FirebaseFirestore.getInstance()
        val prefs = getSharedPreferences("kiosk_prefs", MODE_PRIVATE)
        val deviceId = prefs.getString("device_id", null)

        if (deviceId != null) {
            val data = mapOf(
                "token" to token,
                "timestamp" to System.currentTimeMillis()
            )

            db.collection("devices").document(deviceId)
                .set(data, com.google.firebase.firestore.SetOptions.merge())
                .addOnSuccessListener {
                    Log.d("FIRESTORE", "✅ Token updated for existing device: $deviceId")
                }
                .addOnFailureListener { e ->
                    Log.e("FIRESTORE", "❌ Failed to update token", e)
                }
        } else {
            Log.w("FIRESTORE", "⚠️ No saved deviceId — skipping token update")
        }
    }
}