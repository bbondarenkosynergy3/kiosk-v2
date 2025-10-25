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
        val id = prefs.getString("device_id", null)

        if (id == null) {
            // Приложение ещё не создало deviceId — просто сохраним токен, MainActivity подхватит
            prefs.edit().putString("pending_token", token).apply()
            return
        }

        val update = mapOf(
            "token" to token,
            "timestamp" to System.currentTimeMillis()
        )

        db.collection("company").document("pierce").collection("devices").document(id)
            .set(update, com.google.firebase.firestore.SetOptions.merge())
            .addOnSuccessListener { Log.d("FIRESTORE", "✅ token updated for $id") }
            .addOnFailureListener { e -> Log.e("FIRESTORE", "❌ token update fail", e) }
    }
}