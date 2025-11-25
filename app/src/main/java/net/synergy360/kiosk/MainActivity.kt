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
import android.webkit.*
import android.widget.FrameLayout
import android.widget.TextView
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessaging
import java.util.UUID

class MainActivity : Activity() {

    private lateinit var webView: WebView
    private lateinit var root: FrameLayout
    private val db = FirebaseFirestore.getInstance()

    private var offlineBanner: TextView? = null
    private var commandReg: ListenerRegistration? = null

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

    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private val heartbeatRunnable = object : Runnable {
        override fun run() {
            sendHeartbeat()
            heartbeatHandler.postDelayed(this, 30_000L)
        }
    }

    // -------------------------------------------------------
    //  New corner-tap gesture system
    // -------------------------------------------------------

    private var tlCount = 0       // top-left taps for Test Mode
    private var blCount = 0       // bottom-left taps for Exit
    private var tlLastTap = 0L
    private var blLastTap = 0L

    private fun handleCornerTap(x: Float, y: Float) {
        val w = resources.displayMetrics.widthPixels
        val h = resources.displayMetrics.heightPixels
        val m = (w.coerceAtMost(h) * 0.15f)

        val now = SystemClock.uptimeMillis()

        when {
            // -----------------------------
            //  🔵 Top-left corner → TEST MODE
            // -----------------------------
            x < m && y < m -> {
                if (now - tlLastTap < 1500) {
                    tlCount++
                    if (tlCount >= 4) {
                        tlCount = 0
                        startActivity(Intent(this, TestActivity::class.java))
                        return
                    }
                } else {
                    tlCount = 1
                }
                tlLastTap = now
            }

            // -----------------------------
            //  🔴 Bottom-left corner → EXIT
            // -----------------------------
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
        } catch (_: Exception) {}
    }

    private fun fetchCompany(callback: (String) -> Unit) {
        val ref = db.collection("deviceAssignments").document(androidId)
        ref.get().addOnSuccessListener { snap ->
            if (snap != null && snap.exists()) {
                val company = snap.getString("company") ?: "synergy3"
                val storedDeviceId = snap.getString("deviceId") ?: deviceId
                prefs.edit().putString("company", company)
                    .putString("device_id", storedDeviceId).apply()
                callback(company)
            } else {
                val info = mapOf(
                    "company" to "synergy3",
                    "deviceId" to deviceId,
                    "updatedAt" to System.currentTimeMillis()
                )
                ref.set(info, SetOptions.merge()).addOnSuccessListener {
                    prefs.edit().putString("company", "synergy3")
                        .putString("device_id", deviceId).apply()
                    callback("synergy3")
                }
            }
        }.addOnFailureListener { callback("synergy3") }
    }

    // -------------------------------------------------------
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        try { FirebaseApp.initializeApp(this) } catch (_: Exception) {}

        fetchCompany { continueStartup() }

        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_FULLSCREEN or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        root = FrameLayout(this)
        setContentView(root)

        // admin gesture layer
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
    }

    private fun continueStartup() {
        initWebView()
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
            try { WebView.setDataDirectorySuffix("kiosk") } catch (_: Exception) {}
        }

        webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.cacheMode = WebSettings.LOAD_DEFAULT

        webView.webViewClient = object : WebViewClient() {
            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError
            ) {
                // 🔇 Silent reconnect: логируем ошибку и мягко перезагружаем только main-frame
                logEvent(
                    "WEB_ERROR",
                    "code=${error.errorCode} desc=${error.description} url=${request.url}"
                )

                if (request.isForMainFrame) {
                    Handler(Looper.getMainLooper()).postDelayed({
                        try {
                            view.reload()
                        } catch (_: Exception) {}
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
                webView.loadUrl(url)
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
                try { stopLockTask() } catch (_: Exception) {}
                finish()
            }.setNegativeButton("No", null).show()
    }

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
                    } catch (_: Exception) {}
                    wakeDevice()
                    ack(cmdId, true, "woken")
                }

                "reload" -> {
                    try { webView.reload() } catch (_: Exception) {}
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

                "set_company" -> {
                    val newCompany = snap.getString("newCompany") ?: return@addSnapshotListener

                    // Update deviceAssignments first
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

                            // Copy old device data and update company field
                            val currentData = (snap.data ?: emptyMap<String, Any>()).toMutableMap()
                            currentData["company"] = newCompany

                            // Write the device into the new company collection
                            db.collection("company").document(newCompany)
                                .collection("devices").document(deviceId)
                                .set(currentData, SetOptions.merge())
                                .addOnSuccessListener {

                                    // Update local prefs
                                    prefs.edit().putString("company", newCompany).apply()

                                    // Remove OLD listener
                                    try { commandReg?.remove() } catch (_: Exception) {}
                                    commandReg = null

                                    // Start NEW listener
                                    startCommandListener()

                                    // Reload WebView with new company
                                    val url =
                                        "https://360synergy.net/kiosk3/public/feedback.html?company=$newCompany&id=$deviceId"
                                    try { webView.loadUrl(url) } catch (_: Exception) {}

                                    // ACK (now goes to new company because prefs already updated)
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
        } catch (_: Exception) {}
    }

    private fun sendHeartbeat() {
        val data = mapOf(
            "status" to "online",
            "lastSeen" to System.currentTimeMillis(),
            "heartbeat" to true
        )
        deviceRef().set(data, SetOptions.merge())
    }

    // -------------------------------------------------------
    override fun onResume() {
        super.onResume()

        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                    View.SYSTEM_UI_FLAG_FULLSCREEN

        heartbeatHandler.post(heartbeatRunnable)
        enableKiosk()
    }

    override fun onPause() {
        super.onPause()
        heartbeatHandler.removeCallbacks(heartbeatRunnable)
    }

    private fun enableKiosk() {
        val dpm = getSystemService(DevicePolicyManager::class.java)
        val admin = ComponentName(this, MyDeviceAdminReceiver::class.java)

        if (dpm.isDeviceOwnerApp(packageName)) {
            try { dpm.setLockTaskPackages(admin, arrayOf(packageName)) } catch (_: Exception) {}
            try { startLockTask() } catch (_: Exception) {}
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                        View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
        }
    }


}
