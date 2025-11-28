package net.synergy360.kiosk

import android.app.Application
import com.google.firebase.FirebaseApp

class KioskApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        try {
            FirebaseApp.initializeApp(this)
        } catch (_: Exception) {
        }
    }
}
