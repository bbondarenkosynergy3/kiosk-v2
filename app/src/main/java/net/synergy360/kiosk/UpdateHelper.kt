package net.synergy360.kiosk

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.util.Log
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class UpdateHelper(private val ctx: Context) {

    // Чтобы не вызвать reboot дважды
    @Volatile
    private var rebootTriggered = false

    init {
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

                // Иногда data == null (freeze state), игнорировать нельзя
                val pkg = intent.data?.schemeSpecificPart ?: context.packageName

                if (pkg != context.packageName) return
                if (rebootTriggered) return

                rebootTriggered = true

                Log.i("UPDATE", "APK updated → preparing to reboot…")

                // DO ОБНОВЛЕНИЕ: freeze снимается через 300–1500 мс
                GlobalScope.launch {
                    delay(1200) // самое стабильное окно

                    try {
                        val dpm = context.getSystemService(DevicePolicyManager::class.java)
                        val admin = ComponentName(context, MyDeviceAdminReceiver::class.java)

                        Log.i("UPDATE", "Performing DO reboot…")
                        dpm.reboot(admin)
                    } catch (e: Exception) {
                        Log.e("UPDATE", "DO reboot failed: ${e.message}")

                        // fallback (редко, но бывает)
                        try {
                            Runtime.getRuntime().exec("reboot")
                        } catch (ee: Exception) {
                            Log.e("UPDATE", "Fallback reboot failed: ${ee.message}")
                        }
                    }
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

                session.commit(pending.intentSender) // NOTE: reboot поймает ресивер
                session.close()

            } catch (e: Exception) {
                Log.e("UPDATE", "Update failed: ${e.message}")
            }
        }.start()
    }
}
