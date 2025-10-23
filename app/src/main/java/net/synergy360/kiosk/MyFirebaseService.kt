package net.synergy360.kiosk

import android.os.Build
import android.util.Log
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

class MyFirebaseService : FirebaseMessagingService() {

    // === ACTIVITY LIFECYCLE MONITORING ===
override fun onResume() {
    super.onResume()

    // Восстановить фуллскрин и возобновить таймер
    window.decorView.systemUiVisibility =
        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
        View.SYSTEM_UI_FLAG_FULLSCREEN or
        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION

    handler.post(tick)
    webView.onResume()

    // 🔹 При активации экрана — статус ONLINE
    updateStatus("online")
}

override fun onPause() {
    super.onPause()

    // 🔹 Когда приложение уходит в фон — INACTIVE
    updateStatus("inactive")

    handler.removeCallbacks(tick)
    webView.onPause()
}

override fun onDestroy() {
    super.onDestroy()

    // 🔹 Когда Activity уничтожается — OFFLINE
    updateStatus("offline")
}

}