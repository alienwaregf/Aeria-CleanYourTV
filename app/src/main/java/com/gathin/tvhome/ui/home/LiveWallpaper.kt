package com.gathin.tvhome.ui.home

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Handler
import android.view.PixelCopy
import android.view.SurfaceView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.core.graphics.createBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.gathin.tvhome.ui.components.FilmSlot
import com.gathin.tvhome.settings.AerialSettings
import com.gathin.tvhome.system.DiagLog
import com.gathin.tvhome.ui.theme.Motion
import com.gathin.tvhome.wallpaper.Aerial
import com.gathin.tvhome.wallpaper.AerialFormats
import com.gathin.tvhome.wallpaper.AerialQuality
import com.gathin.tvhome.wallpaper.AerialSet
import com.gathin.tvhome.wallpaper.AerialStream
import com.gathin.tvhome.wallpaper.Aerials
import com.gathin.tvhome.wallpaper.ScreensaverMode
import com.gathin.tvhome.wallpaper.Wallpapers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

internal const val KEEP_STOPPED_MS = 2 * 60 * 1000L

@Composable
internal fun rememberStops(letGo: () -> Boolean): Int {
    var stops by remember { mutableIntStateOf(0) }
    val letGoNow by rememberUpdatedState(letGo)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && letGoNow()) stops++
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    return stops
}

class LiveFilm {
    var film by mutableStateOf<Aerial?>(null); internal set
    internal var player: ExoPlayer? = null

    var stopped by mutableStateOf(false); internal set

    val moving: Boolean get() = player?.isPlaying == true

    internal var moved = false
    val positionMs: Long get() = player?.currentPosition ?: 0L

    internal var insert: ((Aerial) -> Unit)? = null

    fun skip(context: android.content.Context, step: Int) {
        val p = player ?: return
        if (step < 0) p.seekToPreviousMediaItem() else insert?.invoke(AerialSet.addNew(context))
    }

    fun spot(): Pair<String, Long>? = film?.id?.takeIf { player != null }?.let { it to positionMs }

    fun letDecoderGo() {
        player?.run { pause(); stop() }
    }

    fun goOn() {
        player?.run { if (playbackState == Player.STATE_IDLE) prepare(); play() }
    }

    companion object {
        var onHome: LiveFilm? = null
    }
}

@Composable
fun LiveWallpaper(
    moving: Boolean,
    followApp: Boolean = false,
    keep: Boolean,
    cover: Animatable<Float, *>,
    hold: Boolean,
    standIn: Boolean,
    panel: Boolean = false,
    otherFormatSaver: Boolean,
    lastInputAt: () -> Long = { 0L },
    live: LiveFilm,
) {
    val context = LocalContext.current
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var surface by remember { mutableStateOf<SurfaceView?>(null) }
    var showing by remember { mutableStateOf(false) }
    val reader = remember { FilmFrames.load(context); FrameReader(context) }
    val play = moving || hold
    val inSaver by rememberUpdatedState(hold && !moving)
    val keepNow by rememberUpdatedState(keep)
    val standInNow by rememberUpdatedState(standIn)
    val panelNow by rememberUpdatedState(panel)
    val otherFormatNow by rememberUpdatedState(otherFormatSaver)
    val lastInputNow by rememberUpdatedState(lastInputAt)
    var fresh by remember { mutableStateOf(false) }
    var blackForFilm by remember { mutableStateOf(false) }
    var pictureStale by remember { mutableStateOf(false) }

    fun release() {
        reader.close()
        val p = player
        if (live.moved) {
            p?.currentMediaItem?.mediaId?.let { Wallpapers.leaveLiveFilm(it, p.currentPosition) }
        }
        live.moved = false
        player = null
        live.player = null
        live.insert = null
        live.film = null
        live.stopped = false
        showing = false
        Wallpapers.dropFilmFrost()
        p?.release()
    }

    suspend fun open(): ExoPlayer {
        SourcePreview.awaitDecoderFree()
        val quality = AerialFormats.forWallpaper(context)
        val overHttp = Build.VERSION.SDK_INT < Build.VERSION_CODES.N
        val spot = Wallpapers.liveSpot()
        fresh = false
        val films = AerialSet.playlist(context, spot?.first)
        val resume = films.first().takeIf { it.id == spot?.first }
        var failures = 0
        var rendered = false
        return AerialStream.player(context, quality).apply {
            videoChangeFrameRateStrategy = C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
            addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    rendered = true
                    live.moved = true
                    showing = isPlaying
                    failures = 0
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    showing = isPlaying && rendered
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    live.film = Aerials.all(context).firstOrNull { it.id == mediaItem?.mediaId }
                    if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) DiagLog.i("wallpaper film now ${mediaItem?.mediaId} (reason $reason)")
                }

                override fun onPlayerError(error: PlaybackException) {
                    DiagLog.i("wallpaper film failed (${error.errorCodeName})")
                    if (!keepNow) return
                    if (++failures >= 6) return
                    if (failures % 3 == 0) seekToNextMediaItem()
                    prepare()
                }
            })
            live.insert = { film ->
                addMediaItem(currentMediaItemIndex + 1, AerialStream.mediaItem(film, quality, overHttp))
                seekTo(currentMediaItemIndex + 1, 0L)
                if (playbackState == Player.STATE_IDLE) prepare()
            }
            setMediaItems(films.map { AerialStream.mediaItem(it, quality, overHttp) }, 0, if (resume != null) spot?.second ?: 0L else 0L)
            surface?.let { setVideoSurfaceView(it) }
            prepare()
            live.player = this
            live.film = films.first()
        }
    }

    suspend fun catchUp(left: Pair<String, Long>, underFilm: Boolean = false, inApp: Boolean = true, clearsStale: Boolean = false): Boolean {
        val frame = withTimeoutOrNull(CATCH_UP_MS) {
            when {
                !fileFramesShut(context) -> reader.frame(left.first, left.second, Wallpapers.screenW, Wallpapers.screenH, exact = false, nearest = true)
                inApp -> reader.keyFrameInApp(left.first, left.second, Wallpapers.screenW, Wallpapers.screenH)
                else -> null
            }
        }
        if (frame == null) {
            DiagLog.i("wallpaper picture did not catch up (copy ${pixelCopy}, file frames stuck ${FileFramesStuck.now(context)})")
            return false
        }
        if ((cover.value > 0f && !underFilm) || Wallpapers.leftLiveSpot() != left) return false
        DiagLog.i("wallpaper picture catches up with where the film was let go")
        withContext(kotlinx.coroutines.NonCancellable) {
            val started = android.os.SystemClock.elapsedRealtime()
            Wallpapers.useLiveFrame(frame, left.first, left.second)
            if (clearsStale) DiagLog.i("wallpaper picture caught up in ${android.os.SystemClock.elapsedRealtime() - started} ms")
            if (clearsStale && pictureStale) {
                pictureStale = false
                if (!keepNow && player == null) cover.snapTo(0f)
            }
        }
        return true
    }

    suspend fun goOnFromPicture() {
        if (blackForFilm && Wallpapers.moves()) {
            blackForFilm = false
            pictureStale = false
            val left = Wallpapers.leftLiveSpot()
            val pick = left?.let { SaverFrames.nearest(it) }
            if (pick != null && Wallpapers.applyLiveFrame(pick.prepared, pick.film, pick.atMs)) {
                DiagLog.i("wallpaper: back from the screensaver onto its picture (${pick.atMs - left.second} ms from where it was); the film goes on from it")
            } else {
                DiagLog.i("wallpaper: back from the screensaver before a picture of it; the film goes on from the picture from before")
            }
            SaverFrames.clear()
            Wallpapers.dropLeftLiveSpot()
            cover.snapTo(0f)
            return
        }
        if (!pictureStale) return
        pictureStale = false
        DiagLog.i("wallpaper: the picture is older than where the film was let go; the film goes on from the picture")
        Wallpapers.dropLeftLiveSpot()
    }

    suspend fun saverPictures() {
        val first = Wallpapers.leftLiveSpot()
        SaverFrames.latest = null
        if (first != null && SaverFrames.nearest(first)?.takeIf { abs(it.atMs - first.second) <= SAVER_START_SLACK_MS } == null) {
            SaverFrames.start = SaverFrames.make(context, first.first, first.second)
        }
        val began = android.os.SystemClock.elapsedRealtime()
        var next = began + SAVER_PICTURE_EVERY_MS / 2
        var madeOf = first?.first
        while (!keepNow && otherFormatNow) {
            delay(SAVER_FILM_LOOK_MS)
            val spot = Wallpapers.leftLiveSpot() ?: continue
            val now = android.os.SystemClock.elapsedRealtime()
            if (now < next && spot.first == madeOf) continue
            val beat = if (now - began < SAVER_PICTURE_FAST_FOR_MS) SAVER_PICTURE_EVERY_MS else SAVER_PICTURE_SLOW_MS
            next = maxOf(next + beat, now + beat / 2)
            madeOf = spot.first
            SaverFrames.make(context, spot.first, spot.second + beat / 2 + SAVER_PICTURE_DECODE_MS)?.let { SaverFrames.latest = it }
        }
    }

    suspend fun leaveOnPicture(p: ExoPlayer) {
        p.pause()
        val film = p.currentMediaItem?.mediaId ?: return
        val at = p.currentPosition
        val started = android.os.SystemClock.elapsedRealtime()
        val frame = withTimeoutOrNull(LEAVE_FRAME_MS) {
            (if (pixelCopy == false) null else surface?.let { capture(it) })
                ?: reader.frame(film, at, Wallpapers.screenW, Wallpapers.screenH, exact = false, nearest = true)
        }
        if (frame == null) {
            DiagLog.i("wallpaper film: no frame in time to leave on")
            if (fileFramesShut(context)) pictureStale = true
            cover.snapTo(0f)
            return
        }
        Wallpapers.showLiveFrame(frame)
        cover.snapTo(0f)
        p.stop()
        DiagLog.i("wallpaper left on its frame in ${android.os.SystemClock.elapsedRealtime() - started} ms")
        Wallpapers.useLiveFrame(frame, film, at)
    }

    var glassSample by remember { mutableStateOf<GlassSample?>(null) }
    LaunchedEffect(live.film, play, showing) {
        val id = live.film?.id ?: return@LaunchedEffect
        if (!play || !showing) return@LaunchedEffect
        if (pixelCopy == false && glassFrames) reader.open(id)
        sampleGlass({ player }, every = { if (inSaver) SAVER_GLASS_EVERY_MS else if (fileFramesShut(context)) IN_APP_GLASS_EVERY_MS else GLASS_EVERY_MS }) { at ->
            val frame = surface?.let { copyGlass(it) }
                ?: when {
                    pixelCopy == true -> null
                    fileFramesShut(context) -> reader.keyFrameInApp(id, at, 320, 180, quiet = true)
                    else -> reader.frame(id, at, 320, 180, exact = false, quiet = true)
                }
            frame?.also {
                glassSample = GlassSample(id, at, it)
                Wallpapers.keepClipGlass(id, it) { live.film?.id }
            }
        }
    }

    LaunchedEffect(live.film, showing) {
        val film = live.film ?: return@LaunchedEffect
        if (!showing || Wallpapers.screensaver != ScreensaverMode.Aerial) return@LaunchedEffect
        val saver = AerialFormats.forScreensaver(context, overWallpaper = true)
        if (saver != AerialFormats.forWallpaper(context)) {
            AerialStream.prepareSeek(context, film, saver, Build.VERSION.SDK_INT < Build.VERSION_CODES.N)
        }
    }

    val scope = rememberCoroutineScope()
    val stops = rememberStops {
        if (player == null) return@rememberStops false
        DiagLog.i("wallpaper film lets go as Aerial stops")
        release()
        scope.launch { cover.snapTo(0f) }
        true
    }

    LaunchedEffect(play, stops) {
        live.stopped = false
        if (play) {
            if (player == null) goOnFromPicture()
            val left = if (player == null) Wallpapers.leftLiveSpot() else null
            if (player == null) FilmAfterInput.await()
            val p = player ?: open().also { player = it }
            DiagLog.i("wallpaper film starts (state ${p.playbackState}, ${p.currentMediaItem?.mediaId} at ${p.currentPosition} ms)")
            Wallpapers.dropFilmFrost()
            if (p.playbackState == Player.STATE_IDLE) p.prepare()
            p.playWhenReady = true
            snapshotFlow { showing }.first { it }
            DiagLog.i("wallpaper film moving")
            cover.animateTo(1f, tween(1200, easing = Motion.EaseOut))
            if (left != null) {
                delay(CATCH_UP_UNDER_FILM_MS)
                catchUp(left, underFilm = true, inApp = false)
            }
        } else {
            val p = player ?: return@LaunchedEffect
            if (!keepNow) {
                if (cover.value > 0f && !otherFormatNow && !AerialStart.running) leaveOnPicture(p)
                release()
                return@LaunchedEffect
            }
            p.pause()
            if (cover.value > 0f) {
                val film = p.currentMediaItem?.mediaId
                val at = p.currentPosition
                val w = surface?.width ?: Wallpapers.screenW
                val h = surface?.height ?: Wallpapers.screenH
                p.videoDecoderCounters?.let { DiagLog.i("wallpaper film dropped ${it.droppedBufferCount} of ${it.renderedOutputBufferCount + it.droppedBufferCount} frames") }
                val before = Wallpapers.frosted
                glassSample?.takeIf { it.film == film && abs(it.atMs - at) <= SAME_SHOT_MS }?.let {
                    Wallpapers.useLiveFrost(it.frame)
                    glassReady(live)
                }
                glassSample = null
                var frame = if (pixelCopy == false) null else surface?.let { capture(it) }
                if (frame == null && film != null && !followApp) {
                    if (panelNow) delay(PANEL_IN_MS)
                    if (!live.stopped) reader.frame(film, at, 320, 180, exact = false)?.let {
                        Wallpapers.useLiveFrost(it)
                        glassReady(live)
                    }
                    frame = reader.frame(film, at, w, h, exact = true)
                        ?: reader.frame(film, at, w, h, exact = false, nearest = true)
                    if (frame == null && live.stopped) Wallpapers.restoreFrost(before)
                }
                if (frame != null) {
                    Wallpapers.useLiveFrame(frame, film.orEmpty(), at)
                    if (!live.stopped) glassReady(live)
                    if (!copyLostColour) {
                        withTimeoutOrNull(KEEP_STOPPED_MS) { snapshotFlow { standInNow && keepNow }.first { !it } }
                    }
                    if (!keepNow) {
                        live.stopped = false
                        if (!otherFormatNow && !AerialStart.running) cover.snapTo(0f)
                        release()
                        return@LaunchedEffect
                    }
                    DiagLog.i("wallpaper picture takes over from the stopped film")
                    cover.animateTo(0f, tween(320, easing = Motion.EaseOut))
                } else if (copySlow && film != null) {
                    val idle = untilQuietOrLeft({ standInNow && keepNow }, lastInputNow)
                    val copy = if (idle) surface?.let { captureOnce(it) } else null
                    if (copy != null) Wallpapers.useLiveFrame(copy, film, at)
                    if (!keepNow) {
                        live.stopped = false
                        if (!otherFormatNow && !AerialStart.running) cover.snapTo(0f)
                        release()
                        return@LaunchedEffect
                    }
                    DiagLog.i(if (copy != null) "wallpaper picture copied from the film left quiet" else "wallpaper film: no frame; back to the last picture")
                    cover.animateTo(0f, tween(if (copy != null) 320 else 600, easing = Motion.EaseOut))
                } else {
                    DiagLog.i("wallpaper film: no frame copied; back to the last picture")
                    cover.animateTo(0f, tween(600, easing = Motion.EaseOut))
                }
                live.stopped = false
            }
            delay(KEEP_STOPPED_MS)
            release()
        }
    }
    LaunchedEffect(keep) {
        if (!keep && !play && player != null && cover.value == 0f) release()
        if (!keep && otherFormatNow) {
            fresh = true
            blackForFilm = true
        }
        if (keep) goOnFromPicture()
        val black = keep && blackForFilm && play && cover.value > 0f
        if (keep) blackForFilm = false
        if (black) {
            if (withTimeoutOrNull(FRESH_WAIT_MS) { snapshotFlow { showing }.first { it } } == null) cover.snapTo(0f)
        } else if (keep) {
            if (player == null) cover.snapTo(0f)
            if (!play) Wallpapers.leftLiveSpot()?.let { catchUp(it) }
        } else {
            if (otherFormatNow && Wallpapers.moves()) {
                saverPictures()
                return@LaunchedEffect
            }
            if (cover.value > 0f && !otherFormatNow) {
                delay(SAVER_COVERS_MS)
                if (player == null) cover.snapTo(0f)
            }
            delay(BEHIND_CATCH_UP_MS)
            val left = Wallpapers.leftLiveSpot()
            if (!keepNow && !otherFormatNow && !AerialStart.running && player == null && left != null) {
                catchUp(left, underFilm = true, clearsStale = true)
                if (player == null) reader.close()
            }
        }
    }
    DisposableEffect(Unit) { onDispose { release() } }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { ctx -> FilmSlot(ctx).also { surface = it.surface; player?.setVideoSurfaceView(it.surface) } },
        onRelease = { player?.clearVideoSurfaceView(it.surface); surface = null },
    )
}

private suspend fun capture(view: SurfaceView): Bitmap? {
    return captureOnce(view).also { pixelCopy = it != null && !copySlow }
}

internal suspend fun copyFrame(view: SurfaceView): Bitmap? = if (pixelCopy == false) null else capture(view)

internal suspend fun copyGlass(view: SurfaceView): Bitmap? {
    if (pixelCopy == false) return null
    val frame = captureOnce(view, 320, 180)
    if (frame != null) pixelCopy = !copySlow
    else if (++glassMisses >= 3 && pixelCopy == null) pixelCopy = false
    if (frame != null) glassMisses = 0
    return frame
}

private var glassMisses = 0

internal val copyGlassPossible: Boolean get() = pixelCopy != false

private var copyLostColour = false

internal suspend fun untilQuietOrLeft(showing: () -> Boolean, lastInputAt: () -> Long): Boolean = kotlinx.coroutines.coroutineScope {
    val left = async { snapshotFlow { showing() }.first { !it }; false }
    val quiet = async {
        while (true) {
            val since = android.os.SystemClock.uptimeMillis() - lastInputAt()
            if (since >= KEEP_STOPPED_MS) break
            delay(KEEP_STOPPED_MS - since)
        }
        true
    }
    kotlinx.coroutines.selects.select<Boolean> {
        left.onAwait { it }
        quiet.onAwait { it }
    }.also {
        left.cancel()
        quiet.cancel()
    }
}

private suspend fun glassReady(live: LiveFilm) {
    withFrameNanos { }
    withFrameNanos { }
    live.stopped = true
}

private val copyCallbacks: Handler by lazy { Handler(android.os.HandlerThread("film-copy").apply { start() }.looper) }

private var copySlow = false

private const val COPY_SLOW_MS = 150L

private const val PANEL_IN_MS = 450L

private var pixelCopy: Boolean? = null

internal fun fileFramesJam(context: android.content.Context): Boolean {
    FilmFrames.load(context)
    return FilmFrames.tclExtractor(context)
}

internal fun filmGlassReport(context: android.content.Context): String {
    FilmFrames.load(context)
    return "copy=${pixelCopy ?: "untried"} copy_slow=$copySlow tcl_extractor=${FilmFrames.tclExtractor(context)} " +
        "file_stuck=${FileFramesStuck.now(context)} glassless=${Wallpapers.filmGlassless}"
}

internal const val GLASS_EVERY_MS = 4_000L

private const val SAVER_GLASS_EVERY_MS = 10_000L

private const val IN_APP_GLASS_EVERY_MS = 15_000L

private const val SAME_SHOT_MS = 30_000L

private const val CATCH_UP_MS = 5_000L

private const val CATCH_UP_UNDER_FILM_MS = 5_000L

private const val FRESH_WAIT_MS = 4_000L

private const val SAVER_COVERS_MS = 1_400L

internal const val LEAVE_FRAME_MS = 2_500L

private const val SAVER_PICTURE_EVERY_MS = 5_000L
private const val SAVER_PICTURE_DECODE_MS = 800L

private const val SAVER_FILM_LOOK_MS = 500L
private const val SAVER_PICTURE_FAST_FOR_MS = 10 * 60_000L
private const val SAVER_PICTURE_SLOW_MS = 15_000L

private const val SAVER_START_SLACK_MS = 3_000L

private const val SAVER_PICTURE_SLACK_MS = 30_000L

private const val BEHIND_CATCH_UP_MS = 3_000L

internal val glassFrames: Boolean get() = !glassCostly && !AerialSettings.lite

private var glassCostly = false

internal suspend fun sampleGlass(
    player: () -> ExoPlayer?,
    canTake: () -> Boolean = { true },
    every: () -> Long = { GLASS_EVERY_MS },
    take: suspend (atMs: Long) -> Bitmap?,
) {
    var wait = 800L
    var costly = 0
    while (glassFrames && canTake()) {
        var waited = 0L
        while (waited < minOf(wait, every())) {
            delay(250)
            waited += 250
        }
        wait = Long.MAX_VALUE
        val p = player() ?: return
        val before = droppedFrames(p)
        val frame = take(p.currentPosition)
        delay(GLASS_SETTLE_MS)
        val cost = droppedFrames(p) - before
        costly = if (cost >= GLASS_COST_FRAMES) costly + 1 else 0
        if (costly >= 2) {
            DiagLog.i("wallpaper glass: the film dropped $cost frames while one was taken, twice running; a still glass from now on")
            glassCostly = true
            break
        }
        frame?.let { Wallpapers.useFilmFrost(it) }
    }
    if (wait == 800L) delay(wait)
    Wallpapers.useStillFilmFrost()
}

private fun droppedFrames(p: ExoPlayer): Int = p.videoDecoderCounters?.let { it.ensureUpdated(); it.droppedBufferCount } ?: 0

private const val GLASS_SETTLE_MS = 600L

private const val GLASS_COST_FRAMES = 5

private class GlassSample(val film: String, val atMs: Long, val frame: Bitmap)

private suspend fun captureOnce(view: SurfaceView, w: Int = view.width, h: Int = view.height): Bitmap? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N || view.width <= 0 || !view.holder.surface.isValid) return null
    val surface = view.holder.surface
    val bitmap = createBitmap(w, h)
    val started = android.os.SystemClock.elapsedRealtime()
    val result = withContext(Dispatchers.IO) {
        suspendCancellableCoroutine { done ->
            runCatching {
                PixelCopy.request(surface, bitmap, { done.resume(it) }, copyCallbacks)
            }.onFailure { done.resume(-1) }
        }
    }
    val took = android.os.SystemClock.elapsedRealtime() - started
    val unread = if (result == PixelCopy.SUCCESS) unreadable(bitmap) else null
    if (result == PixelCopy.SUCCESS && unread == null && took > COPY_SLOW_MS) {
        DiagLog.i("wallpaper frame copy took $took ms; not copied again")
        FilmFrames.copyWasSlow()
    }
    if (unread == "green") copyLostColour = true
    if (result != PixelCopy.SUCCESS || unread != null) DiagLog.i("wallpaper frame copy: result $result, $unread, $took ms")
    return bitmap.takeIf { result == PixelCopy.SUCCESS && unread == null }
}

private class FrameReader(private val context: android.content.Context) {
    private class Worker {
        val executor = java.util.concurrent.ThreadPoolExecutor(
            0, 1, 30L, java.util.concurrent.TimeUnit.SECONDS, java.util.concurrent.LinkedBlockingQueue(),
        ) { Thread(it, "film-frames").apply { isDaemon = true } }
        var retriever: MediaMetadataRetriever? = null
        var openFor: String? = null
        @Volatile var abandoned = false

        fun close() {
            retriever?.let { runCatching { it.release() } }
            retriever = null
            openFor = null
        }
    }

    @Volatile private var worker = Worker()

    @Volatile private var urlRefused = false

    private suspend fun <T : Any> onWorker(what: String, block: (Worker) -> T?): T? {
        if (fileFramesShut(context)) return null
        val w = worker
        val result = kotlinx.coroutines.CompletableDeferred<T?>()
        val started = android.os.SystemClock.elapsedRealtime()
        try {
            w.executor.execute { result.complete(if (w.abandoned) null else block(w)) }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            return null
        }
        val value = withTimeoutOrNull(DECODE_TIMEOUT_MS) { result.await() }
        if (value == null && !result.isCompleted) abandon(w, what, started)
        return value
    }

    private fun abandon(w: Worker, what: String, started: Long) {
        synchronized(this) {
            if (worker !== w) return
            worker = Worker()
        }
        w.abandoned = true
        FileFramesStuck.mark(context)
        FilmFrames.update()
        DiagLog.i("wallpaper film file stuck ($what, ${android.os.SystemClock.elapsedRealtime() - started} ms); not read again until it returns or the TV restarts")
        runCatching {
            w.executor.execute {
                w.close()
                FileFramesStuck.clear(context)
                DiagLog.i("wallpaper film file returned after ${android.os.SystemClock.elapsedRealtime() - started} ms; read again")
                android.os.Handler(android.os.Looper.getMainLooper()).post { FilmFrames.update() }
            }
            w.executor.shutdown()
        }
    }

    suspend fun open(id: String) {
        onWorker("open") { retrieverFor(it, id) }
    }

    suspend fun frame(id: String, atMs: Long, w: Int, h: Int, exact: Boolean, quiet: Boolean = false, nearest: Boolean = false): Bitmap? {
        if (fileFramesShut(context)) return null
        val started = android.os.SystemClock.elapsedRealtime()
        val frame = onWorker("frame") { wk ->
            val mmr = retrieverFor(wk, id) ?: return@onWorker null
            try {
                val us = atMs * 1000
                val option = when {
                    exact -> MediaMetadataRetriever.OPTION_CLOSEST
                    nearest -> MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                    else -> MediaMetadataRetriever.OPTION_PREVIOUS_SYNC
                }
                if (Build.VERSION.SDK_INT >= 27) mmr.getScaledFrameAtTime(us, option, w, h)
                else mmr.getFrameAtTime(us, option)
            } catch (e: Exception) {
                DiagLog.i("wallpaper frame decode failed: ${e.javaClass.simpleName}")
                wk.close()
                null
            }
        }
        if (!quiet || frame == null) DiagLog.i("wallpaper ${if (exact) "frame" else if (nearest) "key frame" else "glass frame"} decoded: ${frame != null} in ${android.os.SystemClock.elapsedRealtime() - started} ms")
        val sized = frame?.let {
            val sized = if (it.width == w && it.height == h) it else Bitmap.createScaledBitmap(it, w, h, true)
            if (sized.isMutable && sized.config == Bitmap.Config.ARGB_8888) sized else sized.copy(Bitmap.Config.ARGB_8888, true)
        }
        if (sized != null && unreadable(sized) == "green") {
            DiagLog.i("wallpaper decoded frame is green; not used")
            return null
        }
        return sized
    }

    suspend fun keyFrameInApp(id: String, atMs: Long, w: Int, h: Int, quiet: Boolean = false): Bitmap? =
        keyFrameDecodedInApp(context, id, atMs, w, h, quiet)?.bitmap

    fun close() {
        val w = worker
        runCatching { w.executor.execute { w.close() } }
    }

    private fun retrieverFor(w: Worker, id: String): MediaMetadataRetriever? {
        if (w.openFor == id) w.retriever?.let { return it }
        w.close()
        val film = Aerials.all(context).firstOrNull { it.id == id } ?: return null
        val url = AerialStream.url(film, AerialQuality.Hd, Build.VERSION.SDK_INT < Build.VERSION_CODES.N)
        val started = android.os.SystemClock.elapsedRealtime()
        val mmr = open(url, ranged = urlRefused) ?: run {
            if (urlRefused || Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return null
            urlRefused = true
            open(url, ranged = true) ?: return null
        }
        DiagLog.i("wallpaper film file opened${if (urlRefused) " through the app" else ""} in ${android.os.SystemClock.elapsedRealtime() - started} ms")
        if (w.abandoned) {
            runCatching { mmr.release() }
            return null
        }
        w.retriever = mmr
        w.openFor = id
        return mmr
    }

    private fun open(url: String, ranged: Boolean): MediaMetadataRetriever? {
        val mmr = MediaMetadataRetriever()
        return try {
            if (ranged && Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) mmr.setDataSource(RangedHttpSource(url))
            else mmr.setDataSource(url, emptyMap())
            mmr
        } catch (e: Exception) {
            DiagLog.i("wallpaper film file not opened${if (ranged) " through the app" else ""}: ${e.javaClass.simpleName}")
            runCatching { mmr.release() }
            null
        }
    }
}

internal suspend fun keyFrameDecodedInApp(context: android.content.Context, id: String, atMs: Long, w: Int, h: Int, quiet: Boolean = false): com.gathin.tvhome.wallpaper.FilmKeyFrame.Frame? {
    val film = Aerials.all(context).firstOrNull { it.id == id } ?: return null
    val started = android.os.SystemClock.elapsedRealtime()
    val frame = try {
        AerialStream.keyFrame(context, film, atMs, w, h, Build.VERSION.SDK_INT < Build.VERSION_CODES.N)
    } catch (e: kotlinx.coroutines.CancellationException) {
        DiagLog.i("wallpaper key frame in the app cancelled after ${android.os.SystemClock.elapsedRealtime() - started} ms")
        throw e
    } catch (e: Throwable) {
        DiagLog.i("wallpaper key frame in the app failed after ${android.os.SystemClock.elapsedRealtime() - started} ms: ${e.javaClass.simpleName} ${e.message}")
        return null
    }
    if (!quiet) DiagLog.i("wallpaper key frame decoded in the app in ${android.os.SystemClock.elapsedRealtime() - started} ms (read ${frame.readMs} ms, ${frame.sampleBytes / 1024} KB${if (frame.indexKept) ", index kept" else ""}; ${frame.codec} ${frame.decodeMs} ms, to RGB ${frame.convertMs} ms)")
    if (unreadable(frame.bitmap) == "green") {
        DiagLog.i("wallpaper decoded frame is green; not used")
        return null
    }
    return frame
}

internal object SaverFrames {
    class Picture(val film: String, val atMs: Long, val prepared: Wallpapers.PreparedFrame)

    @Volatile var start: Picture? = null
    @Volatile var latest: Picture? = null

    fun clear() {
        start = null
        latest = null
    }

    suspend fun make(context: android.content.Context, film: String, atMs: Long): Picture? {
        val frame = keyFrameDecodedInApp(context, film, atMs, Wallpapers.screenW, Wallpapers.screenH) ?: return null
        return Picture(film, frame.timeMs, Wallpapers.prepareLiveFrame(frame.bitmap))
    }

    suspend fun prepareAhead(context: android.content.Context, film: String, atMs: Long) {
        if (!Wallpapers.moves()) return
        latest = null
        start = make(context, film, atMs)?.also { DiagLog.i("screensaver's first picture made ahead: ${it.film} at ${it.atMs} ms") }
    }

    fun nearest(spot: Pair<String, Long>): Picture? = listOfNotNull(start, latest)
        .filter { it.film == spot.first && abs(it.atMs - spot.second) <= SAVER_PICTURE_SLACK_MS }
        .minByOrNull { abs(it.atMs - spot.second) }
}

private const val DECODE_TIMEOUT_MS = 10_000L

private object FilmFrames {
    @Volatile private var app: android.content.Context? = null

    fun load(context: android.content.Context) {
        if (app != null) return
        val c = context.applicationContext
        app = c
        if (prefs(c).getString(SLOW_KEY, null) == Build.FINGERPRINT) {
            copySlow = true
            pixelCopy = false
        }
        update()
    }

    fun copyWasSlow() {
        copySlow = true
        app?.let { prefs(it).edit().putString(SLOW_KEY, Build.FINGERPRINT).apply() }
        update()
    }

    fun update() {
        val c = app ?: return
        Wallpapers.filmGlassless = fileFramesShut(c)
    }

    fun tclExtractor(context: android.content.Context): Boolean {
        tcl?.let { return it }
        val prefs = prefs(context)
        val known = prefs.getString(TCL_KEY, null)
        val found = if (known?.substringBeforeLast('|') == Build.FINGERPRINT) known.endsWith("|true") else {
            val dirs = listOf("/apex/com.android.media/lib64", "/apex/com.android.media/lib", "/system/lib64", "/system/lib")
            val libs = dirs.map { java.io.File("$it/extractors/libmp4extractor.so") }.filter { it.exists() }
            val tclFound = libs.isEmpty() || libs.any { lib -> runCatching { containsAscii(lib, "TclMPEG4Extractor") }.getOrDefault(true) }
            prefs.edit().putString(TCL_KEY, "${Build.FINGERPRINT}|$tclFound").apply()
            DiagLog.i("wallpaper film file: ${libs.joinToString { it.path }.ifEmpty { "no MP4 extractor found" }}, TCL's extractor $tclFound")
            tclFound
        }
        tcl = found
        return found
    }

    @Volatile private var tcl: Boolean? = null

    private fun prefs(context: android.content.Context) = context.getSharedPreferences("wallpaper_frames", android.content.Context.MODE_PRIVATE)
    private const val SLOW_KEY = "copy_slow_read"
    private const val TCL_KEY = "tcl_extractor"
}

private fun fileFramesShut(context: android.content.Context): Boolean =
    (copySlow && FilmFrames.tclExtractor(context)) || FileFramesStuck.now(context)

private fun containsAscii(file: java.io.File, text: String): Boolean {
    val needle = text.toByteArray(Charsets.US_ASCII)
    val buffer = ByteArray(64 * 1024 + needle.size)
    file.inputStream().use { input ->
        var kept = 0
        while (true) {
            val read = input.read(buffer, kept, buffer.size - kept)
            if (read <= 0) return false
            val end = kept + read
            outer@ for (i in 0..end - needle.size) {
                for (j in needle.indices) if (buffer[i + j] != needle[j]) continue@outer
                return true
            }
            kept = minOf(needle.size - 1, end)
            System.arraycopy(buffer, end - kept, buffer, 0, kept)
        }
    }
}

private object FileFramesStuck {
    @Volatile private var stuck: Boolean? = null

    fun now(context: android.content.Context): Boolean = stuck ?: run {
        val prefs = prefs(context)
        val marked = prefs.getLong(KEY, Long.MIN_VALUE) == bootId(context) &&
            prefs.getInt(KEY_VERSION, 0) >= MARKS_CLEARED_SINCE
        if (marked && !FilmFrames.tclExtractor(context)) {
            prefs.edit().remove(KEY).apply()
            DiagLog.i("wallpaper film file: a stuck mark from an earlier process dropped; read again")
            false
        } else marked
    }.also { stuck = it }

    fun mark(context: android.content.Context) {
        stuck = true
        prefs(context).edit().putLong(KEY, bootId(context)).putInt(KEY_VERSION, com.gathin.tvhome.BuildConfig.VERSION_CODE).apply()
    }

    fun clear(context: android.content.Context) {
        stuck = false
        prefs(context).edit().remove(KEY).apply()
    }

    private fun bootId(context: android.content.Context): Long {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            val count = android.provider.Settings.Global.getInt(context.contentResolver, android.provider.Settings.Global.BOOT_COUNT, -1)
            if (count >= 0) return count.toLong()
        }
        return -(System.currentTimeMillis() - android.os.SystemClock.elapsedRealtime()) / 60_000L
    }

    private fun prefs(context: android.content.Context) = context.getSharedPreferences("wallpaper_frames", android.content.Context.MODE_PRIVATE)
    private const val KEY = "stuck_boot"
    private const val KEY_VERSION = "stuck_version"
    private const val MARKS_CLEARED_SINCE = 99
}

@androidx.annotation.RequiresApi(Build.VERSION_CODES.M)
private class RangedHttpSource(private val url: String) : android.media.MediaDataSource() {
    private val blocks = object : LinkedHashMap<Long, ByteArray>(8, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, ByteArray>?) = size > MAX_BLOCKS
    }
    private val length: Long = fetch(0).let { (first, total) -> blocks[0] = first; total }

    override fun getSize(): Long = length

    @Synchronized
    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position >= length) return -1
        var done = 0
        while (done < size && position + done < length) {
            val at = position + done
            val index = at / BLOCK
            val block = blocks[index] ?: fetch(index).first.also { blocks[index] = it }
            val from = (at - index * BLOCK).toInt()
            if (from >= block.size) break
            val n = minOf(size - done, block.size - from)
            System.arraycopy(block, from, buffer, offset + done, n)
            done += n
        }
        return if (done == 0) -1 else done
    }

    @Synchronized
    override fun close() = blocks.clear()

    private fun fetch(index: Long): Pair<ByteArray, Long> {
        val c = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        try {
            c.connectTimeout = 8_000
            c.readTimeout = 8_000
            c.setRequestProperty("Range", "bytes=${index * BLOCK}-${(index + 1) * BLOCK - 1}")
            if (c.responseCode != 206) throw java.io.IOException("HTTP ${c.responseCode}")
            val total = c.getHeaderField("Content-Range")?.substringAfterLast('/')?.toLongOrNull()
                ?: throw java.io.IOException("no length")
            return c.inputStream.use { it.readBytes() } to total
        } finally {
            c.disconnect()
        }
    }

    private companion object {
        const val BLOCK = 512 * 1024L
        const val MAX_BLOCKS = 12
    }
}

internal fun unreadable(b: Bitmap): String? {
    var lit = 0
    var green = 0
    var lo = 255
    var hi = 0
    for (y in 1..8) for (x in 1..8) {
        val c = b.getPixel(b.width * x / 9, b.height * y / 9)
        val r = c shr 16 and 0xFF
        val g = c shr 8 and 0xFF
        val bl = c and 0xFF
        if (r + g + bl > 24) lit++
        if (r < 48 && bl < 24 && g > r + 60 && g > bl + 60) green++
        val luma = (r * 3 + g * 6 + bl) / 10
        lo = minOf(lo, luma)
        hi = maxOf(hi, luma)
    }
    return when {
        lit < 3 -> "black"
        green > 32 -> "green"
        hi - lo < 6 -> "flat"
        else -> null
    }
}
