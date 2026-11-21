package com.gathin.tvhome.guard

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AppOpsManager
import android.app.PendingIntent
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.media.AudioManager
import android.media.tv.TvInputManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import android.hardware.input.InputManager
import android.view.InputDevice
import android.view.KeyEvent
import android.view.ViewConfiguration
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityManager
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.gathin.tvhome.MainActivity
import com.gathin.tvhome.dream.AerialDream
import com.gathin.tvhome.settings.AerialSettings
import com.gathin.tvhome.system.BootOpen
import com.gathin.tvhome.system.CoocaaHome
import com.gathin.tvhome.system.DeviceProfile
import com.gathin.tvhome.system.DiagLog
import com.gathin.tvhome.system.CecSwitch
import com.gathin.tvhome.system.SystemProbe

class HomeGuardService : AccessibilityService() {

    private var stockHomes: Map<String, Set<String>> = emptyMap()

    private var fallbackHomes: Set<Pair<String, String>> = emptySet()


    private var ownHomeAt = Long.MIN_VALUE / 2

    private var lastWindow: Pair<String, String>? = null

    private val handler = Handler(Looper.getMainLooper())

    private var screenOnAt = Long.MIN_VALUE / 2

    private var bootCompletedAt = 0L

    private var takeOverReason = ""
    private var takeOverOnWake = false
    private val takeOver = Runnable {
        patchwallEarlyTakeOver = false
        takeOverIfStockHomeInFront(takeOverReason, takeOverOnWake, coveredSilently = takeOverOnWake)
    }

    private var patchwallEarlyTakeOver = false

    private var bootStockHolding = false
    private var bootStockShown = false
    private val endBootStockHold = Runnable {
        if (!bootStockHolding) return@Runnable
        bootStockHolding = false
        endStockVisit("the boot hold is over")
        DiagLog.i("guard: stock launcher left on screen at boot (${if (bootStockShown) "it showed" else "it never said so"}); taking Home")
        takeOverOnceSettled("after the stock launcher showed at boot", woke = true)
    }

    private val bootRecheck = Runnable {
        if (SystemClock.elapsedRealtime() >= BOOT_WINDOW_MS) return@Runnable
        if (stockHomes.isEmpty()) refreshStockHomes()
        takeOverIfStockHomeInFront("looking again after boot", woke = false, coveredSilently = true)
    }

    private var patchwallBootCover = false

    private var boundAwakeAt = Long.MIN_VALUE / 2

    private fun recoveryWindow(): Boolean {
        val now = SystemClock.elapsedRealtime()
        return now < BOOT_WINDOW_MS || now - screenOnAt in 0 until WAKE_WINDOW_MS ||
            now - boundAwakeAt in 0 until WAKE_WINDOW_MS
    }

    private fun inBootWindow(): Boolean {
        val now = SystemClock.elapsedRealtime()
        return now < BOOT_WINDOW_MS || (bootCompletedAt != 0L && now - bootCompletedAt < BOOT_WINDOW_MS)
    }

    private fun isPatchwallHomeCover(pkg: String, cls: String): Boolean =
        pkg == PATCHWALL && cls == PATCHWALL_BOOT && recoveryWindow()

    private fun continuousForegroundCheck(): Boolean =
        Build.VERSION.SDK_INT <= Build.VERSION_CODES.M || INVISIBLE_LAUNCHERS.any { it in stockHomes }

    private fun restartForegroundCheck() {
        handler.removeCallbacks(foregroundCheck)
        if (!continuousForegroundCheck() && !(patchwallBootCover && recoveryWindow())) return
        if (usageStatsAllowed(this)) handler.post(foregroundCheck)
        else Log.i(TAG, "usage stats not allowed; not watching the front by them")
    }

    private val foregroundCheck = object : Runnable {
        override fun run() {
            if (!continuousForegroundCheck() && (!patchwallBootCover || !recoveryWindow() || screenOff)) return
            handler.postDelayed(this, FOREGROUND_POLL_MS)
            if (screenOff || !GuardSetup.isUserUnlocked(this@HomeGuardService)) return
            if (SystemClock.uptimeMillis() < allowStockUntil) return
            if (foregroundQueried) return
            foregroundQueried = true
            usageWorker.post {
                val front = foreground()
                handler.post {
                    foregroundQueried = false
                    if (front != null && isRunning) answerForeground(front.first, front.second)
                }
            }
        }

        private fun answerForeground(window: Pair<String, String>, at: Long) {
            if (screenOff || SystemClock.uptimeMillis() < allowStockUntil) return
            if (at <= foregroundTakenAt) return
            if (stockHomes.isEmpty()) refreshStockHomes()
            val (pkg, cls) = window
            val bootCover = isPatchwallHomeCover(pkg, cls)
            if (cls !in stockHomes[pkg].orEmpty() && !bootCover) return
            foregroundTakenAt = at
            if (SystemClock.uptimeMillis() - broughtBackAt < 1_000) return
            if (bootTargetCovered()) {
                Log.i(TAG, "stock launcher's screen over what the TV starts on; opening that again")
                DiagLog.i("guard: $pkg/$cls over what the TV starts on; opening that again")
                goHome(reopenBoot = true)
                return
            }
            Log.i(TAG, "stock launcher $pkg in front by usage stats; bringing Aerial back")
            val woke = SystemClock.elapsedRealtime() - screenOnAt < WAKE_WINDOW_MS
            if (!woke && homeKeyReachesAerial && isDefaultHome() && pressHomeForStock()) {
                broughtBackAt = SystemClock.uptimeMillis()
                handler.removeCallbacks(homeKeyCheck)
                handler.postDelayed(homeKeyCheck, HOME_KEY_CHECK_MS)
                return
            }
            goHome(woke)
        }
    }

    private var foregroundQueried = false
    private val usageThread = lazy { android.os.HandlerThread("AerialGuardUsage").apply { start() } }
    private val usageWorker by lazy { Handler(usageThread.value.looper) }

    private var homeKeyReachesAerial = true
    private val homeKeyCheck = Runnable {
        if (aerialResumed) return@Runnable
        Log.i(TAG, "a Home press did not bring Aerial back; starting it instead from now on")
        homeKeyReachesAerial = false
        goHome()
    }

    private fun stockLauncherByUsageStats(): Boolean {
        if (!usageStatsAllowed(this)) return false
        val pkg = foreground()?.first?.first ?: return false
        if (stockHomes.isEmpty()) refreshStockHomes()
        return pkg in stockHomes
    }

    private var foregroundTakenAt = Long.MIN_VALUE / 2

    private fun foreground(lookbackMs: Long = FOREGROUND_LOOKBACK_MS): Pair<Pair<String, String>, Long>? = runCatching {
        val now = System.currentTimeMillis()
        val events = getSystemService<UsageStatsManager>()?.queryEvents(now - lookbackMs, now) ?: return null
        var last: Pair<Pair<String, String>, Long>? = null
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            @Suppress("DEPRECATION")
            if (event.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND && event.className != null) {
                last = (event.packageName to event.className) to event.timeStamp
            }
        }
        last
    }.getOrNull()


    private fun frontByUsage(): Pair<String, String>? {
        if (!usageStatsAllowed(this)) return null
        val front = usageFront(UNKNOWN_FRONT_LOOKBACK_MS)?.takeIf { !it.closed } ?: return null
        return (front.pkg to front.cls).takeIf { it.first != packageName }
    }

    private fun nothingInFrontByUsage(): Boolean =
        usageStatsAllowed(this) && usageFront(UNKNOWN_FRONT_LOOKBACK_MS)?.closed == true

    private fun usageFront(lookbackMs: Long): UsageFront.Front? = runCatching {
        val now = System.currentTimeMillis()
        val events = getSystemService<UsageStatsManager>()?.queryEvents(now - lookbackMs, now) ?: return null
        val picker = UsageFront.Picker()
        val event = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            picker.add(event.eventType, event.packageName, event.className, event.timeStamp)
        }
        picker.result(System.currentTimeMillis(), ACTIVITY_SETTLE_MS)
    }.getOrNull()

    private fun coveredAerial(window: Pair<String, String>): Pair<String, String>? {
        if (window.first != packageName || aerialResumed || !usageStatsAllowed(this)) return null
        val (front, _) = foreground(UNKNOWN_FRONT_LOOKBACK_MS) ?: return null
        if (front.second !in stockHomes[front.first].orEmpty()) return null
        Log.i(TAG, "Aerial's Home is not resumed and usage stats say ${front.first} is in front")
        return front
    }

    private fun aerialCoveredUnseen(window: Pair<String, String>): Boolean {
        if (window.first != packageName || !window.second.endsWith(".MainActivity") || aerialResumed) return false
        if (MainActivity.leftForAnApp) return false
        if (aerialResumedAt < broughtBackAt || aerialPausedAt - aerialResumedAt !in 0 until COVERED_UNSEEN_MS) return false
        Log.i(TAG, "Aerial's Home was covered ${aerialPausedAt - aerialResumedAt} ms after it showed, with no window change")
        return true
    }

    private fun scheduleBootRechecks() {
        handler.removeCallbacks(bootRecheck)
        BOOT_RECHECKS_MS.forEach { handler.postDelayed(bootRecheck, it) }
    }

    private var sawEvent = false

    private val keyFilterRival by lazy { keyFilterRival() }

    private val deafCheck = Runnable {
        if (stockHomes.isEmpty()) refreshStockHomes()
        if (sawEvent || deafRetried) return@Runnable
        deafRetried = true
        Log.i(TAG, "no window change since bound; switching accessibility off and on")
        GuardSetup.restartAccessibility(this)
    }

    private val userUnlocked = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = onUserUnlocked("user unlocked")
    }

    private var unlockPending = false
    private var unlockPolledSince = 0L

    private val unlockPoll = object : Runnable {
        override fun run() {
            if (!unlockPending) return
            if (GuardSetup.isUserUnlocked(this@HomeGuardService)) onUserUnlocked("user unlocked (polled)")
            else if (SystemClock.elapsedRealtime() - unlockPolledSince < UNLOCK_POLL_FOR_MS) handler.postDelayed(this, UNLOCK_POLL_MS)
        }
    }

    private fun onUserUnlocked(how: String) {
        if (!unlockPending) return
        unlockPending = false
        handler.removeCallbacks(unlockPoll)
        activityCache.clear()
        refreshStockHomes()
        checkMouseWithFilter(this@HomeGuardService)
        restartForegroundCheck()
        watchCecInput()
        updateKeyFilter()
        GuardSetup.ensureEnabled(this@HomeGuardService)
        val skipReason = when {
            getSystemService<PowerManager>()?.isInteractive != true -> "screen off"
            else -> null
        }
        DiagLog.i("guard: $how; running what was put off while locked")
        if (skipReason == null) takeOverOnceSettled(how, woke = true)
        else Log.i(TAG, "$how; not taking over Home ($skipReason)")
    }

    private val dangbeiShutdown = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            DiagLog.i("guard: Dangbei shutdown broadcast; out of the list before the firmware stops Aerial")
            GuardSetup.stepOutForShutdown(this@HomeGuardService)
        }
    }

    private val screenOn = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            screenOff = intent.action == Intent.ACTION_SCREEN_OFF
            updateKeyFilter()
            if (screenOff) {
                if (GuardSetup.isUserUnlocked(this@HomeGuardService)) BootOpen.noteScreenOff(this@HomeGuardService)
                if (GuardSetup.offInStandby(this@HomeGuardService)) GuardSetup.stepOutForStandby(this@HomeGuardService)
                return
            }
            screenOnAt = SystemClock.elapsedRealtime()
            restartForegroundCheck()
            takeOverOnceSettled("screen on", woke = true)
        }
    }

    private var screenOff = false

    private val homeKey = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.getStringExtra("reason") != "homekey") return
            if (SystemClock.uptimeMillis() - ownHomeAt < OWN_HOME_MS) return
            if (SystemClock.elapsedRealtime() < BOOT_WINDOW_MS && GuardSetup.isUserUnlocked(this@HomeGuardService)) {
                BootOpen.noteHomeKey(this@HomeGuardService)
            }
            if (backOnPause) noteHomeInAerial()
            val front = lastWindow?.first
            if (front != null && front !in stockHomes) {
                windowsAtHome = windowsSeen
                homePressedAt = SystemClock.uptimeMillis()
                homeFrom = front
                handler.removeCallbacks(frontAfterHome)
                handler.removeCallbacks(frontAfterHomeAgain)
                val quiet = deafAfterHome || front in quietHomeApps(this@HomeGuardService)
                handler.postDelayed(frontAfterHome, if (quiet) DEAF_LOOK_MS else FRONT_AFTER_HOME_MS)
                return
            }
            if (SystemClock.uptimeMillis() - broughtBackAt < 1_000) return
            Log.i(TAG, "Home pressed with ${front ?: "an unknown app"} in front; bringing Aerial back")
            endStockVisit("Home pressed")
            goHome()
        }
    }

    private var windowsSeen = 0L
    private var windowsAtHome = 0L
    private var homePressedAt = Long.MIN_VALUE / 2

    private val frontAfterHome: Runnable = Runnable {
        lastWindow?.takeIf { it.first == XGIMI_FRAMEWORK && it.second.endsWith(".resolver.ResolverActivity") }?.let {
            Log.i(TAG, "Home pressed and XGIMI's Home chooser came up; bringing Aerial back")
            goHome()
            return@Runnable
        }
        if (windowsSeen != windowsAtHome) return@Runnable
        val front = frontByUsage()
        if (front == null || (front.first !in stockHomes && !isFallbackHomeScreen(front.first, front.second))) {
            val quiet = deafAfterHome || homeFrom in quietHomeApps(this)
            if (quiet && SystemClock.uptimeMillis() - homePressedAt < FRONT_AFTER_HOME_MS) {
                handler.postDelayed(frontAfterHomeAgain, DEAF_LOOK_MS)
            }
            return@Runnable
        }
        Log.i(TAG, "Home pressed, no window change since, and usage stats say ${front.first} is in front; bringing Aerial back")
        lastWindow = front
        endStockVisit("Home pressed, seen by usage stats")
        goHome()
        homeFrom?.let { noteQuietHomeApp(this, it) }
        deafAfterHome = true
        if (!deafAfterHomeRestarted) {
            deafAfterHomeRestarted = true
            Log.i(TAG, "no window change for that; switching accessibility off and on")
            GuardSetup.restartAccessibility(this)
        }
    }
    private val frontAfterHomeAgain: Runnable = Runnable { frontAfterHome.run() }

    private fun watchUiBoost() {
        if (!backOnPause || uiBoostWatched || Settings.Global.getString(contentResolver, UI_BOOST) == null) return
        uiBoostWatched = true
        contentResolver.registerContentObserver(Settings.Global.getUriFor(UI_BOOST), false, uiBoostObserver)
    }

    private var uiBoostWatched = false

    private val uiBoostObserver by lazy {
        object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) = uiBoostChanged()
        }
    }

    private fun uiBoostChanged() {
        if (!stockHomeMayCome()) return
        stockHomeLookFrom = System.currentTimeMillis()
        stockHomeLookUntil = SystemClock.uptimeMillis() + STOCK_HOME_LOOK_FOR_MS
        handler.removeCallbacks(stockHomeLook)
        stockHomeLook.run()
    }

    private var stockHomeLookFrom = 0L
    private var stockHomeLookUntil = Long.MIN_VALUE / 2

    private val stockHomeLook: Runnable = object : Runnable {
        override fun run() {
            if (!stockHomeMayCome()) return
            val front = usageFront(STOCK_HOME_FRONT_MS)
            if (front != null && !front.closed && front.at >= stockHomeLookFrom - STOCK_HOME_FRONT_MS) {
                if (stockHomes[front.pkg]?.contains(front.cls) == true) {
                    Log.i(TAG, "usage stats have ${front.pkg} just come to the front; bringing Aerial back")
                    lastWindow = front.pkg to front.cls
                    goHome()
                    return
                }
                if (front.at >= stockHomeLookFrom - STOCK_HOME_SLACK_MS) return
            }
            if (SystemClock.uptimeMillis() < stockHomeLookUntil) handler.postDelayed(this, STOCK_HOME_LOOK_MS)
        }
    }

    private fun stockHomeMayCome(): Boolean {
        if (!backOnPause || aerialResumed || bootStockHolding) return false
        val now = SystemClock.uptimeMillis()
        if (now < allowStockUntil || now - broughtBackAt < 1_000) return false
        val sinceBoot = SystemClock.elapsedRealtime()
        if (sinceBoot < BOOT_WINDOW_MS || sinceBoot - screenOnAt < WAKE_WINDOW_MS) return false
        return usageStatsAllowed(this)
    }

    private var homeFrom: String? = null

    private val screensaverObserver by lazy {
        object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) = GuardSetup.screensaverSettingChanged(this@HomeGuardService)
        }
    }

    private var connectedAt = 0L

    private val adbObserver by lazy {
        object : ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                val now = SystemClock.elapsedRealtime()
                val awakeFor = if (screenOff) 0L else minOf(now - screenOnAt, now - connectedAt)
                if (GuardSetup.adbSwitchedOffInUse(this@HomeGuardService, awakeFor)) return
                GuardSetup.ensureAdb(this@HomeGuardService)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        GuardSetup.watchEnabledList(this)
        if (SystemClock.elapsedRealtime() >= BOOT_WINDOW_MS || !GuardSetup.isUserUnlocked(this)) return
        if (getSystemService<PowerManager>()?.isInteractive != true) return
        val bootSource = hasBootSource()
        val patchwall = !bootSource && patchwallHomeInFront()
        if (!bootSource && !patchwall) return
        Log.i(TAG, "started shortly after boot; taking over before the service is connected")
        takeOverOnceSettled("started shortly after boot", woke = true)
        patchwallEarlyTakeOver = patchwall
    }

    private fun patchwallHomeInFront(): Boolean {
        if (!usageStatsAllowed(this)) return false
        val (pkg, cls) = frontByUsage() ?: return false
        if (pkg != PATCHWALL) return false
        refreshStockHomes()
        return cls in stockHomes[PATCHWALL].orEmpty()
    }

    private val cecInputObserver = object : ContentObserver(handler) {
        override fun onChange(selfChange: Boolean) = updateKeyFilter()
    }
    private val cecInputCallback = object : TvInputManager.TvInputCallback() {
        override fun onInputAdded(inputId: String) = updateKeyFilter()
        override fun onInputRemoved(inputId: String) = updateKeyFilter()
        override fun onInputUpdated(inputId: String) = updateKeyFilter()
        override fun onInputStateChanged(inputId: String, state: Int) = updateKeyFilter()
    }
    private var cecWatching = false
    private var filteringForCecOnly = false

    private fun watchCecInput() {
        if (cecWatching) {
            runCatching { contentResolver.unregisterContentObserver(cecInputObserver) }
            runCatching { getSystemService<TvInputManager>()?.unregisterCallback(cecInputCallback) }
            cecWatching = false
        }
        val key = CecHome.inputSettingKey(this) ?: return
        runCatching {
            contentResolver.registerContentObserver(Settings.System.getUriFor(key), false, cecInputObserver)
            contentResolver.registerContentObserver(Settings.Global.getUriFor(key), false, cecInputObserver)
            getSystemService<TvInputManager>()?.registerCallback(cecInputCallback, handler)
            cecWatching = true
        }.onFailure { Log.w(TAG, "could not watch the current input", it) }
    }

    override fun onServiceConnected() {
        instance = this
        connectedAt = SystemClock.elapsedRealtime()
        unlockPending = !GuardSetup.isUserUnlocked(this)
        if (GuardSetup.isUserUnlocked(this)) restoreStockVisit(this)
        if (GuardSetup.isUserUnlocked(this)) {
            BootOpen.noteStartedInStandby(this)
        }
        checkMouseWithFilter(this)
        watchUiBoost()
        CecSwitch.register(this)
        if (SystemClock.elapsedRealtime() < BOOT_WINDOW_MS && GuardSetup.isUserUnlocked(this) &&
            DeviceProfile.current(this).bootStockMs > 0
        ) {
            bootStockHolding = true
            val hold = maxOf(BOOT_STOCK_CAP_MS, DeviceProfile.current(this).bootStockMs.toLong())
            allowStockUntil = SystemClock.uptimeMillis() + hold + DeviceProfile.current(this).bootStockMs
            handler.postDelayed(endBootStockHold, hold)
            DiagLog.i("guard: bound at boot; leaving the stock launcher on screen first (device profile)")
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            registerReceiver(userUnlocked, IntentFilter(Intent.ACTION_USER_UNLOCKED))
        }
        val appLeftInFront = GuardSetup.isUserUnlocked(this) && SystemClock.elapsedRealtime() < BOOT_WINDOW_MS && !justUpdated() &&
            GuardSetup.homeInFrontThisBoot(this) == false && !GuardSetup.stockHomeSinceHomeLeft(this) &&
            (Build.VERSION.SDK_INT < 24 || SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime() < 60_000) &&
            !stockLauncherByUsageStats()
        if (appLeftInFront) DiagLog.i("guard: bound again soon after boot with an app in front when Aerial died; leaving it")
        val fastBootTakeOver = !appLeftInFront && GuardSetup.isUserUnlocked(this) &&
            SystemClock.elapsedRealtime() < BOOT_WINDOW_MS &&
            getSystemService<PowerManager>()?.isInteractive == true
        if (fastBootTakeOver) {
            if (!patchwallEarlyTakeOver) takeOverOnceSettled("bound shortly after boot", woke = true)
            scheduleBootRechecks()
        }
        refreshStockHomes()
        registerReceiver(packageChanges, IntentFilter().apply {
            addAction(Intent.ACTION_PACKAGE_ADDED)
            addAction(Intent.ACTION_PACKAGE_REMOVED)
            addAction(Intent.ACTION_PACKAGE_CHANGED)
            addDataScheme("package")
        })
        screenOff = getSystemService<PowerManager>()?.isInteractive == false
        runCatching { getSystemService<InputManager>()?.registerInputDeviceListener(gamepadListener, handler) }
        lookForGamepads()
        updateKeyFilter()
        watchCecInput()
        volumeSeen = volumeNow()
        registerReceiver(screenOn, IntentFilter(Intent.ACTION_SCREEN_ON).apply { addAction(Intent.ACTION_SCREEN_OFF) })
        ContextCompat.registerReceiver(this, dangbeiShutdown, IntentFilter(DANGBEI_STR_SHUTDOWN), ContextCompat.RECEIVER_EXPORTED)
        @Suppress("DEPRECATION")
        ContextCompat.registerReceiver(this, homeKey, IntentFilter(Intent.ACTION_CLOSE_SYSTEM_DIALOGS), ContextCompat.RECEIVER_NOT_EXPORTED)
        contentResolver.registerContentObserver(Settings.Global.getUriFor(Settings.Global.ADB_ENABLED), false, adbObserver)
        contentResolver.registerContentObserver(Settings.Secure.getUriFor("screensaver_components"), false, screensaverObserver)
        GuardSetup.ensureAdb(this)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.M) handler.postDelayed(deafCheck, DEAF_AFTER_MS)
        patchwallBootCover = isActivity(PATCHWALL, PATCHWALL_BOOT)
        if (!screenOff && !recoveryWindow() &&
            (patchwallBootCover || BootOpen.dueAfterMissedWake(this))
        ) {
            boundAwakeAt = SystemClock.elapsedRealtime()
        }
        restartForegroundCheck()
        if (unlockPending && GuardSetup.isUserUnlocked(this)) onUserUnlocked("user unlocked while binding")
        if (!GuardSetup.isUserUnlocked(this)) {
            Log.i(TAG, "bound while user locked; deferred takeover until unlock")
            unlockPolledSince = SystemClock.elapsedRealtime()
            handler.postDelayed(unlockPoll, UNLOCK_POLL_MS)
            return
        }
        val revived = SystemClock.elapsedRealtime() - GuardSetup.lastRevivedAt < 60_000 && !justUpdated()
        val diedInFront = GuardSetup.wasHomeInFront(this) &&
            (Build.VERSION.SDK_INT < 24 || SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime() < 60_000)
        if (revived && !GuardSetup.wasHomeInFront(this) && !GuardSetup.stockHomeSinceHomeLeft(this) &&
            frontByUsage() == null && !nothingInFrontByUsage() &&
            SystemClock.elapsedRealtime() >= BOOT_WINDOW_MS && !fastBootTakeOver
        ) {
            DiagLog.i("guard: re-enabled after being switched off, with an app in front when Aerial died; leaving it")
            return
        }
        val stoppedOverStock = Build.VERSION.SDK_INT >= 24 && SystemClock.elapsedRealtime() >= BOOT_WINDOW_MS &&
            SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime() < 60_000 &&
            GuardSetup.stockHomeSinceHomeLeft(this) && GuardSetup.stockHomeSeenWithin(this, STOPPED_OVER_STOCK_MS)
        val wokeUnseen = boundAwakeAt > 0 && SystemClock.elapsedRealtime() >= BOOT_WINDOW_MS && recoveryWindow()
        if (!fastBootTakeOver && !appLeftInFront &&
            (SystemClock.elapsedRealtime() < BOOT_WINDOW_MS || revived || diedInFront || stoppedOverStock || wokeUnseen) &&
            getSystemService<PowerManager>()?.isInteractive == true
        ) {
            takeOverOnceSettled(
                when {
                    revived -> "re-enabled after being switched off"
                    diedInFront -> "back after Aerial died in front"
                    stoppedOverStock -> "back after Aerial was stopped over the stock launcher"
                    wokeUnseen -> "bound after the screen lit"
                    else -> "bound shortly after boot"
                },
                woke = (!diedInFront && !stoppedOverStock) || revived,
            )
            if (SystemClock.elapsedRealtime() < BOOT_WINDOW_MS) scheduleBootRechecks()
        }
    }

    private fun takeOverOnceSettled(reason: String, woke: Boolean) {
        takeOverReason = reason
        takeOverOnWake = woke
        handler.removeCallbacks(takeOver)
        handler.postDelayed(takeOver, SETTLE_MS)
    }

    private fun hasBootSource(): Boolean = runCatching {
        AerialSettings.init(this)
        AerialSettings.hasBootTarget
    }.getOrDefault(false)

    private fun bootTargetCovered(): Boolean =
        hasBootSource() &&
            SystemClock.uptimeMillis() - homePressedAt >= HOME_LEFT_MS &&
            !BootOpen.homeKeyWithin(this, HOME_LEFT_MS) &&
            BootOpen.openedWithin(
                this,
                if (SystemClock.elapsedRealtime() < BOOT_WINDOW_MS) BOOT_TARGET_COVER_MS else WAKE_TARGET_COVER_MS,
            )

    private fun firmwarePushedAtBoot(pkg: String): Boolean =
        pkg in FIRMWARE_BOOT_APPS && SystemClock.elapsedRealtime() < BOOT_WINDOW_MS && !keySeen

    private var keySeen = false

    private fun justUpdated(): Boolean = runCatching {
        System.currentTimeMillis() - packageManager.getPackageInfo(packageName, 0).lastUpdateTime < 2 * 60_000
    }.getOrDefault(false)

    private fun takeOverIfStockHomeInFront(reason: String, woke: Boolean, coveredSilently: Boolean = false) {
        if (!GuardSetup.isUserUnlocked(this)) return
        val seen = lastWindow?.takeIf { it !in fallbackHomes }?.let { (if (coveredSilently) coveredAerial(it) else null) ?: it }
        val empty = seen == null && nothingInFrontByUsage()
        if (empty) Log.i(TAG, "$reason; usage stats say the app in front went to the back and nothing came up")
        val last = seen ?: if (empty) null else frontByUsage()?.also { Log.i(TAG, "$reason; usage stats say ${it.first} is in front") }
        if (last == null && !empty) {
            DiagLog.i("guard: $reason; front unknown (${if (usageStatsAllowed(this)) "usage stats name no app in ${UNKNOWN_FRONT_LOOKBACK_MS / 3_600_000} h" else "usage stats not allowed"})")
        }
        if (last == null && !empty && GuardSetup.revivedOften(this)) {
            Log.i(TAG, "$reason, but Aerial keeps being stopped and the front is unknown; leaving it")
            return
        }
        val homeLeftAt = GuardSetup.homeLeftAt(this)
        if (last == null && !empty && homeLeftAt != null && !GuardSetup.stockHomeSinceHomeLeft(this) &&
            !BootOpen.screenOffSince(this, homeLeftAt) && !screenOff
        ) {
            DiagLog.i("guard: $reason; Aerial died away from its Home and the front is unknown; leaving it")
            return
        }
        if (last == null && SystemClock.uptimeMillis() - broughtBackAt < 30_000) return
        if (last == null && inBootWindow() && GuardSetup.startsOnSignalSource(this)) {
            DiagLog.i("guard: $reason; the TV starts on a signal source; leaving it")
            GuardSetup.rebindForJmgoKeys(this)
            return
        }
        val jmgoPlayer = last != null && last.first == JMGO_TV &&
            last.second.endsWith(".AmlogicTvActivity")
        if (jmgoPlayer) {
            DiagLog.i("guard: $reason with JMGO's TV player in front; leaving it")
            GuardSetup.rebindForJmgoKeys(this)
        }
        val stockInFront = last == null || (last.first in stockHomes && !jmgoPlayer) ||
            (last.first == XGIMI_FRAMEWORK && last.second.endsWith(".resolver.ResolverActivity")) ||
            firmwarePushedAtBoot(last.first) ||
            (coveredSilently && aerialCoveredUnseen(last))
        if (woke && last?.first == packageName && hasBootSource()) {
            Log.i(TAG, "$reason with Aerial itself in front; flagging the wake")
            goHome(woke = true)
            return
        }
        if (!stockInFront && last != null) Log.i(TAG, "$reason with ${last.first} in front; leaving it there")
        if (!stockInFront || SystemClock.uptimeMillis() < allowStockUntil) return
        if (bootTargetCovered()) {
            Log.i(TAG, "$reason with the stock launcher over what the TV starts on; opening that again")
            val front = last?.let { "${it.first}/${it.second}" } ?: "unknown"
            DiagLog.i("guard: $reason, front $front; opening what the TV starts on again")
            goHome(reopenBoot = true)
            return
        }
        Log.i(TAG, "$reason with the stock launcher in front; bringing Aerial back")
        DiagLog.i("guard: $reason, front ${last?.first ?: "unknown"}; bringing Aerial back")
        goHome(woke)
    }

    override fun onDestroy() {
        if (usageThread.isInitialized()) usageThread.value.quitSafely()
        handler.removeCallbacks(refreshHomesAfterPackageChange)
        runCatching { unregisterReceiver(packageChanges) }
        runCatching { unregisterReceiver(screenOn) }
        runCatching { unregisterReceiver(dangbeiShutdown) }
        runCatching { unregisterReceiver(homeKey) }
        if (uiBoostWatched) {
            uiBoostWatched = false
            runCatching { contentResolver.unregisterContentObserver(uiBoostObserver) }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { unregisterReceiver(userUnlocked) }
        }
        runCatching { getSystemService<InputManager>()?.unregisterInputDeviceListener(gamepadListener) }
        runCatching { contentResolver.unregisterContentObserver(adbObserver) }
        runCatching { contentResolver.unregisterContentObserver(screensaverObserver) }
        if (cecWatching) {
            runCatching { contentResolver.unregisterContentObserver(cecInputObserver) }
            runCatching { getSystemService<TvInputManager>()?.unregisterCallback(cecInputCallback) }
        }
        handler.removeCallbacksAndMessages(null)
        if (instance === this) instance = null
        super.onDestroy()
    }

    override fun onUnbind(intent: Intent?): Boolean {
        if (instance === this) instance = null
        return super.onUnbind(intent)
    }

    override fun onInterrupt() = Unit

    private fun updateKeyFilter() {
        val info = serviceInfo ?: return
        val flag = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        val aerialInFront = aerialResumed && !backOnPause
        val always = filterHomeAlways()
        val padGame = gamepadAttached && !aerialResumed && lastWindow?.first.let { it != null && it != packageName && it !in stockHomes }
        val mouseHang = mouseAttached && (xiaomiInput || mouseDownedSystem(this))
        val wanted = !keysPassThrough && !screenOff && !padGame && !mouseHang &&
            (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R || aerialInFront || always)
        val homeWanted = wanted && (!isDefaultHome() || defaultHomeNeedsHomeFilter)
        val cecWanted = !screenOff && !mouseHang && CecHome.active(this, lastWindow?.first)
        filteringForCecOnly = cecWanted && !homeWanted
        var want = if (homeWanted || cecWanted) info.flags or flag else info.flags and flag.inv()
        if (GuardSetup.isUserUnlocked(this) && DeviceProfile.current(this).neverFilterKeys) want = want and flag.inv()
        if (keyFilterRival) want = want and AccessibilityServiceInfo.DEFAULT.inv()
        if (want == info.flags) return
        info.flags = want
        serviceInfo = info
        Log.i(TAG, if (want and flag != 0) "filtering keys" else "stopped filtering keys")
    }

    private var gamepadAttached = false

    private var gamepadName: String? = null

    private var mouseAttached = false

    private val xiaomiInput by lazy {
        listOf(PATCHWALL, "com.xiaomi.mitv.systemui").any { runCatching { packageManager.getPackageInfo(it, 0) }.isSuccess }
    }

    private fun lookForGamepads() {
        val hadMouse = mouseAttached
        mouseAttached = Build.VERSION.SDK_INT >= 29 && runCatching {
            InputDevice.getDeviceIds().any { id ->
                InputDevice.getDevice(id)?.let {
                    GamepadDevices.isExternalMouse(
                        isExternal = it.isExternal,
                        isVirtual = it.isVirtual,
                        supportsMouse = it.supportsSource(InputDevice.SOURCE_MOUSE),
                    )
                } == true
            }
        }.getOrDefault(false)
        gamepadName = runCatching {
            InputDevice.getDeviceIds().asSequence().mapNotNull { id ->
                InputDevice.getDevice(id)?.takeIf {
                    GamepadDevices.isPhysicalGamepad(
                        name = it.name,
                        isVirtual = it.isVirtual,
                        supportsGamepad = it.supportsSource(InputDevice.SOURCE_GAMEPAD),
                        vendorId = it.vendorId,
                        productId = it.productId,
                        alphabetic = it.keyboardType == InputDevice.KEYBOARD_TYPE_ALPHABETIC,
                    )
                }?.let { it.name.orEmpty().ifEmpty { "(no name)" } }
            }.firstOrNull()
        }.getOrNull()
        gamepadAttached = gamepadName != null
        updateKeyFilter()
        val filtering = ((serviceInfo?.flags ?: 0) and AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS) != 0
        if (mouseAttached && !hadMouse && filtering) noteMouseWithFilter(this)
    }

    private val gamepadListener = object : InputManager.InputDeviceListener {
        override fun onInputDeviceAdded(deviceId: Int) = lookForGamepads()
        override fun onInputDeviceRemoved(deviceId: Int) = lookForGamepads()
        override fun onInputDeviceChanged(deviceId: Int) = lookForGamepads()
    }

    private fun keyFilterRival(): Boolean {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.M) return false
        val me = ComponentName(this, HomeGuardService::class.java)
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES).orEmpty()
            .split(':').mapNotNull { ComponentName.unflattenFromString(it) }.toSet()
        val installed = getSystemService<AccessibilityManager>()?.installedAccessibilityServiceList.orEmpty()
        return installed.any { info ->
            val name = ComponentName.unflattenFromString(info.id)
            name != me && name in enabled &&
                info.flags and AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS != 0 &&
                info.flags and AccessibilityServiceInfo.DEFAULT == 0 &&
                info.capabilities and AccessibilityServiceInfo.CAPABILITY_CAN_REQUEST_FILTER_KEY_EVENTS != 0
        }
    }

    private val keysPassThrough by lazy {
        VIDAA_PACKAGES.any { runCatching { packageManager.getPackageInfo(it, 0) }.isSuccess }
    }

    private fun filterHomeAlways(): Boolean =
        GuardSetup.isUserUnlocked(this) && DeviceProfile.current(this).filterHomeAlways

    private val defaultHomeNeedsHomeFilter by lazy {
        fun installed(name: String) = runCatching { packageManager.getPackageInfo(name, 0) }.isSuccess
        (installed("com.cvte.factory.at") && !installed("com.tianci.system")) || installed("com.yunos.tv.homeshell")
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        keySeen = true
        if (!GuardSetup.isUserUnlocked(this)) return false
        if (event.keyCode in VOLUME_KEYS) {
            return if (filteringForCecOnly || (isDefaultHome() && defaultHomeNeedsHomeFilter)) {
                false
            } else {
                takeVolumeKey(event)
            }
        }
        if (event.action == KeyEvent.ACTION_UP) volumeSeen = volumeNow()
        if (event.keyCode != KeyEvent.KEYCODE_HOME) return false
        if (takeCecHome(event)) return true
        if (isDefaultHome() && !defaultHomeNeedsHomeFilter) return false
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            homeHeld = false
            handler.removeCallbacks(homeHold)
            if (event.eventTime - lastHomeUpAt < DOUBLE_HOME_MS) {
                homeHeld = true
                lastHomeUpAt = Long.MIN_VALUE / 2
                Log.i(TAG, "Home pressed twice; opening recent apps")
                openRecents()
                return true
            }
            handler.postDelayed(homeHold, ViewConfiguration.getLongPressTimeout().toLong())
        }
        if (event.action == KeyEvent.ACTION_UP) {
            handler.removeCallbacks(homeHold)
            if (!event.isCanceled && !homeHeld) {
                Log.i(TAG, "Home key taken before the system saw it")
                lastHomeUpAt = if (AerialDream.endForHome()) Long.MIN_VALUE / 2 else event.eventTime
                goHome()
                closeSystemDialogs()
            }
        }
        return true
    }

    private var cecHomeDown = false
    private var cecHomeDouble = false
    private var cecHomeHeld = false
    private val cecHomeHold = Runnable {
        if (cecHomeDown && CecHome.active(this, lastWindow?.first)) {
            cecHomeHeld = true
            lastCecHomeUp = Long.MIN_VALUE / 2
            Log.i(TAG, "HDMI Home held; returning to Aerial")
            goHome()
        }
    }
    private var lastCecHomeUp = Long.MIN_VALUE / 2

    private fun takeCecHome(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            if (!CecHome.active(this, lastWindow?.first)) return false
            cecHomeDown = true
            cecHomeHeld = false
            cecHomeDouble = event.eventTime - lastCecHomeUp in 0 until DOUBLE_HOME_MS
            handler.removeCallbacks(cecHomeHold)
            if (!cecHomeDouble) {
                Log.i(TAG, "HDMI Home down; sending CEC Home")
                CecHome.send(this, lastWindow?.first)
                handler.postDelayed(cecHomeHold, ViewConfiguration.getLongPressTimeout().toLong())
            }
        }
        if (!cecHomeDown) return false
        if (event.action == KeyEvent.ACTION_UP) {
            handler.removeCallbacks(cecHomeHold)
            cecHomeDown = false
            if (!event.isCanceled && !cecHomeHeld) {
                if (cecHomeDouble) {
                    lastCecHomeUp = Long.MIN_VALUE / 2
                    Log.i(TAG, "HDMI Home pressed twice; returning to Aerial")
                    goHome()
                } else {
                    lastCecHomeUp = event.eventTime
                }
            }
        }
        return true
    }

    private var volumeSeen: Pair<Int, Boolean>? = null

    private var volumeKeyTaken = false

    private fun volumeNow(): Pair<Int, Boolean>? = getSystemService<AudioManager>()?.let {
        runCatching {
            it.getStreamVolume(AudioManager.STREAM_MUSIC) to (Build.VERSION.SDK_INT >= 23 && it.isStreamMute(AudioManager.STREAM_MUSIC))
        }.getOrNull()
    }

    private fun takeVolumeKey(event: KeyEvent): Boolean {
        val stepKey = event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            lastVolumeDown = event.downTime
            handler.removeCallbacks(volumeRepeat)
            if (volumeStepPending) volumeStepIfUnmoved.run()
            handler.removeCallbacks(volumeStepIfUnmoved)
            if (stepKey && event.downTime - volumeDownPassedAt >= VOLUME_LIFT_WAIT_MS) noteVolumeLiftUnseen(this)
            val now = volumeNow()
            val seen = volumeSeen
            val movedFirst = now != null && seen != null && now != seen
            volumeKeyTaken = movedFirst || (stepKey && volumeLiftUnseen(this))
            if (volumeKeyTaken && !movedFirst) {
                volumeStepFrom = now
                volumeStepDirection = if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP) 1 else -1
                volumeStepPending = true
                handler.postDelayed(volumeStepIfUnmoved, VOLUME_UNMOVED_MS)
            }
            volumeDownPassedAt = if (stepKey && !volumeKeyTaken) event.downTime else Long.MAX_VALUE
            if (movedFirst) {
                Log.i(TAG, "volume already changed before the key filter; not passing the key on")
                volumeRepeatDirection = when (event.keyCode) {
                    KeyEvent.KEYCODE_VOLUME_UP -> 1
                    KeyEvent.KEYCODE_VOLUME_DOWN -> -1
                    else -> 0
                }
                if (volumeRepeatDirection != 0 && (repeatsHeldVolume || takenVolumeLiftSeen)) {
                    volumeRepeatUntil = SystemClock.uptimeMillis() + VOLUME_REPEAT_MAX_MS
                    handler.postDelayed(volumeRepeat, ViewConfiguration.getKeyRepeatTimeout().toLong())
                }
            }
        } else if (event.action == KeyEvent.ACTION_DOWN) {
            handler.removeCallbacks(volumeRepeat)
        }
        val taken = volumeKeyTaken
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            DiagLog.i("guard: ${KeyEvent.keyCodeToString(event.keyCode)} down at ${event.downTime}${if (taken) ", not passed on" else ""}")
        }
        if (event.action == KeyEvent.ACTION_UP) {
            if (taken && stepKey && !takenVolumeLiftSeen) {
                takenVolumeLiftSeen = true
                DiagLog.i("guard: the lift of a volume press not passed on reached the filter; held keys step the volume from now on")
            }
            volumeDownPassedAt = Long.MAX_VALUE
            volumeUps[event.keyCode] = event.eventTime
            DiagLog.i("guard: ${KeyEvent.keyCodeToString(event.keyCode)} up at ${event.eventTime}")
            handler.removeCallbacks(volumeRepeat)
            volumeKeyTaken = false
            volumeSeen = volumeNow()
            return false
        }
        return taken
    }

    private var volumeDownPassedAt = Long.MAX_VALUE
    private var volumeStepFrom: Pair<Int, Boolean>? = null
    private var volumeStepDirection = 0
    private var volumeStepPending = false
    private val volumeStepIfUnmoved = Runnable {
        volumeStepPending = false
        if (volumeNow() != volumeStepFrom) return@Runnable
        DiagLog.i("guard: the volume did not move for a press not passed on; stepping it")
        SystemProbe.adjustVolume(this, volumeStepDirection, fromKey = true)
        volumeSeen = volumeNow()
    }

    private var takenVolumeLiftSeen = false

    private var volumeRepeatDirection = 0
    private var volumeRepeatUntil = 0L
    private val volumeRepeat: Runnable = object : Runnable {
        override fun run() {
            if (SystemClock.uptimeMillis() > volumeRepeatUntil) {
                Log.i(TAG, "volume key still not up after ${VOLUME_REPEAT_MAX_MS / 1000} s; no more steps")
                return
            }
            SystemProbe.adjustVolume(this@HomeGuardService, volumeRepeatDirection, fromKey = true)
            handler.postDelayed(this, ViewConfiguration.getKeyRepeatDelay().toLong())
        }
    }

    private val repeatsHeldVolume by lazy {
        runCatching { packageManager.getPackageInfo("com.tcl.settings", 0) }.isSuccess
    }

    private val closesXiaomiPanel by lazy {
        runCatching { packageManager.getPackageInfo("com.xiaomi.mitv.settings", 0) }.isSuccess
    }

    private fun closeSystemDialogs() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S || !closesXiaomiPanel) return
        @Suppress("DEPRECATION")
        runCatching { sendBroadcast(Intent(Intent.ACTION_CLOSE_SYSTEM_DIALOGS).putExtra("reason", "homekey")) }
            .onFailure { Log.w(TAG, "could not close the system dialogs", it) }
    }

    private var lastHomeUpAt = Long.MIN_VALUE / 2

    private var homeHeld = false
    private val homeHold = Runnable {
        homeHeld = true
        Log.i(TAG, "Home held; opening recent apps")
        openRecents()
    }

    private fun openRecents() {
        val xiaomi = ComponentName("com.xiaomi.mitv.systemui", "com.xiaomi.mitv.systemui.MiTVSystemUIService")
        val service = runCatching { packageManager.getServiceInfo(xiaomi, 0) }.getOrNull()
        if (service?.enabled == true && service.applicationInfo.enabled && service.exported &&
            (service.permission.isNullOrEmpty() ||
                ContextCompat.checkSelfPermission(this, service.permission) == PackageManager.PERMISSION_GRANTED)) {
            if (runCatching { startService(Intent().setComponent(xiaomi).putExtra("recent_control", 2)) }.getOrNull() != null) return
        }
        val tcl = ComponentName("com.tcl.cyberui", "com.tcl.global.history.receiver.GlobalHistoryReceiver")
        val receiver = runCatching { packageManager.getReceiverInfo(tcl, 0) }.getOrNull()
        if (receiver?.enabled == true && receiver.applicationInfo.enabled && receiver.exported &&
            receiver.permission.isNullOrEmpty()) {
            val sent = runCatching {
                sendBroadcast(Intent(TCL_TASK_CENTRE).setComponent(tcl).addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES))
            }.isSuccess
            if (sent) return
        }
        for (action in TASK_CENTRE_ACTIONS) {
            val intent = Intent(action)
                .addCategory(Intent.CATEGORY_DEFAULT)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { startActivity(intent) }.isSuccess) return
        }
        performGlobalAction(GLOBAL_ACTION_RECENTS)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        sawEvent = true
        deafAfterHome = false
        windowsSeen++
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        val cls = event.className?.toString() ?: return
        val before = lastWindow?.first
        if (isActivity(pkg, cls)) {
            lastWindow = pkg to cls
            if (!CecHome.active(this, pkg)) {
                lastCecHomeUp = Long.MIN_VALUE / 2
                handler.removeCallbacks(cecHomeHold)
            }
        }
        if (!GuardSetup.isUserUnlocked(this)) return
        if (isFallbackHomeScreen(pkg, cls) && !screenOff) {
            if (SystemClock.uptimeMillis() - broughtBackAt < 1_000) return
            DiagLog.i("guard: $pkg/$cls in front after the unlock; bringing Aerial back")
            goHome()
            return
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.LOLLIPOP_MR1 && pkg == JAMDEO_LAUNCHER &&
            cls == "android.widget.RelativeLayout" && pkg in stockHomes && !screenOff
        ) {
            DiagLog.i("guard: Jamdeo's panel over ${before ?: "?"}; closing it and bringing Aerial back")
            performGlobalAction(GLOBAL_ACTION_BACK)
            goHome()
            return
        }
        if (firmwarePushedAtBoot(pkg) && isActivity(pkg, cls) && !screenOff) {
            DiagLog.i("guard: $pkg/$cls came up at boot with no key pressed; bringing Aerial back")
            goHome()
            return
        }
        val allowed = SystemClock.uptimeMillis() < allowStockUntil
        if (pkg in stockHomes) {
            if (!allowed && isActivity(pkg, cls) && GuardSetup.installerJustClosed(this)) {
                Log.i(TAG, "stock launcher $pkg in front after an update; bringing Aerial back")
                goHome()
                return
            }
            updateKeyFilter()
            if (cls in stockHomes.getValue(pkg)) GuardSetup.noteStockHomeInFront(this)
            if (bootStockHolding && !bootStockShown && cls in stockHomes.getValue(pkg)) {
                bootStockShown = true
                handler.removeCallbacks(endBootStockHold)
                handler.postDelayed(endBootStockHold, DeviceProfile.current(this).bootStockMs.toLong())
            }
            val bootCover = isPatchwallHomeCover(pkg, cls)
            if ((cls !in stockHomes.getValue(pkg) && !bootCover) || allowed) return
            if (before in SOURCE_SWITCHERS || pkg in SOURCE_SWITCHERS) {
                Log.i(TAG, "stock home $pkg came to the front during an input switch; looking again shortly")
                takeOverOnceSettled("after an input switch", woke = false)
                return
            }
            val since = SystemClock.uptimeMillis() - broughtBackAt
            if (since < 1_000) {
                handler.removeCallbacks(stockHomeRecheck)
                handler.postDelayed(stockHomeRecheck, 1_000 - since + 300)
                return
            }
            if (bootTargetCovered()) {
                Log.i(TAG, "stock home $pkg came to the front over what the TV starts on; opening that again")
                DiagLog.i("guard: stock home $pkg/$cls over what the TV starts on; opening that again")
                goHome(reopenBoot = true)
                return
            }
            Log.i(TAG, "stock home $pkg came to the front; bringing Aerial back")
            DiagLog.i("guard: stock home $pkg/$cls in front, not opened on purpose${if (allowStockUntil != 0L) " (allowance ran out)" else ""}; bringing Aerial back")
            goHome(woke = SystemClock.elapsedRealtime() - screenOnAt < WAKE_WINDOW_MS)
            return
        }
        updateKeyFilter()
        if (allowed && pkg != packageName && isApp(pkg) && !visitStillOpening()) endStockVisit("$pkg in front")
    }

    private val activityCache = HashMap<Pair<String, String>, Boolean>()

    private fun isActivity(pkg: String, cls: String): Boolean = activityCache.getOrPut(pkg to cls) {
        runCatching { packageManager.getActivityInfo(android.content.ComponentName(pkg, cls), 0) }.isSuccess
    }

    private fun isApp(pkg: String): Boolean =
        packageManager.getLeanbackLaunchIntentForPackage(pkg) != null || packageManager.getLaunchIntentForPackage(pkg) != null

    private fun isFallbackHomeScreen(pkg: String, cls: String): Boolean =
        cls.endsWith(".FallbackHome") && (pkg to cls) in fallbackHomes && GuardSetup.isUserUnlocked(this)

    private fun isDefaultHome(): Boolean = resolvedHome() == packageName

    private fun resolvedHome(): String? =
        packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName

    private val refreshHomesAfterPackageChange = Runnable { refreshStockHomes() }

    private val packageChanges = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            handler.removeCallbacks(refreshHomesAfterPackageChange)
            handler.postDelayed(refreshHomesAfterPackageChange, PACKAGE_SETTLE_MS)
        }
    }

    private fun refreshStockHomes() {
        val standard = packageManager.queryIntentActivities(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY,
        )
        val vendor = runCatching {
            packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN), PackageManager.GET_RESOLVED_FILTER)
                .filter { ri -> ri.filter?.categoriesIterator()?.asSequence()?.any(Companion::isVendorHomeCategory) == true }
        }.getOrDefault(emptyList())
        val resolved = resolvedHome()
        fallbackHomes = runCatching {
            packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
                .filter { it.priority < 0 && it.activityInfo.packageName != packageName }
                .filter { it.activityInfo.name != LOW_PRIORITY_HOMES[it.activityInfo.packageName] }
                .map { it.activityInfo.packageName to it.activityInfo.name }.toSet()
        }.getOrDefault(emptySet())
        val homes = (standard + vendor)
            .filter { it.activityInfo.packageName != packageName }
            .filter { it.priority >= 0 || it.activityInfo.name == LOW_PRIORITY_HOMES[it.activityInfo.packageName] }
            .filter { it.activityInfo.packageName !in INPUT_PLAYERS || it.activityInfo.packageName == resolved }
            .filter { it.activityInfo.packageName !in NOT_LAUNCHERS }
            .filter {
                (it.activityInfo.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0 ||
                    it.activityInfo.packageName == resolved
            }
            .groupBy({ it.activityInfo.packageName }, { it.activityInfo.name })
            .mapValues { it.value.toSet() }
        stockHomes = homes.mapValues { (pkg, classes) ->
            classes + HOME_SCREENS_BEHIND_TRAMPOLINES[pkg].orEmpty().filter { isActivity(pkg, it) }
        }
        val unfiltered = FIRMWARE_HOMES_WITHOUT_FILTER.mapValues { (pkg, classes) ->
            classes.filter { isActivity(pkg, it) }.toSet()
        }.filterValues { it.isNotEmpty() }
        stockHomes = (stockHomes.keys + unfiltered.keys).associateWith {
            stockHomes[it].orEmpty() + unfiltered[it].orEmpty()
        }
        CoocaaHome.launcher(this)?.takeIf { isActivity(it.packageName, it.className) }?.let { own ->
            stockHomes = stockHomes + (own.packageName to (setOf(own.className) + stockHomes[own.packageName].orEmpty()))
        }
        CoocaaHome.modeScreen(this)?.takeIf { isActivity(it.packageName, it.className) }?.let { mode ->
            stockHomes = stockHomes + (mode.packageName to (stockHomes[mode.packageName].orEmpty() + mode.className))
        }
        knownStockHomes = stockHomes
    }

    private var broughtBackAt = Long.MIN_VALUE / 2

    private val stockHomeRecheck: Runnable = Runnable {
        if (aerialResumed || SystemClock.uptimeMillis() < allowStockUntil) return@Runnable
        val since = SystemClock.uptimeMillis() - broughtBackAt
        if (since < 1_000) return@Runnable
        val front = lastWindow?.first
        if (front != null && front != packageName && front !in stockHomes) return@Runnable
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.R && aerialResumedAt < broughtBackAt && since < APP_SWITCH_HOLD_MS) {
            handler.postDelayed(stockHomeRecheck, APP_SWITCH_HOLD_MS - since)
            return@Runnable
        }
        Log.i(TAG, "the stock launcher came back just after Aerial did; bringing Aerial back again")
        goHome()
    }

    private val backOnPause by lazy {
        Build.VERSION.SDK_INT > Build.VERSION_CODES.R &&
            runCatching { packageManager.getPackageInfo("com.mitv.tvhome", 0) }.isSuccess
    }

    private var homeInAerialAt = Long.MIN_VALUE / 2

    private var lastHomeInAerialAt = Long.MIN_VALUE / 2
    private var recentsWhenBackAt = Long.MIN_VALUE / 2

    private fun noteHomeInAerial() {
        val now = SystemClock.uptimeMillis()
        if (!aerialResumed && now - broughtBackAt > 1_000) return
        if (now - lastHomeInAerialAt < DOUBLE_HOME_BROADCAST_MS) {
            lastHomeInAerialAt = Long.MIN_VALUE / 2
            recentsWhenBackAt = now
            Log.i(TAG, "Home pressed twice; recent apps once Aerial is back")
        } else {
            lastHomeInAerialAt = now
        }
        homeInAerialAt = now
    }

    private fun pressHomeForStock(): Boolean {
        ownHomeAt = SystemClock.uptimeMillis()
        return performGlobalAction(GLOBAL_ACTION_HOME)
    }

    private fun goHome(woke: Boolean = false, reopenBoot: Boolean = false) {
        if (!GuardSetup.isUserUnlocked(this)) return
        if (getSystemService<PowerManager>()?.isInteractive == false) {
            Log.i(TAG, "screen off; leaving Home to the screen-on check")
            return
        }
        if (runCatching { Settings.Global.getString(contentResolver, "tv_power_mode") }.getOrNull() == "QUICK") {
            Log.i(TAG, "quick standby; leaving Home alone")
            return
        }
        broughtBackAt = SystemClock.uptimeMillis()
        runCatching {
            val intent = MainActivity.bringBackIntent(this).putExtra(MainActivity.EXTRA_WOKE, woke)
                .putExtra(MainActivity.EXTRA_REOPEN_BOOT, reopenBoot)
            if (!startPastHomeHold(intent)) startActivity(intent)
        }.onFailure { Log.w(TAG, "could not bring Aerial Home to the front", it) }
    }

    private fun startPastHomeHold(intent: Intent): Boolean {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.O_MR1) return false
        val flags = PendingIntent.FLAG_CANCEL_CURRENT or
            (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0)
        return runCatching { PendingIntent.getActivity(this, BRING_BACK_REQUEST, intent, flags).send() }
            .onFailure { Log.w(TAG, "bringing Aerial back through a PendingIntent failed; starting it directly", it) }
            .isSuccess
    }

    companion object {
        private const val TAG = "AerialGuard"
        private const val BRING_BACK_REQUEST = 933
        private const val BOOT_WINDOW_MS = 3 * 60 * 1000L

        private const val STOPPED_OVER_STOCK_MS = 2 * 60 * 1000L
        private const val UNLOCK_POLL_MS = 2_000L
        private const val UNLOCK_POLL_FOR_MS = 10 * 60 * 1000L
        private const val HOME_KEY_CHECK_MS = 1500L
        private const val BOOT_TARGET_COVER_MS = 60_000L
        private const val WAKE_TARGET_COVER_MS = 15_000L
        private const val HOME_LEFT_MS = 5_000L
        private const val SETTLE_MS = 1500L
        private const val BOOT_STOCK_CAP_MS = 20_000L
        private const val STOCK_OPENING_MS = 2_000L
        private const val WAKE_WINDOW_MS = 15_000L
        private val BOOT_RECHECKS_MS = listOf(30_000L, 45_000L)
        private const val COVERED_UNSEEN_MS = 3_000L
        private const val DEAF_AFTER_MS = 20_000L
        private const val FRONT_AFTER_HOME_MS = 1_500L
        private const val DEAF_LOOK_MS = 150L
        private const val STOCK_HOME_FRONT_MS = 2_000L
        private const val STOCK_HOME_LOOK_MS = 20L
        private const val STOCK_HOME_LOOK_FOR_MS = 600L
        private const val STOCK_HOME_SLACK_MS = 100L
        private const val UI_BOOST = "mitv_fast_cur_anim_scale"
        private const val XGIMI_FRAMEWORK = "com.xgimi.xgimiframeworkui"
        private const val JMGO_TV = "com.jmgo.tv"
        private const val FOREGROUND_POLL_MS = 1_000L
        private const val FOREGROUND_LOOKBACK_MS = 10_000L
        private const val UNKNOWN_FRONT_LOOKBACK_MS = 6 * 60 * 60 * 1000L
        private const val ACTIVITY_SETTLE_MS = 1000L
        private const val STOCK_VISIT_MS = 60 * 60 * 1000L
        private const val DANGBEI_STR_SHUTDOWN = "com.dangbei.action.STR_SHUTDOWN"
        private const val DOUBLE_HOME_MS = 400L
        private const val APP_SWITCH_HOLD_MS = 5_500L
        private const val PACKAGE_SETTLE_MS = 1000L
        private const val PATCHWALL = "com.mitv.tvhome"
        private const val PATCHWALL_BOOT = "com.mitv.tvhome.BootActivity"
        private const val VIDAA_LAUNCHER = "com.jamdeo.tv.vod"

        private val INVISIBLE_LAUNCHERS = setOf(VIDAA_LAUNCHER, "com.ktcp.launcher")
        private const val JAMDEO_LAUNCHER = "com.jamdeo.launcher"
        private const val TCL_TASK_CENTRE ="com.tcl.action.OPEN_GLOBAL_HISTORY"
        private val TASK_CENTRE_ACTIONS = listOf("com.dangbei.recentstask.action.SHOW_RECENTS_FOR_USER")
        private const val VOLUME_REPEAT_MAX_MS = 8_000L
        private const val HOME_PAUSE_MS = 400L
        private const val OWN_HOME_MS = 1_500L
        private const val DOUBLE_HOME_BROADCAST_MS = 600L
        private val VOLUME_KEYS = setOf(KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE)

        private val volumeUps = java.util.concurrent.ConcurrentHashMap<Int, Long>()

        fun volumeKeyCameUp(keyCode: Int, downTime: Long): Boolean = (volumeUps[keyCode] ?: Long.MIN_VALUE) >= downTime

        @Volatile
        private var lastVolumeDown = Long.MIN_VALUE

        fun volumePressOver(context: Context, downTime: Long): Boolean =
            lastVolumeDown > downTime || volumeLiftUnseen(context)

        private const val KEY_MOUSE_FILTER_AT = "guard_mouse_filter_at"
        private const val KEY_MOUSE_FILTER_BOOT = "guard_mouse_filter_boot"
        private const val KEY_MOUSE_DOWNED_SYSTEM = "guard_mouse_downed_system"
        private const val MOUSE_CRASH_MS = 180_000L
        @Volatile
        private var mouseDownedSystem: Boolean? = null

        private fun bootStartedAt() = System.currentTimeMillis() - SystemClock.elapsedRealtime()

        private fun mouseDownedSystem(context: Context): Boolean = mouseDownedSystem
            ?: if (!GuardSetup.isUserUnlocked(context)) false else (context.getSharedPreferences("system", Context.MODE_PRIVATE).getString(KEY_MOUSE_DOWNED_SYSTEM, null) == Build.FINGERPRINT)
                .also { mouseDownedSystem = it }

        private fun noteMouseWithFilter(context: Context) {
            if (!GuardSetup.isUserUnlocked(context) || mouseDownedSystem(context)) return
            val prefs = context.getSharedPreferences("system", Context.MODE_PRIVATE)
            val at = System.currentTimeMillis()
            prefs.edit().putLong(KEY_MOUSE_FILTER_AT, at).putLong(KEY_MOUSE_FILTER_BOOT, bootStartedAt()).commit()
            Handler(Looper.getMainLooper()).postDelayed({
                if (prefs.getLong(KEY_MOUSE_FILTER_AT, 0L) == at) prefs.edit().remove(KEY_MOUSE_FILTER_AT).remove(KEY_MOUSE_FILTER_BOOT).apply()
            }, MOUSE_CRASH_MS)
        }

        private fun checkMouseWithFilter(context: Context) {
            if (!GuardSetup.isUserUnlocked(context)) return
            val prefs = context.getSharedPreferences("system", Context.MODE_PRIVATE)
            val at = prefs.getLong(KEY_MOUSE_FILTER_AT, 0L)
            if (at == 0L) return
            val boot = prefs.getLong(KEY_MOUSE_FILTER_BOOT, 0L)
            val edit = prefs.edit().remove(KEY_MOUSE_FILTER_AT).remove(KEY_MOUSE_FILTER_BOOT)
            val since = System.currentTimeMillis() - at
            val sameBoot = kotlin.math.abs(bootStartedAt() - boot) < 10_000
            val newProcess = Build.VERSION.SDK_INT >= 24 && bootStartedAt() + Process.getStartElapsedRealtime() > at
            if (since in 0..MOUSE_CRASH_MS && sameBoot && newProcess) {
                mouseDownedSystem = true
                edit.putString(KEY_MOUSE_DOWNED_SYSTEM, Build.FINGERPRINT)
                Log.w(TAG, "Aerial died ${since / 1000} s after a mouse connected with the filter on; a mouse stops the filter from now on")
                DiagLog.i("guard: Aerial died ${since / 1000} s after a mouse connected with keys filtered; a mouse stops the filter from now on")
            }
            edit.apply()
        }

        private const val KEY_VOLUME_LIFT_UNSEEN = "guard_volume_lift_unseen"
        private const val VOLUME_UNMOVED_MS = 300L
        private const val VOLUME_LIFT_WAIT_MS = 1_000L
        @Volatile
        private var volumeLiftUnseen: Boolean? = null

        private fun volumeLiftUnseen(context: Context): Boolean = volumeLiftUnseen
            ?: if (!GuardSetup.isUserUnlocked(context)) false else (context.getSharedPreferences("system", Context.MODE_PRIVATE).getString(KEY_VOLUME_LIFT_UNSEEN, null) == Build.FINGERPRINT)
                .also { volumeLiftUnseen = it }

        private fun noteVolumeLiftUnseen(context: Context) {
            if (!GuardSetup.isUserUnlocked(context) || volumeLiftUnseen(context)) return
            volumeLiftUnseen = true
            Log.i(TAG, "a volume press passed on never came up at the filter; no volume press is passed on from now on")
            DiagLog.i("guard: a volume press passed on never came up here; volume presses stay with the firmware from now on")
            context.getSharedPreferences("system", Context.MODE_PRIVATE).edit().putString(KEY_VOLUME_LIFT_UNSEEN, Build.FINGERPRINT).apply()
        }

        @Volatile
        private var deafRetried = false

        @Volatile
        private var deafAfterHomeRestarted = false

        @Volatile
        private var deafAfterHome = false

        private var quietHomeApps: Set<String>? = null

        private const val QUIET_HOME_PREFS = "guard_quiet_home"
        private const val QUIET_HOME_MAX = 20

        private fun quietHomeApps(context: Context): Set<String> = quietHomeApps
            ?: context.getSharedPreferences(QUIET_HOME_PREFS, Context.MODE_PRIVATE)
                .getStringSet("apps", null).orEmpty().toSet().also { quietHomeApps = it }

        private fun noteQuietHomeApp(context: Context, pkg: String) {
            val now = quietHomeApps(context)
            if (pkg in now) return
            val next = (now + pkg).toList().takeLast(QUIET_HOME_MAX).toSet()
            quietHomeApps = next
            Log.i(TAG, "no window change after Home in $pkg; reading usage stats at once there from now on")
            context.getSharedPreferences(QUIET_HOME_PREFS, Context.MODE_PRIVATE).edit().putStringSet("apps", next).apply()
        }

        private val VIDAA_PACKAGES = listOf("com.hisense.vidaaassistant", "com.jamdeo.tv.vod")
        private val INPUT_PLAYERS = setOf("com.tcl.tv", "com.xiaomi.mitv.tvplayer", "com.skyworth.tv", "com.hisilicon.tvui")
        private val NOT_LAUNCHERS = setOf("com.stv.appdaemon", "com.letv.lekids")
        private val SOURCE_SWITCHERS = setOf("com.tianci.tv")
        private const val VENDOR_HOME_PREFIX = "android.intent.category.HOME."
        private const val VENDOR_HOME_SUFFIX = "_HOME"

        internal fun isVendorHomeCategory(category: String): Boolean =
            category.startsWith(VENDOR_HOME_PREFIX) ||
                (category.endsWith(VENDOR_HOME_SUFFIX) && category != "android.intent.category.SECONDARY_HOME" &&
                    !category.endsWith("_AT_HOME"))

        private val FIRMWARE_BOOT_APPS = setOf("com.yunos.tv.bluray")

        private val HOME_SCREENS_BEHIND_TRAMPOLINES = mapOf(
            "com.stv.deskplatform" to listOf("com.stv.deskplatform.ui.activity.Launcher"),
            "com.ktcp.launcher" to listOf("com.ktcp.launcher.activity.LauncherHomeSingleTaskActivity"),
            "com.cos.launcher" to listOf("com.dangbei.tvlauncher.IndexActivity"),
        )

        private val FIRMWARE_HOMES_WITHOUT_FILTER = mapOf(
            "com.skyworth.panasonichome" to listOf("com.skyworth.panasonichome.MainActivity"),
            "com.coocaa.aigc.home" to listOf("com.coocaa.aigc.home.home.rootpage.HomeActivity"),
            "com.tianci.movieplatform" to listOf(
                "com.coocaa.homepage.vast.HomePageActivity",
                "com.ccos.cooui.movieplatform.ElderMainActivity",
            ),
            "com.mylejia.launcher" to listOf("com.mylejia.launcher.activity.DesktopActivity"),
            "com.tcl.cyberui" to listOf("com.tcl.cyberui.MainActivity"),
            "com.jamdeo.tv.vod" to listOf("com.ju.main.launcher.LauncherActivity"),
            "com.xiaomi.eetv.litehome" to listOf("com.xiaomi.eetv.litehome.Launcher"),
            "com.mitv.tvhome.atv" to listOf("com.mitv.tvhome.atv.app.PatchWallActivity"),
        )

        private val LOW_PRIORITY_HOMES = mapOf(
            "com.keylab.speech.view.vidaa" to "com.ju.secondscreen.HomeActivity",
        )

        @Volatile
        private var instance: HomeGuardService? = null

        val isRunning: Boolean get() = instance != null

        fun diagnosticState(): String {
            val s = instance ?: return "Home Guard bound=false resumed=$aerialResumed"
            val flag = AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
            return "Home Guard bound=true resumed=$aerialResumed filter=${s.serviceInfo?.flags?.and(flag) != 0} " +
                "screenOff=${s.screenOff} gamepad=${s.gamepadName?.let { "'$it'" } ?: false} mouse=${s.mouseAttached} mouseDowned=${mouseDownedSystem(s)} volumeLiftUnseen=${volumeLiftUnseen(s)} stockAllowed=${SystemClock.uptimeMillis() < allowStockUntil} " +
                "front=${s.lastWindow} windowEvents=${s.windowsSeen} resolved=${s.resolvedHome()} homes=${s.stockHomes}"
        }

        fun usageStatsAllowed(context: Context): Boolean = runCatching {
            @Suppress("DEPRECATION")
            context.getSystemService<AppOpsManager>()?.checkOpNoThrow(
                AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName,
            ) == AppOpsManager.MODE_ALLOWED
        }.getOrDefault(false)

        fun refreshCecHome() { instance?.run { watchCecInput(); updateKeyFilter() } }

        fun onBootCompleted(): Boolean {
            val s = instance ?: return false
            s.bootCompletedAt = SystemClock.elapsedRealtime()
            s.handler.post {
                if (s.unlockPending && GuardSetup.isUserUnlocked(s)) {
                    s.onUserUnlocked("boot completed while the unlock was unheard")
                    return@post
                }
                if (s.lastWindow?.first == s.packageName) return@post
                if (s.getSystemService<PowerManager>()?.isInteractive == true) {
                    s.takeOverOnceSettled("boot completed", woke = true)
                    s.scheduleBootRechecks()
                }
            }
            return true
        }

        fun stockHomes(): Map<String, Set<String>>? = instance?.stockHomes

        @Volatile
        private var knownStockHomes: Map<String, Set<String>> = emptyMap()

        fun isStockHome(packageName: String): Boolean =
            instance?.stockHomes?.containsKey(packageName) == true || knownStockHomes.containsKey(packageName)

        fun openStockLauncher(pkg: String, startDirectly: () -> Boolean): Boolean {
            allowStockLauncher()
            val s = instance ?: return startDirectly()
            if (s.resolvedHome() == pkg && s.pressHomeForStock()) return true
            return startDirectly() || s.pressHomeForStock()
        }

        @Volatile
        private var allowStockUntil = 0L

        @Volatile
        private var allowStockFrom = 0L

        private fun endStockVisit(why: String) {
            visitContext?.let { GuardSetup.noteStockVisit(it, null) }
            if (allowStockUntil == 0L) return
            if (SystemClock.uptimeMillis() < allowStockUntil) DiagLog.i("guard: stock launcher visit over ($why)")
            allowStockUntil = 0L
        }

        @Volatile
        private var visitContext: Context? = null

        fun allowStockLauncher(context: Context? = null) {
            val now = SystemClock.uptimeMillis()
            allowStockUntil = now + STOCK_VISIT_MS
            allowStockFrom = now
            (context?.applicationContext ?: instance?.applicationContext)?.let {
                visitContext = it
                GuardSetup.noteStockVisit(it, SystemClock.elapsedRealtime() + STOCK_VISIT_MS)
            }
            DiagLog.i("guard: the stock launcher opened on purpose; leaving it in front")
        }

        private fun restoreStockVisit(context: Context) {
            visitContext = context.applicationContext
            if (aerialResumed) return GuardSetup.noteStockVisit(context, null)
            val left = GuardSetup.stockVisitLeftMs(context)
            if (left <= 0L || SystemClock.uptimeMillis() < allowStockUntil) return
            allowStockUntil = SystemClock.uptimeMillis() + left
            DiagLog.i("guard: the stock launcher visit opened before Aerial was stopped still holds; leaving it in front")
        }

        private fun visitStillOpening(): Boolean =
            SystemClock.uptimeMillis() - allowStockFrom < STOCK_OPENING_MS

        @Volatile
        private var aerialResumed = false

        @Volatile
        private var aerialResumedAt = Long.MIN_VALUE / 2

        @Volatile
        private var aerialPausedAt = Long.MIN_VALUE / 2

        fun onAerialResumed() {
            aerialResumed = true
            aerialResumedAt = SystemClock.uptimeMillis()
            val s = instance ?: return
            GuardSetup.installerJustClosed(s)
            endStockVisit("Aerial back")
            s.updateKeyFilter()
            if (SystemClock.uptimeMillis() - s.recentsWhenBackAt < 2_000) {
                s.recentsWhenBackAt = Long.MIN_VALUE / 2
                s.openRecents()
            }
        }

        fun onAerialPaused(): Boolean {
            aerialResumed = false
            aerialPausedAt = SystemClock.uptimeMillis()
            val s = instance ?: return false
            s.updateKeyFilter()
            if (s.backOnPause && SystemClock.uptimeMillis() - s.homeInAerialAt < HOME_PAUSE_MS) {
                s.homeInAerialAt = Long.MIN_VALUE / 2
                Log.i(TAG, "Aerial paused for Home; bringing it back before the stock launcher shows")
                s.goHome()
                return true
            }
            return false
        }
    }
}
