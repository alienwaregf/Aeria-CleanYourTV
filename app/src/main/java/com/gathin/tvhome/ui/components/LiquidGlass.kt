package com.gathin.tvhome.ui.components

import android.graphics.BitmapShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.layer.CompositingStrategy
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import com.gathin.tvhome.settings.AerialSettings
import com.gathin.tvhome.ui.theme.Palette
import com.gathin.tvhome.ui.theme.Motion
import com.gathin.tvhome.wallpaper.Wallpapers
import kotlin.math.roundToInt

fun Modifier.liquidGlass(corner: Dp, bottomCorners: Boolean = true, seeThroughFilm: Boolean = false): Modifier = composed {
    val solid = Wallpapers.moves() && Wallpapers.filmGlassless && Wallpapers.frosted == null
    val latest = (if (Wallpapers.moves() && Wallpapers.filmGlassless) Wallpapers.filmFrost ?: Wallpapers.frosted else Wallpapers.frosted)
        .takeIf { Wallpapers.backdrop != null }
    val held = remember(Wallpapers.selectedId, latest) { latest }
    val (frost, under, swap) = rememberFrostSwap(held)
    var at by remember { mutableStateOf(Offset.Zero) }
    var root by remember { mutableStateOf(IntSize.Zero) }
    val shape = if (bottomCorners) RoundedCornerShape(corner) else RoundedCornerShape(topStart = corner, topEnd = corner)
    this
        .onGloballyPositioned { c ->
            at = c.positionInRoot()
            var r = c
            while (true) r = r.parentLayoutCoordinates ?: break
            root = r.size
        }
        .clip(shape)
        .drawWithCache {
            val shader = frost?.let { BitmapShader(it.asAndroidBitmap(), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
            val underShader = under?.let { BitmapShader(it.asAndroidBitmap(), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
            val radius = corner.toPx()
            val w = size.width.roundToInt().coerceAtLeast(1)
            val h = size.height.roundToInt().coerceAtLeast(1)
            val mask = smoothMask(w, h, radius, if (bottomCorners) radius else 0f)
            val brush = shader?.let { ShaderBrush(it) }
            val flat = mask.asImageBitmap()
            val glass = obtainGraphicsLayer().apply { compositingStrategy = CompositingStrategy.Offscreen }
            var drawnAt: Offset? = null
            var drawnRoot = IntSize.Zero
            var drawnT = -1f
            onDrawBehind {
                if (solid) {
                    drawImage(flat, dstSize = IntSize(w, h), colorFilter = ColorFilter.tint(if (seeThroughFilm) FilmGlass else FilmPanel))
                    if (seeThroughFilm) drawImage(filmSheen, dstSize = IntSize(w, h), filterQuality = FilterQuality.Low)
                    drawGlassEdge(size, radius, bottomCorners)
                } else if (shader == null || brush == null || root == IntSize.Zero) {
                    drawImage(flat, dstSize = IntSize(w, h), colorFilter = ColorFilter.tint(Palette.Glass))
                    drawGlassEdge(size, radius, bottomCorners)
                } else if (AerialSettings.lite) {
                    drawImage(flat, dstSize = IntSize(w, h), colorFilter = ColorFilter.tint(liteGlass(frost)))
                    drawGlassEdge(size, radius, bottomCorners)
                } else {
                    val t = swap()
                    if (drawnAt != at || drawnRoot != root || drawnT != t) {
                        drawnAt = at
                        drawnRoot = root
                        drawnT = t
                        fun place(s: BitmapShader, image: ImageBitmap) = s.setLocalMatrix(Matrix().apply {
                            setScale(root.width.toFloat() / image.width, root.height.toFloat() / image.height)
                            postTranslate(-at.x, -at.y)
                        })
                        place(shader, frost)
                        glass.record(this, layoutDirection, IntSize(w, h)) {
                            drawImage(flat, dstSize = IntSize(w, h))
                            if (underShader != null && under != null && t < 1f) {
                                place(underShader, under)
                                drawRect(ShaderBrush(underShader), colorFilter = PanelTint, blendMode = BlendMode.SrcIn)
                                drawRect(brush, alpha = t, colorFilter = PanelTint, blendMode = BlendMode.SrcAtop)
                            } else {
                                drawRect(brush, colorFilter = PanelTint, blendMode = BlendMode.SrcIn)
                            }
                            drawGlassEdge(size, radius, bottomCorners)
                        }
                    }
                    drawLayer(glass)
                }
            }
        }
}

@Composable
internal fun rememberFrostSwap(frost: ImageBitmap?): Triple<ImageBitmap?, ImageBitmap?, () -> Float> {
    var shown by remember { mutableStateOf(frost) }
    var under by remember { mutableStateOf<ImageBitmap?>(null) }
    val swap = remember { Animatable(1f) }
    LaunchedEffect(frost) {
        if (frost === shown) return@LaunchedEffect
        val old = shown
        shown = frost
        if (old == null || frost == null) {
            under = null
            swap.snapTo(1f)
            return@LaunchedEffect
        }
        under = if (swap.value > 0.5f) old else under ?: old
        swap.snapTo(0f)
        swap.animateTo(1f, tween(FROST_SWAP_MS, easing = Motion.EaseOut))
        under = null
    }
    return Triple(shown, under, { swap.value })
}

private const val FROST_SWAP_MS = 450

private fun DrawScope.drawGlassEdge(size: Size, radius: Float, bottomCorners: Boolean) {
    val w = size.width.roundToInt()
    val h = size.height.roundToInt()
    if (w <= 0 || h <= 0) return
    drawImage(glassEdge(w, h, radius, 1.2.dp.toPx(), bottomCorners), dstSize = IntSize(w, h))
}

fun glassEdge(w: Int, h: Int, radius: Float, rim: Float, bottomCorners: Boolean = true): ImageBitmap =
    baked(listOf("glass-edge", w, h, radius, rim, bottomCorners)) {
        val bitmap = createBitmap(w, h)
        val canvas = android.graphics.Canvas(bitmap)
        val bottom = if (bottomCorners) h.toFloat() else h + radius
        fun white(alpha: Float) = Color.White.copy(alpha = alpha).toArgb()
        canvas.drawPath(
            smoothRectPath(Rect(0f, 0f, w.toFloat(), bottom), radius).asAndroidPath(),
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                shader = LinearGradient(0f, 0f, 0f, bottom, intArrayOf(white(0.10f), 0), floatArrayOf(0f, 0.5f), Shader.TileMode.CLAMP)
            },
        )
        canvas.drawPath(
            smoothRectPath(Rect(rim / 2, rim / 2, w - rim / 2, bottom - rim / 2), radius - rim / 2).asAndroidPath(),
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = rim
                shader = LinearGradient(
                    0f, 0f, w.toFloat(), bottom,
                    intArrayOf(white(0.55f), white(0.10f), white(0.06f), white(0.30f)),
                    floatArrayOf(0f, 0.35f, 0.7f, 1f),
                    Shader.TileMode.CLAMP,
                )
            },
        )
        bitmap.frozen().asImageBitmap()
    }

private fun liteGlass(frost: ImageBitmap): Color = synchronized(liteColors) {
    liteColors.getOrPut(frost) {
        val small = android.graphics.Bitmap.createScaledBitmap(frost.asAndroidBitmap(), 16, 9, true)
        var r = 0f; var g = 0f; var b = 0f
        for (y in 0 until 9) for (x in 0 until 16) {
            val c = small.getPixel(x, y)
            r += android.graphics.Color.red(c); g += android.graphics.Color.green(c); b += android.graphics.Color.blue(c)
        }
        fun dim(sum: Float) = ((sum / 144f) * 0.48f + 14f).coerceIn(0f, 255f) / 255f
        Color(dim(r), dim(g), dim(b))
    }
}

private val liteColors = java.util.WeakHashMap<ImageBitmap, Color>()

private val FilmPanel = Color(0xFF4A4B51)

private val FilmGlass = Color(red = 62, green = 63, blue = 69, alpha = 189)

private val filmSheen: ImageBitmap by lazy {
    val h = 96
    val bitmap = android.graphics.Bitmap.createBitmap(1, h, android.graphics.Bitmap.Config.ARGB_8888)
    for (y in 0 until h) {
        val t = (1f - (y + 0.5f) / h / 0.45f).coerceIn(0f, 1f)
        bitmap.setPixel(0, y, android.graphics.Color.argb((t * t * 0.10f * 255).roundToInt(), 255, 255, 255))
    }
    bitmap.asImageBitmap()
}

private val PanelTint = ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(
    0.48f, 0f, 0f, 0f, 14f,
    0f, 0.48f, 0f, 0f, 14f,
    0f, 0f, 0.48f, 0f, 14f,
    0f, 0f, 0f, 1f, 0f,
)))
