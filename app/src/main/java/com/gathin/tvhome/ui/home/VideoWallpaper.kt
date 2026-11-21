package com.gathin.tvhome.ui.home

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.view.SurfaceView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.exoplayer.ExoPlayer
import com.gathin.tvhome.settings.AerialSettings
import com.gathin.tvhome.ui.components.FilmSlot
import com.gathin.tvhome.system.DiagLog
import com.gathin.tvhome.ui.theme.Motion
import com.gathin.tvhome.wallpaper.AerialStream
import com.gathin.tvhome.wallpaper.ScreensaverVideos
import com.gathin.tvhome.wallpaper.Wallpapers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class HomeVideo {
    internal var player: ExoPlayer? = null

    fun skip(step: Int) {
        val p = player ?: return
        if (step < 0) p.seekToPreviousMediaItem() else p.seekToNextMediaItem()
        if (p.playbackState == Player.STATE_IDLE) p.prepare()
    }

    fun spot(): Pair<String, Long>? = player?.let { p -> p.currentMediaItem?.mediaId?.let { it to p.currentPosition } }

    companion object {
        var onHome: HomeVideo? = null

        fun start(): Pair<String, Long>? = onHome?.spot() ?: Wallpapers.videoLeftSpot() ?: Wallpapers.videoSpot()
    }
}

internal object VideoSaverFrames {
    class Picture(val film: String, val atMs: Long, val prepared: Wallpapers.PreparedFrame) {
        fun recycle() {
            prepared.frame.recycle()
            prepared.frost.recycle()
        }
    }

    @Volatile private var latestField: Picture? = null

    var latest: Picture?
        get() = latestField
        set(value) {
            val old = latestField
            latestField = value
            if (old !== value) old?.recycle()
        }

    suspend fun make(context: android.content.Context, film: String, atMs: Long): Picture? {
        val (frame, at) = keyFrameOfVideo(context, film, atMs, Wallpapers.screenW, Wallpapers.screenH) ?: return null
        return Picture(film, at, Wallpapers.prepareLiveFrame(frame))
    }

    fun apply() {
        val pick = latestField ?: return
        latestField = null
        if (Wallpapers.applyLiveFrame(pick.prepared, pick.film, pick.atMs)) {
            DiagLog.i("video wallpaper: back from the screensaver onto its picture (${pick.film} at ${pick.atMs} ms)")
        }
    }
}

@Composable
fun VideoWallpaper(
    moving: Boolean,
    followApp: Boolean = false,
    keep: Boolean,
    hold: Boolean,
    home: HomeVideo,
    standIn: Boolean,
    lastInputAt: Long,
    cover: Animatable<Float, *>,
    behindApp: Boolean = false,
) {
    val context = LocalContext.current
    var player by remember { mutableStateOf<ExoPlayer?>(null) }
    var surface by remember { mutableStateOf<SurfaceView?>(null) }
    var showing by remember { mutableStateOf(false) }
    var shape by remember { mutableStateOf(Wallpapers.videoSpot()?.first?.let { filmShapes[it] }) }
    var idle by remember { mutableStateOf(false) }
    LaunchedEffect(lastInputAt, moving) { idle = false; if (!moving) return@LaunchedEffect; delay(START_AFTER_MS); idle = true }
    val play = moving && (idle || AerialSettings.wallpaperAlwaysMoves) || hold
    val keepNow by rememberUpdatedState(keep)
    val behindAppNow by rememberUpdatedState(behindApp)
    val standInNow by rememberUpdatedState(standIn)
    val lastInputNow by rememberUpdatedState(lastInputAt)
    val scope = rememberCoroutineScope()

    fun release() {
        val p = player
        player = null
        home.player = null
        showing = false
        Wallpapers.dropFilmFrost()
        p?.release()
    }

    suspend fun open(): ExoPlayer? {
        SourcePreview.awaitDecoderFree()
        val films = withContext(Dispatchers.IO) { Wallpapers.videoFilms() }
        if (films.isEmpty()) return null
        val spot = Wallpapers.videoSpot()
        val first = films.indexOfFirst { it.id == spot?.first }
        var rendered = false
        var failures = 0
        if (first >= 0) filmShapes[films[first].id]?.let { shape = it }
        return ExoPlayer.Builder(context).build().apply {
            volume = 0f
            trackSelectionParameters = trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
            repeatMode = Player.REPEAT_MODE_ALL
            videoScalingMode = scalingFor(shape)
            videoChangeFrameRateStrategy = C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF
            addListener(object : Player.Listener {
                override fun onRenderedFirstFrame() {
                    rendered = true
                    failures = 0
                    showing = isPlaying
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    showing = isPlaying && rendered
                }

                override fun onVideoSizeChanged(videoSize: VideoSize) {
                    if (videoSize.width <= 0 || videoSize.height <= 0) return
                    val turned = (videoFormat?.rotationDegrees ?: 0) % 180 != 0
                    val now = FilmShape(videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height, turned)
                    if (now != shape) DiagLog.i("video wallpaper film ${videoSize.width}x${videoSize.height}${if (turned) ", turned" else ""}")
                    shape = now
                    currentMediaItem?.mediaId?.let { filmShapes[it] = now }
                    videoScalingMode = scalingFor(now)
                }

                override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                    val id = mediaItem?.mediaId ?: return
                    filmShapes[id]?.let { shape = it; videoScalingMode = scalingFor(it) }
                    if (copyGlassPossible || reason == Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED) return
                    scope.launch {
                        val poster = withContext(Dispatchers.IO) { Wallpapers.videoPoster(id, Wallpapers.screenW, Wallpapers.screenH) } ?: return@launch
                        Wallpapers.useLiveFrame(poster, id, 0L)
                        Wallpapers.useStillFilmFrost()
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    DiagLog.i("video wallpaper failed (${error.errorCodeName})")
                    if (!keepNow) return
                    scope.launch { cover.snapTo(0f) }
                    if (++failures >= 6) return
                    if (failures % 3 == 0 && mediaItemCount > 1) seekToNextMediaItem()
                    prepare()
                }
            })
            setMediaItems(
                films.map { MediaItem.Builder().setUri(it.file.toUri()).setMediaId(it.id).build() },
                first.coerceAtLeast(0),
                if (first >= 0) spot?.second ?: 0L else 0L,
            )
            surface?.let { setVideoSurfaceView(it) }
            prepare()
        }
    }

    suspend fun frameOf(film: String, at: Long, held: Boolean): Pair<Bitmap, Long>? {
        val w = surface?.width?.takeIf { it > 0 } ?: Wallpapers.screenW
        val h = surface?.height?.takeIf { it > 0 } ?: Wallpapers.screenH
        if (held && screenShaped(shape?.shape ?: 0f)) surface?.let { copyFrame(it) }?.let { return it to at }
        keyFrameOfVideo(context, film, at, w, h)?.let { return it }
        return withContext(Dispatchers.IO) {
            (if (held || fileFramesJam(context)) null else fileFrame(context, film, at, w, h))?.let { it to at }
                ?: Wallpapers.videoPoster(film, w, h)?.let { it to at }
        }
    }

    var catchUp by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }

    fun letGo() {
        val p = player ?: return
        val film = p.currentMediaItem?.mediaId
        val at = p.currentPosition
        release()
        if (film == null) return
        Wallpapers.leaveVideo(film, at)
        val spot = Wallpapers.videoSpot()
        if (spot?.first == film && kotlin.math.abs(spot.second - at) < SAME_SPOT_MS) return
        catchUp?.cancel()
        catchUp = scope.launch {
            frameOf(film, at, held = false)?.let { (frame, moment) -> Wallpapers.useLiveFrame(frame, film, moment) }
        }
    }

    suspend fun leaveOnPicture(p: ExoPlayer): Boolean {
        p.pause()
        val film = p.currentMediaItem?.mediaId ?: return false
        val at = p.currentPosition
        val started = android.os.SystemClock.elapsedRealtime()
        val frame = withTimeoutOrNull(LEAVE_FRAME_MS) { frameOf(film, at, held = true) } ?: return false
        Wallpapers.useLiveFrame(frame.first, film, frame.second)
        cover.snapTo(0f)
        release()
        Wallpapers.leaveVideo(film, at)
        DiagLog.i("video wallpaper left on its frame in ${android.os.SystemClock.elapsedRealtime() - started} ms")
        return true
    }

    LaunchedEffect(play, showing) {
        if (!play || !showing) return@LaunchedEffect
        sampleGlass({ player }, canTake = { copyGlassPossible }) { surface?.let { copyGlass(it) } }
    }

    val stops = rememberStops {
        if (player == null) return@rememberStops false
        DiagLog.i("video wallpaper lets go as Aerial stops")
        letGo()
        scope.launch { cover.snapTo(0f) }
        true
    }

    LaunchedEffect(play, stops) {
        if (play) {
            withTimeoutOrNull(RETRIEVER_WAIT_MS) { while (retrieving) delay(50) }
            catchUp?.let { withTimeoutOrNull(CATCH_UP_WAIT_MS) { it.join() } }
            if (player == null) VideoSaverFrames.apply()
            val p = player ?: open()?.also { player = it; home.player = it } ?: return@LaunchedEffect
            DiagLog.i("video wallpaper starts (${p.currentMediaItem?.mediaId} at ${p.currentPosition} ms)")
            Wallpapers.dropFilmFrost()
            if (p.playbackState == Player.STATE_IDLE) p.prepare()
            p.playWhenReady = true
            snapshotFlow { showing }.collect { if (it && cover.targetValue < 1f) cover.animateTo(1f, tween(1200, easing = Motion.EaseOut)) }
        } else {
            val p = player ?: return@LaunchedEffect
            if (!keepNow) {
                if (cover.value > 0f && behindAppNow && leaveOnPicture(p)) return@LaunchedEffect
                cover.snapTo(0f)
                letGo()
                return@LaunchedEffect
            }
            p.pause()
            DiagLog.i("video wallpaper stops at ${p.currentPosition} ms")
            if (cover.value > 0f) {
                val film = p.currentMediaItem?.mediaId
                val at = p.currentPosition
                val quiet = if (standInNow && film != null) untilQuietOrLeft({ standInNow && keepNow }, { lastInputNow }) else false
                if (!keepNow) {
                    if (behindAppNow && leaveOnPicture(p)) return@LaunchedEffect
                    cover.snapTo(0f)
                    letGo()
                    return@LaunchedEffect
                }
                val frame = if (followApp) null else film?.let { frameOf(it, at, held = true) }
                if (frame != null && film != null) Wallpapers.useLiveFrame(frame.first, film, frame.second)
                if (quiet) DiagLog.i("video wallpaper picture takes over from the stopped film")
                cover.animateTo(0f, tween(if (frame != null) 320 else 600, easing = Motion.EaseOut))
                if (quiet) {
                    release()
                    return@LaunchedEffect
                }
            }
            delay(KEEP_STOPPED_MS)
            release()
        }
    }
    LaunchedEffect(keep) {
        if (!keep && !play && player != null && cover.value == 0f) letGo()
        if (keep && player == null) VideoSaverFrames.apply()
    }
    DisposableEffect(Unit) { onDispose { release() } }

    AndroidView(
        modifier = Modifier.fillMaxSize().coverShape(shape?.view ?: 0f),
        factory = { ctx -> FilmSlot(ctx).also { surface = it.surface; player?.setVideoSurfaceView(it.surface) } },
        onRelease = { player?.clearVideoSurfaceView(it.surface); surface = null },
    )
}

private fun screenShaped(shape: Float): Boolean =
    shape <= 0f || kotlin.math.abs(shape * Wallpapers.screenH / Wallpapers.screenW - 1f) < SHAPE_SLACK

private data class FilmShape(val shape: Float, val turned: Boolean) {
    val view: Float get() = if (turned) shape else 0f
}

private fun Modifier.coverShape(shape: Float): Modifier = if (screenShaped(shape)) this else layout { measurable, constraints ->
    val w = constraints.maxWidth
    val h = constraints.maxHeight
    val tall = shape < w.toFloat() / h
    val cw = if (tall) w else (h * shape).toInt()
    val ch = if (tall) (w / shape).toInt() else h
    val placeable = measurable.measure(Constraints.fixed(cw, ch))
    layout(w, h) { placeable.place((w - cw) / 2, (h - ch) / 2) }
}

private fun scalingFor(shape: FilmShape?): Int =
    if (screenShaped(shape?.view ?: 0f)) C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING else C.VIDEO_SCALING_MODE_SCALE_TO_FIT

private val filmShapes = java.util.concurrent.ConcurrentHashMap<String, FilmShape>()

private const val SHAPE_SLACK = 0.02f

internal suspend fun keyFrameOfVideo(context: android.content.Context, id: String, atMs: Long, w: Int, h: Int): Pair<Bitmap, Long>? {
    val film = withContext(Dispatchers.IO) { ScreensaverVideos.all(context).firstOrNull { it.id == id } } ?: return null
    val started = android.os.SystemClock.elapsedRealtime()
    val frame = try {
        withTimeoutOrNull(VIDEO_KEY_FRAME_MS) { AerialStream.keyFrameOfFile(film.file, id, atMs, w, h) }
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Throwable) {
        DiagLog.i("video wallpaper key frame in the app failed after ${android.os.SystemClock.elapsedRealtime() - started} ms: ${e.javaClass.simpleName} ${e.message}")
        return null
    } ?: run {
        DiagLog.i("video wallpaper key frame in the app took over $VIDEO_KEY_FRAME_MS ms")
        return null
    }
    DiagLog.i("video wallpaper key frame decoded in the app in ${android.os.SystemClock.elapsedRealtime() - started} ms (${frame.codec} ${frame.decodeMs} ms, ${frame.timeMs - atMs} ms off)")
    if (unreadable(frame.bitmap) == "green") {
        DiagLog.i("video wallpaper decoded frame is green; not used")
        frame.bitmap.recycle()
        return null
    }
    return frame.bitmap to frame.timeMs
}

private const val VIDEO_KEY_FRAME_MS = 4_000L

private fun fileFrame(context: android.content.Context, id: String, atMs: Long, w: Int, h: Int): Bitmap? {
    val film = ScreensaverVideos.all(context).firstOrNull { it.id == id } ?: return null
    val mmr = MediaMetadataRetriever()
    retrieving = true
    return try {
        mmr.setDataSource(film.file.path)
        val frame = mmr.getFrameAtTime(atMs * 1000, MediaMetadataRetriever.OPTION_CLOSEST) ?: return null
        val covered = com.gathin.tvhome.wallpaper.Images.cover(frame, w, h)
        if (covered !== frame) frame.recycle()
        if (covered.isMutable && covered.config == Bitmap.Config.ARGB_8888) covered
        else covered.copy(Bitmap.Config.ARGB_8888, true).also { if (it !== covered) covered.recycle() }
    } catch (e: Exception) {
        DiagLog.i("video wallpaper frame not decoded: ${e.javaClass.simpleName}")
        null
    } finally {
        runCatching { mmr.release() }
        retrieving = false
    }
}

@Volatile private var retrieving = false

private const val RETRIEVER_WAIT_MS = 3_000L

private const val CATCH_UP_WAIT_MS = 1_500L

private const val SAME_SPOT_MS = 1_500L
