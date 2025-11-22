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
        val cmd = msg.data["cmd"] ?: return
        val cmdId = msg.data["cmdId"] ?: "unknown"

        Log.d("FCM", "🔥 FCM_PUSH: cmd=$cmd cmdId=$cmdId payload=${msg.data}")

        when (cmd) {

            "sleep" -> {
                val dpm = getSystemService(DevicePolicyManager::class.java)
                try {
                    dpm.lockNow()
                    Log.d("FCM", "Screen locked from FCM")
                } catch (e: Exception) {
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
                Log.d("FCM", "Screen woken from FCM")
            }

            "reload" -> {
                getSharedPreferences("kiosk_prefs", MODE_PRIVATE)
                    .edit().putBoolean("pending_reload", true).apply()
                Log.d("FCM", "Reload flag set")
            }

            "update_now" -> {
                val url = msg.data["url"]
                if (url != null) {
                    UpdateHelper(this).startUpdate(url)
                    Log.d("FCM", "Update triggered from FCM: $url")
                } else {
                    Log.e("FCM", "Update command received but no URL")
                }
            }
        }
    }

    override fun onNewToken(token: String) {
        Log.d("FCM", "New FCM token: $token")
        // Здесь НИЧЕГО не нужно — MainActivity сам регистрирует устройство и токен.
        // Просто логируем.
    }
}
