package net.synergy360.kiosk

import android.app.Service
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat

class ForegroundService : Service() {

    companion object {
        private const val TAG = "FG_SERVICE"
        private const val CHANNEL_ID = "kiosk_fg_channel"
        private const val CHANNEL_NAME = "Kiosk Background Service"
        private const val NOTIFICATION_ID = 1
    }

    private var wakeLock: PowerManager.WakeLock? = null

    // отдельные хэндлеры, чтобы можно было корректно чистить
    private val watchdogHandler = Handler(Looper.getMainLooper())
    private val dailyRestartHandler = Handler(Looper.getMainLooper())

    // --------------------------------------------------------------------
    // Жизненный цикл
    // --------------------------------------------------------------------
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "ForegroundService created")

        requestBatteryWhitelist()
        acquireWakeLock()
        startDailyRestartTimer()
        startWatchdog()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d(TAG, "ForegroundService onStartCommand")

        // Единая точка запуска foreground-уведомления
        startForeground(NOTIFICATION_ID, buildNotification())

        return START_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        Log.d(TAG, "onTaskRemoved — restarting service")
        restartSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "ForegroundService destroyed — restarting")

        // Чистим все таймеры
        watchdogHandler.removeCallbacksAndMessages(null)
        dailyRestartHandler.removeCallbacksAndMessages(null)

        // Аккуратно отпускаем wakelock
        try {
            wakeLock?.let {
                if (it.isHeld) it.release()
            }
        } catch (e: Exception) {
            Log.e(TAG, "WakeLock release error: ${e.message}")
        }

        // Пытаемся перезапустить сервис
        restartSelf()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // --------------------------------------------------------------------
    // Перезапуск самого себя
    // --------------------------------------------------------------------
    private fun restartSelf() {
        try {
            val serviceIntent = Intent(this, ForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "restartSelf error: ${e.message}")
        }
    }

    // --------------------------------------------------------------------
    // WAKELOCK — держим CPU
    // --------------------------------------------------------------------
    private fun acquireWakeLock() {
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "kiosk:fg_partial_wakelock"
            ).apply {
                setReferenceCounted(false)
                acquire()
            }
            Log.d(TAG, "WAKELOCK acquired")
        } catch (e: Exception) {
            Log.e(TAG, "WakeLock error: ${e.message}")
        }
    }

    // --------------------------------------------------------------------
    // Уведомление + канал
    // --------------------------------------------------------------------
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
            .setContentText("Running in background")
            .setSmallIcon(android.R.drawable.stat_sys_warning) // при желании заменишь на свой
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    // --------------------------------------------------------------------
    // Игнорирование оптимизации батареи
    // --------------------------------------------------------------------
    private fun requestBatteryWhitelist() {
        try {
            val pm = getSystemService(PowerManager::class.java)
            if (pm != null && !pm.isIgnoringBatteryOptimizations(packageName)) {
                val intent = Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:$packageName")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                startActivity(intent)
                Log.d(TAG, "Requested battery whitelist")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Whitelist error: ${e.message}")
        }
    }

    // --------------------------------------------------------------------
    // WATCHDOG — мягкий (без перезапуска самого сервиса каждые 30 сек)
    // --------------------------------------------------------------------
    private fun startWatchdog() {
        watchdogHandler.post(object : Runnable {
            override fun run() {
                try {
                    Log.d(TAG, "Watchdog tick")
                    // Здесь можно при желании делать проверку чего-то,
                    // но без повторного вызова startForegroundService(ForegroundService)
                } finally {
                    // Раз в 5 минут, а не каждые 30 секунд
                    watchdogHandler.postDelayed(this, 5 * 60_000L)
                }
            }
        })
    }

    // --------------------------------------------------------------------
    // Авто-рестарт приложения в 03:00
    // --------------------------------------------------------------------
    private fun startDailyRestartTimer() {
        dailyRestartHandler.post(object : Runnable {
            override fun run() {
                val cal = java.util.Calendar.getInstance()
                val hour = cal.get(java.util.Calendar.HOUR_OF_DAY)
                val minute = cal.get(java.util.Calendar.MINUTE)

                if (hour == 3 && minute == 0) {
                    Log.d(TAG, "Daily restart at 03:00")
                    restartApp()
                }

                // проверяем каждую минуту
                dailyRestartHandler.postDelayed(this, 60_000L)
            }
        })
    }

    private fun restartApp() {
        try {
            val ctx = applicationContext
            val launchIntent = ctx.packageManager
                .getLaunchIntentForPackage(ctx.packageName)
            launchIntent?.apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(this)
            }
        } catch (e: Exception) {
            Log.e(TAG, "restartApp error: ${e.message}")
        }
    }
}
