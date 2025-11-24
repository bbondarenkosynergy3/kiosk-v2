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

        // LOG
        logView = TextView(this)
        wrap.addView(logView)

        setContentView(root)
    }

    private fun log(msg: String) {
        logView.append(msg + "\n")
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
