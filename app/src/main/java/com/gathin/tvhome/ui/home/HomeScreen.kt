package com.gathin.tvhome.ui.home

import android.content.Intent
import android.content.res.Configuration
import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent as NativeKeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntOffsetAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.rememberSplineBasedDecay
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.gathin.tvhome.CcPage
import com.gathin.tvhome.apps.SourceLooks
import com.gathin.tvhome.HomeViewModel
import com.gathin.tvhome.Overlay
import com.gathin.tvhome.R
import com.gathin.tvhome.settings.AerialSettings
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.focusRequester
import com.gathin.tvhome.apps.AppBackgrounds
import com.gathin.tvhome.apps.AppEntry
import com.gathin.tvhome.apps.TopShelfContent
import com.gathin.tvhome.apps.ShelfHostView
import com.gathin.tvhome.apps.rememberShelfHost
import com.gathin.tvhome.ui.control.ControlCenter
import com.gathin.tvhome.ui.wallpaper.SheetCovers
import com.gathin.tvhome.ui.wallpaper.WallpaperSheet
import com.gathin.tvhome.wallpaper.AerialFormats
import com.gathin.tvhome.wallpaper.ScreensaverMode
import com.gathin.tvhome.wallpaper.Wallpapers
import com.gathin.tvhome.ui.components.liquidGlass
import com.gathin.tvhome.ui.theme.LocalReducedMotion
import com.gathin.tvhome.ui.theme.Motion
import com.gathin.tvhome.ui.theme.Type
import com.gathin.tvhome.ui.components.diagLayer
import androidx.compose.runtime.snapshotFlow
import com.gathin.tvhome.system.DiagLog
import com.gathin.tvhome.system.LaunchFallback
import com.gathin.tvhome.system.SystemProbe
import com.gathin.tvhome.tmdb.TmdbTrending
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

private val PlateMarginBottom = 30.dp

private val ShelfRowGap = 30.dp

private val RowNameRoom = 50.dp

private val LargeClockTop = 80.dp

private val LargeClockHeight = 152.dp

private val LargeClockTopMin = 28.dp

private val PlateNameRoom = 28.dp

private object ControlCenterWarmed { var done = false }

private const val CC_WARM_AFTER_MS = 3_000L

private val GridMargin = 56.dp

private val RowAboveTop = 72.dp

internal fun gridGap(columns: Int): Dp = when (columns) {
    4 -> 34.dp
    6 -> 24.dp
    else -> 28.dp
}

internal fun restTileWidth(screenW: Dp, columns: Int): Dp = (screenW - GridMargin * 2 - gridGap(columns) * (columns - 1)) / columns

private class GridMetrics(screenW: Dp, screenH: Dp, val columns: Int, oneRow: Boolean, plateNames: Boolean, peek: Boolean, plate: Boolean, largeClock: Boolean, clockRight: Boolean) {
    val margin = GridMargin
    val gap = gridGap(columns)
    val tileW = restTileWidth(screenW, columns)
    val tileH = tileW * 9f / 16f
    val pitchX = tileW + gap
    val pitchY = tileH + 50.dp

    val firstGap = if (plateNames) PlateNameRoom else 0.dp

    val shelfSecond = if (plate && !plateNames) tileH + ShelfRowGap else pitchY

    private val twoRowFit = screenH - (if (plate) PlateMarginBottom + PlatePadV + firstGap else RowNameRoom) - shelfSecond - tileH

    val clockY = if (oneRow || !largeClock) LargeClockTop else
        (twoRowFit - (if (plate) PlatePadV else 0.dp) - LargeClockHeight)
            .coerceIn(if (clockRight) LargeClockTop else LargeClockTopMin, LargeClockTop)

    private val twoRowFloor = if (largeClock) clockY + LargeClockHeight + if (plate) PlatePadV else 0.dp else 0.dp

    val topRowY = when {
        !oneRow -> maxOf(twoRowFit, twoRowFloor)
        peek -> screenH - pitchY - 12.dp - firstGap
        plate -> screenH - tileH - (PlateMarginBottom + PlatePadV) - firstGap
        else -> screenH - tileH - 70.dp
    }

    val restY = maxOf(screenH * 0.37f, RowAboveTop + pitchY)
    val screenH = screenH

    val collapse = (topRowY - restY).coerceAtLeast(pitchY / 2)

    val shelfLift = if (oneRow) 0.dp else topRowY - twoRowFit

    val shelfRows = if (oneRow) 1 else 2

    fun slot(index: Int, onTop: Boolean): Pair<Dp, Dp> {
        val row = index / columns
        val y = when {
            row == 1 && onTop && shelfRows > 1 -> topRowY + shelfSecond
            else -> topRowY + pitchY * row + if (row > 0 && onTop) firstGap else 0.dp
        }
        return Pair(margin + pitchX * (index % columns), y)
    }

    fun scrollFor(row: Int, rows: Int): Dp {
        if (row <= 0) return 0.dp
        val wanted = topRowY + pitchY * row - restY
        val bottom = topRowY + pitchY * rows + 36.dp - screenH
        return minOf(wanted, maxOf(bottom, topRowY + pitchY - restY))
    }
}

private const val FILM_SAVER_WAIT_MS = 10_000L

internal const val CLOCK_BACK_MS = 1000

private const val HOME_SHOT_AFTER_MS = 450L

private const val HOME_SHOT_EVERY_MS = 30_000L

@Composable
fun HomeScreen(
    vm: HomeViewModel,
    onLaunch: (AppEntry, Rect?) -> Unit,
    onOpen: (Intent) -> Unit,
    onAppInfo: (String) -> Unit,
    onUninstall: (String) -> Unit,
    onStartScreensaver: () -> Unit,
) {
    val apps = vm.apps
    val overlay = vm.overlay
    val moving = vm.movingKey
    val reduced = LocalReducedMotion.current
    val density = LocalDensity.current
    val now by rememberNow()
    val scope = rememberCoroutineScope()

    val requesters = remember { HashMap<String, FocusRequester>() }
    fun requester(key: String) = requesters.getOrPut(key) { FocusRequester() }
    val pillRequester = remember { FocusRequester() }
    val heroRequester = remember { FocusRequester() }
    val appContext = LocalContext.current
    val firmwareInputPanel = remember {
        SystemProbe.tclInputPanel(appContext) != null || SystemProbe.coocaaInputPanel(appContext)
    }

    var focusedKey by remember { mutableStateOf<String?>(null) }
    var focusAfterOverlay by remember { mutableStateOf<String?>(null) }
    var indexAfterOverlay by remember { mutableStateOf(0) }
    var pillFocused by remember { mutableStateOf(false) }
    var lastFocusedIndex by remember { mutableStateOf(0) }
    var heroFocused by remember { mutableStateOf(false) }
    var heroDir by remember { mutableStateOf(0) }
    var heroTimerReset by remember { mutableStateOf(0) }
    val focusedIndex = apps.indexOfFirst { it.key == focusedKey }
    val columns = AerialSettings.homeColumns
    val focusedRow = if (pillFocused || focusedIndex < 0) 0 else focusedIndex / columns
    val isDockApp = !pillFocused && focusedIndex in 0 until columns
    var focusBg by remember { mutableStateOf<ImageBitmap?>(null) }
    var focusBgFrosted by remember { mutableStateOf<ImageBitmap?>(null) }
    val focusBgHit = remember(focusedKey, isDockApp, AppBackgrounds.version, Wallpapers.selectedId, focusBg, focusBgFrosted) {
        val key = focusedKey
        if (!isDockApp || key == null || Wallpapers.moves() || Wallpapers.isTrending()) null
        else AppBackgrounds.peekCached(key)?.let { (bmp, frost) -> bmp.asImageBitmap() to frost.asImageBitmap() }
    }
    val focusBgShown = focusBgHit?.first
    val focusBgFrostedShown = focusBgHit?.second
    LaunchedEffect(focusedKey, isDockApp, AppBackgrounds.version, Wallpapers.selectedId) {
        val key = focusedKey
        android.util.Log.i("AerialDiag", "diag focus dock=$isDockApp key=$key moves=${Wallpapers.moves()}")
        if (!isDockApp) return@LaunchedEffect
        if (key == null || Wallpapers.moves() || Wallpapers.isTrending() || !AppBackgrounds.has(key)) {
            focusBg = null
            focusBgFrosted = null
            return@LaunchedEffect
        }
        if (AppBackgrounds.peekCached(key) != null) return@LaunchedEffect
        focusBg = null
        focusBgFrosted = null
        val (bmp, frost) = withContext(Dispatchers.IO) {
            AppBackgrounds.loadCached(key, Wallpapers.screenW, Wallpapers.screenH)
        } ?: return@LaunchedEffect
        if (focusedKey != key || !isDockApp || Wallpapers.moves() || Wallpapers.isTrending()) return@LaunchedEffect
        focusBg = bmp.asImageBitmap()
        focusBgFrosted = frost.asImageBitmap()
        android.util.Log.i("AerialDiag", "diag focusBg loaded ${bmp.width}x${bmp.height}")
    }
    val shelfHost = rememberShelfHost(
        onExitUp = { pillRequester.requestFocus() },
        onExitDown = { focusedKey?.let { requester(it).requestFocus() } },
    )
    var shelfDataVersion by remember { mutableStateOf(0) }
    val shelfCardsData = remember(focusedKey, isDockApp, shelfDataVersion) {
        isDockApp && focusedKey?.let { TopShelfContent.cardsFor(it).isNotEmpty() } == true
    }
    val shelfPermLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) scope.launch {
            withContext(Dispatchers.IO) { TopShelfContent.reloadAll(appContext) }
            shelfHost.shelf.content(TopShelfContent.allShows())
            shelfDataVersion++
        }
    }
    LaunchedEffect(vm.resumed) {
        if (!vm.resumed) return@LaunchedEffect
        shelfHost.shelf.refresh()
        withContext(Dispatchers.IO) {
            TopShelfContent.reloadAll(appContext)
            TopShelfContent.freshArt(appContext)
        }
        shelfHost.shelf.content(TopShelfContent.allShows())
        shelfDataVersion++
    }
    LaunchedEffect(apps.map { it.key }) { TopShelfContent.clearAppCache() }
    LaunchedEffect(focusedKey, isDockApp, focusBg, Wallpapers.selectedId) {
        val key = focusedKey
        if (!isDockApp || key == null) {
            shelfHost.shelf.focus(null, focusedIndex)
            return@LaunchedEffect
        }
        if (Wallpapers.moves() || Wallpapers.isTrending()) {
            shelfHost.shelf.focus(null, focusedIndex)
            return@LaunchedEffect
        }
        val cards = TopShelfContent.cardsFor(key)
        if (cards.isEmpty() && !TopShelfContent.hasListingsPermission(appContext) &&
            !TopShelfContent.listingsAsked(appContext)
        ) {
            TopShelfContent.markListingsAsked(appContext)
            shelfPermLauncher.launch("android.permission.READ_TV_LISTINGS")
        }
        val app = if (cards.isEmpty() && (focusBg != null || AppBackgrounds.has(key))) null
            else shelfHost.shelfAppFor(appContext, key)
        shelfHost.shelf.focus(app, focusedIndex)
    }

    val gridEnabled = overlay == Overlay.None

    val filmCover = remember { Animatable(0f) }
    val liveFilm = remember { LiveFilm() }
    DisposableEffect(liveFilm) {
        LiveFilm.onHome = liveFilm
        onDispose { if (LiveFilm.onHome === liveFilm) LiveFilm.onHome = null }
    }
    val homeVideo = remember { HomeVideo() }
    DisposableEffect(homeVideo) {
        HomeVideo.onHome = homeVideo
        onDispose { if (HomeVideo.onHome === homeVideo) HomeVideo.onHome = null }
    }
    var filmFailed by remember(vm.screensaverVisible) { mutableStateOf(false) }
    val filmSaverChosen = remember(vm.screensaverVisible) {
        vm.screensaverVisible && saverCarriesWallpaper(appContext) &&
            (filmCover.value > 0.5f || focusedRow == 0 && overlay == Overlay.None && moving == null)
    }
    val filmSaver = filmSaverChosen && !filmFailed
    LaunchedEffect(filmSaverChosen) {
        if (!filmSaverChosen) return@LaunchedEffect
        val moved = withTimeoutOrNull(FILM_SAVER_WAIT_MS) { snapshotFlow { filmCover.value > 0.99f }.first { it } }
        if (moved == null) {
            DiagLog.i("wallpaper film did not move for the screensaver; its own films instead")
            filmFailed = true
        }
    }
    val filmStopped by animateFloatAsState(
        if (liveFilm.stopped) 1f else 0f,
        tween(if (liveFilm.stopped) 250 else 900, easing = Motion.EaseOut), label = "film-stopped",
    )
    val carrying = filmSaver
    val toSaver = remember { Animatable(0f) }
    LaunchedEffect(carrying) {
        when {
            reduced -> toSaver.snapTo(if (carrying) 1f else 0f)
            carrying -> toSaver.animateTo(1f, tween(2000, easing = Motion.EaseInOut))
            else -> toSaver.animateTo(0f, tween(650, easing = Motion.EaseOut))
        }
    }
    val clockToSaver = remember { Animatable(0f) }
    LaunchedEffect(carrying) {
        when {
            reduced -> clockToSaver.snapTo(if (carrying) 1f else 0f)
            carrying -> clockToSaver.animateTo(1f, tween(2000, easing = Motion.EaseInOut))
            else -> clockToSaver.animateTo(0f, tween(CLOCK_BACK_MS, easing = Motion.EaseInOut))
        }
    }

    val scroll = remember { Animatable(0f) }
    var dragged by remember { mutableStateOf(false) }
    val movingNow by rememberUpdatedState(moving)

    fun focusApp(index: Int) {
        val app = apps.getOrNull(index.coerceIn(0, (apps.size - 1).coerceAtLeast(0))) ?: return
        runCatching { requester(app.key).requestFocus() }
    }

    LaunchedEffect(vm.loaded, overlay) {
        if (!vm.loaded || overlay != Overlay.None) return@LaunchedEffect
        withFrameNanos { }
        val wanted = focusAfterOverlay
        focusAfterOverlay = null
        if (wanted != null) { focusedKey = wanted; pillFocused = false }
        val index = apps.indexOfFirst { it.key == focusedKey }
        if (pillFocused && focusedKey != null) runCatching { pillRequester.requestFocus() }
        else focusApp(if (index >= 0) index else if (wanted != null) indexAfterOverlay else lastFocusedIndex)
    }
    LaunchedEffect(overlay, vm.folderApps) {
        val open = (overlay as? Overlay.Folder)?.id ?: (overlay as? Overlay.AppMenu)?.inFolder ?: return@LaunchedEffect
        if (vm.folderApps[open].isNullOrEmpty()) vm.overlay = Overlay.None
    }
    LaunchedEffect(apps) {
        if (focusedKey != null && apps.none { it.key == focusedKey } && overlay == Overlay.None) {
            withFrameNanos { }
            focusApp(lastFocusedIndex)
        }
        moving?.let { withFrameNanos { }; runCatching { requester(it).requestFocus() } }
    }
    LaunchedEffect(moving) {
        val key = moving ?: return@LaunchedEffect
        withFrameNanos { }
        runCatching { requester(key).requestFocus() }
    }
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(windowFocused, vm.homeResetPending, vm.loaded) {
        if (windowFocused && vm.homeResetPending && vm.loaded) {
            withFrameNanos { }
            dragged = false
            focusApp(0)
            vm.homeResetPending = false
        }
    }

    LaunchedEffect(vm.homePressedTick) {
        if (vm.homePressedTick == 0 || !heroFocused) return@LaunchedEffect
        withFrameNanos { }
        heroTimerReset++
        heroFocused = false
        focusApp(0)
        vm.homeResetPending = false
    }

    BackHandler(enabled = overlay == Overlay.None && moving == null) {
        if (heroFocused) {
            heroTimerReset++
            heroFocused = false
            focusApp(if (focusedIndex >= 0) focusedIndex else lastFocusedIndex)
        } else {
            val scrolled = dragged && scroll.value > 1f
            dragged = false
            if (focusedRow > 0 || scrolled) focusApp(focusedIndex.coerceAtLeast(0) % columns)
            else if (AerialSettings.backStartsScreensaver) onStartScreensaver()
        }
    }

    BoxWithConstraints(
        Modifier
            .fillMaxSize()
            .onPreviewKeyEvent { e ->
                if (e.nativeKeyEvent.keyCode != NativeKeyEvent.KEYCODE_BACK) dragged = false
                if (moving != null) {
                    val code = e.nativeKeyEvent.keyCode
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent code != NativeKeyEvent.KEYCODE_HOME
                    when (code) {
                        NativeKeyEvent.KEYCODE_DPAD_LEFT -> vm.moveBy(-1)
                        NativeKeyEvent.KEYCODE_DPAD_RIGHT -> vm.moveBy(1)
                        NativeKeyEvent.KEYCODE_DPAD_UP -> vm.moveBy(-columns)
                        NativeKeyEvent.KEYCODE_DPAD_DOWN -> vm.moveBy(columns)
                        NativeKeyEvent.KEYCODE_BACK, NativeKeyEvent.KEYCODE_DPAD_CENTER,
                        NativeKeyEvent.KEYCODE_ENTER, NativeKeyEvent.KEYCODE_NUMPAD_ENTER -> vm.endMove()
                        else -> return@onPreviewKeyEvent false
                    }
                    return@onPreviewKeyEvent true
                }
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                when (e.nativeKeyEvent.keyCode) {
                    NativeKeyEvent.KEYCODE_MENU -> {
                        if (overlay == Overlay.None && !pillFocused && focusedIndex >= 0) return@onPreviewKeyEvent false
                        if (overlay is Overlay.Folder) return@onPreviewKeyEvent false
                        vm.overlay = if (overlay is Overlay.ControlCenter) Overlay.None else Overlay.ControlCenter()
                        true
                    }
                    NativeKeyEvent.KEYCODE_SETTINGS -> {
                        vm.whenUnlocked {
                            LaunchFallback.arm(SystemProbe.settingsFallbacks(appContext))
                            onOpen(SystemProbe.settingsIntent(appContext))
                        }
                        true
                    }
                    NativeKeyEvent.KEYCODE_TV_INPUT -> {
                        if (firmwareInputPanel) return@onPreviewKeyEvent false
                        vm.overlay = Overlay.ControlCenter(CcPage.Inputs)
                        true
                    }
                    else -> false
                }
            },
    ) {
        val rows = (apps.size + columns - 1) / columns
        val oneRow = AerialSettings.oneRowHome || rows <= 1
        val inGrid = focusedRow >= if (oneRow) 1 else 2
        val plateNames = AerialSettings.showAllNames && AerialSettings.homePlate
        val peek = AerialSettings.peekNextRow
        val hasPlate = AerialSettings.homePlate
        val largeClock = AerialSettings.largeClock
        val clockRightNow = AerialSettings.clockRight
        val metrics = remember(maxWidth, maxHeight, columns, oneRow, plateNames, peek, hasPlate, largeClock, clockRightNow) {
            GridMetrics(maxWidth, maxHeight, columns, oneRow, plateNames, peek, hasPlate, largeClock, clockRightNow)
        }
        val collapsePx = with(density) { metrics.collapse.toPx() }

        val target = with(density) { metrics.scrollFor(if (inGrid) focusedRow - (metrics.shelfRows - 1) else 0, rows).toPx() }
        val lift by animateFloatAsState(
            with(density) { if (focusedRow > 0 && !inGrid) metrics.shelfLift.toPx() else 0f },
            if (reduced || (focusedRow > 0 && !inGrid)) snap() else spring(dampingRatio = 1f, stiffness = 280f), label = "shelf-lift",
        )
        val maxScroll = with(density) { metrics.scrollFor(rows - 1, rows).toPx() }.coerceAtLeast(0f)
        LaunchedEffect(maxScroll) { scroll.updateBounds(0f, maxScroll) }
        val pastShelf by remember(collapsePx) { derivedStateOf { scroll.value > collapsePx / 2 } }
        val decay = rememberSplineBasedDecay<Float>()
        LaunchedEffect(target, dragged) {
            if (dragged) return@LaunchedEffect
            if (reduced) scroll.snapTo(target)
            else scroll.animateTo(target, spring(dampingRatio = 1f, stiffness = 280f))
        }

        val openFolder = (overlay as? Overlay.Folder)?.id ?: (overlay as? Overlay.AppMenu)?.inFolder
        val folderOpen = overlay is Overlay.HiddenApps || openFolder != null
        val ccOpen = overlay is Overlay.ControlCenter
        var sheetIn by remember { mutableStateOf(false) }
        var sheetWarm by remember { mutableStateOf(false) }
        val backdropFrost by animateFloatAsState(
            if (folderOpen) 1f else 0f,
            tween(320, easing = Motion.EaseOut), label = "backdrop-frost",
        )
        val backdropVeil by animateFloatAsState(
            if (ccOpen) 1f else 0f,
            tween(320, easing = Motion.EaseOut), label = "backdrop-veil",
        )
        val homeLayer = rememberGraphicsLayer()
        var homeShot by remember { mutableStateOf<ImageBitmap?>(null) }
        LaunchedEffect(ccOpen) {
            homeShot = null
            if (!ccOpen || Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return@LaunchedEffect
            delay(HOME_SHOT_AFTER_MS)
            while (true) {
                homeShot = runCatching { homeLayer.toImageBitmap() }.getOrNull()
                delay(HOME_SHOT_EVERY_MS)
            }
        }

        val ambientApp = apps.getOrNull(focusedIndex) ?: apps.firstOrNull()

        val dragState = rememberDraggableState { delta ->
            dragged = true
            scope.launch(start = CoroutineStart.UNDISPATCHED) { scroll.snapTo(scroll.value - delta) }
        }
        val previewActive = vm.resumed && !vm.screensaverVisible && Wallpapers.sourceInput == null &&
            isDockApp && focusedIndex >= 0 && apps.getOrNull(focusedIndex)?.source != null
        LaunchedEffect(previewActive) {
            filmCover.snapTo(0f)
        }
        val filmMoves = vm.filmFront && overlay == Overlay.None && !vm.screensaverVisible && !pastShelf &&
            !inGrid && moving == null
        val sourceInput = Wallpapers.sourceInput
            ?: if (previewActive) apps.getOrNull(focusedIndex)?.source?.id else null
        val filmStandsIn = overlay == Overlay.None || overlay is Overlay.ControlCenter || overlay is Overlay.Wallpaper ||
            (overlay is Overlay.AppMenu && overlay.inFolder == null)
        val followAppPause = focusedRow != 0 && apps.getOrNull(focusedIndex)?.source == null &&
            !inGrid && !pastShelf && overlay == Overlay.None && moving == null
        when {
            !previewActive && (focusBg == null || shelfCardsData) && Wallpapers.selectedId == Wallpapers.LIVE -> LiveWallpaper(
                moving = filmMoves && focusedRow == 0,
                followApp = followAppPause,
                keep = vm.filmFront && (!vm.screensaverVisible || filmSaver),
                cover = filmCover,
                hold = filmSaver && vm.filmFront,
                standIn = filmStandsIn,
                panel = overlay != Overlay.None,
                otherFormatSaver = vm.screensaverVisible && !filmSaver && Wallpapers.screensaver == ScreensaverMode.Aerial &&
                    !AerialFormats.screensaverCarriesWallpaper(appContext),
                lastInputAt = { vm.lastInputAt },
                live = liveFilm,
            )
            !previewActive && (focusBg == null || shelfCardsData) && Wallpapers.isVideo() -> key(Wallpapers.selectedId) {
                VideoWallpaper(
                    moving = filmMoves && focusedRow == 0,
                    followApp = followAppPause,
                    keep = vm.filmFront && (!vm.screensaverVisible || filmSaver),
                    hold = filmSaver && vm.filmFront,
                    home = homeVideo,
                    standIn = filmStandsIn,
                    lastInputAt = vm.lastInputAt,
                    cover = filmCover,
                    behindApp = !vm.filmFront,
                )
            }
            sourceInput != null -> SourceWallpaper(sourceInput, show = vm.resumed && !vm.screensaverVisible, cover = filmCover)
        }
        val shelfAway = overlay != Overlay.None || vm.screensaverVisible || moving != null || inGrid
        LaunchedEffect(shelfAway) { shelfHost.shelf.away(shelfAway) }
        val heroVisible = Wallpapers.selectedId == Wallpapers.TRENDING && TmdbTrending.hasContent &&
            overlay == Overlay.None && !Wallpapers.moves() && moving == null && !inGrid && !previewActive
        val shelfHasCards = TopShelfContent.shelfEnabled(appContext) && shelfCardsData &&
            !Wallpapers.moves() && !Wallpapers.isTrending() &&
            overlay == Overlay.None && !vm.screensaverVisible && moving == null && !inGrid
        val shelfUp = shelfHasCards
        val dockSlide by animateFloatAsState(
            targetValue = if (heroFocused) with(density) { (metrics.screenH - metrics.topRowY + 30.dp).toPx() } else 0f,
            animationSpec = if (reduced) snap() else tween(durationMillis = if (heroFocused) 500 else 450, easing = Motion.Ease),
            label = "hero-dock-slide",
        )
        val dotsBottomInset = metrics.screenH - metrics.topRowY
        val dotsShift by animateDpAsState(
            targetValue = if (heroFocused) dotsBottomInset - 10.dp + HeroRestLift else 0.dp,
            animationSpec = if (reduced) snap() else tween(durationMillis = if (heroFocused) 500 else 450, easing = Motion.Ease),
            label = "hero-dots-shift",
        )
        LaunchedEffect(heroVisible, heroTimerReset, TmdbTrending.currentIndex, vm.resumed, vm.screensaverVisible) {
            if (!heroVisible || !vm.resumed || vm.screensaverVisible) return@LaunchedEffect
            while (true) {
                delay(8_000)
                val count = TmdbTrending.items.size
                if (count > 0) {
                    heroDir = 1
                    TmdbTrending.currentIndex = (TmdbTrending.currentIndex + 1) % count
                }
            }
        }
        LaunchedEffect(TmdbTrending.currentIndex, heroVisible) {
            if (!heroVisible) return@LaunchedEffect
            Wallpapers.refreshTrendingStill(TmdbTrending.currentIndex)
        }
        LaunchedEffect(TmdbTrending.items.size) {
            val count = TmdbTrending.items.size
            if (count > 0 && TmdbTrending.currentIndex >= count) {
                heroDir = 0
                TmdbTrending.currentIndex = 0
            }
        }
        Box(
            Modifier
                .fillMaxSize()
                .onPreviewKeyEvent { e ->
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    if (heroFocused && !heroVisible && overlay == Overlay.None) heroFocused = false
                    if (heroFocused) {
                        when (e.nativeKeyEvent.keyCode) {
                            NativeKeyEvent.KEYCODE_DPAD_UP -> {
                                heroTimerReset++
                                heroFocused = false
                                runCatching { pillRequester.requestFocus() }
                                return@onPreviewKeyEvent true
                            }
                            NativeKeyEvent.KEYCODE_DPAD_DOWN -> {
                                heroTimerReset++
                                heroFocused = false
                                focusApp(if (focusedIndex >= 0) focusedIndex else lastFocusedIndex)
                                return@onPreviewKeyEvent true
                            }
                            NativeKeyEvent.KEYCODE_DPAD_LEFT -> {
                                val count = TmdbTrending.items.size
                                if (count > 0) {
                                    heroDir = -1
                                    TmdbTrending.currentIndex = (TmdbTrending.currentIndex - 1 + count) % count
                                }
                                heroTimerReset++
                                return@onPreviewKeyEvent true
                            }
                            NativeKeyEvent.KEYCODE_DPAD_RIGHT -> {
                                val count = TmdbTrending.items.size
                                if (count > 0) {
                                    heroDir = 1
                                    TmdbTrending.currentIndex = (TmdbTrending.currentIndex + 1) % count
                                }
                                heroTimerReset++
                                return@onPreviewKeyEvent true
                            }
                            NativeKeyEvent.KEYCODE_DPAD_CENTER, NativeKeyEvent.KEYCODE_ENTER,
                            NativeKeyEvent.KEYCODE_NUMPAD_ENTER -> {
                                return@onPreviewKeyEvent true
                            }
                            else -> return@onPreviewKeyEvent false
                        }
                    }
                    if (heroVisible && isDockApp && e.nativeKeyEvent.keyCode == NativeKeyEvent.KEYCODE_DPAD_UP) {
                        heroTimerReset++
                        heroFocused = true
                        return@onPreviewKeyEvent true
                    }
                    val focusedCard = shelfHost.shelf.view().findFocus()
                    when (e.nativeKeyEvent.keyCode) {
                        NativeKeyEvent.KEYCODE_DPAD_LEFT,
                        NativeKeyEvent.KEYCODE_DPAD_RIGHT -> {
                            if (focusedCard == null) return@onPreviewKeyEvent false
                            val dir = if (e.nativeKeyEvent.keyCode == NativeKeyEvent.KEYCODE_DPAD_LEFT)
                                android.view.View.FOCUS_LEFT else android.view.View.FOCUS_RIGHT
                            val parent = focusedCard.parent as? android.view.ViewGroup
                            val next = runCatching { parent?.focusSearch(focusedCard, dir) }.getOrNull()
                            if (next != null && next != focusedCard) {
                                runCatching { next.requestFocus() }
                            }
                            true
                        }
                        NativeKeyEvent.KEYCODE_DPAD_UP -> {
                            if (!shelfHasCards) return@onPreviewKeyEvent false
                            if (focusedCard != null) false
                            else {
                                val entry = shelfHost.shelf.cardsEntry()
                                if (entry != null) {
                                    runCatching { entry.requestFocus() }
                                    true
                                } else false
                            }
                        }
                        else -> false
                    }
                }
                .draggable(
                    dragState,
                    Orientation.Vertical,
                    enabled = gridEnabled && rows > 1,
                    onDragStopped = { velocity ->
                        if (!reduced) scroll.animateDecay(-velocity, decay)
                    },
                )
                .pointerInput(Unit) { detectTapGestures { if (movingNow != null) vm.endMove() } }
                .graphicsLayer {
                    alpha = if (backdropFrost > 0.999f || homeShot != null) 0f else 1f
                }
                .drawWithContent {
                    if (ccOpen && Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        homeLayer.record { this@drawWithContent.drawContent() }
                        drawLayer(homeLayer)
                    } else if (sheetIn) {
                        clipRect(bottom = size.height - SheetCovers.toPx()) { this@drawWithContent.drawContent() }
                    } else drawContent()
                },
        ) {
            val plate = if (apps.isEmpty() || !AerialSettings.homePlate) null else with(density) {
                val second = if (metrics.shelfRows > 1) metrics.shelfSecond else 0.dp
                Rect(
                    (metrics.margin - PlatePadH).toPx(),
                    (metrics.topRowY - PlatePadV).toPx(),
                    (metrics.margin + metrics.pitchX * (columns - 1) + metrics.tileW + PlatePadH).toPx(),
                    (metrics.topRowY + metrics.tileH + PlatePadV + metrics.firstGap + second).toPx(),
                )
            }
            LaunchedEffect(Wallpapers.selectedId) { filmCover.snapTo(0f) }
            val pictureClip = remember(Wallpapers.backdrop) { Wallpapers.livePictureSpot()?.first }
            val clip = if (Wallpapers.selectedId == Wallpapers.LIVE) liveFilm.film?.id ?: pictureClip else null
            LaunchedEffect(clip) { Wallpapers.showClipGlass(clip) }
            val clipGlass = Wallpapers.clipGlass?.takeIf { it.first == clip }?.second
            HomeBackground(
                backdrop = if (isDockApp && !shelfCardsData) focusBgShown ?: (Wallpapers.backdrop ?: ambientApp?.art?.backdrop)
                    else Wallpapers.backdrop ?: ambientApp?.art?.backdrop,
                middle = if (Wallpapers.moves()) { {} } else { { ShelfHostView(shelfHost) } },
                hero = {
                    if (heroVisible) {
                        HeroRow(
                            items = TmdbTrending.items,
                            index = TmdbTrending.currentIndex.coerceIn(0, (TmdbTrending.items.size - 1).coerceAtLeast(0)),
                            dir = heroDir,
                            focused = heroFocused,
                            focusRequester = heroRequester,
                            bottomInset = metrics.screenH - metrics.topRowY,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                },
                frosted = if (isDockApp && !shelfCardsData) focusBgFrostedShown ?: Wallpapers.frosted.takeIf { Wallpapers.backdrop != null }
                    else Wallpapers.frosted.takeIf { Wallpapers.backdrop != null },
                plate = plate,
                scroll = { scroll.value },
                plateSlide = { dockSlide },
                collapse = collapsePx,
                cover = { filmCover.value },
                shadeMoving = sourceInput != null,
                filmStopped = { filmStopped },
                toSaver = { toSaver.value },
                filmGlass = (Wallpapers.filmFrost ?: Wallpapers.frosted).takeIf { Wallpapers.moves() && Wallpapers.backdrop != null },
                lift = { lift },
                wallpaper = when {
                    isDockApp && focusBgShown != null && !shelfCardsData -> "appbg:$focusedKey"
                    else -> Wallpapers.selectedId
                },
                glassAwaitsStop = Wallpapers.selectedId == Wallpapers.LIVE && clipGlass == null,
                gridGlass = clipGlass,
            )

            if (heroVisible) {
                val dotIndex = TmdbTrending.currentIndex.coerceIn(0, (TmdbTrending.items.size - 1).coerceAtLeast(0))
                Row(
                    Modifier.align(Alignment.BottomCenter).padding(bottom = dotsBottomInset + 12.dp + HeroRestLift).offset(y = dotsShift),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    for (i in TmdbTrending.items.indices) {
                        key(i) {
                            if (i > 0) Spacer(Modifier.width(5.dp))
                            val dotAlpha by animateFloatAsState(
                                targetValue = if (i == dotIndex) 1f else 0.35f,
                                animationSpec = tween(250),
                                label = "hero-dot-alpha",
                            )
                            Box(
                                Modifier.size(5.dp)
                                    .clip(CircleShape)
                                    .background(Color.White.copy(alpha = dotAlpha)),
                            )
                        }
                    }
                }
            }

            Box(
                Modifier
                    .fillMaxSize()
                    .offset { IntOffset(0, (-scroll.value + dockSlide).roundToInt()) },
            ) {
                val clockRight = AerialSettings.clockRight
                if (AerialSettings.largeClock && !shelfUp && !heroVisible && !Wallpapers.isTrending() && !inGrid) {
                    ShelfClock(
                        now = now,
                        alignEnd = clockRight,
                        modifier = Modifier
                            .diagLayer("clock")
                            .align(if (clockRight) Alignment.TopEnd else Alignment.TopStart)
                            .padding(horizontal = metrics.margin)
                            .padding(top = metrics.clockY)
                            .graphicsLayer {
                                alpha = (1f - (scroll.value / collapsePx * 1.6f).coerceIn(0f, 1f))
                                compositingStrategy = CompositingStrategy.ModulateAlpha
                            },
                        toSaver = { clockToSaver.value },
                        saving = vm.screensaverVisible,
                        away = carrying,
                    )
                } else if (!clockRight && !shelfUp && !heroVisible && !Wallpapers.isTrending() && !inGrid) {
                    CornerClock(
                        now = now,
                        modifier = Modifier
                            .diagLayer("clock")
                            .padding(start = metrics.margin, top = 28.dp)
                            .graphicsLayer {
                                alpha = (1f - (scroll.value / collapsePx * 1.6f).coerceIn(0f, 1f))
                                compositingStrategy = CompositingStrategy.ModulateAlpha
                            },
                        toSaver = { toSaver.value },
                        saving = vm.screensaverVisible,
                    )
                }

                val hiddenFrom = when {
                    !oneRow -> columns * 2
                    !peek -> columns
                    else -> Int.MAX_VALUE
                }
                apps.forEachIndexed { index, app ->
                    key(app.key) {
                        val (x, y) = metrics.slot(index, onTop = !inGrid)
                        val slotPx = with(density) { IntOffset(x.roundToPx(), y.roundToPx()) }
                        val slot = animateIntOffsetAsState(
                            slotPx,
                            spring(dampingRatio = 0.86f, stiffness = Spring.StiffnessMediumLow),
                            label = "slot",
                        )
                        val saverFade = { ((toSaver.value - 0.05f * (index % columns)) / 0.6f).coerceIn(0f, 1f) }
                        AppTile(
                            app = app,
                            width = metrics.tileW,
                            height = metrics.tileH,
                            focusRequester = requester(app.key),
                            enabled = gridEnabled,
                            moving = moving == app.key,
                            opacity = { 1f - saverFade() },
                            onFocused = {
                                focusedKey = app.key
                                lastFocusedIndex = index
                                pillFocused = false
                            },
                            onClick = { bounds ->
                                val mover = moving
                                if (mover == null) {
                                    val folder = app.folder
                                    if (folder == null) onLaunch(app, bounds)
                                    else {
                                        focusAfterOverlay = app.key
                                        indexAfterOverlay = index
                                        vm.overlay = Overlay.Folder(folder.id)
                                    }
                                }
                                else if (mover == app.key) vm.endMove()
                                else {
                                    vm.moveBy(index - apps.indexOfFirst { it.key == mover })
                                    runCatching { requester(mover).requestFocus() }
                                }
                            },
                            onLongPress = { bounds ->
                                if (moving == null) {
                                    focusAfterOverlay = app.key
                                    indexAfterOverlay = index
                                    vm.overlay = Overlay.AppMenu(app.key, bounds ?: Rect.Zero)
                                }
                            },
                            modifier = Modifier
                                .diagLayer("tiles")
                                .offset { slot.value }
                                .offset { IntOffset(0, -lift.roundToInt()) }
                                .offset { IntOffset(0, (saverFade() * 18.dp.toPx()).roundToInt()) }
                                .then(
                                    if (index >= hiddenFrom) Modifier.graphicsLayer {
                                        alpha = (scroll.value / collapsePx * 2.5f).coerceIn(0f, 1f)
                                        compositingStrategy = CompositingStrategy.ModulateAlpha
                                    } else Modifier,
                                ),
                            rowEnd = index % columns == columns - 1 || index == apps.lastIndex,
                            showName = plate == null || index >= columns * metrics.shelfRows || plateNames,
                            nameOnPlate = plate != null && index < columns * metrics.shelfRows,
                        )
                    }
                }
            }
        }

        homeShot?.let { shot -> Canvas(Modifier.fillMaxSize()) { drawImage(shot) } }
        PanelBackdrop(Wallpapers.frosted.takeIf { Wallpapers.backdrop != null }, { backdropFrost }, { backdropVeil })

        Row(
            Modifier
                .align(Alignment.TopEnd)
                .padding(top = 28.dp, end = metrics.margin - 14.dp)
                .graphicsLayer { alpha = if (overlay is Overlay.ControlCenter) 0f else 1f },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (!AerialSettings.largeClock && AerialSettings.clockRight && !shelfUp && !heroVisible && !Wallpapers.isTrending() && !inGrid) {
                CornerClock(
                    now = now,
                    modifier = Modifier
                        .diagLayer("clock")
                        .padding(end = 14.dp)
                        .graphicsLayer {
                            alpha = (1f - (scroll.value / collapsePx * 1.6f).coerceIn(0f, 1f))
                            compositingStrategy = CompositingStrategy.ModulateAlpha
                        },
                    toSaver = { toSaver.value },
                    saving = vm.screensaverVisible,
                )
            }
            StatusPill(
                now = now,
                network = vm.network,
                showTime = shelfHasCards || heroVisible || (if (dragged) pastShelf else inGrid),
                enabled = gridEnabled && moving == null,
                focusRequester = pillRequester,
                modifier = Modifier
                    .diagLayer("pill")
                    .focusProperties {
                        val key = focusedKey
                        if (key != null && apps.indexOfFirst { it.key == key } in 0 until columns) down = requester(key)
                    }
                    .onPreviewKeyEvent { e ->
                        if (e.type == KeyEventType.KeyDown && heroVisible &&
                            e.nativeKeyEvent.keyCode == NativeKeyEvent.KEYCODE_DPAD_DOWN
                        ) {
                            heroTimerReset++
                            heroFocused = true
                            true
                        } else {
                            false
                        }
                    }
                    .graphicsLayer {
                        alpha = 1f - (toSaver.value * 2f).coerceIn(0f, 1f)
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    },
                onFocused = { gained ->
                    if (gained) {
                        if (heroFocused) heroTimerReset++
                        heroFocused = false
                        if (focusedKey == null) {
                            if (apps.isNotEmpty()) focusApp(0)
                        } else {
                            pillFocused = true
                        }
                    }
                },
                onClick = {
                    pillFocused = true
                    vm.overlay = Overlay.ControlCenter()
                },
            )
        }

        AnimatedVisibility(
            visible = moving != null,
            enter = fadeIn(tween(200, easing = Motion.EaseOut)) + slideInVertically(tween(260, easing = Motion.EaseOut)) { it / 2 },
            exit = fadeOut(tween(150, easing = Motion.EaseOut)) + slideOutVertically(tween(150, easing = Motion.EaseOut)) { it / 2 },
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 28.dp),
        ) {
            val touch = LocalConfiguration.current.touchscreen != Configuration.TOUCHSCREEN_NOTOUCH
            BasicText(
                stringResource(if (touch) R.string.move_hint_touch else R.string.move_hint),
                style = Type.Body,
                modifier = Modifier
                    .liquidGlass(20.dp)
                    .height(40.dp)
                    .padding(horizontal = 20.dp, vertical = 10.dp),
            )
        }

        var ccWarm by remember { mutableStateOf(false) }
        LaunchedEffect(vm.loaded) {
            if (!vm.loaded || ControlCenterWarmed.done) return@LaunchedEffect
            while (true) {
                delay(CC_WARM_AFTER_MS)
                val idle = SystemClock.uptimeMillis() - vm.lastInputAt > CC_WARM_AFTER_MS
                if (idle && vm.overlay == Overlay.None && !vm.screensaverVisible && vm.resumed) break
            }
            ControlCenterWarmed.done = true
            ccWarm = true
            repeat(3) { withFrameNanos { } }
            ccWarm = false
            withFrameNanos { }
            if (vm.overlay != Overlay.None) return@LaunchedEffect
            sheetWarm = true
            repeat(3) { withFrameNanos { } }
            sheetWarm = false
        }
        ControlCenter(
            visible = overlay is Overlay.ControlCenter,
            page = (overlay as? Overlay.ControlCenter)?.page ?: CcPage.Main,
            vm = vm,
            now = now,
            onOpen = { vm.overlay = Overlay.None; onOpen(it) },
            onClose = { vm.overlay = Overlay.None },
            warm = ccWarm,
            onShelf = !pastShelf,
        )

        FolderView(
            name = openFolder?.let { id -> apps.firstOrNull { it.folder?.id == id }?.label },
            apps = openFolder?.let { vm.folderApps[it] }.orEmpty(),
            visible = openFolder != null,
            enabled = overlay is Overlay.Folder,
            tileW = metrics.tileW,
            tileH = metrics.tileH,
            gap = metrics.gap,
            columns = columns,
            onLaunch = onLaunch,
            onLongPress = { app, bounds -> vm.overlay = Overlay.AppMenu(app.key, bounds ?: Rect.Zero, inFolder = openFolder) },
            onDismiss = { vm.overlay = Overlay.None },
        )

        val menu = overlay as? Overlay.AppMenu
        fun closeMenu() { vm.overlay = menu?.inFolder?.let { Overlay.Folder(it) } ?: Overlay.None }
        AppMenu(
            app = menu?.let { vm.entry(it.key) },
            anchor = menu?.anchor,
            inFolder = menu?.inFolder != null,
            folders = vm.folders(),
            onMove = { vm.startMove(it) },
            onHide = { key ->
                closeMenu()
                vm.hide(key)
            },
            onInfo = { closeMenu(); onAppInfo(it) },
            onUninstall = { closeMenu(); onUninstall(it) },
            onUnpin = { key ->
                closeMenu()
                vm.entry(key)?.source?.let { vm.setSourcePinned(it, false) }
            },
            onResetIcon = { key ->
                closeMenu()
                vm.resetIcon(key)
            },
            onBootTarget = { key ->
                closeMenu()
                val entry = vm.entry(key)
                val source = entry?.source
                when {
                    source != null -> vm.chooseBootTarget(sourceId = source.id.takeIf { it != AerialSettings.bootSource })
                    entry != null -> vm.chooseBootTarget(pkg = key.takeIf { it != AerialSettings.bootApp })
                }
            },
            onSourceDevice = { id, device ->
                closeMenu()
                SourceLooks.chooseDevice(id, device)
            },
            onToFolder = { key, id ->
                val folderKey = vm.putInFolder(key, id)
                if (menu?.inFolder == null) focusAfterOverlay = folderKey
                closeMenu()
            },
            onOutOfFolder = { key ->
                vm.takeOutOfFolder(key)
                closeMenu()
            },
            onRenameFolder = { id, name ->
                vm.renameFolder(id, name)
                closeMenu()
            },
            onUngroup = { id ->
                vm.ungroup(id)
                closeMenu()
            },
            onDismiss = { closeMenu() },
        )

        HiddenAppsSheet(
            visible = overlay is Overlay.HiddenApps,
            apps = vm.hiddenApps,
            onUnhide = { key ->
                vm.unhide(key)
                if (vm.hiddenApps.isEmpty()) vm.overlay = Overlay.Wallpaper
            },
            onDismiss = { vm.overlay = Overlay.Wallpaper },
        )

        WallpaperSheet(
            visible = overlay is Overlay.Wallpaper,
            onSettled = { sheetIn = it },
            warm = sheetWarm,
            onDismiss = { vm.overlay = Overlay.ControlCenter() },
            hiddenCount = vm.hiddenApps.size,
            onHiddenApps = { vm.whenUnlocked { vm.overlay = Overlay.HiddenApps } },
            onStartScreensaver = {
                vm.overlay = Overlay.None
                onStartScreensaver()
            },
            onPin = { on ->
                if (on) vm.overlay = Overlay.Pin(PinMode.Create) { vm.overlay = Overlay.Wallpaper }
                else vm.whenUnlocked { AerialSettings.choosePin(null); vm.overlay = Overlay.Wallpaper }
            },
        )

        val pin = overlay as? Overlay.Pin
        PinPad(
            visible = pin != null,
            mode = pin?.mode,
            onDone = { pin?.onDone?.invoke() },
            onCancel = { vm.overlay = Overlay.None },
        )

        NoticeBanner(vm.notice, Modifier.align(Alignment.TopCenter).padding(top = 24.dp), vm.noticeGlyph)

        Screensaver(visible = vm.screensaverVisible, apps = apps, now = now, skips = vm.screensaverSkips, carry = when {
                filmSaver -> if (Wallpapers.isVideo()) SaverCarry.Video(homeVideo) else SaverCarry.Film(liveFilm)
                else -> null
            },
        )
    }
}
