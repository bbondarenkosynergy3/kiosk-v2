package net.synergy360.kiosk

import android.app.Activity
import android.util.Log
import android.app.AlertDialog
import android.graphics.Color
import android.os.*
import android.view.*
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import java.util.*
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessaging
import android.os.Build
import net.synergy360.kiosk.BuildConfig

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var root: FrameLayout
    private var sleepOverlay: View? = null
    private var sleepLabel: TextView? = null
    private var offlineBanner: TextView? = null
    private val db = FirebaseFirestore.getInstance()

    // Shared prefs и стабильный ID устройства (создаётся один раз и хранится в prefs)
    private val prefs by lazy { getSharedPreferences("kiosk_prefs", MODE_PRIVATE) }

    private val deviceId: String by lazy {
        // не завязан на токен/версию apk → не меняется при обновлениях
        prefs.getString("device_id", null) ?: run {
            val id = "${Build.MODEL}_${UUID.randomUUID().toString().take(8)}"
            prefs.edit().putString("device_id", id).apply()
            id
        }
    }
    // === HEARTBEAT (обновление статуса каждые 10 мин) ===
    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private val heartbeatInterval = 10 * 60 * 1000L // 10 минут
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            sendHeartbeat()                                // отправляем обновление
            heartbeatHandler.postDelayed(this, heartbeatInterval) // планируем следующее
        }
    }
    // Night sleep window: 21:00 - 09:00 (device local time)
    private val sleepStartHour = 2
    private val sleepEndHour = 7

    // Manual wake duration after tap during sleep (ms)
    private val manualWakeMs = 60_000L
    private var manualWakeUntil: Long = 0L

    // Periodic check for sleep state
    private val checkIntervalMs = 15_000L
    private val handler = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() {
            applySleepStateIfNeeded()
            handler.postDelayed(this, checkIntervalMs)
        }
    }

    // Admin gesture: tap 4 corners within 5s in any order
    private val gestureWindowMs = 5_000L
    private val tappedCorners = mutableSetOf<Int>()
    private var gestureStartTs: Long = 0L

    companion object {
        private const val CORNER_TL = 1
        private const val CORNER_TR = 2
        private const val CORNER_BL = 3
        private const val CORNER_BR = 4
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Immersive fullscreen
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION

        // Keep screen on while app is running (prevents system timeout)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        root = FrameLayout(this)
        setContentView(root)

        // WebView
        webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false
            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                showOffline("Reconnecting…")
            }
            override fun onPageFinished(view: WebView?, url: String?) { hideOffline() }
        }
        webView.loadUrl("https://360synergy.net/kiosk/")


        root.addView(webView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // Touch layer: admin gesture + tap-to-wake
        val touchLayer = object : View(this) {
            override fun onTouchEvent(e: MotionEvent?): Boolean {
                if (e?.action == MotionEvent.ACTION_DOWN) {
                    handleCornerTap(e.x, e.y)
                    if (isInSleepWindow() && isSleeping()) {
                        // Wake on any touch (no reload)
                        manualWakeUntil = SystemClock.uptimeMillis() + manualWakeMs
                        removeSleepOverlay()
                    }
                }
                return false
            }
        }
        root.addView(touchLayer, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))

        // Start periodic checker
        handler.post(tick)

        // Try LockTask (may prompt the first time)
        try { startLockTask() } catch (_: Exception) {}

        // === Получение FCM токена и регистрация устройства ===
        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token ->
                Log.d("FCM", "✅ Token fetched: $token")
                registerDevice(token)  // создаёт или обновляет устройство в Firestore
            }
            .addOnFailureListener { e ->
                Log.e("FCM", "❌ Failed to fetch FCM token", e)

                // Даже если токен не получен (нет сети и т.д.), всё равно создадим запись
                val localId = deviceId
                val data = mapOf(
                    "brand" to Build.BRAND,
                    "model" to Build.MODEL,
                    "sdk" to Build.VERSION.SDK_INT,
                    "timestamp" to System.currentTimeMillis(),
                    "status" to "online",
                    "token" to "unavailable"
                )
                db.collection("devices").document(localId)
                    .set(data, com.google.firebase.firestore.SetOptions.merge())
                    .addOnSuccessListener {
                        Log.d("FIRESTORE", "✅ Device registered without token (ID: $localId)")
                    }
            }
    }

    // Sleep logic
    private fun isInSleepWindow(): Boolean {
        val cal = Calendar.getInstance()
        val h = cal.get(Calendar.HOUR_OF_DAY)
        return if (sleepStartHour < sleepEndHour) {
            h in sleepStartHour until sleepEndHour
        } else {
            (h >= sleepStartHour) || (h < sleepEndHour)
        }
    }

    private fun isSleeping(): Boolean = sleepOverlay != null

    private fun applySleepStateIfNeeded() {
        val now = SystemClock.uptimeMillis()
        val manualAwake = now < manualWakeUntil

        if (isInSleepWindow()) {
            if (!manualAwake) {
                showSleepOverlay()
            } else {
                removeSleepOverlay()
            }
        } else {
            removeSleepOverlay()
            manualWakeUntil = 0L
        }
    }

    private fun showSleepOverlay() {
        if (sleepOverlay == null) {
            sleepOverlay = View(this).apply {
                setBackgroundColor(Color.BLACK)
                alpha = 1f
            }
            root.addView(sleepOverlay, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ))
            sleepLabel = TextView(this).apply {
                text = "Device sleeping"
                setTextColor(Color.GRAY)
                textSize = 14f
            }
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            lp.gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            lp.bottomMargin = 48
            root.addView(sleepLabel, lp)
        }
    }

    private fun removeSleepOverlay() {
        sleepOverlay?.let {
            root.removeView(it)
            sleepOverlay = null
        }
        sleepLabel?.let {
            root.removeView(it)
            sleepLabel = null
        }
    }

    // Offline banner
    private fun showOffline(text: String) {
        if (offlineBanner == null) {
            offlineBanner = TextView(this).apply {
                setBackgroundColor(0xCC000000.toInt())
                setTextColor(Color.WHITE)
                textSize = 16f
                setPadding(32, 24, 32, 24)
                textAlignment = TextView.TEXT_ALIGNMENT_CENTER
            }
            val lp = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            lp.gravity = Gravity.TOP
            root.addView(offlineBanner, lp)
        }
        offlineBanner?.text = text
        offlineBanner?.visibility = View.VISIBLE
    }

    private fun hideOffline() { offlineBanner?.visibility = View.GONE }

    // Admin gesture (4 corners, any order within 5s)
    private fun handleCornerTap(x: Float, y: Float) {
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        val m = (w.coerceAtMost(h) * 0.15f)

        val corner = when {
            x < m && y < m -> CORNER_TL
            x > w - m && y < m -> CORNER_TR
            x < m && y > h - m -> CORNER_BL
            x > w - m && y > h - m -> CORNER_BR
            else -> 0
        }
        if (corner == 0) return

        val now = SystemClock.uptimeMillis()
        if (gestureStartTs == 0L || now - gestureStartTs > gestureWindowMs) {
            tappedCorners.clear()
            gestureStartTs = now
        }
        tappedCorners.add(corner)

        if (tappedCorners.size == 4 && now - gestureStartTs <= gestureWindowMs) {
            tappedCorners.clear()
            gestureStartTs = 0L
            confirmExit()
        }
    }

    private fun confirmExit() {
        AlertDialog.Builder(this)
            .setTitle("Exit kiosk mode?")
            .setMessage("Close the app and leave fullscreen.")
            .setPositiveButton("Yes") { _, _ ->
                try { stopLockTask() } catch (_: Exception) {}
                finish()
            }
            .setNegativeButton("No", null)
            .show()
    }


    // FIRESTORE SYNC
        private fun registerDevice(token: String) {
        val data = mapOf(
            "token" to token,
            "brand" to Build.BRAND,
            "model" to Build.MODEL,
            "sdk" to Build.VERSION.SDK_INT,
            "timestamp" to System.currentTimeMillis(),
            "status" to "online",
            "command" to "idle"
        )

        // Один документ на устройство. merge() — обновит поля, дубликат не создаст.
        db.collection("devices").document(deviceId)
            .set(data, com.google.firebase.firestore.SetOptions.merge())
            .addOnSuccessListener { Log.d("FIRESTORE", "✅ upsert ok (id=$deviceId)") }
            .addOnFailureListener { e -> Log.e("FIRESTORE", "❌ upsert fail", e) }
    }

    private fun updateStatus(status: String) {
        val now = System.currentTimeMillis()
        val update = mapOf(
            "status" to status,
            "lastSeen" to now,
            "timestamp" to now
        )

        // Важно: deviceId уже инициализирован лениво выше, он НЕ пустой.
        db.collection("devices").document(deviceId)
            .set(update, com.google.firebase.firestore.SetOptions.merge())
            .addOnSuccessListener { Log.d("FIRESTORE", "status=$status (id=$deviceId)") }
            .addOnFailureListener { e -> Log.e("FIRESTORE", "status update fail", e) }
    }


    private fun sendHeartbeat() {
        val now = System.currentTimeMillis()
        val updateData = mapOf(
            "status" to "online",
            "lastSeen" to now,
            "heartbeat" to true,
            "timestamp" to now
        )

        db.collection("devices").document(deviceId)
            .set(updateData, com.google.firebase.firestore.SetOptions.merge())
            .addOnSuccessListener {
                Log.d("HEARTBEAT", "❤️ Heartbeat sent (ID: $deviceId)")
            }
            .addOnFailureListener { e ->
                Log.e("HEARTBEAT", "💔 Failed to send heartbeat", e)
            }
    }

    override fun onResume() {
        super.onResume()
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        handler.post(tick)
        webView.onResume()
        // ⚡️ стартуем heartbeat-таймер
        heartbeatHandler.post(heartbeatRunnable)
        // и сразу отметим «я онлайн»
        updateStatus("online")
        heartbeatHandler.post(heartbeatRunnable)
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
        webView.onPause()

        // 🛑 останавливаем heartbeat-таймер
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        // и пишем, что девайс оффлайн
        updateStatus("offline")
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        updateStatus("offline")
    }
}
