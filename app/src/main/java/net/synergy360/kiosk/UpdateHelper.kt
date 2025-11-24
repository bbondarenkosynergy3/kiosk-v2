package net.synergy360.kiosk

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.util.Log
import android.app.admin.DevicePolicyManager
import android.content.ComponentName

class UpdateHelper(private val ctx: Context) {

    init {
        registerUpdateReceiver()
    }

    private fun registerUpdateReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MY_PACKAGE_REPLACED)
            addDataScheme("package")
        }

        ctx.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val pkg = intent.data?.schemeSpecificPart ?: return
                if (pkg != context.packageName) return

                Log.i("UPDATE", "APK updated → rebooting device")

                try {
                    val dpm = context.getSystemService(DevicePolicyManager::class.java)
                    val admin = ComponentName(context, MyDeviceAdminReceiver::class.java)
                    dpm.reboot(admin)
                } catch (e: Exception) {
                    Log.e("UPDATE", "Failed to reboot after update: ${e.message}")
                }
            }
        }, filter)
    }

    fun startUpdate(url: String) {
        Thread {
            try {
                val file = java.io.File(ctx.cacheDir, "update.apk")

                java.net.URL(url).openStream().use { input ->
                    java.io.FileOutputStream(file).use { output ->
                        input.copyTo(output)
                    }
                }

                val installer = ctx.packageManager.packageInstaller
                val params = android.content.pm.PackageInstaller.SessionParams(
                    android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL
                )

                val sessionId = installer.createSession(params)
                val session = installer.openSession(sessionId)

                session.openWrite("package", 0, -1).use { out ->
                    java.io.FileInputStream(file).use { input ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }

                val pending = PendingIntent.getActivity(
                    ctx, 0, Intent(ctx, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )

                session.commit(pending.intentSender)
                session.close()

            } catch (e: Exception) {
                Log.e("UPDATE", "Update failed: ${e.message}")
            }
        }.start()
    }
}
