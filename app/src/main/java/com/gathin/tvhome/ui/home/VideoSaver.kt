package com.gathin.tvhome.ui.home

import android.os.SystemClock
import android.view.SurfaceHolder
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.net.toUri
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import com.gathin.tvhome.ui.components.FilmSlot
import com.gathin.tvhome.system.DiagLog
import com.gathin.tvhome.wallpaper.ScreensaverVideo
import com.gathin.tvhome.wallpaper.ScreensaverVideos
import com.gathin.tvhome.wallpaper.Wallpapers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext

@Composable
internal fun VideoSaver(steps: SharedFlow<Int>, leaving: Boolean = false, onUnavailable: () -> Unit, onEmpty: () -> Unit) {
    val context = LocalContext.current
    val start = remember { if (Wallpapers.isVideo()) HomeVideo.start() else null }
    val wallpaper = remember { Wallpapers.isVideo() }
    val films by produceState<List<ScreensaverVideo>?>(null) {
        value = withContext(Dispatchers.IO) {
            (if (wallpaper) Wallpapers.videoFilms() else emptyList()).ifEmpty { ScreensaverVideos.all(context).shuffled() }
        }
    }
    LocalFilms(films, start, follows = wallpaper, leaving, steps, onUnavailable, onEmpty)
}

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
private fun LocalFilms(
    films: List<ScreensaverVideo>?,
    start: Pair<String, Long>?,
    follows: Boolean,
    leaving: Boolean,
    steps: SharedFlow<Int>,
    onUnavailable: () -> Unit,
    onEmpty: () -> Unit,
) {
    val context = LocalContext.current
    val player = remember {
        LiveFilm.onHome?.letDecoderGo()
        ExoPlayer.Builder(context).setLoadControl(
            DefaultLoadControl.Builder().setTargetBufferBytes(12 * 1024 * 1024)
                .setBufferDurationsMs(5_000, 15_000, 500, 1_000).build(),
        ).build().apply {
            volume = 0f
            trackSelectionParameters = trackSelectionParameters.buildUpon().setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, true).build()
            repeatMode = Player.REPEAT_MODE_ALL
            videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT
        }
    }
    val leavingNow by rememberUpdatedState(leaving)
    var attempt by remember { mutableIntStateOf(0) }
    var playing by remember { mutableStateOf(false) }
    var aspect by remember { mutableFloatStateOf(16f / 9f) }
    val shown = remember { HashSet<String>() }

    fun skip(step: Int) {
        if (player.mediaItemCount == 0) return
        if (step < 0) player.seekToPreviousMediaItem() else player.seekToNextMediaItem()
        if (player.playbackState == Player.STATE_IDLE) player.prepare()
    }
    fun failed() {
        playing = false
        if (player.mediaItemCount <= 1) { onUnavailable(); return }
        player.removeMediaItem(player.currentMediaItemIndex)
        player.prepare()
        attempt++
    }
    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                playing = true
                player.currentMediaItem?.mediaId?.let(shown::add)
                DiagLog.i("local screensaver first frame: ${player.currentMediaItem?.mediaId}")
            }
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width > 0 && videoSize.height > 0) aspect = videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (reason != Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && reason != Player.MEDIA_ITEM_TRANSITION_REASON_REPEAT) playing = false
                attempt++
            }
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_BUFFERING) playing = false
                else if (playbackState == Player.STATE_READY && player.isPlaying) playing = true
            }
            override fun onPlayerError(error: PlaybackException) {
                DiagLog.i("local screensaver failed: ${error.errorCodeName}")
                failed()
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
            if (follows) VideoSaverFrames.apply()
        }
    }
    if (follows) LaunchedEffect(Unit) {
        VideoSaverFrames.latest = null
        val began = SystemClock.uptimeMillis()
        var next = began + FIRST_PICTURE_MS
        var madeOf: String? = null
        while (true) {
            delay(FILM_LOOK_MS)
            if (!player.isPlaying) continue
            val film = player.currentMediaItem?.mediaId ?: continue
            val now = SystemClock.uptimeMillis()
            if (now < next && film == madeOf) continue
            val beat = if (now - began < PICTURE_FAST_FOR_MS) PICTURE_EVERY_MS else PICTURE_SLOW_MS
            next = maxOf(next + beat, now + beat / 2)
            madeOf = film
            val at = player.currentPosition + beat / 2 + PICTURE_DECODE_MS
            VideoSaverFrames.make(context, film, at)?.let { if (!leavingNow) VideoSaverFrames.latest = it }
        }
    }
    LaunchedEffect(leaving) { if (leaving && follows) VideoSaverFrames.apply() }
    LaunchedEffect(films) {
        val list = films ?: return@LaunchedEffect
        DiagLog.i("local screensaver: ${list.size} films")
        if (list.isEmpty()) { onEmpty(); return@LaunchedEffect }
        val first = list.indexOfFirst { it.id == start?.first }
        if (first >= 0) DiagLog.i("local screensaver goes on from the wallpaper: ${start?.first} at ${start?.second} ms")
        player.setMediaItems(
            list.map { MediaItem.Builder().setUri(it.file.toUri()).setMediaId(it.id).build() },
            first.coerceAtLeast(0),
            if (first >= 0) start?.second ?: 0L else 0L,
        )
        player.prepare()
        player.playWhenReady = true
    }
    LaunchedEffect(steps) { steps.collect { skip(it) } }
    LaunchedEffect(attempt, playing, films != null) {
        if (films == null || playing) return@LaunchedEffect
        delay(8_000)
        if (player.currentMediaItem?.mediaId in shown) DiagLog.i("local screensaver: no new frame for 8 s from a film that played; kept")
        else failed()
    }
    Box(Modifier.fillMaxSize()) {
        AndroidView(
            modifier = Modifier.align(Alignment.Center).aspectRatio(aspect),
            factory = { ctx ->
                FilmSlot(ctx).also {
                    it.surface.holder.addCallback(object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) = DiagLog.i("local screensaver surface created")
                        override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
                        override fun surfaceDestroyed(holder: SurfaceHolder) = DiagLog.i("local screensaver surface destroyed")
                    })
                    player.setVideoSurfaceView(it.surface)
                }
            },
            onRelease = { player.clearVideoSurfaceView(it.surface) },
        )
    }
}

private const val FIRST_PICTURE_MS = 1_500L
private const val PICTURE_EVERY_MS = 5_000L
private const val PICTURE_FAST_FOR_MS = 10 * 60_000L
private const val PICTURE_SLOW_MS = 15_000L
private const val PICTURE_DECODE_MS = 800L
private const val FILM_LOOK_MS = 500L
