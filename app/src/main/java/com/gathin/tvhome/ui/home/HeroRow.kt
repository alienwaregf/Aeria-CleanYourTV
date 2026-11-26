package com.gathin.tvhome.ui.home

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import android.util.LruCache
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.gathin.tvhome.R
import com.gathin.tvhome.ratings.Ratings
import com.gathin.tvhome.tmdb.TrendingImages
import com.gathin.tvhome.tmdb.TmdbItem
import com.gathin.tvhome.ui.theme.LocalReducedMotion
import com.gathin.tvhome.ui.theme.Motion
import com.gathin.tvhome.ui.theme.Palette
import com.gathin.tvhome.ui.theme.Type
import com.gathin.tvhome.wallpaper.Images
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal object HeroPosters {
    private const val MAX_BYTES = 2 * (3840 * 2160 * 2 + 320 * 180 * 4)

    data class Entry(val still: Bitmap, val frost: Bitmap)

    private val cache = object : LruCache<String, Entry>(MAX_BYTES) {
        override fun sizeOf(key: String, value: Entry): Int = value.still.byteCount + value.frost.byteCount
    }

    fun peek(url: String, w: Int, h: Int): Bitmap? = synchronized(cache) { cache.get("${w}x${h}:$url")?.still }

    fun peekEntry(url: String, w: Int, h: Int): Entry? = synchronized(cache) { cache.get("${w}x${h}:$url") }

    fun load(url: String, w: Int, h: Int): Bitmap? {
        val key = "${w}x${h}:$url"
        synchronized(cache) { cache.get(key)?.let { return it.still } }
        val still = downloadCover(url, w, h)?.let { bakeScrims(it) } ?: return null
        val frost = Images.frost(still)
        synchronized(cache) {
            cache.get(key)?.let { still.recycle(); frost.recycle(); return it.still }
            cache.put(key, Entry(still, frost))
        }
        return still
    }

    private fun bakeScrims(src: Bitmap): Bitmap {
        val b = if (src.isMutable) src else src.copy(Bitmap.Config.ARGB_8888, true)
        if (b !== src) src.recycle()
        val w = b.width.toFloat()
        val h = b.height.toFloat()
        val c = Canvas(b)
        val p = Paint(Paint.DITHER_FLAG)
        p.shader = LinearGradient(
            0f, 0f, w * 0.75f, 0f,
            intArrayOf(0xCC000000.toInt(), 0x66000000, 0x00000000),
            null, Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 0f, w * 0.75f, h, p)
        p.shader = LinearGradient(
            0f, 0f, 0f, h,
            intArrayOf(0x14000000, 0x40000000, 0xB3000000.toInt()),
            null, Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 0f, w, h, p)
        topShade(c, w, h)
        return b
    }

    private fun topShade(c: Canvas, w: Float, h: Float) {
        val p = Paint(Paint.DITHER_FLAG)
        p.shader = LinearGradient(
            0f, 0f, 0f, h * 0.2f,
            0x66000000, 0x00000000, Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 0f, w, h * 0.2f, p)
    }

    private fun downloadCover(url: String, w: Int, h: Int): Bitmap? {
        val bytes = TrendingImages.bytesFor(url) ?: return null
        return decodeCover(bytes, w, h)
    }

    private fun decodeCover(bytes: ByteArray, w: Int, h: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (true) {
            val doubled = sample * 2
            if (bounds.outWidth / doubled < w || bounds.outHeight / doubled < h) break
            sample = doubled
        }
        while ((bounds.outWidth / sample).toLong() * (bounds.outHeight / sample).toLong() > w.toLong() * h.toLong() * 4) {
            sample *= 2
        }
        val decoded = BitmapFactory.decodeByteArray(
            bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = sample },
        ) ?: return null
        val scale = maxOf(w / decoded.width.toFloat(), h / decoded.height.toFloat())
        val cropW = minOf(decoded.width, maxOf(1, (w / scale).roundToInt()))
        val cropH = minOf(decoded.height, maxOf(1, (h / scale).roundToInt()))
        val left = (decoded.width - cropW) / 2
        val top = (decoded.height - cropH) / 2
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565)
        Canvas(out).drawBitmap(
            decoded,
            Rect(left, top, left + cropW, top + cropH),
            Rect(0, 0, w, h),
            Paint(Paint.FILTER_BITMAP_FLAG),
        )
        if (out !== decoded) decoded.recycle()
        return out
    }
}

private fun downloadBitmap(url: String, maxWidthPx: Int): Bitmap? {
    val bytes = TrendingImages.bytesFor(url) ?: return null
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    var sample = 1
    while (bounds.outWidth / sample > maxWidthPx || bounds.outHeight / sample > maxWidthPx) sample *= 2
    return BitmapFactory.decodeByteArray(
        bytes, 0, bytes.size,
        BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = Bitmap.Config.ARGB_8888
        },
    )
}

internal object TitleLogos {
    private const val MAX_BYTES = 16 * 1024 * 1024

    private val cache = object : LruCache<String, Bitmap>(MAX_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun load(url: String): Bitmap? {
        synchronized(cache) { cache.get(url)?.let { return it } }
        val bmp = downloadBitmap(url, 2560) ?: return null
        synchronized(cache) {
            cache.get(url)?.let { bmp.recycle(); return it }
            cache.put(url, bmp)
        }
        return bmp
    }
}

internal object ProviderLogos {
    private const val MAX_BYTES = 2 * 1024 * 1024

    private val cache = object : LruCache<String, Bitmap>(MAX_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun load(url: String): Bitmap? {
        synchronized(cache) { cache.get(url)?.let { return it } }
        val bmp = downloadBitmap(url, 256) ?: return null
        synchronized(cache) {
            cache.get(url)?.let { bmp.recycle(); return it }
            cache.put(url, bmp)
        }
        return bmp
    }
}

private fun formatRating(voteAverage: Double): String =
    String.format(Locale.US, "%.1f", voteAverage)

@Composable
private fun RatingIcon(res: Int) {
    val painter = painterResource(res)
    val ratio = painter.intrinsicSize.let { if (it.height > 0f) it.width / it.height else 1f }
    Image(
        painter = painter,
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier = Modifier.height(18.dp).aspectRatio(ratio),
    )
}

val HeroRestLift = 24.dp

@Composable
fun HeroRow(
    items: List<TmdbItem>,
    index: Int,
    dir: Int,
    focused: Boolean,
    focusRequester: FocusRequester,
    bottomInset: Dp,
    modifier: Modifier = Modifier,
) {
    if (items.isEmpty()) return
    val item = items[index.coerceIn(items.indices)]
    val safeIndex = index.coerceIn(items.indices)

    LaunchedEffect(focused) {
        if (focused) runCatching { focusRequester.requestFocus() }
    }

    val reduced = LocalReducedMotion.current
    val density = LocalDensity.current
    val infoSlidePx = with(density) { 28.dp.toPx() }
    val slide = rememberTrendingSlideState(item, safeIndex, infoSlidePx)

    LaunchedEffect(item) {
        slide.sync(item, safeIndex)
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

    LaunchedEffect(index, item.backdropUrl) {
        slide.advance(items, index, dir)
    }
    val infoShift by animateDpAsState(
        targetValue = if (focused) bottomInset - 4.dp + HeroRestLift else 0.dp,
        animationSpec = if (reduced) snap() else tween(durationMillis = if (focused) 500 else 450, easing = Motion.Ease),
        label = "hero-info-shift",
    )
    val overviewAlpha by animateFloatAsState(
        targetValue = if (focused) 0.85f else 0f,
        animationSpec = if (reduced) snap() else tween(
            durationMillis = if (focused) 400 else 160,
            delayMillis = if (focused) 120 else 0,
            easing = Motion.Ease,
        ),
        label = "hero-overview-alpha",
    )

    Box(modifier.fillMaxWidth().clipToBounds().focusRequester(focusRequester).focusable()) {
        TrendingSlideImages(slide)
        Column(
            Modifier.align(Alignment.BottomStart)
                .padding(start = 45.dp, end = 160.dp, bottom = bottomInset + 28.dp + HeroRestLift)
                .offset(y = infoShift)
                .graphicsLayer {
                    translationX = slide.infoX.value
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
                RatingIcon(R.drawable.ic_rating_tmdb)
                Spacer(Modifier.width(4.dp))
                BasicText(formatRating(displayedItem.voteAverage), style = Type.Callout)
                val imdb = displayedItem.imdbRating
                if (imdb.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    BasicText("·", style = Type.Callout)
                    Spacer(Modifier.width(8.dp))
                    RatingIcon(R.drawable.ic_rating_imdb)
                    Spacer(Modifier.width(4.dp))
                    BasicText(imdb, style = Type.Callout)
                }
                if (displayedItem.rtPercent >= 0) {
                    Spacer(Modifier.width(8.dp))
                    BasicText("·", style = Type.Callout)
                    Spacer(Modifier.width(8.dp))
                    RatingIcon(Ratings.rtCriticIcon(displayedItem.rtState, displayedItem.rtPercent))
                    Spacer(Modifier.width(4.dp))
                    BasicText(displayedItem.rtPercent.toString() + "%", style = Type.Callout)
                }
                if (displayedItem.rtAudiencePercent >= 0) {
                    Spacer(Modifier.width(8.dp))
                    BasicText("·", style = Type.Callout)
                    Spacer(Modifier.width(8.dp))
                    RatingIcon(Ratings.rtAudienceIcon(displayedItem.rtAudienceState, displayedItem.rtAudiencePercent))
                    Spacer(Modifier.width(4.dp))
                    BasicText(displayedItem.rtAudiencePercent.toString() + "%", style = Type.Callout)
                }
                if (displayedItem.traktRating >= 0.0) {
                    Spacer(Modifier.width(8.dp))
                    BasicText("·", style = Type.Callout)
                    Spacer(Modifier.width(8.dp))
                    RatingIcon(R.drawable.ic_rating_trakt)
                    Spacer(Modifier.width(4.dp))
                    BasicText(formatRating(displayedItem.traktRating), style = Type.Callout)
                }
            }
            if (displayedItem.overview.isNotEmpty() && (focused || overviewAlpha > 0f)) {
                Spacer(Modifier.height(6.dp))
                BasicText(
                    displayedItem.overview,
                    style = Type.Body.copy(color = Palette.SecondaryLabel),
                    modifier = Modifier.graphicsLayer { alpha = overviewAlpha },
                )
                Spacer(Modifier.height(6.dp))
                BasicText(
                    stringResource(R.string.trailer_hint),
                    style = Type.Callout.copy(color = Palette.Label),
                    modifier = Modifier.graphicsLayer { alpha = overviewAlpha },
                )
            }
        }
    }
}
