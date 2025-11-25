package net.synergy360.kiosk

import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.app.admin.DevicePolicyManager
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class MyFirebaseService : FirebaseMessagingService() {

    /* --------------------------------------------------
       🔵 HELPERS
       -------------------------------------------------- */

    /** Sleep helper (Device Owner only) */
    private fun sleepDevice(): Boolean {
        return try {
            val dpm = getSystemService(DevicePolicyManager::class.java)
            dpm.lockNow()
            Log.d("SLEEP", "lockNow() executed")
            logFs("sleep_executed", "Device locked")
            true
        } catch (e: Exception) {
            Log.e("SLEEP", "sleep failed: ${e.message}")
            logFs("sleep_failed", e.message ?: "error")
            false
        }
    }

    /** Wake helper — имитация кнопки Power */
    private fun wakeDeviceLikePower(): Boolean {
        return try {
            val pm = getSystemService(PowerManager::class.java)
            @Suppress("DEPRECATION")
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "kiosk:fcm_wake"
            )

            wl.acquire(3000)
            wl.release()

            Log.d("WAKE", "Wake via wakelock")
            logFs("wake_executed", "screen on by wakelock")
            true
        } catch (e: Exception) {
            Log.e("WAKE", "wake failed: ${e.message}")
            logFs("wake_failed", e.message ?: "error")
            false
        }
    }

    /** Авто-retry для WAKE (если экран в Doze) */
    private fun wakeWithRetry() {
        if (wakeDeviceLikePower()) return

        Handler(Looper.getMainLooper()).postDelayed({
            Log.d("WAKE", "Retrying wake after 2s…")
            wakeDeviceLikePower()
        }, 2000)
    }

    /** Firestore логгер */
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

    /* --------------------------------------------------
       🔵 MAIN FCM HANDLER
       -------------------------------------------------- */
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        val data = remoteMessage.data
        if (data.isEmpty()) {
            Log.d("FCM", "📩 Empty FCM message received")
            return
        }

        val command = data["command"] ?: data["cmd"] ?: return
        val cmdId = data["commandId"] ?: data["cmdId"] ?: System.currentTimeMillis().toString()

        Log.d("FCM", "🔥 FCM command received: $command id=$cmdId payload=$data")
        logFs("fcm_received", "cmd=$command id=$cmdId")

        val prefs = getSharedPreferences("kiosk_prefs", MODE_PRIVATE)

        when (command) {

            /* -----------------------------
               🗓 НОВЫЙ ВАРИАНТ: set_full_schedule
               ----------------------------- */
            "set_full_schedule" -> {
                val json = data["scheduleJson"]
                if (json.isNullOrEmpty()) {
                    ack(cmdId, false, "missing_scheduleJson")
                } else {
                    ScheduleManager.saveFullSchedule(this, json)
                    ScheduleManager.applyTodayFromPrefs(this)  // <— важно!
                    ack(cmdId, true, "full_schedule_saved_and_applied")
                }
            }

            /* -----------------------------
               🛏 SLEEP
               ----------------------------- */
            "sleep" -> {
                val ok = sleepDevice()
                ack(cmdId, ok, if (ok) "screen locked" else "sleep failed")
            }

            /* -----------------------------
               🌅 WAKE
               ----------------------------- */
            "wake" -> {
                wakeWithRetry()
                ack(cmdId, true, "wake triggered")
            }

            /* -----------------------------
               🔄 RELOAD WEBVIEW
               ----------------------------- */
            "reload" -> {
                prefs.edit().putBoolean("pending_reload", true).apply()
                ack(cmdId, true, "scheduled")
            }

            /* -----------------------------
               📡 UPDATE
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
       🔵 ACK to Firestore
       -------------------------------------------------- */
    private fun ack(cmdId: String, ok: Boolean, msg: String) {
        val prefs = getSharedPreferences("kiosk_prefs", MODE_PRIVATE)
        val deviceId = prefs.getString("device_id", null)
        val company = prefs.getString("company", "unknowncompany")!!

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
       🔄 TOKEN
       -------------------------------------------------- */
    override fun onNewToken(token: String) {
        Log.d("FCM", "🔄 New token: $token")

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

        FirebaseFirestore.getInstance()
            .collection("company")
            .document(company)
            .collection("devices")
            .document(id)
            .set(update, com.google.firebase.firestore.SetOptions.merge())
            .addOnSuccessListener {
                Log.d("FIRESTORE", "✅ token updated for $id")
            }
            .addOnFailureListener { e ->
                Log.e("FIRESTORE", "❌ token update fail", e)
            }
        try {
            startForegroundService(Intent(this, ForegroundService::class.java))
        } catch (_: Exception) {}
    }
}
