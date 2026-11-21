package com.gathin.tvhome.guard

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import android.util.Log
import com.gathin.tvhome.MainActivity
import com.gathin.tvhome.dream.AerialDream
import com.gathin.tvhome.system.BootOpen
import com.gathin.tvhome.system.DiagLog
import com.gathin.tvhome.system.SystemScreensaver

object GuardSetup {
    private const val TAG = "AerialGuard"

    @Volatile
    var lastRevivedAt = Long.MIN_VALUE / 2
        private set

    private const val PREFS = "system"

    private const val JMGO_KEY_SERVICE_PACKAGE = "com.jmgo.systemapi"
    private const val JMGO_TV_PACKAGE = "com.jmgo.tv"
    private const val KEY_JMGO_KEY_REBIND_BOOT = "jmgo_key_rebind_boot"

    private const val KEY_REVIVALS = "revivals"

    private const val REVIVAL_WINDOW_MS = 10 * 60 * 1000L
    private const val KEY_KEEP_ADB = "keep_adb"
    private const val ENABLED_LISTENERS = "enabled_notification_listeners"

    private const val ADB_BOOT_WINDOW_MS = 3 * 60_000L
    private const val ADB_WAKE_SLACK_MS = 60_000L
    private val adbDanceLock = Any()
    @Volatile private var adbDancing = false
    @Volatile private var adbDanced = false

    @Volatile var adbProbePort = 5555

    private const val KEY_SHUTDOWN_AT = "shutdown_announced_at"
    private const val KEY_SHUTDOWN_BOOT_ID = "shutdown_announced_boot_id"
    private const val SHUTDOWN_HOLD_MS = 2 * 60_000L

    @Volatile private var shutdownRecheck = false

    private const val KEY_OWN_SCREENSAVER = "own_screensaver"
    private const val KEY_HOME_IN_FRONT = "home_in_front"
    private const val KEY_HOME_IN_FRONT_AT = "home_in_front_at"
    private const val KEY_HOME_IN_FRONT_BOOT = "home_in_front_boot"
    private const val KEY_STOCK_HOME_AT = "stock_home_at"
    private const val KEY_STOCK_HOME_BOOT = "stock_home_boot"
    private const val KEY_HOME_IN_FRONT_BOOT_ID = "home_in_front_boot_id"
    private const val KEY_STOCK_HOME_BOOT_ID = "stock_home_boot_id"
    private const val KEY_INSTALLER_OPENED = "installer_opened_at"
    private const val KEY_STOCK_VISIT_AT = "stock_visit_at"
    private const val KEY_STOCK_VISIT_UNTIL = "stock_visit_until"
    private const val KEY_STOCK_VISIT_BOOT = "stock_visit_boot"
    private const val KEY_STOCK_VISIT_BOOT_ID = "stock_visit_boot_id"
    private const val KEY_SCREENSAVER_SWITCHED_OFF = "screensaver_switched_off"
    private const val KEY_SCREENSAVER_ENABLED = "screensaver_enabled"
    private const val KEY_OFF_IN_STANDBY = "guard_off_in_standby"

    fun offInStandby(context: Context): Boolean = isUserUnlocked(context) &&
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_OFF_IN_STANDBY, false)

    fun setOffInStandby(context: Context, off: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_OFF_IN_STANDBY, off).commit()
    }

    private fun standingOut(context: Context): Boolean =
        offInStandby(context) && context.getSystemService<PowerManager>()?.isInteractive == false

    @Volatile
    private var screenOnWatch: BroadcastReceiver? = null

    @Synchronized
    private fun watchScreenOn(context: Context, litNowCounts: Boolean = true) {
        if (screenOnWatch != null) return
        val app = context.applicationContext
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                if (screenOnWatch !== this) return
                runCatching { app.unregisterReceiver(this) }
                screenOnWatch = null
                Log.i(TAG, "screen on; putting Home Guard back after standby")
                clearShutdown(app)
                ensureEnabled(app)
            }
        }
        screenOnWatch = receiver
        app.registerReceiver(receiver, IntentFilter(Intent.ACTION_SCREEN_ON))
        if (litNowCounts && context.getSystemService<PowerManager>()?.isInteractive == true) receiver.onReceive(app, Intent(Intent.ACTION_SCREEN_ON))
    }

    fun stepOutForStandby(service: android.accessibilityservice.AccessibilityService) {
        Log.i(TAG, "screen off; taking Home Guard out for standby")
        watchScreenOn(service)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && runCatching { service.disableSelf() }.isSuccess) return
        if (ContextCompat.checkSelfPermission(service, Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return
        val me = ComponentName(service, HomeGuardService::class.java).flattenToString()
        val resolver = service.contentResolver
        runCatching {
            val others = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
                .split(':').filter { it.isNotBlank() && ComponentName.unflattenFromString(it)?.flattenToString() != me }
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, others.joinToString(":"))
        }.onFailure { Log.w(TAG, "could not take Home Guard out", it) }
    }

    fun stepOutForShutdown(service: android.accessibilityservice.AccessibilityService) {
        Log.i(TAG, "shutdown announced; taking Home Guard out")
        if (isUserUnlocked(service)) service.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_SHUTDOWN_AT, SystemClock.elapsedRealtime())
            .putString(KEY_SHUTDOWN_BOOT_ID, BootOpen.BOOT_ID).commit()
        watchScreenOn(service, litNowCounts = false)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) runCatching { service.disableSelf() }
    }

    private fun shuttingDown(context: Context): Boolean {
        if (!isUserUnlocked(context)) return false
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val at = prefs.getLong(KEY_SHUTDOWN_AT, 0L)
        if (at == 0L) return false
        val sameBoot = prefs.getString(KEY_SHUTDOWN_BOOT_ID, null) == BootOpen.BOOT_ID
        return sameBoot && SystemClock.elapsedRealtime() - at in 0 until SHUTDOWN_HOLD_MS
    }

    private fun clearShutdown(context: Context) {
        if (!isUserUnlocked(context)) return
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_SHUTDOWN_AT).remove(KEY_SHUTDOWN_BOOT_ID).apply()
    }

    fun setHomeInFront(context: Context, inFront: Boolean) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_HOME_IN_FRONT, inFront)
            .putLong(KEY_HOME_IN_FRONT_AT, SystemClock.elapsedRealtime())
            .putLong(KEY_HOME_IN_FRONT_BOOT, System.currentTimeMillis() - SystemClock.elapsedRealtime())
            .putString(KEY_HOME_IN_FRONT_BOOT_ID, BootOpen.BOOT_ID).apply()

    private fun thisBoot(prefs: android.content.SharedPreferences, at: Long, stampKey: String, idKey: String): Boolean {
        if (at !in 0..SystemClock.elapsedRealtime()) return false
        val id = prefs.getString(idKey, null)
        val now = BootOpen.BOOT_ID
        if (id != null && now != null) return id == now
        val bootNow = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        return kotlin.math.abs(prefs.getLong(stampKey, 0L) - bootNow) <= 2 * 60 * 1000L
    }

    fun homeInFrontThisBoot(context: Context): Boolean? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val at = prefs.getLong(KEY_HOME_IN_FRONT_AT, -1L)
        if (!thisBoot(prefs, at, KEY_HOME_IN_FRONT_BOOT, KEY_HOME_IN_FRONT_BOOT_ID)) return null
        return prefs.getBoolean(KEY_HOME_IN_FRONT, false)
    }

    fun homeLeftAt(context: Context): Long? {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val at = prefs.getLong(KEY_HOME_IN_FRONT_AT, -1L)
        if (!thisBoot(prefs, at, KEY_HOME_IN_FRONT_BOOT, KEY_HOME_IN_FRONT_BOOT_ID) || prefs.getBoolean(KEY_HOME_IN_FRONT, false)) return null
        return at
    }

    fun noteStockHomeInFront(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_STOCK_HOME_AT, SystemClock.elapsedRealtime())
            .putLong(KEY_STOCK_HOME_BOOT, System.currentTimeMillis() - SystemClock.elapsedRealtime())
            .putString(KEY_STOCK_HOME_BOOT_ID, BootOpen.BOOT_ID).apply()

    fun noteStockVisit(context: Context, untilElapsed: Long?) {
        if (!isUserUnlocked(context)) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (untilElapsed == null) {
            if (!prefs.contains(KEY_STOCK_VISIT_UNTIL)) return
            prefs.edit().remove(KEY_STOCK_VISIT_AT).remove(KEY_STOCK_VISIT_UNTIL).remove(KEY_STOCK_VISIT_BOOT)
                .remove(KEY_STOCK_VISIT_BOOT_ID).apply()
            return
        }
        prefs.edit()
            .putLong(KEY_STOCK_VISIT_AT, SystemClock.elapsedRealtime())
            .putLong(KEY_STOCK_VISIT_UNTIL, untilElapsed)
            .putLong(KEY_STOCK_VISIT_BOOT, System.currentTimeMillis() - SystemClock.elapsedRealtime())
            .putString(KEY_STOCK_VISIT_BOOT_ID, BootOpen.BOOT_ID).commit()
    }

    fun stockVisitLeftMs(context: Context): Long {
        if (!isUserUnlocked(context)) return 0L
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val at = prefs.getLong(KEY_STOCK_VISIT_AT, -1L)
        if (!thisBoot(prefs, at, KEY_STOCK_VISIT_BOOT, KEY_STOCK_VISIT_BOOT_ID)) return 0L
        return (prefs.getLong(KEY_STOCK_VISIT_UNTIL, 0L) - SystemClock.elapsedRealtime()).coerceAtLeast(0L)
    }

    fun stockHomeSinceHomeLeft(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val at = prefs.getLong(KEY_STOCK_HOME_AT, -1L)
        if (!thisBoot(prefs, at, KEY_STOCK_HOME_BOOT, KEY_STOCK_HOME_BOOT_ID)) return false
        return at >= prefs.getLong(KEY_HOME_IN_FRONT_AT, -1L)
    }

    fun stockHomeSeenWithin(context: Context, ms: Long): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val at = prefs.getLong(KEY_STOCK_HOME_AT, -1L)
        if (!thisBoot(prefs, at, KEY_STOCK_HOME_BOOT, KEY_STOCK_HOME_BOOT_ID)) return false
        return SystemClock.elapsedRealtime() - at < ms
    }

    fun startAfterStopOverStock(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || HomeGuardService.isRunning) return false
        if (SystemClock.elapsedRealtime() < 3 * 60_000L) return false
        if (context.getSystemService<PowerManager>()?.isInteractive != true) return false
        if (!stockHomeSinceHomeLeft(context) || !stockHomeSeenWithin(context, 5_000L)) return false
        return runCatching { context.startActivity(MainActivity.bringBackIntent(context)) }.isSuccess
    }

    fun wasHomeInFront(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_HOME_IN_FRONT, false)

    fun noteInstallerOpened(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_INSTALLER_OPENED, System.currentTimeMillis()).commit()
    }

    fun installerJustClosed(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val at = prefs.getLong(KEY_INSTALLER_OPENED, 0L)
        if (at == 0L) return false
        prefs.edit().remove(KEY_INSTALLER_OPENED).apply()
        return System.currentTimeMillis() - at in 0..15 * 60_000L
    }

    fun noteRevival(context: Context) {
        if (context.getSystemService<PowerManager>()?.isInteractive == false) return
        if (revivalNoted || SystemClock.elapsedRealtime() < BOOT_START_MS) return
        val updatedAt = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).lastUpdateTime }.getOrDefault(0L)
        if (System.currentTimeMillis() - updatedAt in 0..BOOT_START_MS) return
        revivalNoted = true
        val now = System.currentTimeMillis()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val recent = recentRevivals(context, now) + now
        prefs.edit().putString(KEY_REVIVALS, recent.joinToString(",")).apply()
    }

    @Volatile
    private var revivalNoted = false
    private const val BOOT_START_MS = 2 * 60 * 1000L

    fun revivedOften(context: Context): Boolean = recentRevivals(context, System.currentTimeMillis()).size >= 3

    private fun recentRevivals(context: Context, now: Long): List<Long> {
        val bootedAt = now - SystemClock.elapsedRealtime()
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_REVIVALS, null).orEmpty()
            .split(',').mapNotNull { it.toLongOrNull() }.filter { now - it in 0..REVIVAL_WINDOW_MS && it >= bootedAt }
    }

    fun keepAdb(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_KEEP_ADB, false)

    fun setKeepAdb(context: Context, keep: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_KEEP_ADB, keep).apply()
        if (keep) ensureAdb(context)
    }

    fun adbSwitchedOffInUse(context: Context, screenOnForMs: Long): Boolean {
        if (adbDancing || !isUserUnlocked(context) || !keepAdb(context)) return false
        if (Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 1) != 0) return false
        if (SystemClock.elapsedRealtime() < ADB_BOOT_WINDOW_MS || screenOnForMs < ADB_WAKE_SLACK_MS) return false
        if ((context.getSystemService(Context.POWER_SERVICE) as? PowerManager)?.isInteractive != true) return false
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_KEEP_ADB, false).apply()
        Log.i(TAG, "ADB debugging switched off with the TV in use; keep_adb is off now")
        DiagLog.i("guard: ADB switched off with the TV in use; keep_adb switched off, not turning it back on")
        return true
    }

    fun isUserUnlocked(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.N ||
            context.getSystemService(UserManager::class.java).isUserUnlocked

    fun ensureAdb(context: Context) {
        if (adbDancing) return
        if (!isUserUnlocked(context) || !keepAdb(context)) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return
        val resolver = context.contentResolver
        if (Settings.Global.getInt(resolver, Settings.Global.ADB_ENABLED, 0) == 0) {
            runCatching {
                Settings.Global.putInt(resolver, Settings.Global.ADB_ENABLED, 1)
                Log.i(TAG, "ADB debugging had been switched off; turned it back on")
            }.onFailure { Log.w(TAG, "could not turn ADB back on", it) }
            return
        }
        ensureAdbListening(context)
    }

    private fun ensureAdbListening(context: Context) {
        if (adbDancing || adbDanced || Build.VERSION.SDK_INT >= Build.VERSION_CODES.R ||
            Build.VERSION.SDK_INT < Build.VERSION_CODES.M
        ) return
        if (SystemClock.elapsedRealtime() > ADB_BOOT_WINDOW_MS) return
        Thread {
            if (adbdListens(adbProbePort)) return@Thread
            synchronized(adbDanceLock) {
                if (adbDanced || SystemClock.elapsedRealtime() > ADB_BOOT_WINDOW_MS) return@Thread
                adbDanced = true
                adbDancing = true
                try {
                    runCatching {
                        Settings.Global.putInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0)
                        Thread.sleep(1_000)
                        Settings.Global.putInt(context.contentResolver, Settings.Global.ADB_ENABLED, 1)
                        Log.i(TAG, "adb_enabled was on but adbd was not listening; toggled it to restart it")
                    }.onFailure { Log.w(TAG, "could not toggle ADB debugging", it) }
                } finally {
                    adbDancing = false
                }
            }
        }.apply { isDaemon = true }.start()
    }

    internal fun adbdListens(port: Int): Boolean = try {
        java.net.Socket().use { it.connect(java.net.InetSocketAddress("127.0.0.1", port), 400) }
        true
    } catch (_: Exception) {
        false
    }

    fun rescanIfMissing(context: Context) {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.LOLLIPOP_MR1) return
        val me = ComponentName(context, HomeGuardService::class.java)
        val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            .split(':').any { ComponentName.unflattenFromString(it) == me }
        if (!enabled) return
        val installed = runCatching {
            context.getSystemService<android.view.accessibility.AccessibilityManager>()?.installedAccessibilityServiceList
                ?.any { it.resolveInfo?.serviceInfo?.let { s -> s.packageName == me.packageName && s.name == me.className } == true }
        }.getOrNull() ?: return
        if (installed) return
        Log.i(TAG, "the guard is enabled but Android lists no such service; asking it to read the services again")
        val pm = context.packageManager
        val marker = ComponentName(context, BootReceiver::class.java)
        runCatching {
            pm.setComponentEnabledSetting(marker, PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
            Handler(Looper.getMainLooper()).postDelayed({
                runCatching { pm.setComponentEnabledSetting(marker, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP) }
            }, 1500)
        }.onFailure { Log.w(TAG, "could not change a component's state", it) }
    }

    fun rebindIfStalled(context: Context) {
        if (HomeGuardService.isRunning) return
        rescanIfMissing(context)
        if (standingOut(context)) { watchScreenOn(context); return }
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return
        val me = ComponentName(context, HomeGuardService::class.java).flattenToString()
        val resolver = context.contentResolver
        val entries = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            .split(':').filter { it.isNotBlank() }
        val others = entries.filter { ComponentName.unflattenFromString(it)?.flattenToString() != me }
        if (others.size == entries.size) return
        runCatching {
            lastRevivedAt = SystemClock.elapsedRealtime()
            Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, others.joinToString(":"))
            Handler(Looper.getMainLooper()).postDelayed({
                runCatching {
                    val now = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
                        .split(':').filter { it.isNotBlank() && ComponentName.unflattenFromString(it)?.flattenToString() != me }
                    Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (now + me).joinToString(":"))
                }
            }, 1500)
            Log.i(TAG, "Home Guard was enabled but not running; asked Android to bind it again")
        }
    }

    fun restartAccessibility(context: Context) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.M) {
            val me = ComponentName(context, HomeGuardService::class.java).flattenToString()
            fun others() = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
                .split(':').filter { it.isNotBlank() && ComponentName.unflattenFromString(it)?.flattenToString() != me }
            runCatching {
                Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, others().joinToString(":"))
                Handler(Looper.getMainLooper()).postDelayed({
                    runCatching { Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (others() + me).joinToString(":")) }
                        .onFailure { Log.w(TAG, "could not put Home Guard back", it) }
                }, 1500)
            }.onFailure { Log.w(TAG, "could not take Home Guard out", it) }
            return
        }
        runCatching {
            Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 0)
            Handler(Looper.getMainLooper()).postDelayed({
                runCatching { Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1) }
                    .onFailure { Log.w(TAG, "could not switch accessibility back on", it) }
            }, 1500)
        }.onFailure { Log.w(TAG, "could not switch accessibility off", it) }
    }

    fun startsOnSignalSource(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(JMGO_TV_PACKAGE, 0) }.isSuccess &&
            runCatching {
                val getInt = Class.forName("android.os.SystemProperties")
                    .getMethod("getInt", String::class.java, Int::class.javaPrimitiveType)
                getInt.invoke(null, "persist.sys.boot.source", 0) as Int
            }.getOrDefault(0) != 0

    fun rebindForJmgoKeys(context: Context) {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.M) return
        if (runCatching { context.packageManager.getPackageInfo(JMGO_KEY_SERVICE_PACKAGE, 0) }.isFailure) return
        val resolved = runCatching {
            context.packageManager.resolveActivity(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
                PackageManager.MATCH_DEFAULT_ONLY,
            )
        }.getOrNull()
        if (resolved?.activityInfo?.packageName == context.packageName) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return
        val bootId = runCatching { java.io.File("/proc/sys/kernel/random/boot_id").readText().trim() }
            .getOrNull()?.takeIf { it.isNotEmpty() } ?: return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(KEY_JMGO_KEY_REBIND_BOOT, null) == bootId) return
        prefs.edit().putString(KEY_JMGO_KEY_REBIND_BOOT, bootId).commit()
        DiagLog.i("jmgo keys: rebinding accessibility so the remote's keys reach the guard")
        restartAccessibility(context)
    }

    fun setOwnScreensaver(context: Context, own: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_OWN_SCREENSAVER, own).apply()
        if (own) keepScreensaver(context)
    }

    private fun keepScreensaver(context: Context) {
        if (!isUserUnlocked(context)) return
        val resolver = context.contentResolver
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val key = "screensaver_components"
        val current = Settings.Secure.getString(resolver, key).orEmpty()
        val mine = ComponentName(context, AerialDream::class.java).flattenToString()
        val renamed = "${context.packageName}/${context.packageName}.dream.OrchardDream" in current
        if (renamed) prefs.edit().putBoolean(KEY_OWN_SCREENSAVER, true).apply()
        if (!prefs.getBoolean(KEY_OWN_SCREENSAVER, false) || current == mine) return
        if (systemChosen(context)) return
        runCatching {
            Settings.Secure.putString(resolver, key, mine)
            Log.i(TAG, "the system screensaver was $current; put Aerial's back")
        }.onFailure { Log.w(TAG, "could not set the system screensaver", it) }
    }

    fun setSystemScreensaverOff(context: Context, off: Boolean) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return
        val resolver = context.contentResolver
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        runCatching {
            if (off) {
                val mine = ComponentName(context, AerialDream::class.java).flattenToString()
                if (Settings.Secure.getString(resolver, "screensaver_components") != mine) return
                if (Settings.Secure.getInt(resolver, KEY_SCREENSAVER_ENABLED, 1) == 0) return
                Settings.Secure.putInt(resolver, KEY_SCREENSAVER_ENABLED, 0)
                prefs.edit().putBoolean(KEY_SCREENSAVER_SWITCHED_OFF, true).apply()
                Log.i(TAG, "screensaver set to never; switched the system's off")
            } else if (prefs.getBoolean(KEY_SCREENSAVER_SWITCHED_OFF, false)) {
                Settings.Secure.putInt(resolver, KEY_SCREENSAVER_ENABLED, 1)
                prefs.edit().remove(KEY_SCREENSAVER_SWITCHED_OFF).apply()
                Log.i(TAG, "screensaver delay chosen; switched the system's back on")
            }
        }.onFailure { Log.w(TAG, "could not change the system screensaver", it) }
    }

    private fun systemChosen(context: Context) =
        context.getSharedPreferences("wallpaper", Context.MODE_PRIVATE).getString("screensaver", null) == "system"

    fun screensaverModeChanged(context: Context) {
        if (!isUserUnlocked(context)) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(KEY_OWN_SCREENSAVER, false)) return
        if (!systemChosen(context)) { keepScreensaver(context); return }
        val resolver = context.contentResolver
        val mine = ComponentName(context, AerialDream::class.java).flattenToString()
        val firmware = SystemScreensaver.firmwareDream(context) ?: return
        if (Settings.Secure.getString(resolver, "screensaver_components").orEmpty() != mine) return
        runCatching {
            Settings.Secure.putString(resolver, "screensaver_components", firmware)
            Log.i(TAG, "screensaver set to the system's; the dream is $firmware again")
        }.onFailure { Log.w(TAG, "could not give the system screensaver back", it) }
    }

    fun screensaverSettingChanged(context: Context) = keepScreensaver(context)

    private fun restoreReceivers(context: Context) {
        restoreReceiversNow(context)
        val app = context.applicationContext
        receiverRecheck.removeCallbacksAndMessages(null)
        for (delay in RECEIVER_RECHECK_MS) receiverRecheck.postDelayed({ restoreReceiversNow(app) }, delay)
    }

    private val receiverRecheck by lazy { Handler(Looper.getMainLooper()) }
    private val RECEIVER_RECHECK_MS = longArrayOf(5_000L, 15_000L)

    private fun restoreReceiversNow(context: Context) {
        val pm = context.packageManager
        for (receiver in listOf(BootReceiver::class.java, ConfigReceiver::class.java)) {
            val component = ComponentName(context, receiver)
            runCatching {
                val state = pm.getComponentEnabledSetting(component)
                if (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT ||
                    state == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) return@runCatching
                pm.setComponentEnabledSetting(
                    component, PackageManager.COMPONENT_ENABLED_STATE_DEFAULT, PackageManager.DONT_KILL_APP,
                )
                DiagLog.i("revival: cleared disabled ${receiver.simpleName} override (was $state)")
            }.onFailure {
                DiagLog.e("revival: could not restore ${receiver.simpleName}", it)
            }
        }
    }

    private fun ensureListenerBeforeNougat(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) return
        val me = ComponentName(context, RevivalListener::class.java).flattenToString()
        val current = Settings.Secure.getString(context.contentResolver, ENABLED_LISTENERS).orEmpty()
        if (current.split(':').any { it == me }) return
        runCatching {
            Settings.Secure.putString(context.contentResolver, ENABLED_LISTENERS, if (current.isEmpty()) me else "$current:$me")
            Log.i(TAG, "notification listener was not allowed; added it")
        }.onFailure { Log.w(TAG, "could not allow the notification listener", it) }
    }

    private const val LIST_WATCH_WINDOW_MS = 5 * 60_000L
    private const val LIST_WATCH_DELAY_MS = 1_500L
    private const val LIST_WATCH_MAX_TRIES = 3

    private var listWatchTries = 0

    @Volatile private var listWatch: ContentObserver? = null

    fun watchEnabledList(context: Context) {
        if (listWatch != null) return
        val app = context.applicationContext
        val handler = Handler(Looper.getMainLooper())
        val me = ComponentName(app, HomeGuardService::class.java).flattenToString()
        val watch = object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                handler.postDelayed({
                    if (listWatchTries >= LIST_WATCH_MAX_TRIES) return@postDelayed
                    if (SystemClock.elapsedRealtime() > LIST_WATCH_WINDOW_MS) return@postDelayed
                    val listed = Settings.Secure
                        .getString(app.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                        .orEmpty().split(':')
                        .filter { it.isNotBlank() }
                        .any { ComponentName.unflattenFromString(it)?.flattenToString() == me }
                    if (listed) return@postDelayed
                    if (ensureEnabled(app)) {
                        listWatchTries++
                        Log.i(TAG, "the firmware dropped Home Guard from the enabled list; putting it back ($listWatchTries)")
                    }
                }, LIST_WATCH_DELAY_MS)
            }
        }
        runCatching {
            app.contentResolver.registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES), false, watch,
            )
            listWatch = watch
        }.onFailure { Log.w(TAG, "could not watch the enabled list", it) }
    }

    fun ensureEnabled(context: Context): Boolean {
        ensureAdb(context)
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return false
        watchEnabledList(context)
        restoreReceivers(context)
        keepScreensaver(context)
        RevivalSync.ensure(context)
        ensureListenerBeforeNougat(context)
        if (standingOut(context)) { watchScreenOn(context); return false }
        if (shuttingDown(context)) {
            watchScreenOn(context, litNowCounts = false)
            if (!shutdownRecheck) {
                shutdownRecheck = true
                val app = context.applicationContext
                Handler(Looper.getMainLooper()).postDelayed({ shutdownRecheck = false; ensureEnabled(app) }, SHUTDOWN_HOLD_MS + 1_000L)
            }
            return false
        }
        val me = ComponentName(context, HomeGuardService::class.java).flattenToString()
        val resolver = context.contentResolver
        val current = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
        val entries = current.split(':').filter { it.isNotBlank() }
        val alreadyOn = entries.any { ComponentName.unflattenFromString(it)?.flattenToString() == me }
        return runCatching {
            if (!alreadyOn) {
                lastRevivedAt = SystemClock.elapsedRealtime()
                Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, (entries + me).joinToString(":"))
                Log.i(TAG, "Home Guard had been switched off; turned it back on")
            }
            Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1)
            !alreadyOn
        }.onFailure { Log.w(TAG, "could not re-enable Home Guard", it) }.getOrDefault(false)
    }
}
