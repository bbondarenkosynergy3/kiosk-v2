package net.synergy360.kiosk

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class MyDeviceAdminReceiver : DeviceAdminReceiver() {
    override fun onEnabled(context: Context, intent: Intent) {
        Log.i("DeviceOwner", "✅ Device admin enabled")
    }
    override fun onDisabled(context: Context, intent: Intent) {
        Log.i("DeviceOwner", "❌ Device admin disabled")
    }
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        Log.i("DeviceOwner", "📡 Received broadcast: ${intent.action}")
    }
}