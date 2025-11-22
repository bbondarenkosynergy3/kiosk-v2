package net.synergy360.kiosk

import android.app.Activity
import android.content.Intent
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
import com.google.firebase.FirebaseApp
import android.os.Build
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.os.PowerManager

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var root: FrameLayout
    private var offlineBanner: TextView? = null
    private val db = FirebaseFirestore.getInstance()

    // === Safe WebView init with retries for DO/Knox ===
    private var webViewInitialized = false

    private fun initWebViewSafeWithRetry(retryCount: Int = 0) {
        if (webViewInitialized) return

        try {
            if (Build.VERSION.SDK_INT >= 28) {
                try {
                    WebView.setDataDirectorySuffix("kiosk")
                } catch (e: Exception) {
                    Log.e("WEBVIEW", "setDataDirectorySuffix failed: ${e.message}")
                }
            }

            webView = WebView(this)
            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ): Boolean = false

                override fun onReceivedError(
                    view: WebView,
                    request: WebResourceRequest,
                    error: WebResourceError
                ) {
                    showOffline("Reconnecting…")
                }

                override fun onPageFinished(view: WebView?, url: String?) {
                    hideOffline()
                }
            }

            root.addView(
                webView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )

            webViewInitialized = true
            Log.d("WEBVIEW", "✅ WebView initialized")
        } catch (t: Throwable) {
            Log.e("WEBVIEW", "WebView init failed (retryCount=$retryCount): ${t.message}")
            if (retryCount < 5) {
                Handler(Looper.getMainLooper()).postDelayed({
                    initWebViewSafeWithRetry(retryCount + 1)
                }, 3000)
            } else {
                showOffline("Web engine not ready")
            }
        }
    }

    // Универсальная функция логирования событий в Firestore
    private fun logEvent(tag: String, message: String) {
        try {
            val data = mapOf(
                "tag" to tag,
                "message" to message,
                "timestamp" to System.currentTimeMillis(),
                "deviceId" to deviceId,
                "company" to prefs.getString("company", "unknown")
            )
            FirebaseFirestore.getInstance().collection("startupLogs").add(data)
        } catch (e: Exception) {
            Log.e("LOGGING", "Failed to log to Firestore: ${e.message}")
        }
    }

    // Shared prefs и стабильный ID устройства (создаётся один раз и хранится в prefs)
    private val prefs by lazy { getSharedPreferences("kiosk_prefs", MODE_PRIVATE) }

    private val deviceId: String by lazy {
        prefs.getString("device_id", null) ?: run {
            val id = "${Build.MODEL}_${UUID.randomUUID().toString().take(8)}"
            prefs.edit().putString("device_id", id).apply()
            id
        }
    }

    // === HEARTBEAT (обновление статуса каждые 30 сек) ===
    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private val heartbeatInterval = 30_000L // 30 секунд
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            sendHeartbeat()
            heartbeatHandler.postDelayed(this, heartbeatInterval)
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
        try {
            // Firebase init
            FirebaseApp.initializeApp(this)
            try {
                FirebaseFirestore.getInstance().collection("startupLogs").add(
                    mapOf(
                        "event" to "Firebase init reached",
                        "time" to System.currentTimeMillis()
                    )
                )
                Log.d("FIREBASE", "✅ Firestore reached at init")
            } catch (e: Exception) {
                Log.e("FIREBASE", "❌ Failed to send Firestore init log: ${e.message}")
            }

            Handler(Looper.getMainLooper()).postDelayed({
                try {
                    FirebaseFirestore.getInstance().collection("startupLogs").add(
                        mapOf(
                            "event" to "Delayed Firestore test log after 5s",
                            "time" to System.currentTimeMillis()
                        )
                    )
                    Log.d("FIREBASE", "✅ Delayed Firestore test log sent")
                } catch (e: Exception) {
                    Log.e("FIREBASE", "❌ Failed delayed Firestore log: ${e.message}")
                }
            }, 5000)
            Log.d("FIREBASE", "✅ Firebase initialized successfully")

            // Ранний PROVISIONING_SUCCESSFUL
            val intent = Intent("android.app.action.PROVISIONING_SUCCESSFUL")
            intent.setPackage("com.android.managedprovisioning")
            sendBroadcast(intent)
            Log.i("Provisioning", "✅ Early PROVISIONING_SUCCESSFUL broadcast sent to system")
            logEvent("Provisioning", "Early PROVISIONING_SUCCESSFUL broadcast sent")
        } catch (e: Exception) {
            Log.e("Provisioning", "⚠️ Early provisioning setup failed: ${e.message}")
        }
        logEvent("Lifecycle", "onCreate() started")

        try {
            val data = mapOf(
                "event" to "onCreate_started",
                "timestamp" to System.currentTimeMillis()
            )
            FirebaseFirestore.getInstance().collection("debugLogs").add(data)
        } catch (_: Exception) { }

        // company в prefs
        if (!prefs.contains("company")) {
            prefs.edit().putString("company", "synergy3").apply()
            Log.d("SETUP", "✅ Default company saved to prefs: synergy3")
        } else {
            Log.d(
                "SETUP",
                "✅ Company already in prefs: ${prefs.getString("company", "unknown")}"
            )
        }

        // Immersive fullscreen
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION

        // Не даём экрану гаснуть, пока приложение активно
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        root = FrameLayout(this)
        setContentView(root)
        initWebViewSafeWithRetry()
        Log.d("WEBVIEW", "🔧 Early WebView init called after setContentView")

        // Touch layer: только админ-жест
        val touchLayer = object : View(this) {
            override fun onTouchEvent(e: MotionEvent?): Boolean {
                if (e?.action == MotionEvent.ACTION_DOWN) {
                    handleCornerTap(e.x, e.y)
                }
                return false
            }
        }
        root.addView(
            touchLayer,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        )

        // FCM токен + регистрация
        Handler(Looper.getMainLooper()).postDelayed({
            logEvent("Provisioning", "📡 Firestore connectivity check after 5s delay")
        }, 5000)

        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token ->
                Log.d("FCM", "✅ Token fetched: $token")

                val company = prefs.getString("company", "synergy3") ?: "unknowncompany"
                val data = mapOf(
                    "company" to company,
                    "token" to token,
                    "brand" to Build.BRAND,
                    "model" to Build.MODEL,
                    "sdk" to Build.VERSION.SDK_INT,
                    "timestamp" to System.currentTimeMillis(),
                    "status" to "online",
                    "command" to "idle",
                    "commandId" to "init",
                    "payload" to emptyMap<String, Any>()
                )

                db.collection("company").document(company).collection("devices")
                    .document(deviceId)
                    .set(data, SetOptions.merge())
                    .addOnSuccessListener {
                        Log.d("FIRESTORE", "✅ Device registered (id=$deviceId)")

                        logEvent(
                            "Provisioning",
                            "Device registered successfully, loading WebView"
                        )

                        val fullUrl =
                            "https://360synergy.net/kiosk3/public/feedback.html?company=$company&id=$deviceId"
                        Log.d("WEBVIEW", "🌐 Preparing WebView for URL: $fullUrl")

                        initWebViewSafeWithRetry()

                        Handler(Looper.getMainLooper()).post {
                            if (this::webView.isInitialized) {
                                Log.d("WEBVIEW", "🌐 Loading URL into WebView: $fullUrl")
                                webView.loadUrl(fullUrl)
                            } else {
                                Log.e(
                                    "WEBVIEW",
                                    "WebView is not initialized yet, cannot load $fullUrl"
                                )
                            }
                        }

                        startCommandListener()
                        try {
                            val intent2 =
                                Intent("android.app.action.PROVISIONING_SUCCESSFUL")
                            sendBroadcast(intent2)
                            Log.i(
                                "Provisioning",
                                "✅ Sent PROVISIONING_SUCCESSFUL broadcast to system"
                            )
                            logEvent("Provisioning", "Sent PROVISIONING_SUCCESSFUL broadcast")
                        } catch (e: Exception) {
                            Log.e(
                                "Provisioning",
                                "⚠️ Failed to send provisioning success broadcast: ${e.message}"
                            )
                        }
                    }
            }
            .addOnFailureListener { e ->
                Log.e("FCM", "❌ Failed to fetch FCM token", e)

                logEvent(
                    "FCM",
                    "Failed to fetch token, proceeding with fallback registration"
                )

                val company = prefs.getString("company", "pierce") ?: "unknowncompany"
                val localId = deviceId
                val fallbackData = mapOf(
                    "company" to company,
                    "brand" to Build.BRAND,
                    "model" to Build.MODEL,
                    "sdk" to Build.VERSION.SDK_INT,
                    "timestamp" to System.currentTimeMillis(),
                    "status" to "online",
                    "token" to "unavailable",
                    "command" to "idle",
                    "commandId" to "init",
                    "payload" to emptyMap<String, Any>()
                )
                db.collection("company").document(company).collection("devices")
                    .document(localId)
                    .set(fallbackData, SetOptions.merge())
                    .addOnSuccessListener {
                        Log.d(
                            "FIRESTORE",
                            "✅ Device registered without token (ID: $localId)"
                        )
                        logEvent("Provisioning", "Fallback device registration successful")
                        startCommandListener()
                        try {
                            val intent2 =
                                Intent("android.app.action.PROVISIONING_SUCCESSFUL")
                            sendBroadcast(intent2)
                            Log.i(
                                "Provisioning",
                                "✅ Sent PROVISIONING_SUCCESSFUL broadcast to system"
                            )
                            logEvent("Provisioning", "Sent PROVISIONING_SUCCESSFUL broadcast")
                        } catch (e: Exception) {
                            Log.e(
                                "Provisioning",
                                "⚠️ Failed to send provisioning success broadcast: ${e.message}"
                            )
                        }
                    }
            }
    }

    // --- Wake helper: имитация нажатия Power для пробуждения ---
    private fun wakeDeviceLikePowerButton() {
        try {
            val pm = getSystemService(PowerManager::class.java)
            @Suppress("DEPRECATION")
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "kiosk:wake_command"
            )
            wl.acquire(3000)
            wl.release()
            Log.d("WAKE", "✅ wakeDeviceLikePowerButton executed")
        } catch (e: Exception) {
            Log.e("WAKE", "wakeDeviceLikePowerButton failed: ${e.message}")
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

    private fun hideOffline() {
        offlineBanner?.visibility = View.GONE
    }

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

    private fun updateStatus(status: String) {
        val now = System.currentTimeMillis()
        val update = mapOf(
            "status" to status,
            "lastSeen" to now,
            "timestamp" to now
        )

        val company = prefs.getString("company", "pierce") ?: "unknowncompany"
        db.collection("company").document(company).collection("devices")
            .document(deviceId)
            .set(update, SetOptions.merge())
            .addOnSuccessListener {
                Log.d("FIRESTORE", "status=$status (id=$deviceId)")
            }
            .addOnFailureListener { e ->
                Log.e("FIRESTORE", "status update fail", e)
            }
    }

    // CommandListener
    private var commandReg: ListenerRegistration? = null

    private fun deviceRef(): com.google.firebase.firestore.DocumentReference {
        val company = prefs.getString("company", "pierce") ?: "unknowncompany"
        return db.collection("company").document(company)
            .collection("devices").document(deviceId)
    }

    private fun startCommandListener() {
        commandReg = deviceRef().addSnapshotListener { snap, e ->
            if (e != null) {
                Log.e("COMMANDS", "Listener error", e)
                return@addSnapshotListener
            }
            Log.d("COMMAND", "👂 Listening for command changes on $deviceId")
            if (snap == null || !snap.exists()) {
                return@addSnapshotListener
            }

            val cmd = snap.getString("command") ?: "idle"
            val cmdId = snap.getString("commandId")
            val payload =
                (snap.get("payload") as? Map<*, *>)?.filterKeys { it is String } as? Map<String, Any>
                    ?: emptyMap()

            val lastHandled = prefs.getString("last_cmd_id", null)
            if (cmd == "idle" || cmdId == null || cmdId == lastHandled) {
                return@addSnapshotListener
            }

            Log.d("COMMANDS", "New command: $cmd id=$cmdId payload=$payload")

            when (cmd) {
                "reload" -> {
                    if (this::webView.isInitialized) {
                        webView.post { webView.reload() }
                        ackCommand(cmdId, true, "reloaded")
                    } else {
                        ackCommand(cmdId, false, "webview not initialized")
                    }
                }

                "open_url" -> {
                    val url = payload["url"] as? String
                    if (!url.isNullOrBlank()) {
                        if (this::webView.isInitialized) {
                            webView.post { webView.loadUrl(url) }
                            ackCommand(cmdId, true, "opened:$url")
                        } else {
                            ackCommand(cmdId, false, "webview not initialized")
                        }
                    } else {
                        ackCommand(cmdId, false, "url missing")
                    }
                }

                "ping" -> {
                    ackCommand(cmdId, true, "pong")
                }

                "sleep" -> {
                    val dpm = getSystemService(DevicePolicyManager::class.java)
                    try {
                        dpm.lockNow()
                        ackCommand(cmdId, true, "screen off")
                    } catch (e: Exception) {
                        ackCommand(cmdId, false, "lockNow failed: ${e.message}")
                    }
                }

                "wake" -> {
                    wakeDeviceLikePowerButton()
                    ackCommand(cmdId, true, "screen on")
                }

                "update_now" -> {
                    val url =
                        "https://github.com/bbondarenkosynergy3/kiosk-v2/releases/latest/download/synergy360-kiosk-release-v.apk"
                    try {
                        UpdateHelper(this).startUpdate(url)
                        ackCommand(cmdId, true, "update started")
                    } catch (e: Exception) {
                        ackCommand(cmdId, false, "update failed: ${e.message}")
                    }
                }

                else -> {
                    ackCommand(cmdId, false, "unknown command: $cmd")
                }
            }
        }
    }

    private fun ackCommand(cmdId: String, ok: Boolean, msg: String) {
        val now = System.currentTimeMillis()
        val data = mapOf(
            "command" to "idle",
            "lastCommandId" to cmdId,
            "lastCommandStatus" to if (ok) "ok" else "error",
            "lastCommandMessage" to msg,
            "lastCommandAt" to now
        )
        deviceRef().set(data, SetOptions.merge())
            .addOnSuccessListener {
                prefs.edit().putString("last_cmd_id", cmdId).apply()
                Log.d("COMMANDS", "ACK sent for $cmdId ($msg)")
            }
            .addOnFailureListener { e ->
                Log.e("COMMANDS", "ACK failed", e)
            }
    }

    private fun sendHeartbeat() {
        val now = System.currentTimeMillis()
        val updateData = mapOf(
            "status" to "online",
            "lastSeen" to now,
            "heartbeat" to true,
            "timestamp" to now
        )

        val company = prefs.getString("company", "synergy3") ?: "unknowncompany"
        db.collection("company").document(company).collection("devices")
            .document(deviceId)
            .set(updateData, SetOptions.merge())
            .addOnSuccessListener {
                Log.d("HEARTBEAT", "❤️ Heartbeat sent (ID: $deviceId)")
            }
            .addOnFailureListener { e ->
                Log.e("HEARTBEAT", "💔 Failed to send heartbeat", e)
            }
    }

    override fun onResume() {
        super.onResume()
        if (prefs.getBoolean("pending_reload", false)) {
            Log.d("COMMANDS", "pending_reload applied onResume")
            prefs.edit().putBoolean("pending_reload", false).apply()

            if (this::webView.isInitialized) {
                try {
                    webView.reload()
                } catch (e: Exception) {
                    Log.e("WEBVIEW", "reload failed: ${e.message}")
                }
            }
        }
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION

        if (this::webView.isInitialized) {
            webView.onResume()
        } else {
            Log.e("WEBVIEW", "onResume: webView not initialized")
        }

        heartbeatHandler.post(heartbeatRunnable)
        updateStatus("online")
        enableKioskIfOwner()
    }

    override fun onPause() {
        super.onPause()

        if (this::webView.isInitialized) {
            webView.onPause()
        } else {
            Log.e("WEBVIEW", "onPause: webView not initialized")
        }

        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        updateStatus("offline")
    }

    override fun onDestroy() {
        super.onDestroy()

        heartbeatHandler.removeCallbacks(heartbeatRunnable)

        commandReg?.remove()
        commandReg = null

        updateStatus("offline")
    }

    private fun enableKioskIfOwner() {
        val dpm = getSystemService(DevicePolicyManager::class.java)
        val admin = ComponentName(this, MyDeviceAdminReceiver::class.java)

        if (dpm.isDeviceOwnerApp(packageName)) {
            try { dpm.setLockTaskPackages(admin, arrayOf(packageName)) } catch (_: Throwable) { }
            try { dpm.setStatusBarDisabled(admin, true) } catch (_: Throwable) { }
            try { dpm.setKeyguardDisabled(admin, true) } catch (_: Throwable) { }

            if (dpm.isLockTaskPermitted(packageName)) {
                try { startLockTask() } catch (_: Throwable) { }
            }

            window.decorView.systemUiVisibility =
                (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            window.decorView.systemUiVisibility =
                (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }
    }
}
