package net.synergy360.kiosk

import android.app.Activity
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.os.*
import android.provider.Settings
import android.util.Log
import android.view.*
import android.view.KeyEvent
import android.webkit.*
import android.widget.FrameLayout
import android.widget.TextView
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessaging
import org.json.JSONObject
import java.util.UUID

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var root: FrameLayout
    private val db = FirebaseFirestore.getInstance()

    private var offlineBanner: TextView? = null
    private var commandReg: ListenerRegistration? = null
    private var settingsReg: ListenerRegistration? = null
    private var scheduleReg: ListenerRegistration? = null

    private var webViewInitialized = false

    private val prefs by lazy { getSharedPreferences("kiosk_prefs", MODE_PRIVATE) }

    private val deviceId: String by lazy {
        prefs.getString("device_id", null) ?: run {
            val id = "${Build.MODEL}_${UUID.randomUUID().toString().take(8)}"
            prefs.edit().putString("device_id", id).apply()
            id
        }
    }

    private val androidId: String by lazy {
        Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
    }

    // --------------------------------------------------------------------
    // HEARTBEAT
    // --------------------------------------------------------------------
    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            sendHeartbeat()
            heartbeatHandler.postDelayed(this, 30_000L)
        }
    }

    // --------------------------------------------------------------------
    // WATCHDOG — контроль зависаний WebView
    // --------------------------------------------------------------------
    private val watchdogHandler = Handler(Looper.getMainLooper())
    private var watchdogFails = 0

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            try {
                webView.evaluateJavascript(
                    "(function(){ return 'ping_' + Date.now(); })();"
                ) { result ->
                    if (result != null && result.contains("ping_")) {
                        watchdogFails = 0
                    } else {
                        watchdogFails++
                    }
                }
            } catch (_: Exception) {
                watchdogFails++
            }

            // если WebView завис → reload
            if (watchdogFails == 3) {
                Log.e("WATCHDOG", "WebView freeze → reload()")
                try {
                    webView.reload()
                } catch (_: Exception) {
                }
            }

            // если WebView мёртв → рестарт Activity
            if (watchdogFails >= 6) {
                Log.e("WATCHDOG", "WebView DEAD → restartApp()")
                restartApp()
            }

            watchdogHandler.postDelayed(this, 15_000L)
        }
    }

    // -------------------------------------------------------
    //  Corner-tap gesture system (верхний левый / нижний левый)
    // -------------------------------------------------------
    private var tlCount = 0
    private var blCount = 0       // bottom-left taps for Exit
    private var tlLastTap = 0L
    private var blLastTap = 0L

    private var touchLayer: View? = null

    private fun handleCornerTap(x: Float, y: Float) {
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        val m = (w.coerceAtMost(h) * 0.15f)

        val now = SystemClock.uptimeMillis()

        when {
            // top-left
            x < m && y < m -> {
                if (now - tlLastTap < 1500) {
                    tlCount++
                    if (tlCount >= 4) {
                        tlCount = 0
                        // сюда можно повесить скрытое меню/настройки
                        return
                    }
                } else {
                    tlCount = 1
                }
                tlLastTap = now
            }

            // bottom-left → EXIT
            x < m && y > h - m -> {
                if (now - blLastTap < 1500) {
                    blCount++
                    if (blCount >= 4) {
                        blCount = 0
                        confirmExit()
                        return
                    }
                } else {
                    blCount = 1
                }
                blLastTap = now
            }
        }
    }

    // -------------------------------------------------------
    private fun getCompany(): String =
        prefs.getString("company", "synergy3") ?: "synergy3"

    private fun logEvent(tag: String, msg: String) {
        try {
            db.collection("startupLogs").add(
                mapOf(
                    "tag" to tag,
                    "message" to msg,
                    "ts" to System.currentTimeMillis(),
                    "deviceId" to deviceId,
                    "company" to getCompany()
                )
            )
        } catch (_: Exception) {
        }
    }

    private fun startSettingsListener(company: String) {
        // пока заглушка, чтобы не ломать твою архитектуру
    }

    private fun startScheduleListener(company: String) {
        try {
            scheduleReg?.remove()
        } catch (_: Exception) {
        }

        val ref = db.collection("company")
            .document(company)
            .collection("settings")
            .document("schedule")

        scheduleReg = ref.addSnapshotListener { snap, _ ->
            if (snap == null || !snap.exists()) {
                val defaults = mapOf(
                    "monday" to mapOf("enabled" to false, "sleep" to "23:00", "wake" to "07:00"),
                    "tuesday" to mapOf("enabled" to false, "sleep" to "23:00", "wake" to "07:00"),
                    "wednesday" to mapOf("enabled" to false, "sleep" to "23:00", "wake" to "07:00"),
                    "thursday" to mapOf("enabled" to false, "sleep" to "23:00", "wake" to "07:00"),
                    "friday" to mapOf("enabled" to false, "sleep" to "23:00", "wake" to "07:00"),
                    "saturday" to mapOf("enabled" to false, "sleep" to "23:00", "wake" to "07:00"),
                    "sunday" to mapOf("enabled" to false, "sleep" to "23:00", "wake" to "07:00")
                )
                ref.set(defaults, SetOptions.merge())
                val json = JSONObject(defaults).toString()
                ScheduleManager.saveFullSchedule(this, json)
                ScheduleManager.applyTodayFromPrefs(this)
                return@addSnapshotListener
            }

            val map = snap.data ?: return@addSnapshotListener
            val json = JSONObject(map).toString()

            ScheduleManager.saveFullSchedule(this, json)
            ScheduleManager.applyTodayFromPrefs(this)
        }
    }

    private fun fetchCompany(callback: (String) -> Unit) {
        val ref = db.collection("deviceAssignments").document(androidId)
        ref.get()
            .addOnSuccessListener { snap ->
                if (snap != null && snap.exists()) {
                    val company = snap.getString("company") ?: "synergy3"
                    val storedDeviceId = snap.getString("deviceId") ?: deviceId
                    prefs.edit().putString("company", company)
                        .putString("device_id", storedDeviceId)
                        .apply()
                    callback(company)
                } else {
                    val info = mapOf(
                        "company" to "synergy3",
                        "deviceId" to deviceId,
                        "updatedAt" to System.currentTimeMillis()
                    )
                    ref.set(info, SetOptions.merge())
                        .addOnSuccessListener {
                            prefs.edit().putString("company", "synergy3")
                                .putString("device_id", deviceId)
                                .apply()
                            callback("synergy3")
                        }
                        .addOnFailureListener {
                            callback("synergy3")
                        }
                }
            }
            .addOnFailureListener {
                callback("synergy3")
            }
    }

    // -------------------------------------------------------
    // LIFECYCLE
    // -------------------------------------------------------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try {
            FirebaseApp.initializeApp(this)
        } catch (_: Exception) {
        }

        // UI / immersive
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        root = FrameLayout(this)
        setContentView(root)

        // overlay для corner-tap
        touchLayer = object : View(this) {
            override fun onTouchEvent(e: MotionEvent?): Boolean {
                if (e?.action == MotionEvent.ACTION_DOWN) {
                    handleCornerTap(e.x, e.y)
                }
                // не перехватываем, чтобы WebView продолжал получать события
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

        // после инициализации UI — тянем company из Firestore
        fetchCompany { continueStartup() }
    }

    private fun continueStartup() {
        initWebView()
        startSettingsListener(getCompany())
        startScheduleListener(getCompany())

        // автозапуск ForegroundService
        try {
            val s = Intent(this, ForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(s)
            } else {
                startService(s)
            }
        } catch (_: Exception) {
        }

        // Load last url logic
        val lastUrl = prefs.getString("last_url", null)
        if (lastUrl != null) {
            try {
                webView.loadUrl(lastUrl)
            } catch (_: Exception) {}
        } else {
            // Try to fetch from Firestore
            deviceRef().get()
                .addOnSuccessListener { snap ->
                    val firestoreUrl = snap?.getString("lastUrl")
                    if (firestoreUrl != null) {
                        try {
                            webView.loadUrl(firestoreUrl)
                        } catch (_: Exception) {}
                    } else {
                        // fallback
                        val url = "https://360synergy.net/kiosk3/public/feedback.html?company=${getCompany()}&id=$deviceId"
                        try {
                            webView.loadUrl(url)
                        } catch (_: Exception) {}
                    }
                }
                .addOnFailureListener {
                    // fallback
                    val url = "https://360synergy.net/kiosk3/public/feedback.html?company=${getCompany()}&id=$deviceId"
                    try {
                        webView.loadUrl(url)
                    } catch (_: Exception) {}
                }
        }

        FirebaseMessaging.getInstance().token
            .addOnSuccessListener { token ->
                registerDevice(token)
                startCommandListener()
            }
            .addOnFailureListener {
                registerDevice("unavailable")
                startCommandListener()
            }
    }

    // -------------------------------------------------------
    private fun initWebView() {
        if (webViewInitialized) return

        if (Build.VERSION.SDK_INT >= 28) {
            try {
                WebView.setDataDirectorySuffix("kiosk")
            } catch (_: Exception) {
            }
        }

        webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = false
        webView.settings.cacheMode = WebSettings.LOAD_NO_CACHE
        webView.settings.useWideViewPort = true
        webView.settings.loadWithOverviewMode = true

        // --- FULL CACHE CLEAR ---
        try {
            webView.clearCache(true)
            webView.clearHistory()
            WebStorage.getInstance().deleteAllData()
        } catch (_: Exception) {}

        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                logEvent(
                    "WEB_ERROR",
                    "code=${error.errorCode} desc=${error.description} url=${request.url}"
                )
                if (request.isForMainFrame) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        try {
                            view.reload()
                        } catch (_: Exception) {
                        }
                    }, 3_000L)
                }
            }

            override fun onPageFinished(v: WebView?, url: String?) {
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

        // слой жестов всегда поверх WebView
        touchLayer?.bringToFront()

        webViewInitialized = true
    }

    private fun registerDevice(token: String) {
        val company = getCompany()

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

        db.collection("company").document(company)
            .collection("devices").document(deviceId)
            .set(data, SetOptions.merge())
            .addOnSuccessListener {
                val url =
                    "https://360synergy.net/kiosk3/public/feedback.html?company=$company&id=$deviceId"
                val fullUrl = url + "&v=" + System.currentTimeMillis()
                saveLastUrl(fullUrl)
                webView.loadUrl(fullUrl)
            }
    }

    // -------------------------------------------------------
    private fun showOffline(t: String) {
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
        offlineBanner?.text = t
        offlineBanner?.visibility = View.VISIBLE
    }

    private fun hideOffline() {
        offlineBanner?.visibility = View.GONE
    }

    private fun confirmExit() {
        AlertDialog.Builder(this)
            .setTitle("Exit kiosk?")
            .setMessage("Leave fullscreen and close app?")
            .setPositiveButton("Yes") { _, _ ->
                try {
                    stopLockTask()
                } catch (_: Exception) {
                }
                finish()
            }
            .setNegativeButton("No", null)
            .show()
    }

    // -------------------------------------------------------
    // Firestore командный список
    // -------------------------------------------------------
    private fun startCommandListener() {
        commandReg = deviceRef().addSnapshotListener { snap, e ->
            if (e != null || snap == null || !snap.exists()) return@addSnapshotListener

            val cmd = snap.getString("command") ?: return@addSnapshotListener
            val cmdId = snap.getString("commandId") ?: return@addSnapshotListener

            val lastHandled = prefs.getString("last_cmd_id", null)
            if (cmdId == lastHandled) return@addSnapshotListener

            when (cmd) {

                "sleep" -> {
                    val dpm = getSystemService(DevicePolicyManager::class.java)
                    val admin = ComponentName(this, MyDeviceAdminReceiver::class.java)
                    try {
                        dpm.setKeyguardDisabled(admin, false)
                        dpm.lockNow()
                        ack(cmdId, true, "locked")
                    } catch (e2: Exception) {
                        ack(cmdId, false, "lockNow failed: ${e2.message}")
                    }
                }

                "wake" -> {
                    val dpm = getSystemService(DevicePolicyManager::class.java)
                    val admin = ComponentName(this, MyDeviceAdminReceiver::class.java)
                    try {
                        dpm.setKeyguardDisabled(admin, true)
                    } catch (_: Exception) {
                    }
                    wakeDevice()
                    ack(cmdId, true, "woken")
                }

                "reload" -> {
                    try {
                        val reloadUrl = webView.url + "&v=" + System.currentTimeMillis()
                        webView.loadUrl(reloadUrl)
                        saveLastUrl(reloadUrl)
                    } catch (_: Exception) {
                    }
                    ack(cmdId, true, "reloaded")
                }

                "update_now" -> {
                    val url =
                        "https://github.com/bbondarenkosynergy3/kiosk-v2/releases/latest/download/synergy360-kiosk-release-v.apk"
                    try {
                        UpdateHelper(this).startUpdate(url)
                        ack(cmdId, true, "update started")
                    } catch (e: Exception) {
                        ack(cmdId, false, "update failed: ${e.message}")
                    }
                }

                "ping" -> ack(cmdId, true, "pong")

                "open_url" -> {
                    val url = snap.get("payload")
                        ?.let { it as? Map<*, *> }
                        ?.get("url") as? String
                    if (url != null) {
                        try {
                            val fullUrl = url + "&v=" + System.currentTimeMillis()
                            webView.loadUrl(fullUrl)
                            saveLastUrl(fullUrl)
                        } catch (_: Exception) {
                        }
                        ack(cmdId, true, "opened url")
                    } else {
                        ack(cmdId, false, "missing url")
                    }
                }

                "set_company" -> {
                    val newCompany = snap.getString("newCompany") ?: return@addSnapshotListener

                    db.collection("deviceAssignments").document(androidId)
                        .set(
                            mapOf(
                                "company" to newCompany,
                                "deviceId" to deviceId,
                                "updatedAt" to System.currentTimeMillis()
                            ),
                            SetOptions.merge()
                        )
                        .addOnSuccessListener {

                            val currentData =
                                (snap.data ?: emptyMap<String, Any>()).toMutableMap()
                            currentData["company"] = newCompany

                            db.collection("company").document(newCompany)
                                .collection("devices").document(deviceId)
                                .set(currentData, SetOptions.merge())
                                .addOnSuccessListener {

                                    prefs.edit().putString("company", newCompany).apply()

                                    try {
                                        settingsReg?.remove()
                                    } catch (_: Exception) {
                                    }
                                    settingsReg = null
                                    startSettingsListener(newCompany)

                                    try {
                                        scheduleReg?.remove()
                                    } catch (_: Exception) {
                                    }
                                    scheduleReg = null
                                    startScheduleListener(newCompany)

                                    try {
                                        commandReg?.remove()
                                    } catch (_: Exception) {
                                    }
                                    commandReg = null

                                    startCommandListener()

                                    val url =
                                        "https://360synergy.net/kiosk3/public/feedback.html?company=$newCompany&id=$deviceId"
                                    try {
                                        webView.loadUrl(url)
                                    } catch (_: Exception) {
                                    }

                                    ack(cmdId, true, "company switched")
                                }
                        }
                }

                else -> ack(cmdId, false, "unknown")
            }
        }
    }

    private fun deviceRef() =
        db.collection("company").document(getCompany())
            .collection("devices").document(deviceId)

    private fun ack(id: String, ok: Boolean, msg: String) {
        prefs.edit().putString("last_cmd_id", id).apply()

        deviceRef().set(
            mapOf(
                "command" to "idle",
                "lastCommandId" to id,
                "lastCommandStatus" to if (ok) "ok" else "error",
                "lastCommandMessage" to msg,
                "lastCommandAt" to System.currentTimeMillis()
            ),
            SetOptions.merge()
        )
    }

    private fun wakeDevice() {
        try {
            val pm = getSystemService(PowerManager::class.java)
            @Suppress("DEPRECATION")
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "kiosk:wake"
            )
            wl.acquire(3000)
            wl.release()
        } catch (_: Exception) {
        }
    }

    private fun sendHeartbeat() {
        val data = mapOf(
            "status" to "online",
            "lastSeen" to System.currentTimeMillis(),
            "heartbeat" to true
        )
        deviceRef().set(data, SetOptions.merge())
    }

    private fun enableKiosk() {
        val dpm = getSystemService(DevicePolicyManager::class.java)
        val admin = ComponentName(this, MyDeviceAdminReceiver::class.java)

        if (dpm.isDeviceOwnerApp(packageName)) {
            try {
                dpm.setStatusBarDisabled(admin, true)
            } catch (_: Exception) {
            }
            try {
                dpm.setLockTaskPackages(admin, arrayOf(packageName))
            } catch (_: Exception) {
            }
            try {
                startLockTask()
            } catch (_: Exception) {
            }
        }
    }

    // -------------------------------------------------------
    // UI / системные события
    // -------------------------------------------------------
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                        View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        }
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (prefs.getBoolean("volumeLocked", false)) {
            val code = event.keyCode
            if (code == KeyEvent.KEYCODE_VOLUME_UP || code == KeyEvent.KEYCODE_VOLUME_DOWN) {
                return true
            }
        }
        return super.dispatchKeyEvent(event)
    }

    // =====================================================================
    //  ANTI-TAMPER — запрет выхода из киоска
    // =====================================================================
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        Log.d("ANTI_TAMPER", "HOME/RECENTS pressed → restoring MainActivity")

        Handler(Looper.getMainLooper()).postDelayed({
            val i = Intent(this, MainActivity::class.java)
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            startActivity(i)
        }, 300)
    }

    // Полная блокировка кнопки BACK
    override fun onBackPressed() {
        // блокируем кнопку назад
    }

    // =====================================================================
    //  ОБЪЕДИНЁННЫЙ onResume()
    // =====================================================================
    override fun onResume() {
        super.onResume()

        // восстановление киоска / full-screen
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_FULLSCREEN

        // heartbeat
        heartbeatHandler.post(heartbeatRunnable)

        // kiosk mode
        enableKiosk()

        // watchdog
        watchdogHandler.postDelayed(watchdogRunnable, 20_000L)
    }

    // =====================================================================
    //  ОБЪЕДИНЁННЫЙ onPause()
    // =====================================================================
    override fun onPause() {
        super.onPause()

        // heartbeat
        heartbeatHandler.removeCallbacks(heartbeatRunnable)

        // watchdog
        watchdogHandler.removeCallbacks(watchdogRunnable)
    }

    // =====================================================================
    //  Рестарт приложения (для WATCHDOG)
    // =====================================================================
    private fun restartApp() {
        val i = packageManager.getLaunchIntentForPackage(packageName)
        i?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(i)
        finish()
    }

    override fun onDestroy() {
        super.onDestroy()

        try {
            commandReg?.remove()
        } catch (_: Exception) {
        }
        try {
            settingsReg?.remove()
        } catch (_: Exception) {
        }
        try {
            scheduleReg?.remove()
        } catch (_: Exception) {
        }

        heartbeatHandler.removeCallbacks(heartbeatRunnable)
        watchdogHandler.removeCallbacks(watchdogRunnable)
    }
    // Save last url to prefs and Firestore
    private fun saveLastUrl(url: String) {
        try {
            prefs.edit().putString("last_url", url).apply()
        } catch (_: Exception) {}
        try {
            val map = mapOf("lastUrl" to url)
            deviceRef().set(map, SetOptions.merge())
        } catch (_: Exception) {}
    }
