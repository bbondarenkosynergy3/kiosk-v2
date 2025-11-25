package net.synergy360.kiosk

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.*
import android.util.Log

class ForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private val watchdogHandler = Handler(Looper.getMainLooper())

    override fun onCreate() {
        super.onCreate()
        Log.d("FG_SERVICE", "ForegroundService created")

        requestBatteryWhitelist()
        acquireWakeLock()
        startAsForeground()
        startWatchdog()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("FG_SERVICE", "ForegroundService started")

        // Android 12+ protection (foreground restart)
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                startForeground(
                    1,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } catch (_: Exception) {}
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("FG_SERVICE", "ForegroundService destroyed — restarting")

        wakeLock?.release()

        // авто перезапуск
        try {
            val i = Intent(this, ForegroundService::class.java)
            startForegroundService(i)
        } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------
    // PARTIAL WAKELOCK — держит CPU 24/7 (экран может спать)
    // ------------------------------------------------------------
    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "kiosk:fg_partial_wakelock"
            )
            wakeLock?.setReferenceCounted(false)
            wakeLock?.acquire()
            Log.d("FG_SERVICE", "WAKELOCK acquired")
        } catch (e: Exception) {
            Log.e("FG_SERVICE", "WakeLock error: ${e.message}")
        }
    }

    // ------------------------------------------------------------
    // SAFEST FOREGROUND START
    // ------------------------------------------------------------
    private fun startAsForeground() {
        val notification = buildNotification()
        startForeground(1, notification)
    }

    private fun buildNotification(): Notification {
        val channelId = "kiosk_fg_channel"
        val channelName = "Kiosk Background Service"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                channelId,
                channelName,
                NotificationManager.IMPORTANCE_MIN
            )
            chan.setSound(null, null)
            chan.enableLights(false)
            chan.enableVibration(false)

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(chan)
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, channelId)
                .setContentTitle("")
                .setContentText("")
                .setSmallIcon(android.R.color.transparent)
                .build()
        } else {
            Notification()
        }
    }

    // ------------------------------------------------------------
    // BATTERY OPTIMIZATION WHITELIST
    // ------------------------------------------------------------
    private fun requestBatteryWhitelist() {
        try {
            val pm = getSystemService(PowerManager::class.java)
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:$packageName")
                )
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                Log.d("FG_SERVICE", "Requested battery whitelist")
            }
        } catch (e: Exception) {
            Log.e("FG_SERVICE", "Whitelist error: ${e.message}")
        }
    }

    // ------------------------------------------------------------
    // WATCHDOG — держит сервис живым
    // ------------------------------------------------------------
    private fun startWatchdog() {
        watchdogHandler.post(object : Runnable {
            override fun run() {
                try {
                    startForegroundService(
                        Intent(this@ForegroundService, ForegroundService::class.java)
                    )
                } catch (_: Exception) {}

                watchdogHandler.postDelayed(this, 30_000L)
            }
        })
    }
}
