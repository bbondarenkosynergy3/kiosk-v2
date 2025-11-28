package net.synergy360.kiosk

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class MyDeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        Log.i("DeviceOwner", "Device admin enabled")
        // Никаких Firebase здесь — иначе краш во время provisioning
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
        // Оставляем — это безопасно
    }
}
