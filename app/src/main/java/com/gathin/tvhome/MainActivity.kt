package com.gathin.tvhome

import android.app.ActivityOptions
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.media.AudioManager
import android.os.Build
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewTreeObserver
import android.view.WindowManager
import android.provider.Settings
import android.widget.Toast
import androidx.core.content.getSystemService
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.lifecycleScope
import com.gathin.tvhome.apps.AppBackgrounds
import com.gathin.tvhome.apps.BilibiliSubs
import com.gathin.tvhome.apps.HuyaSubs
import com.gathin.tvhome.apps.YoutubeSubs
import com.gathin.tvhome.apps.AppEntry
import com.gathin.tvhome.apps.CustomIcons
import com.gathin.tvhome.apps.IconLibrary
import com.gathin.tvhome.apps.AppNames
import com.gathin.tvhome.apps.SourceLooks
import com.gathin.tvhome.apps.StandIn
import com.gathin.tvhome.apps.TileArtFactory
import com.gathin.tvhome.apps.rewritesLauncher
import com.gathin.tvhome.guard.GuardSetup
import com.gathin.tvhome.guard.HomeGuardService
import com.gathin.tvhome.ui.home.FilmAfterInput
import com.gathin.tvhome.ui.home.HomeScreen
import com.gathin.tvhome.ui.home.AerialStart
import com.gathin.tvhome.ui.home.LiveFilm
import com.gathin.tvhome.ui.home.saverCarriesWallpaper
import com.gathin.tvhome.ui.home.asFocusedTile
import com.gathin.tvhome.ui.theme.LocalReducedMotion
import com.gathin.tvhome.ui.home.TileFaces
import com.gathin.tvhome.ui.theme.FontWarmup
import com.gathin.tvhome.ui.theme.canvasDensity
import com.gathin.tvhome.update.SelfCompile
import com.gathin.tvhome.update.Updates
import com.gathin.tvhome.ui.components.UhdCanvas
import com.gathin.tvhome.settings.AerialSettings
import com.gathin.tvhome.wallpaper.PhoneServer
import com.gathin.tvhome.wallpaper.Wallpapers
import com.gathin.tvhome.wallpaper.Aerials
import com.gathin.tvhome.system.BootOpen
import com.gathin.tvhome.system.DiagLog
import com.gathin.tvhome.system.CecSwitch
import com.gathin.tvhome.system.FrameStats
import com.gathin.tvhome.wallpaper.ScreensaverMode
import com.gathin.tvhome.system.SystemScreensaver
import com.gathin.tvhome.system.Diagnostics
import com.gathin.tvhome.system.MissingClipboard
import com.gathin.tvhome.system.Sounds
import com.gathin.tvhome.system.NetKind
import com.gathin.tvhome.system.SystemProbe
import com.gathin.tvhome.system.HisenseSources
import com.gathin.tvhome.system.KonkaSources
import com.gathin.tvhome.system.WhaleyBoot
import com.gathin.tvhome.system.HomeReopen
import com.gathin.tvhome.system.LaunchFallback
import com.gathin.tvhome.system.settingsLaunchIntent
import com.gathin.tvhome.ratings.Ratings
import com.gathin.tvhome.tmdb.TmdbTrending
import com.gathin.tvhome.weather.Weather
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class MainActivity : ComponentActivity() {

    private val vm by viewModels<HomeViewModel>()

    private val packageReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = vm.reload()
    }

    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = vm.refreshVolume()
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = runOnUiThread {
            vm.refreshNetwork()
            Weather.refreshIfStale(networkCameUp = true)
            TmdbTrending.refreshIfStale()
            Updates.checkIfStale(networkCameUp = true)
        }
        override fun onLost(network: Network) = runOnUiThread { vm.refreshNetwork() }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = runOnUiThread { vm.refreshNetwork() }
    }

    override fun getSystemService(name: String): Any? =
        super.getSystemService(name) ?: if (name == CLIPBOARD_SERVICE) MissingClipboard.standIn else null

    private var locked = false

    private val unlocked = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = startReal(null)
    }

    private fun showLocked() {
        locked = true
        DiagLog.i("onCreate before the user is unlocked; showing the kept backdrop")
        val picture = Wallpapers.lockedBootBackdrop(this)?.takeIf { it.exists() }
            ?.let { runCatching { BitmapFactory.decodeFile(it.path) }.getOrNull() }
        window.setBackgroundDrawable(picture?.let { BitmapDrawable(resources, it) } ?: ColorDrawable(Color.BLACK))
        ContextCompat.registerReceiver(this, unlocked, IntentFilter(Intent.ACTION_USER_UNLOCKED), ContextCompat.RECEIVER_EXPORTED)
        if (GuardSetup.isUserUnlocked(this)) startReal(null)
    }

    private var realStarted = false

    private fun handOverToFirmwareFallbackHome(): Boolean {
        if (handedOverAtBoot) return false
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val fallback = runCatching { packageManager.queryIntentActivities(home, PackageManager.MATCH_SYSTEM_ONLY) }
            .getOrDefault(emptyList())
            .map { it.activityInfo }
            .firstOrNull { it.packageName != packageName && it.name.endsWith(".FallbackHome") && it.name !in AOSP_FALLBACK_HOMES }
            ?: return false
        handedOverAtBoot = true
        val started = runCatching {
            startActivity(home.setClassName(fallback.packageName, fallback.name).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }.isSuccess
        DiagLog.i("onCreate before the user is unlocked; ${if (started) "left to" else "could not start"} ${fallback.packageName}/${fallback.name}")
        if (started) {
            finish()
            watchHandOver(applicationContext)
        }
        return started
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        DiagLog.catchCrashes()
        if (!GuardSetup.isUserUnlocked(this)) {
            super.onCreate(null)
            if (handOverToFirmwareFallbackHome()) return
            hideSystemBars()
            showLocked()
            return
        }
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null && reopenAsHome()) return
        WhaleyBoot.turnOffPowerLight(this)
        startReal(savedInstanceState)
    }

    private fun reopenAsHome(): Boolean {
        val started = intent ?: return false
        if (started.action != null && started.action != Intent.ACTION_MAIN) return false
        if (started.hasCategory(Intent.CATEGORY_HOME) || !isTaskRoot) return false
        val again = bringBackIntent(this)
        if (!again.hasCategory(Intent.CATEGORY_HOME)) return false
        if (!HomeReopen.mayReopen(SystemClock.elapsedRealtime())) {
            DiagLog.i("started as ${started.categories} again right after reopening as Home; staying")
            return false
        }
        DiagLog.i("started as ${started.categories} in task $taskId; reopening as Home")
        return runCatching {
            startActivity(again)
            finishAndRemoveTask()
            true
        }.getOrDefault(false)
    }

    private fun startReal(savedInstanceState: Bundle?) {
        if (realStarted) return
        realStarted = true
        val fromLocked = locked
        if (locked) {
            locked = false
            runCatching { unregisterReceiver(unlocked) }
        }
        DiagLog.init(this)
        DiagLog.i("onCreate ${intent.action} restored=${savedInstanceState != null}; ${DiagLog.device()}")
        hideSystemBars()
        val fresh = savedInstanceState == null
        FontWarmup.start(this)
        val uhd = UhdCanvas.scale(this)
        Wallpapers.init(this)
        Weather.init(this)
        Updates.init(this)
        TmdbTrending.init(this)
        Ratings.init(this)
        startClockTick()
        startBiliHuyaTick()
        Aerials.refreshIfStale(this)
        lifecycleScope.launch(Dispatchers.IO) { SelfCompile.init(this@MainActivity) }
        CustomIcons.init(this)
        AppBackgrounds.init(this)
        YoutubeSubs.init(this)
        BilibiliSubs.init(this)
        HuyaSubs.init(this)
        IconLibrary.init(this)
        SourceLooks.init(this)
        AppNames.init(this)
        AerialSettings.init(this)
        TileArtFactory.useCanvasDensity(canvasDensity(resources.displayMetrics) * uhd)
        Sounds.init(this)
        val reducedMotion = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f

        val baked = Wallpapers.backdrop
        if (baked != null) window.setBackgroundDrawable(BitmapDrawable(resources, baked.asAndroidBitmap()))
        window.decorView.viewTreeObserver.addOnDrawListener(object : ViewTreeObserver.OnDrawListener {
            override fun onDraw() {
                idleHandler.post {
                    window.setBackgroundDrawable(null)
                    window.decorView.viewTreeObserver.removeOnDrawListener(this)
                }
            }
        })
        if (DiagLog.modeSwitch(this)) preferSmoothestRefreshRate() else DiagLog.i("refresh rate left as it is (mode_switch off)")
        if (DiagLog.softLayer(this) && uhd == 1f) window.decorView.setLayerType(View.LAYER_TYPE_SOFTWARE, null)
        FrameStats.attach(window)
        logWindow()

        val home: @Composable () -> Unit = {
            val canvas = Density(
                density = canvasDensity(resources.displayMetrics),
                fontScale = LocalDensity.current.fontScale,
            )
            CompositionLocalProvider(LocalReducedMotion provides reducedMotion, LocalDensity provides canvas) {
                HomeScreen(
                    vm = vm,
                    onLaunch = ::launch,
                    onOpen = ::open,
                    onAppInfo = { open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", it, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                    onUninstall = { open(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", it, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) },
                    onStartScreensaver = { idleHandler.postDelayed({ startScreensaver() }, 150) },
                )
            }
        }
        if (Build.VERSION.SDK_INT >= 29 && uhd > 1f) {
            setContentView(UhdCanvas.host(this, ComposeView(this).apply { setContent(home) }, uhd))
        } else {
            setContent(content = home)
        }

        ContextCompat.registerReceiver(
            this, screenOnReceiver,
            IntentFilter(Intent.ACTION_SCREEN_ON).apply { addAction(Intent.ACTION_SCREEN_OFF) },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                snapshotFlow { vm.overlay is Overlay.ControlCenter }.collectLatest { open ->
                    while (open) {
                        delay(PANEL_REFRESH_MS)
                        vm.refreshPanel()
                    }
                }
            }
        }
        if (fresh && firstStartSinceBoot()) openBootSource("boot")
        if (intent.getBooleanExtra(EXTRA_WOKE, false)) openBootSource("brought back on wake")
        if (intent.getBooleanExtra(EXTRA_REOPEN_BOOT, false)) openBootSource("covered by the stock launcher", again = true)
        if (fromLocked) {
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) startWork()
            if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) resumeWork()
        }
    }

    private fun startClockTick() {
        lifecycleScope.launch {
            while (true) {
                val now = System.currentTimeMillis()
                val next = ((now / CLOCK_TICK_MS) + 1) * CLOCK_TICK_MS
                delay(next - now)
                if (vm.resumed) {
                    Weather.refreshClockTick()
                }
                if (TmdbTrending.trendingGateOpen()) {
                    TmdbTrending.refreshClockTick()
                }
                if (Wallpapers.selectedId == Wallpapers.AUTO) {
                    launch(Dispatchers.IO) { YoutubeSubs.refreshAllIfStale() }
                }
            }
        }
    }

    private fun startBiliHuyaTick() {
        lifecycleScope.launch {
            while (true) {
                val now = System.currentTimeMillis()
                val next = ((now / BILI_HUYA_TICK_MS) + 1) * BILI_HUYA_TICK_MS
                delay(next - now)
                if (Wallpapers.selectedId == Wallpapers.AUTO) {
                    launch(Dispatchers.IO) { BilibiliSubs.refreshAllIfStale() }
                    launch(Dispatchers.IO) { HuyaSubs.refreshAllIfStale() }
                }
            }
        }
    }

    private fun hideSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level < TRIM_MEMORY_RUNNING_LOW || level == TRIM_MEMORY_UI_HIDDEN) return
        DiagLog.i("trim memory, level $level")
        Wallpapers.trimMemory()
        TileFaces.trimMemory()
    }

    override fun onDestroy() {
        if (locked) runCatching { unregisterReceiver(unlocked) }
        runCatching { unregisterReceiver(screenOnReceiver) }
        super.onDestroy()
    }


    private var bootSourceOpenedAt = Long.MIN_VALUE / 2

    private val screenOnReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SCREEN_OFF) { BootOpen.noteScreenOff(context); return }
            idleHandler.postDelayed({
                if (lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) openBootSource("screen on")
            }, 1500)
        }
    }

    private fun firstStartSinceBoot(): Boolean {
        if (SystemClock.elapsedRealtime() > 3 * 60 * 1000L) {
            if (AerialSettings.bootSource != null || AerialSettings.bootApp != null) {
                DiagLog.i("boot target: Home first created ${SystemClock.elapsedRealtime() / 1000} s after boot, too late to open it")
            }
            return false
        }
        if (Build.VERSION.SDK_INT < 24) return true
        val boot = Settings.Global.getInt(contentResolver, Settings.Global.BOOT_COUNT, -1)
        val prefs = getSharedPreferences("system", MODE_PRIVATE)
        if (boot < 0 || prefs.getInt("boot_source_boot", -2) == boot) return false
        prefs.edit().putInt("boot_source_boot", boot).apply()
        return true
    }

    private fun openBootSource(reason: String, again: Boolean = false) {
        val id = AerialSettings.bootSource
        val pkg = AerialSettings.bootApp
        if (id == null && pkg == null) return
        if (!again && SystemClock.elapsedRealtime() - bootSourceOpenedAt < 15_000) return
        if (!again && !BootOpen.due(this)) {
            DiagLog.i("$reason: already opened ${id ?: pkg} since the TV came on")
            return
        }
        bootSourceOpenedAt = SystemClock.elapsedRealtime()
        lifecycleScope.launch {
            val direct = pkg?.let { p ->
                runCatching {
                    val launch = packageManager.getLeanbackLaunchIntentForPackage(p) ?: packageManager.getLaunchIntentForPackage(p)
                    launch?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                        ?.let { packageManager.getApplicationLabel(packageManager.getApplicationInfo(p, 0)).toString() to it }
                }.getOrNull()
            }
            val intent = direct ?: withTimeoutOrNull(30_000) {
                snapshotFlow {
                    if (id != null) vm.sources.firstOrNull { it.id == id }?.let { it.label to it.intent }
                    else vm.entry(pkg!!)?.let { it.label to it.launchIntent() }
                }.filterNotNull().first()
            } ?: run {
                DiagLog.i("$reason: ${id ?: pkg} not listed; not opening it")
                return@launch
            }
            if (id == null && SystemProbe.network(this@MainActivity).kind == NetKind.Offline) {
                withTimeoutOrNull(BOOT_APP_NETWORK_WAIT_MS) {
                    while (SystemProbe.network(this@MainActivity).kind == NetKind.Offline) delay(500)
                }
            }
            DiagLog.i("$reason: opening ${intent.first}, what the TV starts on")
            BootOpen.noteOpened(this@MainActivity)
            openedAppSinceHome = true
            open(intent.second)
            if (again && id != null) nudgeVolumeAfterReopen()
        }
    }

    private fun nudgeVolumeAfterReopen() {
        Handler(Looper.getMainLooper()).postDelayed({
            val am = getSystemService<AudioManager>() ?: return@postDelayed
            runCatching {
                if (Build.VERSION.SDK_INT >= 23 && am.isStreamMute(AudioManager.STREAM_MUSIC)) return@runCatching
                val level = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                val other = if (level > 0) level - 1 else 1
                if (other > am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)) return@runCatching
                am.setStreamVolume(AudioManager.STREAM_MUSIC, other, 0)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0)
                DiagLog.i("boot input opened again; volume set again at $level")
            }
        }, REOPEN_VOLUME_NUDGE_MS)
    }

    private fun preferSmoothestRefreshRate() {
        if (Build.VERSION.SDK_INT < 31) return
        if (AerialSettings.lite) return DiagLog.i("refresh rate left as it is (lite effects)")
        val display = display ?: return
        val current = display.mode
        val seamless = current.alternativeRefreshRates
        val best = display.supportedModes
            .filter { m ->
                m.physicalWidth == current.physicalWidth && m.physicalHeight == current.physicalHeight &&
                    m.refreshRate <= 120.5f && seamless.any { kotlin.math.abs(it - m.refreshRate) < 0.5f }
            }
            .maxByOrNull { it.refreshRate }
            ?.takeIf { it.refreshRate > current.refreshRate + 0.5f } ?: return
        DiagLog.i("refresh rate ${current.refreshRate} -> ${best.refreshRate} Hz (mode ${best.modeId})")
        window.attributes = window.attributes.apply { preferredDisplayModeId = best.modeId }
    }


    private var draws = 0
    private var keysLogged = 0

    private var staleVolumeDown = Long.MIN_VALUE

    private var volumeDownHere = Long.MIN_VALUE
    private var volumeUpHere = Long.MIN_VALUE

    private var volumeMovedDown = Long.MIN_VALUE

    private var volumeAtLift: Pair<Int, Boolean>? = null

    private fun volumeNow(): Pair<Int, Boolean>? = getSystemService<AudioManager>()?.let {
        runCatching {
            it.getStreamVolume(AudioManager.STREAM_MUSIC) to (Build.VERSION.SDK_INT >= 23 && it.isStreamMute(AudioManager.STREAM_MUSIC))
        }.getOrNull()
    }

    private fun dropVolumeKey(event: KeyEvent): Boolean {
        val name = KeyEvent.keyCodeToString(event.keyCode)
        if (event.action == KeyEvent.ACTION_UP) {
            volumeUpHere = event.eventTime
            volumeAtLift = volumeNow()
            return event.downTime == volumeMovedDown
        }
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (event.repeatCount == 0) {
            volumeDownHere = event.downTime
            val now = volumeNow()
            val before = volumeAtLift
            if (now != null && before != null && now != before) {
                volumeMovedDown = event.downTime
                DiagLog.i("key $name down: the volume already moved ($before -> $now); dropped")
                return true
            }
            return false
        }
        if (event.downTime == volumeMovedDown) return true
        val over = volumeUpHere >= event.downTime || volumeDownHere > event.downTime ||
            HomeGuardService.volumeKeyCameUp(event.keyCode, event.downTime) ||
            HomeGuardService.volumePressOver(this, event.downTime)
        if (!over) return false
        if (event.downTime != staleVolumeDown) {
            staleVolumeDown = event.downTime
            DiagLog.i("key $name #${event.repeatCount} repeats a press that is over; dropped")
        }
        return true
    }

    private fun logWindow() {
        val m = resources.displayMetrics
        val modes = if (Build.VERSION.SDK_INT >= 23) display()?.let { d ->
            val alt = if (Build.VERSION.SDK_INT >= 31) d.mode.alternativeRefreshRates.joinToString() else "?"
            "mode ${d.mode.modeId} ${d.mode.physicalWidth}x${d.mode.physicalHeight}@${d.mode.refreshRate} (seamless: $alt) of " +
                d.supportedModes.joinToString { "${it.modeId}:${it.physicalWidth}x${it.physicalHeight}@${it.refreshRate}" }
        } else null
        DiagLog.i(
            "display ${m.widthPixels}x${m.heightPixels} ${m.densityDpi}dpi, canvas density ${canvasDensity(m)}, uhd ${UhdCanvas.scale(this)} (${UhdCanvas.why}); $modes; " +
                "hwui ${systemProperty("debug.hwui.renderer")}, soft_layer=${DiagLog.softLayer(this)}, saver_fade_offscreen=${DiagLog.saverFadeOffscreen(this)}",
        )
        window.decorView.viewTreeObserver.addOnDrawListener {
            draws++
            if (draws <= 3) DiagLog.i("draw $draws: ${window.decorView.width}x${window.decorView.height}, hw=${window.decorView.isHardwareAccelerated}")
        }
        for (s in 2..120 step 6) idleHandler.postDelayed({
            val asked = SystemClock.uptimeMillis()
            Choreographer.getInstance().postFrameCallback {
                DiagLog.i(
                    "${s}s: frame after ${SystemClock.uptimeMillis() - asked} ms, draws $draws, " +
                        "focus ${window.decorView.hasWindowFocus()}, shown ${window.decorView.isShown}, " +
                        "state ${lifecycle.currentState}, apps ${vm.apps.size} loaded ${vm.loaded}, " +
                        "screensaver ${vm.screensaverVisible}, overlay ${vm.overlay}",
                )
            }
        }, s * 1000L)
    }

    private fun display(): android.view.Display? =
        if (Build.VERSION.SDK_INT >= 30) display else @Suppress("DEPRECATION") windowManager.defaultDisplay

    private fun systemProperty(key: String): String? = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, key) as String
    }.getOrNull()

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        DiagLog.i("window focus $hasFocus")
        if (!hasFocus) SystemScreensaver.noteHomeHidden()
        if (!hasFocus && vm.resumed && vm.screensaverVisible) {
            DiagLog.i("a window came over the screensaver; ending it")
            hideScreensaver()
        }
    }

    override fun dump(prefix: String, fd: java.io.FileDescriptor?, writer: java.io.PrintWriter, args: Array<out String>?) {
        super.dump(prefix, fd, writer, args)
        if (locked) return
        if (args?.contains("report") == true) {
            writer.print(Diagnostics.report(this))
        } else {
            writer.println(HomeGuardService.diagnosticState())
            DiagLog.dump(writer)
        }
    }


    private val idleHandler = Handler(Looper.getMainLooper())
    private val showScreensaver = Runnable { if (vm.resumed) startScreensaver() }

    private val prepareScreensaver = Runnable {
        if (vm.resumed && !vm.screensaverVisible) lifecycleScope.launch { AerialStart.prepareAhead(this@MainActivity, SAVER_AHEAD_MS) }
    }

    private val endHomeBounce = Runnable {
        if (vm.homeBounce) DiagLog.i("not back from Home's bounce; the film lets go")
        vm.homeBounce = false
    }

    private fun startScreensaver() {
        AerialSettings.relock()
        val carry = saverCarriesWallpaper(this)
        if (!carry && Wallpapers.screensaver != ScreensaverMode.System && !dreamFails(this) && SystemScreensaver.isAerials(this)) {
            LiveFilm.onHome?.letDecoderGo()
            if (SystemScreensaver.startDream(this)) {
                DiagLog.i("screensaver starts as the system's dream")
                dreamAskedAt = SystemClock.uptimeMillis()
                SystemScreensaver.noteDreamAsked()
                return
            }
            LiveFilm.onHome?.goOn()
        }
        DiagLog.i("screensaver starts (${Wallpapers.screensaver})")
        vm.screensaverVisible = true
        SelfCompile.onScreensaver { vm.screensaverVisible }
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (!screenNeverOff()) idleHandler.postDelayed(releaseScreen, SCREENSAVER_HOLD_MS)
    }
    private val releaseScreen = Runnable { window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }

    private var dreamAskedAt = Long.MIN_VALUE / 2
    private val dreamCheck = Runnable {
        if (!vm.resumed || !hasWindowFocus()) return@Runnable
        SystemScreensaver.forgetDreamAsked()
        noteDreamFails(this)
        DiagLog.i("the system's dream did not show; Aerial's own screensaver instead")
        LiveFilm.onHome?.goOn()
        startScreensaver()
    }
    private var swallowKeyUp = -1

    private val keysDown = HashSet<Int>()

    private var wakeQuietUntil = 0L
    private var swallowTouch = false

    private var openedAppSinceHome = false
        set(value) {
            field = value
            if (value) openedAppAt = SystemClock.uptimeMillis()
        }
    private var openedAppAt = Long.MIN_VALUE / 2

    private fun restartIdleTimer() {
        vm.lastInputAt = SystemClock.uptimeMillis()
        idleHandler.removeCallbacks(showScreensaver)
        idleHandler.removeCallbacks(prepareScreensaver)
        if (!vm.resumed) return
        if (AerialSettings.screensaverMinutes != AerialSettings.NEVER) {
            val idle = idleBeforeScreensaver()
            idleHandler.postDelayed(showScreensaver, idle)
            if (idle > SAVER_AHEAD_MS) idleHandler.postDelayed(prepareScreensaver, idle - SAVER_AHEAD_MS)
            if (holdsOffTheDream() && window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON == 0) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }
        else if (Wallpapers.screensaver != ScreensaverMode.System &&
            window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON == 0 && screenNeverOff()) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun screenNeverOff(): Boolean =
        runCatching { Settings.System.getLong(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT) }.getOrDefault(0L) >= 24 * 60 * 60 * 1000L

    private fun idleBeforeScreensaver(): Long {
        val chosen = AerialSettings.screensaverMinutes * 60_000L
        val firmware = firmwareScreensaverDelay() ?: dreamDelay()?.takeUnless { holdsOffTheDream() }
        return if (firmware != null && firmware > 0) minOf(chosen, maxOf(30_000L, firmware - 15_000L))
        else chosen
    }

    private fun firmwareScreensaverDelay(): Long? =
        runCatching { Settings.System.getLong(contentResolver, "screen_saver_timeout") }.getOrNull()?.takeIf { it > 0 }

    private fun dreamDelay(): Long? =
        if (SystemScreensaver.isAerials(this)) runCatching { Settings.System.getLong(contentResolver, Settings.System.SCREEN_OFF_TIMEOUT) }.getOrNull()?.takeIf { it > 0 } else null

    private fun holdsOffTheDream(): Boolean {
        if (firmwareScreensaverDelay() != null) return false
        val dream = dreamDelay() ?: return false
        return AerialSettings.screensaverMinutes * 60_000L > dream - 15_000L
    }

    private fun hideScreensaver() {
        if (vm.screensaverVisible) DiagLog.i("screensaver ends")
        vm.screensaverVisible = false
        idleHandler.removeCallbacks(releaseScreen)
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (locked) return super.dispatchKeyEvent(event)
        if (event.keyCode == dreamWokeKey) {
            DiagLog.i("key ${KeyEvent.keyCodeToString(event.keyCode)} #${event.repeatCount} is the rest of the press that woke the dream")
            if (event.action == KeyEvent.ACTION_UP) { dreamWokeKey = -1; return true }
            if (event.repeatCount > 0) return true
            dreamWokeKey = -1
        }
        if (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            if (dropVolumeKey(event)) return true
        } else if (event.action == KeyEvent.ACTION_UP) {
            volumeAtLift = volumeNow()
        }
        if (keysLogged++ < 200) DiagLog.i("key ${KeyEvent.keyCodeToString(event.keyCode)} ${if (event.action == KeyEvent.ACTION_DOWN) "down" else "up"} #${event.repeatCount} at ${event.eventTime}, view focused ${currentFocus != null}")
        restartIdleTimer()
        val loneLift = event.action == KeyEvent.ACTION_UP && !event.isCanceled && event.keyCode !in keysDown
        if (event.action == KeyEvent.ACTION_DOWN) keysDown += event.keyCode else keysDown -= event.keyCode
        if (!vm.screensaverVisible && event.keyCode != swallowKeyUp) Sounds.onKey(event)
        if (vm.screensaverVisible && vm.screensaverSkips.onKey(event)) return true
        if (vm.screensaverVisible && loneLift) {
            DiagLog.i("key ${KeyEvent.keyCodeToString(event.keyCode)} lifted with no press; ending the screensaver")
            hideScreensaver()
            wakeQuietUntil = SystemClock.uptimeMillis() + WAKE_QUIET_MS
            return true
        }
        if (vm.screensaverVisible && event.action == KeyEvent.ACTION_DOWN) {
            hideScreensaver()
            swallowKeyUp = event.keyCode
            wakeQuietUntil = SystemClock.uptimeMillis() + WAKE_QUIET_MS
            return true
        }
        if (event.keyCode == swallowKeyUp) {
            if (event.action == KeyEvent.ACTION_UP) swallowKeyUp = -1
            return true
        }
        if (event.eventTime < maxOf(wakeQuietUntil, dreamWokeAt + WAKE_QUIET_MS)) {
            DiagLog.i("key ${KeyEvent.keyCodeToString(event.keyCode)} right after waking, ignored")
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (locked) return super.dispatchTouchEvent(event)
        restartIdleTimer()
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            swallowTouch = vm.screensaverVisible
            if (swallowTouch) hideScreensaver()
        }
        if (swallowTouch) {
            if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) swallowTouch = false
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (locked) return
        DiagLog.i("onNewIntent ${intent.action}")
        if (intent.getBooleanExtra(EXTRA_WOKE, false)) openBootSource("brought back on wake")
        if (intent.getBooleanExtra(EXTRA_REOPEN_BOOT, false)) openBootSource("covered by the stock launcher", again = true)
        if (intent.action == ACTION_PREVIEW_SCREENSAVER) {
            idleHandler.post { startScreensaver() }
            return
        }
        if (intent.hasCategory(Intent.CATEGORY_HOME) || intent.action == Intent.ACTION_MAIN) {
            vm.onHomePressed(resetFocus = !openedAppSinceHome)
            openedAppSinceHome = false
        }
    }

    override fun onStart() {
        super.onStart()
        if (locked) return
        startWork()
    }

    private fun startWork() {
        DiagLog.i("onStart")
        SelfCompile.onHomeStarted(this)
        lifecycleScope.launch(Dispatchers.IO) { PhoneServer.listen(this@MainActivity) }
        GuardSetup.ensureEnabled(this)
        idleHandler.postDelayed({ GuardSetup.rebindIfStalled(this) }, 5_000)
        vm.reloadIfPackagesChanged()
        ContextCompat.registerReceiver(
            this, packageReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_PACKAGE_ADDED)
                addAction(Intent.ACTION_PACKAGE_REMOVED)
                addAction(Intent.ACTION_PACKAGE_CHANGED)
                addAction(Intent.ACTION_PACKAGE_REPLACED)
                addDataScheme("package")
            },
            ContextCompat.RECEIVER_EXPORTED,
        )
        ContextCompat.registerReceiver(
            this, volumeReceiver,
            IntentFilter("android.media.VOLUME_CHANGED_ACTION"),
            ContextCompat.RECEIVER_EXPORTED,
        )
        getSystemService<ConnectivityManager>()?.let { cm ->
            if (Build.VERSION.SDK_INT >= 24) cm.registerDefaultNetworkCallback(networkCallback)
            else cm.registerNetworkCallback(NetworkRequest.Builder().removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(), networkCallback)
        }
        Weather.refreshIfStale()
        TmdbTrending.refreshIfStale()
        Updates.checkIfStale()
    }

    override fun onResume() {
        super.onResume()
        if (locked) return
        resumeWork()
    }

    private fun resumeWork() {
        vm.resumed = true
        CecSwitch.register(this)
        idleHandler.removeCallbacks(endHomeBounce)
        vm.homeBounce = false
        DiagLog.i("onResume; screensaver after ${idleBeforeScreensaver() / 1000} s")
        if (SystemClock.uptimeMillis() - dreamAskedAt < 1_000) {
            idleHandler.postDelayed(dreamCheck, 1_500)
        }
        restartIdleTimer()
        GuardSetup.setHomeInFront(this, true)
        HomeGuardService.onAerialResumed()
        Updates.onHomeResumed()
        Weather.refreshOnResume()
        TmdbTrending.refreshIfStale()
        vm.refreshSystem()
        if (!resumedInProcess) {
            resumedInProcess = true
            val age = if (Build.VERSION.SDK_INT >= 24) SystemClock.elapsedRealtime() - android.os.Process.getStartElapsedRealtime() else -1L
            if (BootOpen.dueAfterColdWake(this, age)) openBootSource("first in front after a cold wake")
        }
        if (SystemClock.uptimeMillis() - openedAppAt > JUST_OPENED_MS) {
            openedAppSinceHome = false
            leftForAnApp = false
        }
        LaunchFallback.next()?.let { page ->
            idleHandler.postDelayed({
                if (vm.resumed && hasWindowFocus()) {
                    DiagLog.i("the page just opened closed at once; opening ${page.component ?: page.action}")
                    open(page)
                } else {
                    DiagLog.i("back in front under a window of the page just opened; not opening ${page.component ?: page.action}")
                }
            }, FALLBACK_FOCUS_MS)
        }
        GuardSetup.rebindForJmgoKeys(this)
    }

    override fun onPause() {
        super.onPause()
        if (locked) return
        vm.resumed = false
        DiagLog.i("onPause")
        leftForAnApp = openedAppSinceHome
        GuardSetup.setHomeInFront(this, false)
        Updates.onHomePaused()
        if (HomeGuardService.onAerialPaused()) {
            vm.homeBounce = true
            idleHandler.removeCallbacks(endHomeBounce)
            idleHandler.postDelayed(endHomeBounce, HOME_BOUNCE_MS)
        }
        idleHandler.removeCallbacks(showScreensaver)
        idleHandler.removeCallbacks(dreamCheck)
        hideScreensaver()
    }

    override fun onStop() {
        super.onStop()
        if (locked) return
        DiagLog.i("onStop")
        SelfCompile.onHomeStopped()
        idleHandler.removeCallbacks(endHomeBounce)
        vm.homeBounce = false
        AerialSettings.relock()
        unregisterReceiver(packageReceiver)
        unregisterReceiver(volumeReceiver)
        runCatching { getSystemService<ConnectivityManager>()?.unregisterNetworkCallback(networkCallback) }
    }

    private fun launch(app: AppEntry, bounds: Rect?) {
        if (AerialSettings.locked && SystemProbe.isSettingsApp(this, app.packageName)) {
            vm.whenUnlocked { launch(app, bounds) }
            return
        }
        openedAppSinceHome = true
        SystemProbe.settingsTileIntent(this, app.packageName)?.let {
            open(it)
            return
        }
        if (HomeGuardService.isStockHome(app.packageName) &&
            HomeGuardService.openStockLauncher(app.packageName) { startStockLauncher(app) }
        ) return
        val options = bounds?.asFocusedTile()?.let {
            ActivityOptions.makeScaleUpAnimation(
                window.decorView, it.left.toInt(), it.top.toInt(), it.width.toInt(), it.height.toInt(),
            ).toBundle().apply {
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) remove("android:activity.packageName")
            }
        }
        LaunchFallback.arm(if (app.packageName == "com.xiaomi.mitv.settings") SystemProbe.settingsFallbacks(this) else emptyList())
        if (app.source != null && KonkaSources.needsSwitch(app.launchIntent())) {
            open(app.launchIntent())
            return
        }
        try {
            FilmAfterInput.noteOpened(this, app.launchIntent())
            if (HisenseSources.open(this, app.launchIntent())) return
            startActivity(settingsLaunchIntent(this, app.launchIntent()), options)
        } catch (e: Exception) {
            if (app.source != null && SystemProbe.openInputPicker(this)) return
            if (e is SecurityException && startStockLauncher(app)) {
                if (isHomeApp(app.packageName)) HomeGuardService.allowStockLauncher()
                return
            }
            Toast.makeText(this, getString(R.string.launch_failed, app.label), Toast.LENGTH_SHORT).show()
        }
    }

    private fun isHomeApp(pkg: String): Boolean = runCatching {
        packageManager.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .any { it.activityInfo.packageName == pkg }
    }.getOrDefault(false)

    private fun startStockLauncher(app: AppEntry): Boolean {
        val intent = app.launchIntent()
        return try {
            startActivity(intent)
            true
        } catch (e: SecurityException) {
            val standIn = StandIn.find(this, app.component)
            DiagLog.i("${app.component.className} needs a permission; opening ${standIn?.className ?: "nothing"} instead")
            standIn != null && runCatching {
                startActivity(Intent(Intent.ACTION_MAIN).setComponent(standIn).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.isSuccess || startStockDirectly(app)
        } catch (e: Exception) {
            startStockDirectly(app)
        }
    }

    private fun startStockDirectly(app: AppEntry): Boolean {
        if (!hidesStandardTasks(this)) return false
        return runCatching {
            startActivity(
                Intent(Intent.ACTION_MAIN).setComponent(app.component)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
            )
        }.isSuccess
    }

    private fun open(intent: Intent) {
        FilmAfterInput.noteOpened(this, intent)
        if (KonkaSources.needsSwitch(intent)) {
            val app = applicationContext
            kotlin.concurrent.thread(name = "AerialKonkaInput") {
                KonkaSources.switchTo(app, intent)
                runOnUiThread { startPage(intent) }
            }
            return
        }
        startPage(intent)
    }

    private fun startPage(intent: Intent) {
        try {
            SystemProbe.startSettingsIntent(this, bareOnRewritingFirmware(intent))
        } catch (e: Exception) {
            if (intent.data?.host == "android.media.tv" && SystemProbe.openInputPicker(this)) return
            LaunchFallback.next()?.let { return open(it) }
            Toast.makeText(this, R.string.unavailable, Toast.LENGTH_SHORT).show()
        }
    }

    private fun bareOnRewritingFirmware(intent: Intent): Intent {
        if (!rewritesLauncher || intent.component != null) return intent
        val target = runCatching { packageManager.resolveActivity(intent, 0)?.activityInfo }.getOrNull()
            ?: return intent
        return Intent().setComponent(ComponentName(target.packageName, target.name))
            .setData(intent.data)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    }

    companion object {
        private const val HOME_BOUNCE_MS = 500L

        private const val FALLBACK_FOCUS_MS = 400L

        private const val SAVER_AHEAD_MS = 8_000L

        private const val CLOCK_TICK_MS = 30 * 60 * 1000L
        private const val BILI_HUYA_TICK_MS = 10 * 60 * 1000L

        private var dreamFailsNow = false

        private fun dreamFails(context: Context): Boolean = dreamFailsNow || dreamFailCount(context) >= 2

        private fun dreamFailCount(context: Context): Int {
            val prefs = context.getSharedPreferences("system", MODE_PRIVATE)
            return if (prefs.getString(DREAM_FAILS_ON, null) == Build.FINGERPRINT) prefs.getInt(DREAM_FAILS, 0) else 0
        }

        private fun noteDreamFails(context: Context) {
            dreamFailsNow = true
            context.getSharedPreferences("system", MODE_PRIVATE).edit()
                .putString(DREAM_FAILS_ON, Build.FINGERPRINT).putInt(DREAM_FAILS, dreamFailCount(context) + 1).apply()
        }

        fun dreamShowedAfter(context: Context, blackMs: Long) {
            if (blackMs <= DREAM_BLACK_MS) return noteDreamShown(context)
            DiagLog.i("the system's dream showed after $blackMs ms of black; Aerial's own screensaver from now on")
            noteDreamFails(context)
        }

        private const val DREAM_BLACK_MS = 2_000L

        private fun noteDreamShown(context: Context) {
            if (dreamFailCount(context) > 0) context.getSharedPreferences("system", MODE_PRIVATE).edit().remove(DREAM_FAILS).apply()
        }

        private const val JUST_OPENED_MS = 1_000L
        private const val DREAM_FAILS_ON = "dream_fails_on"
        private const val DREAM_FAILS = "dream_fails"

        @Volatile
        var leftForAnApp = false
            private set

        private var resumedInProcess = false

        private var handedOverAtBoot = false

        private fun watchHandOver(context: Context) {
            Handler(Looper.getMainLooper()).postDelayed({
                val booted = runCatching {
                    Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
                        .invoke(null, "sys.boot_completed") as String
                }.getOrNull()
                if (booted == "1") return@postDelayed
                DiagLog.i("boot not finished ${HAND_OVER_WATCH_MS / 1000} s after leaving it to the firmware's FallbackHome; showing Aerial")
                runCatching {
                    context.startActivity(Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }, HAND_OVER_WATCH_MS)
        }

        private const val HAND_OVER_WATCH_MS = 90_000L
        private val AOSP_FALLBACK_HOMES = setOf("com.android.settings.FallbackHome", "com.android.tv.settings.system.FallbackHome", "com.android.tv.settings.ui.activity.FallbackHome")

        fun bringBackIntent(context: Context): Intent {
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val intent = if (hidesStandardTasks(context) && isSystemHome(context)) {
                home.setPackage(context.packageName)
            } else {
                Intent(context, MainActivity::class.java).setAction(Intent.ACTION_MAIN)
            }
            return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }

        internal fun hidesStandardTasks(context: Context): Boolean =
            Build.VERSION.SDK_INT >= 33 &&
                runCatching { context.packageManager.getPackageInfo("com.tianci.system", 0) }.isSuccess

        fun isSystemHome(context: Context): Boolean = runCatching {
            val pm = context.packageManager
            val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
            val resolved = pm.resolveActivity(home, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
            resolved == context.packageName ||
                (resolved == "android" &&
                    pm.queryIntentActivities(home, PackageManager.MATCH_DEFAULT_ONLY).any { it.activityInfo.packageName == context.packageName })
        }.getOrDefault(false)

        const val SCREENSAVER_HOLD_MS = 4 * 60 * 60 * 1000L

        private const val PANEL_REFRESH_MS = 3_000L

        private const val BOOT_APP_NETWORK_WAIT_MS = 20_000L
        private const val REOPEN_VOLUME_NUDGE_MS = 4_000L

        const val ACTION_PREVIEW_SCREENSAVER = "com.gathin.tvhome.PREVIEW_SCREENSAVER"

        const val EXTRA_WOKE = "com.gathin.tvhome.WOKE"

        const val EXTRA_REOPEN_BOOT = "com.gathin.tvhome.REOPEN_BOOT"

        const val WAKE_QUIET_MS = 400L

        @Volatile
        var dreamWokeAt = 0L

        @Volatile var dreamWokeKey = -1
    }
}
