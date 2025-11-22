package net.synergy360.kiosk

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.os.PowerManager
import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class FCMCommandReceiver : FirebaseMessagingService() {

    override fun onMessageReceived(msg: RemoteMessage) {
        val cmd = msg.data["command"] ?: return
        val payload = msg.data

        Log.d("FCM", "🔥 FCM command: $cmd payload=$payload")

        when (cmd) {
            "sleep" -> {
                val dpm = getSystemService(DevicePolicyManager::class.java)
                val admin = ComponentName(this, MyDeviceAdminReceiver::class.java)
                try { dpm.lockNow() } catch (e: Exception) {
                    Log.e("FCM", "sleep failed: ${e.message}")
                }
            }

            "wake" -> {
                val pm = getSystemService(PowerManager::class.java)
                @Suppress("DEPRECATION")
                val wl = pm.newWakeLock(
                    PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                    "kiosk:wake"
                )
                wl.acquire(3000)
                wl.release()
            }

            "reload" -> {
                // Ставим флаг, чтобы MainActivity reload сделала при запуске
                getSharedPreferences("kiosk_prefs", MODE_PRIVATE)
                    .edit().putBoolean("pending_reload", true).apply()
            }

            "update" -> {
                val url = payload["url"]
                if (url != null)
                    UpdateHelper(this).startUpdate(url)
            }
        }
    }

    override fun onNewToken(token: String) {
        Log.d("FCM", "New FCM token: $token")
        // Отправим в Firestore
        DeviceRegistrar.updateToken(this, token)
    }
}
