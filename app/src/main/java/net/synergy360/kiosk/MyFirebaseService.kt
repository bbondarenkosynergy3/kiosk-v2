package net.synergy360.kiosk

import android.os.Build
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.google.android.gms.tasks.OnSuccessListener
import com.google.android.gms.tasks.OnFailureListener

class MyFirebaseService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        Log.d("FCM_TOKEN", "New token: $token")

        val db = Firebase.firestore
        val deviceId = Build.MODEL

        val data = hashMapOf(
            "token" to token,
            "brand" to Build.BRAND,
            "model" to Build.MODEL,
            "timestamp" to System.currentTimeMillis(),
            "status" to "online",
            "command" to "idle"
        )

        db.collection("devices")
            .add(data)
            .addOnSuccessListener(OnSuccessListener<Void> {
        Log.d("Firestore", "Token saved")
              })
            .addOnFailureListener(OnFailureListener { e ->
        Log.e("Firestore", "Error saving token", e)
    })

    override fun onMessageReceived(message: RemoteMessage) {
        Log.d("FCM_MESSAGE", "Received message: ${message.data}")
        // 🔹 Здесь позже будет логика команд Firestore или FCM
    }
}