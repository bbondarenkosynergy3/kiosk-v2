package net.synergy360.kiosk

import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class MyFirebaseService : FirebaseMessagingService() {

    // ------------------------------------------------------------
    // 🔵  Firestore logger
    // ------------------------------------------------------------
    private fun logFs(event: String, msg: String) {
        try {
            val prefs = getSharedPreferences("kiosk_prefs", MODE_PRIVATE)
            val deviceId = prefs.getString("device_id", "unknown")
            val company = prefs.getString("company", "unknown")

            val data = mapOf(
                "event" to event,
                "message" to msg,
                "timestamp" to System.currentTimeMillis(),
                "deviceId" to deviceId,
                "company" to company
            )

            FirebaseFirestore.getInstance()
                .collection("fcmEvents")
                .add(data)
        } catch (_: Exception) {}
    }

    // ------------------------------------------------------------
    // 🔵  Sleep (Device Owner)
    // ------------------------------------------------------------
    private fun sleepDevice(): Boolean {
        return try {
            val dpm = getSystemService(DevicePolicyManager::class.java)
            val admin = ComponentName(this, MyDeviceAdminReceiver::class.java)

            // включаем стандартный локскрин
            try { dpm.setKeyguardDisabled(admin, false) } catch (_: Exception) {}

            dpm.lockNow()
            logFs("sleep_executed", "Device locked")
            true
        } catch (e: Exception) {
            logFs("sleep_failed", e.message ?: "unknown")
            false
        }
    }

    // ------------------------------------------------------------
    // 🔵  Wake (через PowerManager)
    // ------------------------------------------------------------
    private fun wakeDevice(): Boolean {
        return try {
            val pm = getSystemService(PowerManager::class.java)
            @Suppress("DEPRECATION")
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "kiosk:fcm_wake"
            )
            wl.acquire(2500)
            wl.release()

            logFs("wake_executed", "Device woken by wakelock")
            true
        } catch (e: Exception) {
            logFs("wake_failed", e.message ?: "unknown")
            false
        }
    }

    private fun wakeWithRetry() {
        if (wakeDevice()) return

        Handler(Looper.getMainLooper()).postDelayed({
            Log.d("WAKE", "Retrying wake…")
            wakeDevice()
        }, 2000)
    }

    // ------------------------------------------------------------
    // 🔵  Main FCM handler
    // ------------------------------------------------------------
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        val data = remoteMessage.data
        if (data.isEmpty()) return

        val command = data["command"] ?: data["cmd"] ?: return
        val cmdId = data["commandId"] ?: data["cmdId"] ?: System.currentTimeMillis().toString()

        Log.d("FCM", "Received command=$command id=$cmdId payload=$data")
        logFs("fcm_received", "cmd=$command")

        val prefs = getSharedPreferences("kiosk_prefs", MODE_PRIVATE)

        when (command) {

            // ----------------------------------------------------
            // SET FULL SCHEDULE (новый вариант)
            // ----------------------------------------------------
            "set_full_schedule" -> {
                val json = data["scheduleJson"]
                if (json.isNullOrEmpty()) {
                    ack(cmdId, false, "missing_scheduleJson")
                } else {
                    ScheduleManager.saveFullSchedule(this, json)
                    ScheduleManager.applyTodayFromPrefs(this)
                    ack(cmdId, true, "schedule_saved_and_applied")
                }
            }

            // ----------------------------------------------------
            // SLEEP
            // ----------------------------------------------------
            "sleep" -> {
                val ok = sleepDevice()
                ack(cmdId, ok, if (ok) "sleep_ok" else "sleep_failed")
            }

            // ----------------------------------------------------
            // WAKE
            // ----------------------------------------------------
            "wake" -> {
                wakeWithRetry()
                ack(cmdId, true, "wake_triggered")
            }

            // ----------------------------------------------------
            // RELOAD WEBVIEW (handled in MainActivity)
            // ----------------------------------------------------
            "reload" -> {
                prefs.edit().putBoolean("pending_reload", true).apply()
                ack(cmdId, true, "reload_flag_set")
            }

            // ----------------------------------------------------
            // UPDATE APK
            // ----------------------------------------------------
            "update" , "update_now" -> {
                val url = data["url"]
                if (url.isNullOrEmpty()) {
                    ack(cmdId, false, "missing_url")
                    return
                }
                try {
                    UpdateHelper(this).startUpdate(url)
                    ack(cmdId, true, "update_started")
                } catch (e: Exception) {
                    ack(cmdId, false, "update_failed: ${e.message}")
                }
            }

            // ----------------------------------------------------
            // OPEN URL (handled in MainActivity)
            // ----------------------------------------------------
            "open_url" -> {
                val url = data["url"]
                if (url.isNullOrEmpty()) {
                    ack(cmdId, false, "missing_url")
                } else {
                    prefs.edit().putString("pending_open_url", url).apply()
                    ack(cmdId, true, "url_saved")
                }
            }
        }
    }

    // ------------------------------------------------------------
    // 🔵 ACK → Firestore
    // ------------------------------------------------------------
    private fun ack(cmdId: String, ok: Boolean, msg: String) {
        val prefs = getSharedPreferences("kiosk_prefs", MODE_PRIVATE)
        val deviceId = prefs.getString("device_id", null)
        val company = prefs.getString("company", "unknown") ?: "unknown"

        if (deviceId == null) return

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
    }

    // ------------------------------------------------------------
    // 🔵 Update FCM token
    // ------------------------------------------------------------
    override fun onNewToken(token: String) {
        Log.d("FCM", "New token: $token")

        val prefs = getSharedPreferences("kiosk_prefs", MODE_PRIVATE)
        val deviceId = prefs.getString("device_id", null)
        val company = prefs.getString("company", "unknown") ?: "unknown"

        if (deviceId == null) {
            prefs.edit().putString("pending_token", token).apply()
            return
        }

        val update = mapOf(
            "token" to token,
            "timestamp" to System.currentTimeMillis()
        )

        FirebaseFirestore.getInstance()
            .collection("company")
            .document(company)
            .collection("devices")
            .document(deviceId)
            .set(update, com.google.firebase.firestore.SetOptions.merge())

        // 💡 Важно: НЕ перезапускаем сервис каждые 30 сек,
        // но если токен обновился — можно мягко его запустить.
        try {
            startForegroundService(Intent(this, ForegroundService::class.java))
        } catch (_: Exception) {}
    }
}
