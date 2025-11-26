package net.synergy360.kiosk

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.BroadcastReceiver
import android.util.Log
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class UpdateHelper(private val ctx: Context) {

    private val db = FirebaseFirestore.getInstance()

    @Volatile
    private var rebootTriggered = false

    init {
        registerUpdateReceiver()
    }

    private fun log(status: String, extra: Map<String, Any?> = emptyMap()) {
        val data = mutableMapOf<String, Any?>(
            "status" to status,
            "timestamp" to System.currentTimeMillis(),
            "device" to android.os.Build.MODEL,
            "build" to android.os.Build.DISPLAY
        )
        data.putAll(extra)

        db.collection("updateLogs").add(data)
            .addOnSuccessListener { Log.i("UPDATE_LOG", "Logged: $status") }
            .addOnFailureListener { e -> Log.e("UPDATE_LOG", "Log fail: ${e.message}") }
    }

override fun onReceive(context: Context, intent: Intent) {
    val receivedPkg = intent.data?.schemeSpecificPart ?: context.packageName

    if (receivedPkg != context.packageName) return
    if (rebootTriggered) return

    rebootTriggered = true

    log("apk_update_detected", mapOf(
        "action" to intent.action,
        "rawPackage" to receivedPkg
    ))

    GlobalScope.launch {
        delay(1200)

        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val admin = ComponentName(context, MyDeviceAdminReceiver::class.java)

        // 1) Попытка перезагрузки через Device Owner
        try {
            log("reboot_initiated")
            dpm.reboot(admin)
            return@launch
        } catch (e: Exception) {
            log("reboot_dpm_failed", mapOf("error" to e.message))
        }

        // 2) Fallback reboot через shell
        try {
            log("reboot_fallback_exec")
            Runtime.getRuntime().exec("reboot")
            return@launch
        } catch (e: Exception) {
            log("reboot_fallback_failed", mapOf("error" to e.message))
        }

        // 3) Если reboot недоступен — автозапуск приложения
        try {
            val launch = context.packageManager
                .getLaunchIntentForPackage(context.packageName)

            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                context.startActivity(launch)
                log("autostart_after_update_success")
            } else {
                log("autostart_after_update_failed", mapOf("reason" to "no launch intent"))
            }
        } catch (e: Exception) {
            log("autostart_after_update_exception", mapOf("error" to e.message))
        }
    }
}, filter)
    }

    fun startUpdate(url: String) {
        Thread {
            try {
                log("update_start", mapOf("url" to url))

                val file = java.io.File(ctx.cacheDir, "update.apk")

                try {
                    java.net.URL(url).openStream().use { input ->
                        java.io.FileOutputStream(file).use { output ->
                            input.copyTo(output)
                        }
                    }
                    log("apk_download_success", mapOf("size" to file.length()))
                } catch (e: Exception) {
                    log("apk_download_failed", mapOf("error" to e.message))
                    throw e
                }

                val installer = ctx.packageManager.packageInstaller
                val params = android.content.pm.PackageInstaller.SessionParams(
                    android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL
                )

                val sessionId = installer.createSession(params)
                val session = installer.openSession(sessionId)

                try {
                    session.openWrite("package", 0, -1).use { out ->
                        java.io.FileInputStream(file).use { input ->
                            input.copyTo(out)
                            session.fsync(out)
                        }
                    }
                    log("apk_write_success")
                } catch (e: Exception) {
                    log("apk_write_failed", mapOf("error" to e.message))
                    throw e
                }

                val pending = PendingIntent.getActivity(
                    ctx, 0, Intent(ctx, MainActivity::class.java),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                )

                log("apk_commit_start")
                session.commit(pending.intentSender)
                session.close()
                log("apk_commit_success")

            } catch (e: Exception) {
                log("update_failed", mapOf("error" to e.message))
                Log.e("UPDATE", "Update failed: ${e.message}")
            }
        }.start()
    }
}
