package net.synergy360.installer

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.*
import android.util.Log
import android.widget.*
import androidx.core.content.FileProvider
import com.bumptech.glide.Glide
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import javax.net.ssl.HttpsURLConnection

class InstallerActivity : Activity() {

    private lateinit var logo: ImageView
    private lateinit var title: TextView
    private lateinit var subtitle: TextView
    private lateinit var deviceNameInput: EditText
    private lateinit var submitButton: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var progressText: TextView
    private lateinit var progressContainer: LinearLayout

    private val kioskApkUrl =
        "https://github.com/bbondarenkosynergy3/kiosk-v2/releases/latest/download/synergy360-kiosk-release-v.apk"

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_installer)

        logo = findViewById(R.id.logo)
        title = findViewById(R.id.title)
        subtitle = findViewById(R.id.subtitle)
        deviceNameInput = findViewById(R.id.deviceNameInput)
        submitButton = findViewById(R.id.submitButton)
        progressBar = findViewById(R.id.progressBar)
        progressText = findViewById(R.id.progressText)
        progressContainer = findViewById(R.id.progressContainer)

        val uri: Uri? = intent?.data
        val prefs = getSharedPreferences("installer_prefs", MODE_PRIVATE)

        var company = "synergy3"
        var initialName = ""
        var logoUrl: String? = null
        var primary = "#3F3D56"
        var accent = "#7B5FFF"

        if (uri != null) {
            company = uri.getQueryParameter("company") ?: company
            initialName = uri.getQueryParameter("name") ?: ""
            logoUrl = uri.getQueryParameter("logo")

            uri.getQueryParameter("primary")?.let { primary = it }
            uri.getQueryParameter("accent")?.let { accent = it }
        }

        // Сохраняем брендинг, чтобы FinishActivity мог его использовать
        prefs.edit()
            .putString("branding_primary", primary)
            .putString("branding_accent", accent)
            .putString("branding_logo", logoUrl)
            .apply()

        applyBranding(primary, accent, logoUrl)

        if (initialName.isNotEmpty()) {
            deviceNameInput.setText(initialName)
        }

        submitButton.setOnClickListener {
            val deviceName = deviceNameInput.text.toString().trim()
            if (deviceName.length < 3) {
                Toast.makeText(this, "Enter at least 3 characters", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            // Сохраняем pending данные для Kiosk
            prefs.edit()
                .putBoolean("pending_setup", true)
                .putString("pending_device_name", deviceName)
                .putString("pending_company", company)
                .apply()

            downloadApk()
        }
    }

    private fun applyBranding(primary: String, accent: String, logoUrl: String?) {
        try {
            val p = Color.parseColor(primary)
            val a = Color.parseColor(accent)

            title.setTextColor(p)
            subtitle.setTextColor(p)

            val gradient = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(p, a)
            )
            gradient.cornerRadius = 48f
            submitButton.background = gradient
            submitButton.setTextColor(Color.WHITE)

            if (!logoUrl.isNullOrEmpty()) {
                Glide.with(this).load(logoUrl).into(logo)
            }
        } catch (e: Exception) {
            Log.e("BRAND", "Branding failed: ${e.message}")
        }
    }

    private fun downloadApk() {
        progressContainer.visibility = LinearLayout.VISIBLE
        submitButton.isEnabled = false
        progressBar.progress = 0
        progressText.text = "0%"

        Thread {
            try {
                val url = URL(kioskApkUrl)
                val conn = url.openConnection() as HttpsURLConnection
                conn.connect()

                val fileLength = conn.contentLength
                val input = conn.inputStream

                val outFile = File(getExternalFilesDir(null), "kiosk.apk")
                val output = FileOutputStream(outFile)

                val buffer = ByteArray(4096)
                var total: Long = 0
                var count: Int

                while (input.read(buffer).also { count = it } != -1) {
                    total += count
                    output.write(buffer, 0, count)

                    if (fileLength > 0) {
                        val progress = ((total * 100) / fileLength).toInt()
                        runOnUiThread {
                            progressBar.progress = progress
                            progressText.text = "$progress%"
                        }
                    }
                }

                output.flush()
                output.close()
                input.close()

                runOnUiThread {
                    progressText.text = "Installing…"
                }

                installApk(outFile)

            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Download failed: ${e.message}", Toast.LENGTH_LONG).show()
                    progressContainer.visibility = LinearLayout.GONE
                    submitButton.isEnabled = true
                }
            }
        }.start()
    }

    private fun installApk(file: File) {
        val apkUri = FileProvider.getUriForFile(
            this,
            "net.synergy360.installer.provider",
            file
        )

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        // Старт системного установщика
        startActivity(intent)

        // Через небольшую паузу показываем finish-экран
        Handler(Looper.getMainLooper()).postDelayed({
            startActivity(Intent(this, FinishActivity::class.java))
        }, 1500)
    }
}