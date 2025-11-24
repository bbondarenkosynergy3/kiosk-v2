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
        // Регистрируем при создании helper
        registerUpdateReceiver()
    }

    private fun registerUpdateReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_MY_PACKAGE_REPLACED)
            addAction(Intent.ACTION_PACKAGE_REPLACED)
            addDataScheme("package")
        }

        ctx.registerReceiver(object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val pkg = intent.data?.schemeSpecificPart
                if (pkg == context.packageName) {
                    Log.i("UPDATE", "APK updated, rebooting device…")

                    try {
                        val dpm = context.getSystemService(DevicePolicyManager::class.java)
                        val admin = ComponentName(context, MyDeviceAdminReceiver::class.java)

                        dpm.reboot(admin) // 💥 Мгновенная безопасная перезагрузка
                    } catch (e: Exception) {
                        Log.e("UPDATE", "Reboot failed: ${e.message}")
                    }
                }
            }
        }, filter)
    }

    fun startUpdate(url: String) {
        Thread {
            try {
                val connection = java.net.URL(url).openConnection()
                connection.connect()
                val input = connection.getInputStream()

                val file = java.io.File(ctx.cacheDir, "update.apk")
                val output = java.io.FileOutputStream(file)
                input.copyTo(output)
                output.close()
                input.close()

                val packageInstaller = ctx.packageManager.packageInstaller
                val params = android.content.pm.PackageInstaller.SessionParams(
                    android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL
                )

                val sessionId = packageInstaller.createSession(params)
                val session = packageInstaller.openSession(sessionId)

                val out = session.openWrite("package", 0, -1)
                java.io.FileInputStream(file).use { it.copyTo(out) }
                session.fsync(out)
                out.close()

                // Даже если MainActivity не запустится — reboot решит всё.
                val intent = Intent(ctx, MainActivity::class.java)
                val pending = PendingIntent.getActivity(
                    ctx, 0, intent,
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
