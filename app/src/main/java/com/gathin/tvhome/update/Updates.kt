package com.gathin.tvhome.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import com.gathin.tvhome.guard.GuardSetup
import com.gathin.tvhome.system.DiagLog
import java.io.File

object Updates {
    private const val TAG = "AerialUpdates"

    private const val PACKAGE = "com.gathin.slimtv"

    fun canInstall(context: Context): Boolean = context.packageName == PACKAGE

    @RequiresApi(28)
    internal fun reinstallSelf(app: Context, auto: Boolean) {
        val apk = File(app.applicationInfo.sourceDir)
        val dm = dexMetadata(apk)
        if (dm == null) {
            Log.w(TAG, "self compile: no dex metadata in apk")
            DiagLog.i("self compile: no dex metadata in apk")
            return
        }
        runCatching {
            val result = Intent(app, UpdateResult::class.java).putExtra(EXTRA_WITH_DM, true)
                .putExtra(EXTRA_SELF, if (auto) SELF_AUTO else SELF_ASKED)
            val id = commitSession(app, apk, dm, result)
            Log.i(TAG, "self compile: session $id committed, dex metadata ${dm.size}")
        }.onFailure {
            Log.w(TAG, "self compile: no session", it)
            DiagLog.i("self compile: no session (${it.javaClass.simpleName}: ${it.message})")
            SelfCompile.onInstallResult(app, success = false)
        }
    }

    @RequiresApi(28)
    private fun commitSession(app: Context, file: File, dm: ByteArray?, result: Intent): Int {
        val installer = app.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.packageName)
            if (Build.VERSION.SDK_INT >= 33) setPackageSource(PackageInstaller.PACKAGE_SOURCE_OTHER)
            if (Build.VERSION.SDK_INT >= 31) setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                file.inputStream().use { input ->
                    session.openWrite("aerial.apk", 0, file.length()).use { out ->
                        input.copyTo(out)
                        session.fsync(out)
                    }
                }
                if (dm != null) session.openWrite("aerial.dm", 0, dm.size.toLong()).use { out ->
                    out.write(dm)
                    session.fsync(out)
                }
                val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
                GuardSetup.noteInstallerOpened(app)
                session.commit(PendingIntent.getBroadcast(app, id, result, flags).intentSender)
            }
        } catch (e: Exception) {
            runCatching { installer.abandonSession(id) }
            throw e
        }
        return id
    }

    internal fun ownDexMetadata(context: Context): ByteArray? = dexMetadata(File(context.applicationInfo.sourceDir))

    private fun dexMetadata(apk: File): ByteArray? = runCatching {
        val name = if (Build.VERSION.SDK_INT >= 31) "assets/aerial-dm/31.dm" else "assets/aerial-dm/28.dm"
        java.util.zip.ZipFile(apk).use { zip -> zip.getEntry(name)?.let { e -> zip.getInputStream(e).use { it.readBytes() } } }
    }.getOrNull()

    private const val EXTRA_WITH_DM = "com.gathin.tvhome.update.WITH_DM"
    private const val EXTRA_SELF = "com.gathin.tvhome.update.SELF"
    private const val SELF_ASKED = 1
    private const val SELF_AUTO = 2

    internal fun onSessionResult(context: Context, intent: Intent) {
        val app = context.applicationContext
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val self = intent.getIntExtra(EXTRA_SELF, 0)
        if (self != 0) return onSelfResult(app, intent, status, auto = self == SELF_AUTO)
    }

    private fun onSelfResult(app: Context, intent: Intent, status: Int, auto: Boolean) {
        val id = intent.getIntExtra(PackageInstaller.EXTRA_SESSION_ID, -1)
        if (status == PackageInstaller.STATUS_SUCCESS) {
            DiagLog.i("self compile: installed, odex ${SelfCompile.odexBytes(app)?.div(1024)} KB")
            SelfCompile.onInstallResult(app, success = true)
            return
        }
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION && !auto) {
            @Suppress("DEPRECATION")
            val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
            if (confirm != null && runCatching { app.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess) return
        }
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION && id >= 0) runCatching { app.packageManager.packageInstaller.abandonSession(id) }
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        Log.w(TAG, "self compile: not installed ($status, $message)")
        DiagLog.i("self compile: not installed ($status, $message)")
        SelfCompile.onInstallResult(app, success = false)
    }
}
