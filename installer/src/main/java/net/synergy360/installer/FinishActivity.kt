package net.synergy360.installer

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import com.bumptech.glide.Glide

class FinishActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_finish)

        val prefs = getSharedPreferences("installer_prefs", MODE_PRIVATE)

        val primary = prefs.getString("branding_primary", "#3F3D56") ?: "#3F3D56"
        val accent = prefs.getString("branding_accent", "#7B5FFF") ?: "#7B5FFF"
        val logoUrl = prefs.getString("branding_logo", null)

        val logo: ImageView = findViewById(R.id.finishLogo)
        val title: TextView = findViewById(R.id.finishTitle)
        val subtitle: TextView = findViewById(R.id.finishSubtitle)
        val launchBtn: Button = findViewById(R.id.launchKiosk)

        title.text = "Installation complete"
        subtitle.text = "Your device is ready to start Synergy360 Kiosk."

        // Лого бренда, если есть
        if (!logoUrl.isNullOrEmpty()) {
            Glide.with(this).load(logoUrl).into(logo)
        }

        // Градиент кнопки из primary + accent
        try {
            val p = Color.parseColor(primary)
            val a = Color.parseColor(accent)

            val gradient = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(p, a)
            )
            gradient.cornerRadius = 32f
            launchBtn.background = gradient
            launchBtn.setTextColor(Color.WHITE)
        } catch (_: Exception) {
        }

        launchBtn.setOnClickListener {
            val launchIntent = packageManager.getLaunchIntentForPackage("net.synergy360.kiosk")
            if (launchIntent != null) {
                startActivity(launchIntent)
            }
            finish()
        }
    }
}