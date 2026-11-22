package com.gathin.tvhome.ui.home

import android.os.Build
import android.os.SystemClock
import android.view.KeyEvent
import android.view.SurfaceHolder
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.gathin.tvhome.MainActivity
import com.gathin.tvhome.R
import com.gathin.tvhome.system.SystemScreensaver
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.gathin.tvhome.ui.components.FilmSlot
import com.gathin.tvhome.apps.AppEntry
import com.gathin.tvhome.system.DiagLog
import com.gathin.tvhome.ui.components.Dissolve
import com.gathin.tvhome.ui.components.diagLayer
import com.gathin.tvhome.ui.theme.Motion
import com.gathin.tvhome.ui.theme.Palette
import com.gathin.tvhome.ui.theme.Type
import com.gathin.tvhome.wallpaper.AerialStream
import com.gathin.tvhome.wallpaper.Aerial
import com.gathin.tvhome.wallpaper.AerialSet
import androidx.compose.runtime.mutableStateListOf
import com.gathin.tvhome.settings.AerialSettings
import com.gathin.tvhome.ratings.Ratings
import com.gathin.tvhome.tmdb.TmdbTrending
import com.gathin.tvhome.wallpaper.AerialFormats
import com.gathin.tvhome.wallpaper.AerialQuality
import com.gathin.tvhome.wallpaper.Aerials
import com.gathin.tvhome.wallpaper.ScreensaverMode
import com.gathin.tvhome.wallpaper.Wallpapers
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.util.Locale
import kotlin.math.roundToInt
import kotlin.random.Random

@Composable
fun Screensaver(visible: Boolean, apps: List<AppEntry>, now: LocalDateTime, skips: ScreensaverSkips, carry: SaverCarry? = null) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(1200, easing = Motion.EaseOut)),
        exit = fadeOut(tween(220, easing = Motion.EaseOut)),
        modifier = Modifier.fillMaxSize(),
    ) {
        val held = remember { mutableStateOf(carry) }
        if (visible) held.value = carry
        when (val carried = held.value) {
            is SaverCarry.Film -> { FilmCarry(carried.film, now, skips.steps); return@AnimatedVisibility }
            is SaverCarry.Video -> {
                LaunchedEffect(skips.steps) { skips.steps.collect { carried.film.skip(it) } }
                return@AnimatedVisibility
            }
            null -> Unit
        }
        val context = LocalContext.current
        var aerialFailed by remember { mutableStateOf(false) }
        var videosFailed by remember { mutableStateOf(false) }
        var videosEmpty by remember { mutableStateOf(false) }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            when (Wallpapers.screensaver) {
                ScreensaverMode.Aerial -> if (aerialFailed) ClockSaver(apps, now) else AerialSaver(
                    now, skips.steps, onUnavailable = { aerialFailed = true }, startAt = remember {
                        LiveFilm.onHome?.letDecoderGo()
                        AerialStart.start(context)
                    },
                )
                ScreensaverMode.System -> ClockSaver(apps, now, plain = true, nightTint = true)
                ScreensaverMode.Videos -> when {
                    videosEmpty -> Box(Modifier.fillMaxSize().background(Color.Black))
                    videosFailed -> ClockSaver(apps, now)
                    else -> {
                        VideoSaver(skips.steps, leaving = !visible, onUnavailable = { videosFailed = true }, onEmpty = { videosEmpty = true })
                        Labels(caption = null, now = now, shade = false)
                    }
                }
                ScreensaverMode.Trending -> TrendingSaver(now, skips.steps)
            }
        }
    }
}

class ScreensaverSkips {
    private val flow = MutableSharedFlow<Int>(extraBufferCapacity = 8)
    val steps: SharedFlow<Int> get() = flow

    fun onKey(event: KeyEvent): Boolean {
        val step = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_LEFT -> -1
            KeyEvent.KEYCODE_DPAD_RIGHT -> 1
            else -> return false
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) flow.tryEmit(step)
        return true
    }
}

sealed interface SaverCarry {
    class Film(val film: LiveFilm) : SaverCarry

    class Video(val film: HomeVideo) : SaverCarry

}


fun saverCarriesWallpaper(context: android.content.Context): Boolean = when {
    Wallpapers.selectedId == Wallpapers.LIVE -> Wallpapers.screensaver == ScreensaverMode.Aerial && AerialFormats.screensaverCarriesWallpaper(context)
    Wallpapers.isVideo() -> Wallpapers.screensaver == ScreensaverMode.Videos
    else -> false
}

object AerialStart {
    fun start(context: android.content.Context): Pair<String, Long>? {
        AerialSet.takeDaily(context)?.let { film ->
            DiagLog.i("today's new film ${film.id}")
            Wallpapers.leaveLiveFilm(film.id, 0L)
            return film.id to 0L
        }
        return LiveFilm.onHome?.spot() ?: Wallpapers.liveSpot()
    }

    suspend fun prepareAhead(context: android.content.Context, leadMs: Long) {
        if (Wallpapers.screensaver != ScreensaverMode.Aerial || AerialFormats.screensaverCarriesWallpaper(context)) return
        val quality = AerialFormats.forScreensaver(context, overWallpaper = true).let { q ->
            aerialQualityCap?.takeIf { it.ordinal < q.ordinal } ?: q
        }
        val live = LiveFilm.onHome
        val (id, at) = if (AerialSet.due(context)) AerialSet.nextNew(context).id to 0L
            else live?.spot()?.let { (id, at) -> id to at + if (live.moving) leadMs else 0L } ?: Wallpapers.liveSpot() ?: return
        val film = Aerials.all(context).firstOrNull { it.id == id } ?: return
        AerialStream.prepareStart(context, film, quality, aerialsOverHttp, at)
        if (Wallpapers.selectedId == Wallpapers.LIVE) SaverFrames.prepareAhead(context, id, at)
    }

    @Volatile
    var running = false
}

@Composable
private fun FilmCarry(film: LiveFilm, now: LocalDateTime, steps: SharedFlow<Int>) {
    var caption by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(film.film) {
        while (true) {
            caption = film.film?.captionAt((film.positionMs / 1000).toInt())
            delay(1000)
        }
    }
    val context = LocalContext.current
    LaunchedEffect(steps) { steps.collect { film.skip(context, it) } }
    LaunchedEffect(Unit) { if (AerialSet.due(context)) film.skip(context, 1) }
    Labels(caption, now, showTime = false, shade = false)
}

private const val SHADE_TOP = 0.18f
private const val SHADE_BOTTOM = 0.7f

private val labelShadeImage by lazy { labelShade() }

private fun labelShade(): ImageBitmap {
    val h = 128f
    val out = android.graphics.Bitmap.createBitmap(4, h.toInt(), android.graphics.Bitmap.Config.ARGB_8888)
    val c = android.graphics.Canvas(out)
    fun black(a: Float) = android.graphics.Color.argb((a * 255).roundToInt(), 0, 0, 0)
    c.drawRect(0f, 0f, 4f, h, android.graphics.Paint().apply {
        shader = android.graphics.LinearGradient(0f, h * SHADE_BOTTOM, 0f, h, 0, black(0.45f), android.graphics.Shader.TileMode.CLAMP)
    })
    c.drawRect(0f, 0f, 4f, h, android.graphics.Paint().apply {
        shader = android.graphics.LinearGradient(0f, 0f, 0f, h * SHADE_TOP, black(0.3f), 0, android.graphics.Shader.TileMode.CLAMP)
    })
    return out.asImageBitmap()
}

@Composable
private fun Labels(
    caption: String?,
    now: LocalDateTime,
    showTime: Boolean = true,
    shade: Boolean = true,
) {
    val time by animateFloatAsState(if (showTime) 1f else 0f, tween(2000), label = "time")
    Box(Modifier.fillMaxSize()) {
        if (shade) Canvas(Modifier.fillMaxSize().diagLayer("shade")) {
            val shade = labelShadeImage
            val w = size.width.roundToInt()
            val h = size.height.roundToInt()
            val top = (h * SHADE_TOP).roundToInt()
            val bottom = (h * SHADE_BOTTOM).roundToInt()
            val sh = shade.height
            drawImage(shade, srcSize = IntSize(shade.width, (sh * SHADE_TOP).roundToInt()), dstSize = IntSize(w, top), filterQuality = FilterQuality.Low)
            drawImage(
                shade, srcOffset = IntOffset(0, (sh * SHADE_BOTTOM).roundToInt()), srcSize = IntSize(shade.width, sh - (sh * SHADE_BOTTOM).roundToInt()),
                dstOffset = IntOffset(0, bottom), dstSize = IntSize(w, h - bottom), filterQuality = FilterQuality.Low,
            )
        }
        val drift = saverDrift(now)
        val large = AerialSettings.largeClock
        val laidOut = if (large) Type.Clock.fontSize else Type.Title.fontSize
        if (time > 0f && AerialSettings.screensaverClock) BasicText(
            timeText(now),
            style = if (large) Type.Clock.copy(color = SaverClockStyle.color) else SaverClockStyle.copy(fontSize = laidOut),
            modifier = Modifier
                .align(if (AerialSettings.clockRight) Alignment.TopEnd else Alignment.TopStart)
                .padding(top = SaverClockTop, start = SaverClockSide, end = SaverClockSide)
                .graphicsLayer {
                    val s = SaverClockStyle.fontSize.toPx() / laidOut.toPx()
                    transformOrigin = TransformOrigin(if (AerialSettings.clockRight) 1f else 0f, 0f)
                    scaleX = s
                    scaleY = s
                    alpha = time
                    translationX = drift.x.toPx()
                    translationY = drift.y.toPx()
                },
        )
        AnimatedContent(
            targetState = caption.orEmpty(),
            transitionSpec = { fadeIn(tween(600, delayMillis = 400, easing = Motion.EaseOut)) togetherWith fadeOut(tween(400, easing = Motion.EaseOut)) },
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 56.dp, bottom = 44.dp)
                .graphicsLayer { translationX = drift.x.toPx(); translationY = -drift.y.toPx() },
            label = "caption",
        ) { text ->
            BasicText(
                text, maxLines = 2, overflow = TextOverflow.Ellipsis,
                style = Type.Title.copy(color = Color.White.copy(alpha = 0.92f)),
                modifier = Modifier.widthIn(max = 620.dp),
            )
        }
    }
}

@Composable
internal fun saverDrift(now: LocalDateTime): DpOffset {
    val slot = now.toLocalDate().toEpochDay() * 720 + (now.hour * 60 + now.minute) / 2
    val rnd = remember(slot) { Random(slot) }
    val target = remember(slot) { DpOffset(SaverDriftX * (rnd.nextFloat() * 2 - 1), SaverDriftY * (rnd.nextFloat() * 2 - 1)) }
    val x by animateDpAsState(target.x, tween(6000, easing = Motion.EaseInOut), label = "drift-x")
    val y by animateDpAsState(target.y, tween(6000, easing = Motion.EaseInOut), label = "drift-y")
    return DpOffset(x, y)
}

private val SaverDriftX = 24.dp
private val SaverDriftY = 14.dp

internal val SaverClockStyle = Type.SmallClock.copy(fontSize = Type.LargeTitle.fontSize * 1.3f, color = Color.White.copy(alpha = 0.92f))
internal val SaverClockTop = 40.dp
internal val SaverClockSide = 56.dp

private var aerialsOverHttp by mutableStateOf(Build.VERSION.SDK_INT < Build.VERSION_CODES.N)

private var aerialQualityCap: AerialQuality? = null

@Composable
private fun AerialSaver(
    now: LocalDateTime,
    steps: SharedFlow<Int>,
    onUnavailable: () -> Unit,
    startAt: Pair<String, Long>? = null,
) {
    val context = LocalContext.current
    var quality by remember {
        val wanted = AerialFormats.forScreensaver(context, overWallpaper = startAt != null)
        mutableStateOf(aerialQualityCap?.takeIf { it.ordinal < wanted.ordinal } ?: wanted)
    }
    val overLive = remember { Wallpapers.selectedId == Wallpapers.LIVE }
    fun leave(film: String, atMs: Long, persist: Boolean) = Wallpapers.leaveLiveFilm(film, atMs, persist)
    @Suppress("NAME_SHADOWING")
    val startAt = remember {
        startAt?.let { (id, at) ->
            AerialStream.prepared?.takeIf { it.film == id && it.quality == quality && kotlin.math.abs(it.atMs - at) < PREPARED_SLACK_MS }
                ?.let { DiagLog.i("screensaver starts at the moment fetched ahead (${it.atMs - at} ms off)"); id to it.atMs }
                ?: (id to at)
        }
    }
    val films = remember { mutableStateListOf<Aerial>().apply { addAll(AerialSet.playlist(context, startAt?.first)) } }
    val firstAtMs = remember { startAt?.takeIf { (id, _) -> films.first().id == id }?.second ?: 0L }
    val player = remember { AerialStream.player(context, quality, decodeOnScreen = true) }
    var index by remember { mutableIntStateOf(0) }
    var failures by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    var caption by remember { mutableStateOf<String?>(null) }
    var attempt by remember { mutableIntStateOf(0) }
    var shownAny by remember { mutableStateOf(false) }
    var retried by remember { mutableStateOf(false) }
    val handler = remember { android.os.Handler(android.os.Looper.getMainLooper()) }
    val film = films[index.mod(films.size)]

    val liftMs = if (quality == AerialQuality.DolbyVision) DV_LIFT_MS else 1500
    val veil by animateFloatAsState(if (playing) 0f else 1f, tween(if (playing) liftMs else 400), label = "veil")

    fun load(at: Int, fromMs: Long = 0L) {
        player.setMediaItems(films.map { AerialStream.mediaItem(it, quality, aerialsOverHttp) }, at, fromMs)
        player.prepare()
        player.playWhenReady = true
    }

    fun next() {
        playing = false
        player.seekToNextMediaItem()
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
    }

    fun skip(step: Int) {
        if (step < 0) {
            playing = false
            player.seekToPreviousMediaItem()
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            return
        }
        val new = AerialSet.addNew(context)
        val at = player.currentMediaItemIndex + 1
        films.add(at, new)
        player.addMediaItem(at, AerialStream.mediaItem(new, quality, aerialsOverHttp))
        playing = false
        player.seekTo(at, 0L)
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
    }

    DisposableEffect(player) {
        val opened = SystemClock.elapsedRealtime()
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                if (!playing) {
                    DiagLog.i("first frame of ${player.currentMediaItem?.mediaId} after ${SystemClock.elapsedRealtime() - opened} ms")
                    SystemScreensaver.dreamFirstFrame()?.let { MainActivity.dreamShowedAfter(context, it) }
                }
                playing = true
                shownAny = true
                failures = 0
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) DiagLog.i("${mediaItem?.mediaId} starts over (kept film)")
                index = player.currentMediaItemIndex
                attempt++
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) playing = false
            }

            override fun onPlayerError(error: PlaybackException) {
                DiagLog.i("aerial film failed (${error.errorCodeName}): ${player.currentMediaItem?.localConfiguration?.uri}")
                playing = false
                if (!shownAny && !retried && error.errorCode in DECODER_ERRORS) {
                    retried = true
                    DiagLog.i("trying ${quality.key} again in $RETRY_MS ms")
                    handler.postDelayed({ load(player.currentMediaItemIndex, if (player.currentMediaItemIndex == 0) firstAtMs else 0L) }, RETRY_MS)
                    attempt++
                    return
                }
                if (quality != AerialQuality.Hd && error.errorCode in DECODER_ERRORS) {
                    aerialQualityCap = AerialFormats.lower(quality)
                    quality = AerialFormats.lower(quality)
                    load(player.currentMediaItemIndex)
                    attempt++
                    return
                }
                if (!aerialsOverHttp) {
                    aerialsOverHttp = true
                    load(player.currentMediaItemIndex)
                } else {
                    failures++
                    next()
                }
                attempt++
            }
        }
        player.addListener(listener)
        load(0, firstAtMs)
        AerialStart.running = true
        onDispose {
            AerialStart.running = false
            if (shownAny) player.currentMediaItem?.mediaId?.let { leave(it, player.currentPosition, persist = true) }
            handler.removeCallbacksAndMessages(null)
            player.removeListener(listener)
            player.release()
        }
    }

    LaunchedEffect(index) {
        caption = null
        var ticks = 0
        while (true) {
            delay(1000)
            if (!playing) continue
            caption = film.captionAt((player.currentPosition / 1000).toInt())
            leave(film.id, player.currentPosition, persist = ticks++ % 30 == 0)
        }
    }
    LaunchedEffect(steps) {
        steps.collect { step ->
            if (step < 0 && films.size < 2) return@collect
            skip(step)
        }
    }
    LaunchedEffect(attempt, playing) {
        if (playing) return@LaunchedEffect
        delay(STALL_MS)
        DiagLog.i("no picture from ${player.currentMediaItem?.mediaId} after $STALL_MS ms; next film")
        failures++
        if (!shownAny && failures >= 2) onUnavailable() else next()
    }
    LaunchedEffect(failures) {
        if (failures >= 3) onUnavailable()
    }
    LaunchedEffect(playing, index) {
        val home = AerialFormats.forWallpaper(context)
        if (playing && overLive && home != quality) AerialStream.prepareSeek(context, film, home, aerialsOverHttp)
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx ->
            FilmSlot(ctx).also {
                it.surface.holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) = DiagLog.i("film surface created")
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                    override fun surfaceDestroyed(holder: SurfaceHolder) = DiagLog.i("film surface destroyed")
                })
                player.setVideoSurfaceView(it.surface)
            }
        },
        onRelease = { player.clearVideoSurfaceView(it.surface) },
    )
    Box(Modifier.fillMaxSize().graphicsLayer { alpha = veil }.background(Color.Black))
    Labels(if (playing) caption ?: film.captionAt(((if (index == 0) firstAtMs else 0L) / 1000).toInt()) else null, now, shade = false)
}

private const val DV_LIFT_MS = 400

private const val PREPARED_SLACK_MS = 6_000L

private const val RETRY_MS = 1_500L

private const val STALL_MS = 8_000L

private val DECODER_ERRORS = PlaybackException.ERROR_CODE_DECODER_INIT_FAILED..PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED

@Composable
private fun ClockSaver(apps: List<AppEntry>, now: LocalDateTime, plain: Boolean = false, nightTint: Boolean = false) {
    val minute = now.withSecond(0).withNano(0)
    val scene = remember(minute, apps.size, Wallpapers.backdrop) {
        val rnd = Random(minute.hashCode())
        Scene(
            light = if (plain) null else Wallpapers.backdrop ?: apps.takeIf { it.isNotEmpty() }?.get(rnd.nextInt(apps.size))?.art?.backdrop,
            x = rnd.nextFloat(),
            y = rnd.nextFloat(),
        )
    }
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = maxWidth
        val h = maxHeight
        Dissolve(targetState = scene.light, durationMs = 2400, easing = Motion.EaseInOut, modifier = Modifier.fillMaxSize(), label = "clock-light") { light ->
            Canvas(Modifier.fillMaxSize()) {
                drawRect(Color.Black)
                if (light != null) {
                    drawImage(light, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = FilterQuality.Low, alpha = 0.75f)
                }
            }
        }
        Crossfade(targetState = scene, animationSpec = tween(2400, easing = Motion.EaseInOut), label = "clock-scene") { s ->
            Box(Modifier.fillMaxSize()) {
                Column(
                    Modifier.offset(
                        x = 64.dp + (w - 64.dp * 2 - 420.dp) * s.x,
                        y = 56.dp + (h - 56.dp * 2 - 170.dp) * s.y,
                    ),
                ) { ShelfClock(now = now, saving = true, nightTint = nightTint) }
            }
        }
    }
}

private data class Scene(val light: ImageBitmap?, val x: Float, val y: Float)

@Composable
private fun SaverRatingIcon(res: Int) {
    val painter = painterResource(res)
    val ratio = painter.intrinsicSize.let { if (it.height > 0f) it.width / it.height else 1f }
    Image(
        painter = painter,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier.height(18.dp).aspectRatio(ratio),
    )
}

@Composable
private fun TrendingSaver(now: LocalDateTime, steps: SharedFlow<Int>) {
    val context = LocalContext.current
    val items = TmdbTrending.items
    val density = LocalDensity.current
    val infoSlidePx = with(density) { 28.dp.toPx() }
    var saverDir by remember { mutableStateOf(1) }
    LaunchedEffect(Unit) {
        TmdbTrending.init(context)
        TmdbTrending.refreshIfStale()
    }
    LaunchedEffect(items.size) {
        if (items.isNotEmpty() && TmdbTrending.currentIndex >= items.size) TmdbTrending.currentIndex = 0
    }
    LaunchedEffect(TmdbTrending.currentIndex, items.size) {
        if (items.size <= 1) return@LaunchedEffect
        delay(8_000)
        saverDir = 1
        TmdbTrending.currentIndex = (TmdbTrending.currentIndex + 1) % items.size
    }
    LaunchedEffect(steps) {
        steps.collect { step ->
            val count = TmdbTrending.items.size
            if (count > 0) {
                saverDir = if (step >= 0) 1 else -1
                TmdbTrending.currentIndex = (TmdbTrending.currentIndex + step).mod(count)
            }
        }
    }
    if (items.isEmpty()) {
        Box(Modifier.fillMaxSize().background(Color.Black))
        return
    }
    val index = TmdbTrending.currentIndex.coerceIn(items.indices)
    val item = items[index]
    val slide = rememberTrendingSlideState(item, index, infoSlidePx)

    LaunchedEffect(item) {
        slide.sync(item, index)
    }
    LaunchedEffect(index, item.backdropUrl) {
        slide.advance(items, index, saverDir)
    }
    val displayedItem = slide.displayedItem
    var titleLogo by remember(displayedItem.logoUrl) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(displayedItem.logoUrl) {
        if (displayedItem.logoUrl.isBlank()) return@LaunchedEffect
        val bmp = withContext(Dispatchers.IO) { TitleLogos.load(displayedItem.logoUrl) }
        if (bmp != null) titleLogo = bmp.asImageBitmap()
    }
    var providerLogos by remember(displayedItem) { mutableStateOf<List<ImageBitmap>>(emptyList()) }
    LaunchedEffect(displayedItem) {
        val urls = displayedItem.providerUrls
        if (urls.isEmpty()) return@LaunchedEffect
        val bmps = withContext(Dispatchers.IO) {
            urls.mapNotNull { ProviderLogos.load(it)?.asImageBitmap() }
        }
        providerLogos = bmps
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        TrendingSlideImages(slide)
        val drift = saverDrift(now)
        Column(
            Modifier.align(Alignment.BottomStart)
                .padding(start = 45.dp, end = 160.dp, bottom = 44.dp)
                .graphicsLayer {
                    translationX = drift.x.toPx() + slide.infoX.value
                    translationY = -drift.y.toPx()
                    alpha = slide.infoAlpha.value
                },
        ) {
            val logo = titleLogo
            if (logo != null) {
                Image(
                    bitmap = logo,
                    contentDescription = null,
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.CenterStart,
                    modifier = Modifier.sizeIn(maxWidth = 390.dp, maxHeight = 135.dp),
                )
            } else {
                BasicText(
                    displayedItem.title,
                    style = Type.LargeTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val providers = providerLogos
                if (providers.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        val shown = providers.take(9)
                        for (bmp in shown) {
                            val ratio = if (bmp.height > 0) bmp.width.toFloat() / bmp.height.toFloat() else 1f
                            Image(
                                bitmap = bmp,
                                contentDescription = null,
                                contentScale = ContentScale.Fit,
                                modifier = Modifier.height(18.dp).aspectRatio(ratio),
                            )
                        }
                        if (providers.size > shown.size) {
                            BasicText("+${providers.size - shown.size}", style = Type.Callout)
                        }
                    }
                    Spacer(Modifier.width(8.dp))
                }
                Box(
                    Modifier.clip(RoundedCornerShape(6.dp))
                        .background(Color.White.copy(alpha = 0.16f))
                        .padding(horizontal = 8.dp, vertical = 3.dp),
                ) {
                    BasicText(
                        stringResource(if (displayedItem.mediaType == "movie") R.string.hero_movie else R.string.hero_tv),
                        style = Type.Caption.copy(color = Palette.Label),
                    )
                }
                Spacer(Modifier.width(8.dp))
                BasicText("·", style = Type.Callout)
                Spacer(Modifier.width(8.dp))
                if (displayedItem.year.isNotEmpty()) {
                    BasicText(displayedItem.year, style = Type.Callout)
                    Spacer(Modifier.width(8.dp))
                    BasicText("·", style = Type.Callout)
                    Spacer(Modifier.width(8.dp))
                }
                SaverRatingIcon(R.drawable.ic_rating_tmdb)
                Spacer(Modifier.width(4.dp))
                BasicText(String.format(Locale.US, "%.1f", displayedItem.voteAverage), style = Type.Callout)
                val imdb = displayedItem.imdbRating
                if (imdb.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    BasicText("·", style = Type.Callout)
                    Spacer(Modifier.width(8.dp))
                    SaverRatingIcon(R.drawable.ic_rating_imdb)
                    Spacer(Modifier.width(4.dp))
                    BasicText(imdb, style = Type.Callout)
                }
                if (displayedItem.rtPercent >= 0) {
                    Spacer(Modifier.width(8.dp))
                    BasicText("·", style = Type.Callout)
                    Spacer(Modifier.width(8.dp))
                    BasicText("RT " + displayedItem.rtPercent.toString() + "%", style = Type.Callout)
                }
                if (displayedItem.rtAudiencePercent >= 0) {
                    Spacer(Modifier.width(8.dp))
                    BasicText("·", style = Type.Callout)
                    Spacer(Modifier.width(8.dp))
                    BasicText("观众 " + displayedItem.rtAudiencePercent.toString() + "%", style = Type.Callout)
                }
                if (displayedItem.traktRating >= 0.0) {
                    Spacer(Modifier.width(8.dp))
                    BasicText("·", style = Type.Callout)
                    Spacer(Modifier.width(8.dp))
                    SaverRatingIcon(R.drawable.ic_rating_trakt)
                    Spacer(Modifier.width(4.dp))
                    BasicText(String.format(Locale.US, "%.1f", displayedItem.traktRating), style = Type.Callout)
                }
            }
            if (displayedItem.overview.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                BasicText(
                    displayedItem.overview,
                    style = Type.Body.copy(color = Palette.SecondaryLabel),
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                for (i in items.indices) {
                    key(i) {
                        if (i > 0) Spacer(Modifier.width(5.dp))
                        val dotAlpha by animateFloatAsState(
                            targetValue = if (i == TmdbTrending.currentIndex.coerceIn(items.indices)) 1f else 0.35f,
                            animationSpec = tween(250),
                            label = "saver-dot-alpha",
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
        Labels(caption = null, now = now, showTime = true, shade = false)
    }
}
