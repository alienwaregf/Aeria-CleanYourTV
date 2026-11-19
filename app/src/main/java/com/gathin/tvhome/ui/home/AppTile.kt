package com.gathin.tvhome.ui.home

import android.graphics.BitmapShader
import android.graphics.BlurMaskFilter
import android.graphics.ComposeShader
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.Shader
import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.GraphicsLayerScope
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.core.graphics.createBitmap
import com.gathin.tvhome.apps.AppEntry
import com.gathin.tvhome.apps.TileArt
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.mutableIntStateOf
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import com.gathin.tvhome.settings.AerialSettings
import com.gathin.tvhome.settings.FocusStyle
import com.gathin.tvhome.system.Sounds
import com.gathin.tvhome.ui.components.isSelect
import com.gathin.tvhome.ui.components.baked
import com.gathin.tvhome.ui.components.frozen
import com.gathin.tvhome.ui.components.smoothMask
import com.gathin.tvhome.ui.components.smoothRectPath
import com.gathin.tvhome.system.DiagLog
import com.gathin.tvhome.ui.theme.LocalReducedMotion
import com.gathin.tvhome.ui.theme.Motion
import com.gathin.tvhome.ui.theme.Type
import com.gathin.tvhome.ui.components.DiagLayers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.roundToInt

private const val FOCUSED_SCALE = 1.15f
private const val PRESSED_SCALE = 1.05f

fun Rect.asFocusedTile(): Rect {
    val dx = width * (FOCUSED_SCALE - 1f) / 2f
    val dy = height * (FOCUSED_SCALE - 1f) / 2f
    return Rect(left - dx, top - dy, right + dx, bottom + dy)
}

private val SineEasing = Easing { t -> (1f - cos(PI * t).toFloat()) / 2f }
private val Corner = 12.dp
private const val RIM_AT_REST = 0.07f

private val RingGap = 5.dp
private val ShadowBlur = 14.dp
private val ShadowDrop = 12.dp
private val RingWidth = 3.dp

@Composable
fun AppTile(
    app: AppEntry,
    width: Dp,
    height: Dp,
    focusRequester: FocusRequester,
    enabled: Boolean,
    moving: Boolean,
    opacity: () -> Float = { 1f },
    onFocused: () -> Unit,
    onClick: (Rect?) -> Unit,
    onLongPress: (Rect?) -> Unit,
    modifier: Modifier = Modifier,
    rowEnd: Boolean = false,
    showName: Boolean = true,
    nameOnPlate: Boolean = false,
) {
    val reduced = LocalReducedMotion.current
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    var focused by remember { mutableStateOf(false) }
    val rawLogged = remember { booleanArrayOf(false) }
    var pressed by remember { mutableStateOf(false) }
    var bounds by remember { mutableStateOf<Rect?>(null) }
    var longPressJob by remember { mutableStateOf<Job?>(null) }
    var longFired by remember { mutableStateOf(false) }
    var menuPressed by remember { mutableStateOf(false) }
    val click by rememberUpdatedState(onClick)
    val longPress by rememberUpdatedState(onLongPress)

    val leanY = remember { Animatable(0f) }
    val leanX = remember { Animatable(0f) }
    val push = remember { Animatable(Offset.Zero, Offset.VectorConverter) }

    val scale by animateFloatAsState(
        targetValue = when {
            reduced -> 1f
            pressed -> PRESSED_SCALE
            focused || moving -> FOCUSED_SCALE
            else -> 1f
        },
        animationSpec = if (pressed) tween(110, easing = Motion.EaseOut) else spring(dampingRatio = 1f, stiffness = 520f),
        label = "tile-scale",
    )
    val liftState = animateFloatAsState(
        targetValue = if (focused || moving) 1f else 0f,
        animationSpec = spring(dampingRatio = 1f, stiffness = 380f),
        label = "tile-lift",
    )
    val lift by liftState
    val ringState = animateFloatAsState(
        targetValue = if (AerialSettings.focusStyle == FocusStyle.HighContrast) 1f else 0f,
        animationSpec = tween(200, easing = Motion.EaseOut),
        label = "tile-ring",
    )
    val ring by ringState
    val ringInState = animateFloatAsState(
        targetValue = if (focused || moving) 1f else 0f,
        animationSpec = if (focused || moving) spring(dampingRatio = 1f, stiffness = 700f) else spring(dampingRatio = 1f, stiffness = 2400f),
        label = "tile-ring-in",
    )
    val ringIn by ringInState
    val showShadow by remember { derivedStateOf { liftState.value > 0.01f } }
    val showFrame by remember { derivedStateOf { ringState.value > 0.01f && ringInState.value > 0.01f } }

    val wobble = if (moving && !reduced) {
        rememberInfiniteTransition(label = "jiggle").animateFloat(
            initialValue = -1.3f, targetValue = 1.3f,
            animationSpec = infiniteRepeatable(tween(130, easing = SineEasing), RepeatMode.Reverse),
            label = "jiggle-angle",
        )
    } else null

    fun nudge(dx: Float, dy: Float) {
        if (reduced) return
        scope.launch {
            push.animateTo(Offset(dx * 14f, dy * 14f), tween(90, easing = Motion.EaseOut))
            push.animateTo(Offset.Zero, spring(dampingRatio = 0.65f, stiffness = 520f))
        }
        scope.launch {
            leanY.animateTo(dx * 5f, tween(90, easing = Motion.EaseOut))
            leanY.animateTo(0f, spring(dampingRatio = 0.65f, stiffness = 420f))
        }
        scope.launch {
            leanX.animateTo(-dy * 5f, tween(90, easing = Motion.EaseOut))
            leanX.animateTo(0f, spring(dampingRatio = 0.65f, stiffness = 420f))
        }
    }

    Box(modifier.size(width, height)) {
        if (showShadow && !AerialSettings.lite && DiagLayers.shows("shadow")) {
            Canvas(
                Modifier
                    .size(width, height)
                    .graphicsLayer {
                        zoom(scale)
                        rotationY = leanY.value
                        rotationX = leanX.value
                        rotationZ = wobble?.value ?: 0f
                        translationX = push.value.x
                        translationY = push.value.y + ShadowDrop.toPx() * lift
                        cameraDistance = 14f * density
                        alpha = lift * opacity()
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    },
            ) {
                val shadow = tileShadow(size.width.roundToInt(), size.height.roundToInt(), Corner.toPx(), ShadowBlur.toPx())
                translate(-shadow.ext, -shadow.ext) { drawImage(shadow.image) }
            }
        }
        Box(
            Modifier
                .size(width, height)
                .onGloballyPositioned { bounds = it.boundsInWindow() }
                .graphicsLayer {
                    alpha = opacity()
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                    zoom(scale)
                    rotationY = leanY.value
                    rotationX = leanX.value
                    rotationZ = wobble?.value ?: 0f
                    translationX = push.value.x
                    translationY = push.value.y
                    cameraDistance = 14f * density
                    shape = RoundedCornerShape(Corner)
                    clip = true
                }
                .focusRequester(focusRequester)
                .onFocusChanged {
                    if (it.isFocused && !focused) Sounds.focusMoved()
                    focused = it.isFocused
                    if (it.isFocused) onFocused() else {
                        pressed = false
                        longPressJob?.cancel()
                    }
                }
                .onKeyEvent { e ->
                    val code = e.nativeKeyEvent.keyCode
                    val down = e.type == KeyEventType.KeyDown
                    val dir = when (code) {
                        NativeKeyEvent.KEYCODE_DPAD_LEFT -> FocusDirection.Left
                        NativeKeyEvent.KEYCODE_DPAD_RIGHT -> FocusDirection.Right
                        NativeKeyEvent.KEYCODE_DPAD_UP -> FocusDirection.Up
                        NativeKeyEvent.KEYCODE_DPAD_DOWN -> FocusDirection.Down
                        else -> null
                    }
                    if (dir != null) {
                        if (down && !focusManager.moveFocus(dir) && e.nativeKeyEvent.repeatCount == 0) {
                            Sounds.limit()
                            when (dir) {
                                FocusDirection.Left -> nudge(-1f, 0f)
                                FocusDirection.Right -> nudge(1f, 0f)
                                FocusDirection.Up -> nudge(0f, -1f)
                                else -> nudge(0f, 1f)
                            }
                        }
                        return@onKeyEvent true
                    }
                    if (e.nativeKeyEvent.keyCode == NativeKeyEvent.KEYCODE_MENU) {
                        if (down && e.nativeKeyEvent.repeatCount == 0) menuPressed = true
                        else if (e.type == KeyEventType.KeyUp && menuPressed) {
                            menuPressed = false
                            Sounds.select()
                            onLongPress(bounds)
                        }
                        return@onKeyEvent true
                    }
                    if (!e.isSelect()) return@onKeyEvent false
                    if (down) {
                        if (e.nativeKeyEvent.repeatCount > 0 && pressed && !longFired) {
                            longPressJob?.cancel()
                            longFired = true
                            pressed = false
                            Sounds.select()
                            onLongPress(bounds)
                        } else if (e.nativeKeyEvent.repeatCount == 0) {
                            pressed = true
                            longFired = false
                            longPressJob?.cancel()
                            longPressJob = scope.launch {
                                delay(550)
                                if (pressed) {
                                    longFired = true
                                    pressed = false
                                    Sounds.select()
                                    onLongPress(bounds)
                                }
                            }
                        }
                    } else if (e.type == KeyEventType.KeyUp) {
                        longPressJob?.cancel()
                        if (pressed && !longFired) {
                            Sounds.select()
                            onClick(bounds)
                        }
                        pressed = false
                    }
                    true
                }
                .pointerInput(enabled) {
                    if (!enabled) return@pointerInput
                    detectTapGestures(
                        onPress = {
                            pressed = true
                            tryAwaitRelease()
                            pressed = false
                        },
                        onTap = {
                            runCatching { focusRequester.requestFocus() }
                            Sounds.select()
                            click(bounds)
                        },
                        onLongPress = {
                            pressed = false
                            runCatching { focusRequester.requestFocus() }
                            Sounds.select()
                            longPress(bounds)
                        },
                    )
                }
                .focusProperties { if (rowEnd) right = FocusRequester.Cancel }
                .focusable(enabled),
        ) {
            Spacer(Modifier.fillMaxSize().drawWithCache {
                val art = app.art
                val w = size.width.roundToInt()
                val h = size.height.roundToInt()
                val full = IntSize(w, h)
                val fw = focusedPx(size.width)
                val fh = focusedPx(size.height)
                fun face(fw: Int, fh: Int): ImageBitmap? = TileFaces.ready(art.back, fw, fh)
                    ?: if (TileFaces.background) null
                    else TileFaces.face(art.back, fw, fh, Corner.toPx() * fw / w, 0.5.dp.toPx() * fw / w)
                val shape = BitmapShader(smoothMask(w, h, Corner.toPx()), Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                    .apply { setLocalMatrix(Matrix().apply { setScale(size.width / w, size.height / h) }) }
                val rim = tileRim(w, h, Corner.toPx(), 0.5.dp.toPx())
                onDrawBehind {
                    val lifted = scale > (1f + FOCUSED_SCALE) / 2f
                    val pw = if (lifted) fw else w
                    val ph = if (lifted) fh else h
                    TileFaces.version
                    val lite = AerialSettings.lite
                    scale(size.width / pw, size.height / ph, pivot = Offset.Zero) {
                        val made = face(pw, ph)
                        if (made != null) drawImage(made)
                        else {
                            if (!rawLogged[0] && (focused || lift > 0.01f)) { rawLogged[0] = true; DiagLog.i("tile ${app.label} drawn before its face, ${pw}x$ph") }
                            clipPath(smoothRectPath(Rect(0f, 0f, pw.toFloat(), ph.toFloat()), Corner.toPx() * pw / size.width)) {
                                drawImage(art.back, dstSize = IntSize(pw, ph), filterQuality = FilterQuality.Medium)
                            }
                        }
                        art.fore?.let { fore ->
                            val at = art.foreAt
                            val px = if (lite) 0f else leanY.value / 7f * pw * 0.035f
                            val py = if (lite) 0f else -leanX.value / 7f * ph * 0.035f
                            drawImage(
                                fore,
                                dstOffset = IntOffset((at.left * pw + px).roundToInt(), (at.top * ph + py).roundToInt()),
                                dstSize = IntSize((at.width * pw).roundToInt(), (at.height * ph).roundToInt()),
                                filterQuality = FilterQuality.Medium,
                            )
                        }
                    }
                    if (lift > 0.01f && !lite) {
                        val glintX = size.width * (0.3f + leanY.value / 7f * 0.25f)
                        val glint = RadialGradient(
                            glintX, -size.height * 0.25f, size.width * 0.75f,
                            Color.White.copy(alpha = 0.28f * lift).toArgb(), Color.Transparent.toArgb(), Shader.TileMode.CLAMP,
                        )
                        drawRect(ShaderBrush(ComposeShader(glint, shape, PorterDuff.Mode.DST_IN)), blendMode = BlendMode.Screen)
                    }
                    if (lift > 0.01f) drawImage(rim, dstSize = full, alpha = 0.08f * lift)
                }
            })
        }

        if (showFrame) {
            Canvas(
                Modifier
                    .size(width, height)
                    .graphicsLayer {
                        zoom(scale)
                        rotationY = leanY.value
                        rotationX = leanX.value
                        rotationZ = wobble?.value ?: 0f
                        translationX = push.value.x
                        translationY = push.value.y
                        cameraDistance = 14f * density
                        alpha = ring * ringIn * opacity()
                        compositingStrategy = CompositingStrategy.ModulateAlpha
                    },
            ) {
                val frame = ringFrame(size.width.roundToInt(), size.height.roundToInt(), Corner.toPx(), RingGap.toPx(), RingWidth.toPx(), 1.dp.toPx())
                val e = frame.ext - RingGap.toPx() * 0.6f * (1f - ringIn)
                translate(-e, -e) {
                    scale((size.width + 2 * e) / frame.image.width, (size.height + 2 * e) / frame.image.height, pivot = Offset.Zero) {
                        drawImage(frame.image)
                    }
                }
            }
        }

        if (showName && DiagLayers.shows("names")) BasicText(
            text = app.label,
            style = Type.Headline.copy(
                textAlign = TextAlign.Center,
                shadow = Shadow(Color.Black.copy(alpha = 0.6f), blurRadius = 8f),
            ),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = height + 10.dp)
                .requiredSize(width + 36.dp, 24.dp)
                .graphicsLayer {
                    alpha = (if (AerialSettings.showAllNames) 0.7f + 0.3f * lift else lift) * opacity()
                    compositingStrategy = CompositingStrategy.ModulateAlpha
                    translationY = height.toPx() * (scale - 1f) / 2f - (1f - lift) * 6.dp.toPx() +
                        if (nameOnPlate) 0f else ring * ringIn * (RingGap + RingWidth).toPx() * scale
                },
        )
    }
}


private fun focusedPx(px: Float): Int {
    val r = px.roundToInt()
    return r + 2 * (r * (FOCUSED_SCALE - 1f) / 2f).roundToInt()
}

private fun GraphicsLayerScope.zoom(scale: Float) {
    val t = (scale - 1f) / (FOCUSED_SCALE - 1f)
    scaleX = 1f + t * (focusedPx(size.width) / size.width - 1f)
    scaleY = 1f + t * (focusedPx(size.height) / size.height - 1f)
}

object TileFaces {
    private val made = java.util.WeakHashMap<ImageBitmap, HashMap<Long, ImageBitmap>>()

    var version by mutableIntStateOf(0); private set

    @Volatile var background = false; private set

    @Volatile private var restKey = 0L

    fun trimMemory() = synchronized(made) {
        val keep = restKey
        for (sizes in made.values) sizes.keys.retainAll { it == keep }
    }

    fun ready(back: ImageBitmap, w: Int, h: Int): ImageBitmap? =
        synchronized(made) { made[back]?.get((w.toLong() shl 32) or h.toLong()) }

    fun face(back: ImageBitmap, w: Int, h: Int, radius: Float, rimWidth: Float): ImageBitmap {
        val key = (w.toLong() shl 32) or h.toLong()
        synchronized(made) { made[back]?.get(key) }?.let { return it }
        val face = tileFace(back, w, h, radius, rimWidth)
        synchronized(made) {
            made[back]?.get(key)?.let { face.asAndroidBitmap().recycle(); return it }
            made.getOrPut(back) { HashMap(2) }[key] = face
        }
        return face
    }

    suspend fun prepare(arts: List<TileArt>, screenW: Int, density: Float, focused: Boolean = false) = coroutineScope {
        val tileW = restTileWidth((screenW / density).dp, AerialSettings.homeColumns).value * density
        val tileH = tileW * 9f / 16f
        val w = tileW.roundToInt()
        val h = tileH.roundToInt()
        val fw = if (focused) focusedPx(tileW) else w
        val fh = if (focused) focusedPx(tileH) else h
        val radius = Corner.value * density * fw / w
        val rim = 0.5f * density * fw / w
        if (!focused) {
            val key = (w.toLong() shl 32) or h.toLong()
            val resized = restKey != 0L && restKey != key
            restKey = key
            if (resized) trimMemory()
        }
        val todo = arts.distinctBy { System.identityHashCode(it.back) }.filter { ready(it.back, fw, fh) == null }
        if (todo.isEmpty()) return@coroutineScope
        if (!focused) background = true
        try {
            for (batch in todo.chunked(BATCH)) {
                batch.map { art -> async { runCatching { face(art.back, fw, fh, radius, rim) } } }.awaitAll()
                withContext(Dispatchers.Main) { version++ }
            }
        } finally {
            if (!focused) background = false
        }
    }

    private const val BATCH = 5
}

private fun tileFace(back: ImageBitmap, w: Int, h: Int, radius: Float, rimWidth: Float): ImageBitmap {
    val src = back.asAndroidBitmap()
    val bitmap = createBitmap(w, h)
    android.graphics.Canvas(bitmap).apply {
        val fill = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
            shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
                .apply { setLocalMatrix(Matrix().apply { setScale(w.toFloat() / src.width, h.toFloat() / src.height) }) }
        }
        drawPath(smoothRectPath(Rect(0f, 0f, w.toFloat(), h.toFloat()), radius).asAndroidPath(), fill)
        drawBitmap(tileRim(w, h, radius, rimWidth, RIM_AT_REST).asAndroidBitmap(), 0f, 0f, null)
    }
    return bitmap.frozen().asImageBitmap()
}

private fun tileRim(w: Int, h: Int, radius: Float, width: Float, alpha: Float = 1f): ImageBitmap = baked(listOf("tile-rim", w, h, radius, width, alpha)) {
    val bitmap = createBitmap(w, h)
    android.graphics.Canvas(bitmap).apply {
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.White.copy(alpha = alpha).toArgb() }
        drawPath(smoothRectPath(Rect(0f, 0f, w.toFloat(), h.toFloat()), radius).asAndroidPath(), white)
        val inner = smoothRectPath(Rect(width, width, w - width, h - width), radius - width).asAndroidPath()
        drawPath(inner, Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) })
    }
    bitmap.frozen().asImageBitmap()
}

private class TileShadow(val image: ImageBitmap, val ext: Float)

private fun tileShadow(w: Int, h: Int, corner: Float, blur: Float): TileShadow = baked(listOf("shadow", w, h, corner, blur)) {
    val ext = ceil(blur * 1.5f)
    val bitmap = createBitmap(w + 2 * ext.toInt(), h + 2 * ext.toInt())
    android.graphics.Canvas(bitmap).apply {
        translate(ext, ext)
        drawPath(
            smoothRectPath(Rect(0f, 0f, w.toFloat(), h.toFloat()), corner).asAndroidPath(),
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = Color.Black.copy(alpha = 0.7f).toArgb()
                maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
            },
        )
    }
    TileShadow(bitmap.frozen().asImageBitmap(), ext)
}

private class RingFrame(val image: ImageBitmap, val ext: Float)

private fun ringFrame(w: Int, h: Int, corner: Float, gap: Float, width: Float, dp: Float): RingFrame =
    baked(listOf("ring", w, h, corner, gap, width, dp)) {
        val out = gap + width / 2
        val halo = gap + width + dp
        val ext = ceil(halo + dp + 1f)
        val bitmap = createBitmap(w + 2 * ext.toInt(), h + 2 * ext.toInt())
        val canvas = android.graphics.Canvas(bitmap).apply { translate(ext, ext) }
        fun around(d: Float) = smoothRectPath(Rect(-d, -d, w + d, h + d), corner + d).asAndroidPath()
        fun line(lineWidth: Float, color: Color) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = lineWidth
            this.color = color.toArgb()
        }
        canvas.drawPath(around(halo), line(2 * dp, Color.Black.copy(alpha = 0.3f)))
        canvas.drawPath(around(out), line(width, Color.White).apply {
            shader = LinearGradient(
                -out, -out, w + out, h + out,
                intArrayOf(Color.White.toArgb(), Color.White.copy(alpha = 0.78f).toArgb(), Color.White.copy(alpha = 0.94f).toArgb()),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP,
            )
        })
        canvas.drawPath(around(out - width / 2 - 0.75f * dp), line(dp, Color.White.copy(alpha = 0.35f)))
        RingFrame(bitmap.frozen().asImageBitmap(), ext)
    }
