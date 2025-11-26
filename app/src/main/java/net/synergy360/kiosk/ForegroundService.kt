package net.synergy360.kiosk

import android.app.Service
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.*
import android.util.Log
import android.provider.Settings
import android.app.admin.DevicePolicyManager
import androidx.core.app.NotificationCompat
import com.google.firebase.firestore.FirebaseFirestore

class ForegroundService : Service() {

    companion object {
        private const val TAG = "FG_SERVICE"
        private const val CHANNEL_ID = "kiosk_fg_channel"
        private const val CHANNEL_NAME = "Kiosk Background Service"
        private const val NOTIFICATION_ID = 1
    }

    private var wakeLock: PowerManager.WakeLock? = null

    private val watchdogHandler = Handler(Looper.getMainLooper())
    private val restartHandler = Handler(Looper.getMainLooper())

    // -------------------------
    // 🔵 SCHEDULE ENGINE
    // -------------------------
    private val scheduleHandler = Handler(Looper.getMainLooper())
    private var lastScheduleState: String = "unknown" // "sleep" / "awake"

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "onCreate")

        // 1) foreground сразу
        startForeground(NOTIFICATION_ID, buildNotification())

        // 2) WakeLock
        acquireWakeLock()

        // 3) battery optimizations
        requestBatteryWhitelistSafe()

        // 4) Timers
        startWatchdog()
        startDailyRestartTimer()

        // 5) Авто-сон / авто-wake
        startScheduleChecker()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "onStartCommand")
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.e(TAG, "SERVICE DESTROYED — restarting")

        watchdogHandler.removeCallbacksAndMessages(null)
        restartHandler.removeCallbacksAndMessages(null)
        scheduleHandler.removeCallbacksAndMessages(null)

        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (_: Exception) {}

        restartSelf()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        restartSelf()
    }

    override fun onBind(intent: Intent?) = null

    // -------------------------------------------------------------
    // Foreground notification
    // -------------------------------------------------------------
    private fun createChannelIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL_ID) == null) {

                val channel = NotificationChannel(
                    CHANNEL_ID,
                    CHANNEL_NAME,
                    NotificationManager.IMPORTANCE_MIN
                ).apply {
                    setSound(null, null)
                    enableLights(false)
                    enableVibration(false)
                    setShowBadge(false)
                }
                manager.createNotificationChannel(channel)
            }
        }
    }

    private fun buildNotification(): Notification {
        createChannelIfNeeded()

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("360 Synergy Kiosk")
            .setContentText("Running")
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    // -------------------------------------------------------------
    // WakeLock
    // -------------------------------------------------------------
    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "kiosk:fg_partial"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.d(TAG, "WakeLock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "WakeLock error: ${e.message}")
        }
    }

    // -------------------------------------------------------------
    // Safe battery whitelist
    // -------------------------------------------------------------
    private fun requestBatteryWhitelistSafe() {
        try {
            val pm = getSystemService(PowerManager::class.java)
            if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {

                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        val intent = Intent(
                            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                            android.net.Uri.parse("package:$packageName")
                        ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
                        startActivity(intent)
                    } catch (_: Exception) {}
                }, 3000)
            }
        } catch (_: Exception) {}
    }

    // -------------------------------------------------------------
    // WATCHDOG
    // -------------------------------------------------------------
    private fun startWatchdog() {
        watchdogHandler.post(object : Runnable {
            override fun run() {

                val alive = isProcessAlive()

                if (!alive) {
                    Log.e(TAG, "Watchdog: activity dead → restarting app")
                    restartApp()
                }

                watchdogHandler.postDelayed(this, 2 * 60_000L)
            }
        })
    }

    private fun isProcessAlive(): Boolean {
        return try {
            val pid = android.os.Process.myPid()
            pid > 0
        } catch (_: Exception) {
            false
        }
    }

    // -------------------------------------------------------------
    // DAILY RESTART (03:00)
    // -------------------------------------------------------------
    private fun startDailyRestartTimer() {
        restartHandler.post(object : Runnable {
            override fun run() {

                val cal = java.util.Calendar.getInstance()
                if (cal.get(java.util.Calendar.HOUR_OF_DAY) == 3 &&
                    cal.get(java.util.Calendar.MINUTE) == 0
                ) {
                    Log.d(TAG, "Daily restart at 03:00")
                    restartApp()
                }

                restartHandler.postDelayed(this, 60_000L)
            }
        })
    }

    // -------------------------------------------------------------
    // 🔵 SCHEDULE — CHECKER
    // -------------------------------------------------------------
    private fun startScheduleChecker() {
        scheduleHandler.post(object : Runnable {
            override fun run() {

                try {
                    applyScheduleState()
                } catch (e: Exception) {
                    Log.e("SCHEDULE", "Schedule error: ${e.message}")
                }

                scheduleHandler.postDelayed(this, 60_000L)
            }
        })
    }

private fun applyScheduleState() {
    val schedule = ScheduleManager.getScheduleForToday(this) ?: return

    val cal = java.util.Calendar.getInstance()
    val h = cal.get(java.util.Calendar.HOUR_OF_DAY)
    val m = cal.get(java.util.Calendar.MINUTE)
    val nowMinutes = h * 60 + m

    val sleepMinutes = schedule.sleepH * 60 + schedule.sleepM
    val wakeMinutes = schedule.wakeH * 60 + schedule.wakeM

    val shouldSleep =
        if (sleepMinutes < wakeMinutes) {
            // обычный режим
            nowMinutes >= sleepMinutes || nowMinutes < wakeMinutes
        } else {
            // ночной переход через 00:00
            nowMinutes >= sleepMinutes || nowMinutes < wakeMinutes
        }

    if (shouldSleep && lastScheduleState != "sleep") {
        Log.d("SCHEDULE", "Auto-sleep triggered")
        autoSleep()
        logSchedule("schedule_auto_sleep", "Device slept by schedule")
        lastScheduleState = "sleep"
    }

    if (!shouldSleep && lastScheduleState != "awake") {
        Log.d("SCHEDULE", "Auto-wake triggered")
        autoWake()
        logSchedule("schedule_auto_wake", "Device woken by schedule")
        lastScheduleState = "awake"
    }
}

    private fun autoSleep() {
        try {
            val dpm = getSystemService(DevicePolicyManager::class.java)
            val admin = android.content.ComponentName(this, MyDeviceAdminReceiver::class.java)

            if (dpm.isDeviceOwnerApp(packageName)) {
                dpm.lockNow()
            }
        } catch (e: Exception) {
            Log.e("SCHEDULE", "autoSleep error: ${e.message}")
        }
    }

    private fun autoWake() {
        try {
            val pm = getSystemService(PowerManager::class.java)
            @Suppress("DEPRECATION")
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "kiosk:auto_wake"
            )
            wl.acquire(3000)
            wl.release()
        } catch (e: Exception) {
            Log.e("SCHEDULE", "autoWake error: ${e.message}")
        }
    }

    private fun logSchedule(event: String, msg: String) {
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
                .collection("scheduleEvents")
                .add(data)
        } catch (_: Exception) {}
    }

    // -------------------------------------------------------------
    private fun restartSelf() {
        try {
            val i = Intent(this, ForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                startForegroundService(i)
            else
                startService(i)
        } catch (e: Exception) {
            Log.e(TAG, "restartSelf error: ${e.message}")
        }
    }

    private fun restartApp() {
        try {
            val ctx = applicationContext
            val intent = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
            intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "restartApp error: ${e.message}")
        }
    }
}
