package net.synergy360.kiosk

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import android.os.Build
import android.util.Log

class ForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        Log.d("FG_SERVICE", "ForegroundService created")

        acquireWakeLock()
        startAsForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.d("FG_SERVICE", "ForegroundService started")
        return START_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d("FG_SERVICE", "ForegroundService destroyed")
        wakeLock?.release()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ------------------------------------------------------------
    //  PARTIAL_WAKE_LOCK — держит CPU 24/7 (экран может спать)
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
            Log.d("FG_SERVICE", "PARTIAL_WAKE_LOCK ACQUIRED")
        } catch (e: Exception) {
            Log.e("FG_SERVICE", "WakeLock error: ${e.message}")
        }
    }

    // ------------------------------------------------------------
    //  Старт сервиса без уведомления (Device Owner позволяет)
    // ------------------------------------------------------------
    private fun startAsForeground() {
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

        val notification =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, channelId)
                    .setContentTitle("")
                    .setContentText("")
                    .setSmallIcon(android.R.color.transparent) // полностью скрыто
                    .build()
            } else {
                Notification()
            }

        startForeground(1, notification)
    }
}
