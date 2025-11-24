package net.synergy360.kiosk

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import android.os.Build
import com.google.firebase.firestore.FirebaseFirestore

class MyDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        Log.i("DeviceOwner", "Device admin enabled")
        try {
            FirebaseFirestore.getInstance().collection("startupLogs").add(
                mapOf(
                    "event" to "device_admin_enabled",
                    "timestamp" to System.currentTimeMillis(),
                    "device" to Build.MODEL,
                    "build" to Build.DISPLAY
                )
            )
        } catch (_: Exception) { }
    }

    override fun onProfileProvisioningComplete(context: Context, intent: Intent) {
        Log.i("DeviceOwner", "Provisioning complete → launching MainActivity")
        Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(this)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        Log.i("DeviceOwner", "Receiver action: ${intent.action}")
    }
}
