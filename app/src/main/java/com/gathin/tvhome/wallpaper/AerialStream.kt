package com.gathin.tvhome.wallpaper

import android.content.Context
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.ContentMetadataMutations
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecInfo
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.MediaCodecVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.extractor.DefaultExtractorInput
import androidx.media3.extractor.DiscardingTrackOutput
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.TrackOutput
import androidx.media3.extractor.mp4.Mp4Extractor
import androidx.media3.extractor.text.SubtitleParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.File

@OptIn(UnstableApi::class)
object AerialStream {
    private const val TAG = "AerialStream"

    private const val TAIL_BYTES = 1L * 1024 * 1024

    private const val CACHE_BYTES = 64L * 1024 * 1024

    const val KEEP_MAX_BYTES = 3_000_000_000L

    @Volatile
    var keepBytes = CACHE_BYTES
        private set

    @Volatile
    private var cache: SimpleCache? = null
    private val evictor = AerialEvictor { keepBytes }

    private fun cache(context: Context): SimpleCache {
        return cache ?: synchronized(this) {
            cache ?: run {
                val dir = File(context.applicationContext.cacheDir, "aerials")
                val kept = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                keepBytes = ((context.applicationContext.cacheDir.usableSpace + kept) / 4).coerceIn(CACHE_BYTES, KEEP_MAX_BYTES)
                SimpleCache(dir, evictor, StandaloneDatabaseProvider(context.applicationContext)).also { cache = it }
            }
        }
    }

    fun keepLimit(context: Context): Long =
        ((context.applicationContext.cacheDir.usableSpace + (cache?.cacheSpace ?: 0L)) / 4)
            .coerceIn(CACHE_BYTES, KEEP_MAX_BYTES)

    fun usage(context: Context): Pair<Long, Long> = cache(context).cacheSpace to keepBytes

    private fun http() = DefaultHttpDataSource.Factory()
        .setConnectTimeoutMs(5_000)
        .setReadTimeoutMs(8_000)

    fun url(film: Aerial, quality: AerialQuality, overHttp: Boolean): String =
        film.url(quality).let { if (overHttp) it.replaceFirst("https://", "http://") else it }

    private fun key(film: Aerial, quality: AerialQuality) = "${film.id}@${quality.key}"

    fun mediaItem(film: Aerial, quality: AerialQuality, overHttp: Boolean): MediaItem =
        MediaItem.Builder().setUri(url(film, quality, overHttp)).setMediaId(film.id).setCustomCacheKey(key(film, quality)).build()

    fun player(context: Context, quality: AerialQuality, decodeOnScreen: Boolean = false): ExoPlayer {
        val cache = cache(context)
        val source = CacheDataSource.Factory()
            .setCache(cache)
            .setUpstreamDataSourceFactory(http())
            .setCacheWriteDataSinkFactory(CacheDataSink.Factory().setCache(cache))
            .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
        val load = DefaultLoadControl.Builder()
            .setBufferDurationsMs(10_000, 30_000, 1_000, 2_000)
            .setTargetBufferBytes(
                when (quality) {
                    AerialQuality.DolbyVision -> 48
                    AerialQuality.Uhd -> 32
                    else -> 24
                } * 1024 * 1024,
            )
            .setPrioritizeTimeOverSizeThresholds(false)
            .build()
        val builder = if (decodeOnScreen) ExoPlayer.Builder(context, OnScreenRenderers(context)) else ExoPlayer.Builder(context)
        return builder
            .setLoadControl(load)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(source).setLoadErrorHandlingPolicy(DefaultLoadErrorHandlingPolicy(1)),
            )
            .build()
            .apply {
                volume = 0f
                repeatMode = Player.REPEAT_MODE_ALL
                videoScalingMode = C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
            }
    }

    private class OnScreenRenderers(context: Context) : DefaultRenderersFactory(context) {
        override fun buildVideoRenderers(
            context: Context,
            extensionRendererMode: Int,
            mediaCodecSelector: MediaCodecSelector,
            enableDecoderFallback: Boolean,
            eventHandler: Handler,
            eventListener: VideoRendererEventListener,
            allowedVideoJoiningTimeMs: Long,
            out: ArrayList<Renderer>,
        ) {
            out += object : MediaCodecVideoRenderer(
                context, mediaCodecSelector, allowedVideoJoiningTimeMs, enableDecoderFallback,
                eventHandler, eventListener, MAX_DROPPED_VIDEO_FRAME_COUNT_TO_NOTIFY,
            ) {
                override fun shouldUsePlaceholderSurface(codecInfo: MediaCodecInfo) = false
            }
        }
    }

    private const val LEAD_BYTES = 256L * 1024

    suspend fun prepareSeek(context: Context, film: Aerial, quality: AerialQuality, overHttp: Boolean) =
        runInterruptible(Dispatchers.IO) {
            val cache = cache(context)
            val key = key(film, quality)
            val known = ContentMetadata.getContentLength(cache.getContentMetadata(key))
            if (known > TAIL_BYTES && cache.isCached(key, 0, LEAD_BYTES) && cache.isCached(key, known - TAIL_BYTES, TAIL_BYTES)) return@runInterruptible
            val started = SystemClock.elapsedRealtime()
            runCatching {
                val uri = android.net.Uri.parse(url(film, quality, overHttp))
                val length = if (known > 0) known else contentLength(uri)
                ContentMetadataMutations().also { ContentMetadataMutations.setContentLength(it, length) }
                    .let { cache.applyContentMetadataMutations(key, it) }
                val writer = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(http())
                for ((position, size) in listOf(0L to LEAD_BYTES, maxOf(0L, length - TAIL_BYTES) to TAIL_BYTES)) {
                    val spec = DataSpec.Builder().setUri(uri).setKey(key).setPosition(position).setLength(size).build()
                    CacheWriter(writer.createDataSource(), spec, null, null).cache()
                }
                Log.i(TAG, "index of $key fetched in ${SystemClock.elapsedRealtime() - started} ms")
            }.onFailure { Log.w(TAG, "could not fetch the index of $key", it) }
        }

    private const val START_BYTES = 6L * 1024 * 1024

    class Start(val film: String, val quality: AerialQuality, val atMs: Long)

    @Volatile
    var prepared: Start? = null
        private set

    suspend fun prepareStart(context: Context, film: Aerial, quality: AerialQuality, overHttp: Boolean, atMs: Long) {
        prepareSeek(context, film, quality, overHttp)
        runInterruptible(Dispatchers.IO) {
            val cache = cache(context)
            val key = key(film, quality)
            val started = SystemClock.elapsedRealtime()
            runCatching {
                val uri = android.net.Uri.parse(url(film, quality, overHttp))
                val reader = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(http()).setCacheWriteDataSinkFactory(null)
                val seekMap = seekMap(reader, uri, key)
                if (atMs * 1000 > seekMap.durationUs - 3_000_000) error("too near the end")
                val position = seekMap.getSeekPoints(atMs * 1000).first.position
                val writer = CacheDataSource.Factory().setCache(cache).setUpstreamDataSourceFactory(http())
                val spec = DataSpec.Builder().setUri(uri).setKey(key).setPosition(position).setLength(START_BYTES).build()
                CacheWriter(writer.createDataSource(), spec, null, null).cache()
                prepared = Start(film.id, quality, atMs)
                Log.i(TAG, "$key at $atMs ms fetched ahead in ${SystemClock.elapsedRealtime() - started} ms")
            }.onFailure { Log.w(TAG, "could not fetch $key at $atMs ms ahead", it) }
        }
    }

    internal suspend fun keyFrame(context: Context, film: Aerial, atMs: Long, w: Int, h: Int, overHttp: Boolean): FilmKeyFrame.Frame =
        runInterruptible(keyFrames) {
            val tid = android.os.Process.myTid()
            val before = android.os.Process.getThreadPriority(tid)
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            try {
                val reader = CacheDataSource.Factory().setCache(cache(context)).setUpstreamDataSourceFactory(http()).setCacheWriteDataSinkFactory(null)
                val uri = android.net.Uri.parse(url(film, AerialQuality.Hd, overHttp))
                FilmKeyFrame.decode(reader, uri, key(film, AerialQuality.Hd), atMs, w, h)
            } finally {
                android.os.Process.setThreadPriority(before)
            }
        }

    internal suspend fun keyFrameOfFile(file: java.io.File, key: String, atMs: Long, w: Int, h: Int): FilmKeyFrame.Frame =
        runInterruptible(keyFrames) {
            val tid = android.os.Process.myTid()
            val before = android.os.Process.getThreadPriority(tid)
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            try {
                FilmKeyFrame.decode(androidx.media3.datasource.FileDataSource.Factory(), android.net.Uri.fromFile(file), "video:$key", atMs, w, h)
            } finally {
                android.os.Process.setThreadPriority(before)
            }
        }

    private val keyFrames = Dispatchers.IO.limitedParallelism(1)

    private fun seekMap(factory: CacheDataSource.Factory, uri: android.net.Uri, key: String): SeekMap {
        var map: SeekMap? = null
        val extractor = Mp4Extractor(SubtitleParser.Factory.UNSUPPORTED, 0)
        extractor.init(object : ExtractorOutput {
            override fun track(id: Int, type: Int): TrackOutput = DiscardingTrackOutput()
            override fun endTracks() = Unit
            override fun seekMap(seekMap: SeekMap) { map = seekMap }
        })
        val holder = PositionHolder()
        var position = 0L
        repeat(8) {
            val source = factory.createDataSource()
            try {
                val length = source.open(DataSpec.Builder().setUri(uri).setKey(key).setPosition(position).build())
                val input = DefaultExtractorInput(source, position, if (length == C.LENGTH_UNSET.toLong()) C.LENGTH_UNSET.toLong() else position + length)
                var result = Extractor.RESULT_CONTINUE
                while (result == Extractor.RESULT_CONTINUE && map == null) result = extractor.read(input, holder)
                map?.let { return it }
                if (result != Extractor.RESULT_SEEK) error("no index")
                position = holder.position
            } finally {
                source.close()
            }
        }
        error("no index after 8 seeks")
    }

    private fun contentLength(uri: android.net.Uri): Long {
        val source = http().createDataSource()
        try {
            source.open(DataSpec.Builder().setUri(uri).setPosition(0).setLength(1).build())
            val range = source.responseHeaders.entries
                .firstOrNull { it.key.equals("Content-Range", ignoreCase = true) }?.value?.firstOrNull()
            return range?.substringAfterLast('/')?.toLongOrNull() ?: error("no Content-Range: $range")
        } finally {
            source.close()
        }
    }
}

@OptIn(UnstableApi::class)
private class AerialEvictor(private val limit: () -> Long) : CacheEvictor {
    private val spans = java.util.TreeSet<CacheSpan> { a, b ->
        if (a.lastTouchTimestamp != b.lastTouchTimestamp) a.lastTouchTimestamp.compareTo(b.lastTouchTimestamp) else a.compareTo(b)
    }
    private var size = 0L

    override fun requiresCacheSpanTouches() = true
    override fun onCacheInitialized() = Unit

    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        if (length != C.LENGTH_UNSET.toLong()) evict(cache, length)
    }

    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        spans.add(span)
        size += span.length
        evict(cache, 0)
    }

    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        spans.remove(span)
        size -= span.length
    }

    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        onSpanRemoved(cache, oldSpan)
        onSpanAdded(cache, newSpan)
    }

    fun evict(cache: Cache, required: Long) {
        val max = limit()
        while (size + required > max && spans.isNotEmpty()) cache.removeSpan(spans.first())
    }
}
