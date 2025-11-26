package net.synergy360.kiosk

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.*
import android.util.Log
import android.provider.Settings
import androidx.core.app.NotificationCompat   // ✔ FIX
import android.content.pm.ServiceInfo

class ForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private val watchdogHandler = Handler(Looper.getMainLooper())

    // ✔ Добавлены константы
    private val CHANNEL_ID = "kiosk_fg_channel"
    private val NOTIFICATION_ID = 1

    override fun onCreate() {
        super.onCreate()
        Log.d("FG_SERVICE", "ForegroundService created")

        requestBatteryWhitelist()
        acquireWakeLock()
        startAsForeground()
        startDailyRestartTimer()
        startWatchdog()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("FG_SERVICE", "ForegroundService started")

        // Android 12+ защита — НО без указания типа (иначе SecurityException)
        if (Build.VERSION.SDK_INT >= 31) {
            try {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification()
                )
            } catch (_: Exception) {}
        }

        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("FG_SERVICE", "ForegroundService destroyed — restarting")

        wakeLock?.release()

        try {
            val i = Intent(this, ForegroundService::class.java)
            startForegroundService(i)
        } catch (_: Exception) {}
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------
    // PARTIAL WAKELOCK — держит CPU 24/7
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
    // SAFE FOREGROUND START
    // ------------------------------------------------------------
    private fun startAsForeground() {

        // ✔ заменена иконка (твоя отсутствует)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("360 Synergy Kiosk")
            .setContentText("Running")
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setOngoing(true)
            .build()

        startForeground(
            NOTIFICATION_ID,
            notification
        )
    }

    private fun buildNotification(): Notification {

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val chan = NotificationChannel(
                CHANNEL_ID,
                "Kiosk Background Service",
                NotificationManager.IMPORTANCE_MIN
            )
            chan.setSound(null, null)
            chan.enableLights(false)
            chan.enableVibration(false)

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(chan)
        }

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
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
    // WATCHDOG — держит сервис в живом состоянии
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

    // =====================================================================
    // DAILY AUTO-RESTART — 03:00
    // =====================================================================
    private fun startDailyRestartTimer() {
        val handler = Handler(mainLooper)

        handler.post(object : Runnable {
            override fun run() {
                val cal = java.util.Calendar.getInstance()
                val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
                val minute = cal.get(java.util.Calendar.MINUTE)

                if (hour == 3 && minute <= 1) {
                    Log.d("DAILY_RESTART", "Restarting app at 03:00")
                    restartApp()
                }

                handler.postDelayed(this, 60000)
            }
        })
    }

    private fun restartApp() {
        val ctx = applicationContext
        val i = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
        i?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        ctx.startActivity(i)
    }
}
