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

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
    Log.d("FCM", "Message received: ${remoteMessage.data}")
}
}