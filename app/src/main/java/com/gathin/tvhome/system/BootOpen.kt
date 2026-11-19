package com.gathin.tvhome.system

import android.content.Context
import android.content.SharedPreferences
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.content.edit
import androidx.core.content.getSystemService
import kotlin.math.abs

object BootOpen {
    private const val PREFS = "system"
    private const val OPENED_ELAPSED = "boot_open_elapsed"
    private const val OPENED_BOOT = "boot_open_boot"
    private const val OPENED_BOOT_ID = "boot_open_boot_id"
    private const val SCREEN_OFF_ELAPSED = "screen_off_elapsed"
    private const val SCREEN_OFF_BOOT = "screen_off_boot"
    private const val SCREEN_OFF_BOOT_ID = "screen_off_boot_id"
    private const val OPENED_ASLEEP = "boot_open_asleep"
    private const val HOME_ELAPSED = "home_key_elapsed"
    private const val HOME_BOOT = "home_key_boot"
    private const val HOME_BOOT_ID = "home_key_boot_id"

    internal val BOOT_ID: String? by lazy {
        runCatching { java.io.File("/proc/sys/kernel/random/boot_id").readText().trim() }
            .getOrNull()?.takeIf { it.isNotEmpty() }
    }


    private fun bootStamp(): Long = System.currentTimeMillis() - SystemClock.elapsedRealtime()

    private fun thisBoot(elapsed: Long, stamp: Long, bootId: String?): Boolean {
        val now = BOOT_ID
        if (bootId != null && now != null) return bootId == now
        return stamp > 0 && elapsed in 0..SystemClock.elapsedRealtime() &&
            abs(stamp - bootStamp()) < SAME_BOOT_SLACK_MS
    }

    fun noteScreenOff(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit(commit = true) {
            putLong(SCREEN_OFF_ELAPSED, SystemClock.elapsedRealtime()).putLong(SCREEN_OFF_BOOT, bootStamp())
                .putString(SCREEN_OFF_BOOT_ID, BOOT_ID)
        }
    }

    fun noteHomeKey(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit(commit = true) {
            putLong(HOME_ELAPSED, SystemClock.elapsedRealtime()).putLong(HOME_BOOT, bootStamp())
                .putString(HOME_BOOT_ID, BOOT_ID)
        }
    }

    fun homeKeyWithin(context: Context, ms: Long): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val at = prefs.getLong(HOME_ELAPSED, -1L)
        return at >= 0 && thisBoot(at, prefs.getLong(HOME_BOOT, 0L), prefs.getString(HOME_BOOT_ID, null)) &&
            SystemClock.elapsedRealtime() - at < ms
    }

    fun noteOpened(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit(commit = true) {
            putLong(OPENED_ELAPSED, SystemClock.elapsedRealtime()).putLong(OPENED_BOOT, bootStamp())
                .putLong(OPENED_ASLEEP, asleep()).putString(OPENED_BOOT_ID, BOOT_ID)
        }
    }

    fun dueAfterColdWake(context: Context, processAgeMs: Long): Boolean {
        if (processAgeMs !in 0..COLD_WAKE_MS) return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return sleptSince(prefs.getLong(OPENED_ASLEEP, -1L), asleep()) || offSinceOpened(prefs)
    }

    fun noteStartedInStandby(context: Context) {
        if (context.getSystemService<PowerManager>()?.isInteractive == false) noteScreenOff(context)
    }

    private fun offSinceOpened(prefs: SharedPreferences): Boolean {
        val opened = prefs.getLong(OPENED_ELAPSED, -1L)
        if (opened < 0 || !thisBoot(opened, prefs.getLong(OPENED_BOOT, 0L), prefs.getString(OPENED_BOOT_ID, null)))
            return false
        val off = prefs.getLong(SCREEN_OFF_ELAPSED, -1L)
        return off >= 0 && thisBoot(off, prefs.getLong(SCREEN_OFF_BOOT, 0L), prefs.getString(SCREEN_OFF_BOOT_ID, null)) &&
            off > opened
    }

    fun openedWithin(context: Context, ms: Long): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val opened = prefs.getLong(OPENED_ELAPSED, -1L)
        return opened >= 0 && thisBoot(opened, prefs.getLong(OPENED_BOOT, 0L), prefs.getString(OPENED_BOOT_ID, null)) &&
            SystemClock.elapsedRealtime() - opened < ms
    }

    private fun asleep(): Long = SystemClock.elapsedRealtime() - SystemClock.uptimeMillis()

    internal fun sleptSince(openedAsleep: Long, asleepNow: Long): Boolean =
        openedAsleep >= 0 && asleepNow - openedAsleep > SLEPT_MS

    fun due(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val opened = prefs.getLong(OPENED_ELAPSED, -1L)
        if (opened < 0 || !thisBoot(opened, prefs.getLong(OPENED_BOOT, 0L), prefs.getString(OPENED_BOOT_ID, null)))
            return true
        return sleptSince(prefs.getLong(OPENED_ASLEEP, -1L), asleep()) || offSinceOpened(prefs)
    }

    fun dueAfterMissedWake(context: Context): Boolean =
        offSinceOpened(context.getSharedPreferences(PREFS, Context.MODE_PRIVATE))

    private const val SAME_BOOT_SLACK_MS = 2 * 60 * 1000L
    private const val SLEPT_MS = 30_000L

    private const val COLD_WAKE_MS = 3 * 60 * 1000L
}
