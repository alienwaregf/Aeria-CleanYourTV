package com.gathin.tvhome.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.gathin.tvhome.dream.AerialDream

object SystemScreensaver {
    private const val TAG = "AerialScreensaver"
    private const val COMPONENTS = "screensaver_components"
    private const val DEFAULT_COMPONENT = "screensaver_default_component"

    const val XIAOMI = "com.xiaomi.mitv.hyper.screensaver"

    private val SOMNAMBULATOR = ComponentName("com.android.systemui", "com.android.systemui.Somnambulator")

    private fun dreamsOn(context: Context) =
        runCatching { Settings.Secure.getInt(context.contentResolver, "screensaver_enabled", 0) == 1 }.getOrDefault(false)

    private fun component(context: Context): String =
        runCatching { Settings.Secure.getString(context.contentResolver, COMPONENTS) }.getOrNull().orEmpty()

    fun isAerials(context: Context): Boolean =
        dreamsOn(context) && component(context).split(',').firstOrNull() == ComponentName(context, AerialDream::class.java).flattenToString()

    fun firmwareDream(context: Context): String? =
        runCatching { Settings.Secure.getString(context.contentResolver, DEFAULT_COMPONENT) }.getOrNull()
            ?.takeIf { it.isNotBlank() && !it.startsWith(context.packageName + "/") }

    fun firmwareDelayMs(context: Context): Long? {
        val resolver = context.contentResolver
        val xiaomi = runCatching { context.packageManager.getPackageInfo(XIAOMI, 0) }.isSuccess
        if (xiaomi) {
            val ms = runCatching { Settings.System.getLong(resolver, "screen_saver_timeout") }.getOrNull() ?: return null
            return if (ms > 0) ms else 0
        }
        if (!dreamsOn(context)) return 0
        return runCatching { Settings.System.getLong(resolver, Settings.System.SCREEN_OFF_TIMEOUT) }.getOrNull()
            ?.takeIf { it in 1 until 24 * 60 * 60 * 1000L }
    }

    @Volatile private var dreamAskedAt = -1L
    @Volatile private var homeHiddenAt = -1L

    fun noteDreamAsked() {
        dreamAskedAt = SystemClock.elapsedRealtime()
        homeHiddenAt = -1L
    }

    fun forgetDreamAsked() { dreamAskedAt = -1L }

    fun noteHomeHidden() {
        val asked = dreamAskedAt
        if (asked >= 0 && homeHiddenAt < 0 && SystemClock.elapsedRealtime() - asked < DREAM_ANSWER_MS) homeHiddenAt = SystemClock.elapsedRealtime()
    }

    fun dreamFirstFrame(): Long? {
        val asked = dreamAskedAt
        val now = SystemClock.elapsedRealtime()
        if (asked < 0 || now - asked > DREAM_FRAME_MS) return null
        dreamAskedAt = -1L
        return now - (homeHiddenAt.takeIf { it >= 0 } ?: asked)
    }

    private const val DREAM_ANSWER_MS = 5_000L
    private const val DREAM_FRAME_MS = 15_000L

    fun startDream(context: Context): Boolean {
        if (!dreamsOn(context)) return false
        val intent = Intent(Intent.ACTION_MAIN).setComponent(SOMNAMBULATOR).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { context.packageManager.resolveActivity(intent, 0) }.getOrNull() == null) return false
        return runCatching { context.startActivity(intent) }
            .onFailure { Log.w(TAG, "could not start the dream", it) }.isSuccess
    }

    fun startFirmware(context: Context): Boolean {
        val pm = context.packageManager
        val xiaomi = runCatching { pm.getPackageInfo(XIAOMI, 0) }.isSuccess
        if (xiaomi) {
            val saver = Intent(Intent.ACTION_MAIN).setClassName(XIAOMI, "$XIAOMI.MainActivity")
                .putExtra("show_saver_list", false)
                .putExtra("show_widget_view", false)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { context.startActivity(saver) }.isSuccess) return true
            Log.w(TAG, "could not open Xiaomi's screensaver")
        }
        return startDream(context)
    }
}
