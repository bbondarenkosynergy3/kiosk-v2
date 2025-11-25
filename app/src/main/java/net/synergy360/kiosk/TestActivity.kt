package net.synergy360.kiosk

import android.app.Activity
import android.os.Bundle
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import android.widget.LinearLayout
import android.widget.TextView
import android.content.Intent

class TestActivity : Activity() {

    private lateinit var logView: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = ScrollView(this)
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }
        root.addView(wrap, ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ))

        fun btn(label: String, onClick: () -> Unit): Button {
            return Button(this).apply {
                text = label
                setOnClickListener { onClick() }
                wrap.addView(this)
            }
        }

        // --- КНОПКИ ---

        btn("Simulate Day Switch") {
            sendBroadcast(Intent("kiosk.day_switch").setPackage(packageName))
            log("Sent day switch")
        }

        btn("Simulate TIME: 23:59 → evaluate only") {
            val action = ScheduleManager.getCurrentAction("23:00", "08:00", "23:59")
            log("evaluate(23:59) = $action")
        }

        btn("Apply Today From Prefs") {
            ScheduleManager.applyTodayFromPrefs(this)
            log("applyTodayFromPrefs() called")
        }

        btn("Show Alarms") {
            val out = ShellDump.run("dumpsys alarm | grep kiosk")
            log(out)
        }

        btn("Sleep NOW") {
            sendBroadcast(Intent("kiosk.sleep").setPackage(packageName))
            log("Sent kiosk.sleep")
        }

        btn("Wake NOW") {
            sendBroadcast(Intent("kiosk.wake").setPackage(packageName))
            log("Sent kiosk.wake")
        }

        btn("Exit Kiosk") {
            sendBroadcast(Intent("kiosk.exit").setPackage(packageName))
            log("Requested kiosk.exit")
        }

        btn("Test Brightness 120") {
            val intent = Intent("kiosk.test.brightness").apply {
                putExtra("value", 120)
            }
            sendBroadcast(intent)
            log("Brightness test value=120 sent")
        }

        btn("Test Volume 40%") {
            val intent = Intent("kiosk.test.volume").apply {
                putExtra("value", 40)
            }
            sendBroadcast(intent)
            log("Volume test value=40 sent")
        }

        btn("Show Prefs") {
            val p = getSharedPreferences("kiosk_prefs", MODE_PRIVATE)
            log("company=${p.getString("company","?")} deviceId=${p.getString("device_id","?")} volLock=${p.getBoolean("volumeLocked", false)} brLock=${p.getBoolean("brightnessLocked", false)}")
        }

        btn("Test FCM ACK") {
            val intent = Intent("kiosk.test.ack").apply {
                putExtra("cmdId", "test_ack_${System.currentTimeMillis()}")
            }
            sendBroadcast(intent)
            log("Sent test FCM ACK broadcast")
        }

        btn("Monitor Sleep/Wake") {
            val intent = Intent(this, ActionMonitorActivity::class.java)
            startActivity(intent)
        }

        // LOG
        logView = TextView(this)
        wrap.addView(logView)

        setContentView(root)
    }

    private fun log(msg: String) {
        logView.append(msg + "\n")
    }

    private fun testBrightnessManual(value: Int) {
        val intent = Intent("kiosk.test.brightness").apply {
            putExtra("value", value)
        }
        sendBroadcast(intent)
        log("Manual brightness test value=$value sent")
    }

    private fun testVolumeManual(value: Int) {
        val intent = Intent("kiosk.test.volume").apply {
            putExtra("value", value)
        }
        sendBroadcast(intent)
        log("Manual volume test value=$value sent")
    }
}

object ShellDump {
    fun run(cmd: String): String {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            p.inputStream.bufferedReader().readText()
        } catch (e: Exception) {
            "error: ${e.message}"
        }
    }
}

class ActionMonitorActivity : Activity() {

    private lateinit var logView: TextView
    private var receiver: android.content.BroadcastReceiver? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = ScrollView(this)
        val wrap = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }
        root.addView(
            wrap,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        logView = TextView(this)
        wrap.addView(logView)

        log("Monitoring kiosk.sleep / kiosk.wake in real time...")

        val filter = android.content.IntentFilter().apply {
            addAction("kiosk.sleep")
            addAction("kiosk.wake")
        }

        receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(ctx: android.content.Context?, intent: Intent?) {
                val action = intent?.action ?: return
                log("Received: $action at ${System.currentTimeMillis()}")
            }
        }

        registerReceiver(receiver, filter)

        setContentView(root)
    }

    private fun log(msg: String) {
        logView.append(msg + "\n")
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(receiver) } catch (_: Exception) {}
    }
}
