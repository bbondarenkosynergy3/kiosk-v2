package net.synergy360.kiosk

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.os.Build
import com.google.firebase.firestore.FirebaseFirestore

class MyDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        Log.i("DeviceOwner", "✅ Device admin enabled")
        // Log event to Firestore
        try {
            val data = mapOf(
                "event" to "device_admin_enabled",
                "timestamp" to System.currentTimeMillis(),
                "device" to Build.MODEL,
                "build" to Build.DISPLAY
            )
            FirebaseFirestore.getInstance().collection("startupLogs").add(data)
        } catch (e: Exception) {
            Log.e("DeviceOwner", "Failed to log device admin enabled to Firestore: ${e.message}")
        }
    }
    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        Log.i("DeviceOwner", "🎉 Provisioning complete — launching MainActivity")
        val launch = Intent(context, MainActivity::class.java)
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(launch)
    }
    override fun onDisabled(context: Context, intent: Intent) {
        Log.i("DeviceOwner", "❌ Device admin disabled")
    }
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        Log.i("DeviceOwner", "📡 Received broadcast: ${intent.action}")
    }
}
