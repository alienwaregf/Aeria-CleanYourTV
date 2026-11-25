package com.gathin.tvhome.update

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import com.gathin.tvhome.BuildConfig
import com.gathin.tvhome.system.DiagLog
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

object SelfCompile {
    private const val TAG = "AerialUpdates"
    private const val PREFS = "self_compile"
    private const val KEY_TRIED = "tried_version"
    private const val KEY_ASKED_SYSTEM = "system_install"
    private const val KEY_SYSTEM_RESULT = "system_result"

    private const val COMPILED_BYTES = 1L shl 20

    private const val AUTO_DELAY_MS = 20_000L

    var offered by mutableStateOf(false); private set

    private val main = Handler(Looper.getMainLooper())
    private var inFront = false
    private var checkedThisRun = false
    private var app: Context? = null
    private val askingSystem = AtomicBoolean(false)

    fun odexBytes(context: Context): Long? = runCatching {
        val dir = File(File(context.applicationInfo.sourceDir).parentFile, "oat")
        listOf("arm", "arm64", "x86", "x86_64").map { File(dir, "$it/base.odex") }
            .firstOrNull { it.exists() }?.length()
    }.getOrNull()

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun triedThisVersion(context: Context) = prefs(context).getLong(KEY_TRIED, 0) == BuildConfig.VERSION_CODE.toLong()

    private fun installedAt(context: Context): Long =
        runCatching {
            if (Build.VERSION.SDK_INT >= 33) context.packageManager.getPackageInfo(
                context.packageName, PackageManager.PackageInfoFlags.of(0)
            ).lastUpdateTime
            else @Suppress("DEPRECATION") context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime
        }.getOrDefault(0L)

    private fun askedSystemThisInstall(context: Context) = prefs(context).getLong(KEY_ASKED_SYSTEM, -1) == installedAt(context)

    private fun uncompiled(context: Context) = odexBytes(context)?.let { it < COMPILED_BYTES } ?: true

    private fun systemWorthIt(context: Context) =
        Build.VERSION.SDK_INT in 24..30 && !askedSystemThisInstall(context) && uncompiled(context)

    private fun worthIt(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 28 || !Updates.canInstall(context) || triedThisVersion(context)) return false
        val odex = odexBytes(context) ?: 0L
        return odex < COMPILED_BYTES && Updates.ownDexMetadata(context) != null
    }

    fun init(context: Context) {
        if (checkedThisRun) return
        checkedThisRun = true
        app = context.applicationContext
        val worth = worthIt(context)
        val system = systemWorthIt(context)
        if (worth || system) DiagLog.i("self compile: uncompiled (odex ${odexBytes(context)?.div(1024)} KB)")
        offered = worth && Build.VERSION.SDK_INT < 31 && !system
    }

    fun recheck(context: Context) {
        if (!offered) return
        val odex = odexBytes(context) ?: return
        if (odex < COMPILED_BYTES) return
        offered = false
        DiagLog.i("self compile: compiled since (odex ${odex / 1024} KB); no longer offered")
    }

    fun diagnostics(context: Context): String =
        "tried for this version=${triedThisVersion(context)}, offered=$offered" +
            (prefs(context).getString(KEY_SYSTEM_RESULT, null)?.let { ", package manager: $it" } ?: "")

    fun onHomeStarted(context: Context) {
        recheck(context)
        inFront = true
        main.removeCallbacks(auto)
    }

    fun onHomeStopped() {
        inFront = false
        main.removeCallbacks(auto)
        if (Build.VERSION.SDK_INT >= 24) main.postDelayed(auto, AUTO_DELAY_MS)
    }

    fun onScreensaver(still: () -> Boolean) {
        if (Build.VERSION.SDK_INT !in 24..30) return
        main.removeCallbacks(saverCompile)
        saverStill = still
        main.postDelayed(saverCompile, AUTO_DELAY_MS)
    }

    private var saverStill: () -> Boolean = { false }

    private val saverCompile = Runnable {
        val context = app ?: return@Runnable
        if (saverStill() && systemWorthIt(context)) Thread({ askSystem(context) }, "self-compile").start()
    }

    private val auto = Runnable {
        val context = app ?: return@Runnable
        if (inFront) return@Runnable
        if (Build.VERSION.SDK_INT < 31) {
            if (systemWorthIt(context)) Thread({ askSystem(context) }, "self-compile").start()
            return@Runnable
        }
        if (worthIt(context)) run(context, auto = true)
    }

    private fun askSystem(context: Context) {
        if (!askingSystem.compareAndSet(false, true)) return
        try {
        val app = context.applicationContext
        prefs(app).edit { putLong(KEY_ASKED_SYSTEM, installedAt(app)) }
        val before = odexBytes(app)
        val started = android.os.SystemClock.elapsedRealtime()
        var said = runCatching { PackageCompile.compile(app, "speed-profile") }.getOrElse { "${it.javaClass.simpleName}: ${it.message}" }
        if (said.contains("Success") && uncompiled(app)) {
            said = runCatching { PackageCompile.compile(app, "speed") }.getOrElse { "${it.javaClass.simpleName}: ${it.message}" }
        }
        val after = odexBytes(app)
        val done = after != null && after >= COMPILED_BYTES
        val took = android.os.SystemClock.elapsedRealtime() - started
        val result = if (done) "compiled, odex ${after!! / 1024} KB in ${took / 1000} s" else "not compiled (${said.take(120)}), odex ${after?.div(1024)} KB"
        prefs(app).edit { putString(KEY_SYSTEM_RESULT, result) }
        Log.i(TAG, "self compile: package manager asked, odex $before -> $after bytes in $took ms: $said")
        DiagLog.i("self compile: package manager: $result")
        if (!done) main.post { offered = worthIt(app) }
        } finally {
            askingSystem.set(false)
        }
    }

    fun run(context: Context, auto: Boolean) {
        val app = context.applicationContext
        prefs(app).edit { putLong(KEY_TRIED, BuildConfig.VERSION_CODE.toLong()) }
        offered = false
        Log.i(TAG, "self compile: reinstalling (${if (auto) "auto" else "asked"}), odex ${odexBytes(app)} bytes")
        DiagLog.i("self compile: reinstalling (${if (auto) "auto" else "asked"})")
        if (Build.VERSION.SDK_INT >= 28) Updates.reinstallSelf(app, auto)
    }

    fun askSystemNow(context: Context) {
        prefs(context).edit { remove(KEY_ASKED_SYSTEM) }
        Thread({ askSystem(context) }, "self-compile").start()
    }

    fun runNow(context: Context) {
        prefs(context).edit { remove(KEY_TRIED) }
        run(context, auto = Build.VERSION.SDK_INT >= 31)
    }
}
