package com.gathin.tvhome.system

import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.gathin.tvhome.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

object DiagLog {
    private const val TAG = "AerialDiag"
    private const val KEEP = 600
    private const val FILE_LIMIT = 1 shl 20

    private val lines = ArrayDeque<String>()
    private val writer by lazy {
        Executors.newSingleThreadExecutor { Thread(it, "AerialDiag").apply { isDaemon = true } }
    }
    private val stamp = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)
    @Volatile private var file: File? = null

    fun enabled(context: Context): Boolean = BuildConfig.DIAG || prefs(context).getBoolean("diag", false)

    fun setEnabled(context: Context, on: Boolean) {
        setOption(context, "diag", on)
        if (on) init(context) else file = null
    }

    fun modeSwitch(context: Context): Boolean = prefs(context).getBoolean("mode_switch", !BuildConfig.DIAG)
    fun softLayer(context: Context): Boolean = prefs(context).getBoolean("soft_layer", false)

    fun saverFadeOffscreen(context: Context): Boolean = prefs(context).getBoolean("saver_fade_offscreen", false)
    fun setOption(context: Context, key: String, on: Boolean) = prefs(context).edit().putBoolean(key, on).apply()
    private fun prefs(context: Context) = context.getSharedPreferences("system", Context.MODE_PRIVATE)

    fun init(context: Context) {
        if (file != null || !enabled(context)) return
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        file = File(dir, "aerial-diag.log")
        i("log file ${file?.absolutePath}")
    }

    fun i(message: String) {
        Log.i(TAG, message)
        val line = synchronized(lines) {
            "${stamp.format(Date())} +${SystemClock.uptimeMillis()} $message".also {
                lines.addLast(it)
                while (lines.size > KEEP) lines.removeFirst()
            }
        }
        val f = file ?: return
        writer.execute {
            runCatching {
                if (f.length() > FILE_LIMIT) f.renameTo(File(f.parentFile, "aerial-diag.old.log"))
                val fresh = !f.exists()
                f.appendText(line + "\n")
                if (fresh) f.setReadable(true, false)
            }
        }
    }

    fun e(message: String, error: Throwable) = i("$message: ${Log.getStackTraceString(error)}")

    fun catchCrashes() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        if (previous is Crashes) return
        Thread.setDefaultUncaughtExceptionHandler(Crashes(previous))
    }

    private class Crashes(val previous: Thread.UncaughtExceptionHandler?) : Thread.UncaughtExceptionHandler {
        override fun uncaughtException(t: Thread, e: Throwable) {
            val f = file
            val line = "${synchronized(lines) { stamp.format(Date()) }} crash on ${t.name}: ${Log.getStackTraceString(e)}"
            Log.e(TAG, line)
            if (f != null) runCatching { f.appendText(line + "\n") }
            previous?.uncaughtException(t, e)
        }
    }

    fun device(): String =
        "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE}, ${Build.HARDWARE}, ${Build.BOARD}), " +
            "Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), ${Build.FINGERPRINT}, " +
            "Aerial ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"

    fun recent(n: Int): List<String> = synchronized(lines) { lines.toList().takeLast(n) }

    fun dump(writer: PrintWriter) {
        writer.println("Aerial log (${device()}):")
        synchronized(lines) { lines.forEach { writer.println("  $it") } }
    }
}
