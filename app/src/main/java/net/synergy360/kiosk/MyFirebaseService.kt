package net.synergy360.kiosk

import android.os.Build
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class MyFirebaseService : FirebaseMessagingService() {

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        Log.d("FCM", "Message received: ${remoteMessage.data}")

        val action = remoteMessage.data["action"]
        when (action) {
            "reload" -> {
                Log.d("FCM", "Reload command received")
                // TODO: реализовать reload страницы через BroadcastReceiver
            }
            "lock" -> {
                Log.d("FCM", "Lock command received")
            }
            "wake" -> {
                Log.d("FCM", "Wake command received")
            }
            "reboot" -> {
                Log.d("FCM", "Reboot command received")
            }
            else -> {
                Log.d("FCM", "Unknown action: $action")
            }
        }
    }

    override fun onNewToken(token: String) {
    Log.d("FCM", "New token: $token")

    val db = FirebaseFirestore.getInstance()
    val deviceInfo = hashMapOf(
        "token" to token,
        "model" to Build.MODEL,
        "brand" to Build.BRAND,
        "sdk" to Build.VERSION.SDK_INT,
        "timestamp" to System.currentTimeMillis()
    )

    db.collection("devices")
        .document(Build.MODEL) // 👈 фиксированный ID устройства
        .set(deviceInfo)
        .addOnSuccessListener { Log.d("Firestore", "✅ Device registered successfully!") }
        .addOnFailureListener { e -> Log.w("Firestore", "❌ Error adding device", e) }
}
}