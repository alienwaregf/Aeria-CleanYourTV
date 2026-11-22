package com.gathin.tvhome.ui.home

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.text.format.DateFormat
import android.graphics.PorterDuff
import android.graphics.Matrix
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.togetherWith
import androidx.compose.animation.fadeIn
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.gathin.tvhome.ui.components.smoothBorder
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.composed
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.findRootCoordinates
import androidx.compose.ui.layout.AlignmentLine
import androidx.compose.ui.layout.FirstBaseline
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.TextUnit
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gathin.tvhome.R
import com.gathin.tvhome.settings.AerialSettings
import com.gathin.tvhome.settings.ClockHours
import com.gathin.tvhome.wallpaper.Images
import com.gathin.tvhome.system.NetKind
import com.gathin.tvhome.system.NetworkStatus
import com.gathin.tvhome.ui.components.Dissolve
import com.gathin.tvhome.ui.components.Pressable
import com.gathin.tvhome.ui.components.frozen
import com.gathin.tvhome.ui.components.glassEdge
import com.gathin.tvhome.ui.components.rememberFrostSwap
import com.gathin.tvhome.ui.components.smoothMask
import com.gathin.tvhome.ui.theme.Glyph
import com.gathin.tvhome.ui.theme.GlyphIcon
import com.gathin.tvhome.ui.theme.LocalReducedMotion
import com.gathin.tvhome.ui.theme.Motion
import com.gathin.tvhome.wallpaper.Wallpapers
import com.gathin.tvhome.ui.theme.Palette
import com.gathin.tvhome.ui.theme.clockTextColor
import com.gathin.tvhome.ui.theme.Type
import com.gathin.tvhome.ui.components.diagLayer
import com.gathin.tvhome.weather.Weather
import com.gathin.tvhome.weather.WeatherKind
import com.gathin.tvhome.weather.WeatherNow
import com.gathin.tvhome.weather.kindNameRes
import java.text.SimpleDateFormat
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun rememberNow(): State<LocalDateTime> {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val now = remember { mutableStateOf(LocalDateTime.now()) }
    DisposableEffect(context, lifecycle) {
        fun refresh() { now.value = LocalDateTime.now() }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = refresh()
        }
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_TIME_TICK)
            addAction(Intent.ACTION_TIME_CHANGED)
            addAction(Intent.ACTION_TIMEZONE_CHANGED)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        var registered = false
        fun stop() {
            if (registered) {
                context.unregisterReceiver(receiver)
                registered = false
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
                    registered = true
                    refresh()
                }
                Lifecycle.Event.ON_RESUME -> refresh()
                Lifecycle.Event.ON_STOP -> stop()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            stop()
        }
    }
    return now
}

@Composable
fun timeText(now: LocalDateTime): String {
    val context = LocalContext.current
    val twelve = twelveHour(context, AerialSettings.clockHours)
    val pattern = if (twelve) "h:mm" else "H:mm"
    return now.format(DateTimeFormatter.ofPattern(pattern))
}

internal fun twelveHour(context: Context, hours: ClockHours): Boolean = when (hours) {
    ClockHours.Twelve -> true
    ClockHours.TwentyFour -> false
    ClockHours.System -> runCatching { android.provider.Settings.System.getString(context.contentResolver, android.provider.Settings.System.TIME_12_24) }.getOrNull() == "12"
}

@Composable
fun dateText(now: LocalDateTime): String {
    val locale = Locale.getDefault()
    val pattern = remember(locale) {
        DateFormat.getBestDateTimePattern(locale, "MMMMdEEEE")
            .replace(Regex("(?<=[^\\s,，'])EEEE"), " EEEE")
            .replace(Regex("EEEE(?=[^\\s,，'])"), "EEEE ")
    }
    val millis = now.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
    return SimpleDateFormat(pattern, locale).format(Date(millis))
}

@Composable
fun HomeBackground(
    backdrop: ImageBitmap?,
    frosted: ImageBitmap?,
    plate: Rect?,
    scroll: () -> Float,
    collapse: Float,
    cover: () -> Float = { 0f },
    shadeMoving: Boolean = false,
    filmStopped: () -> Float = { 0f },
    toSaver: () -> Float = { 0f },
    filmGlass: ImageBitmap? = null,
    lift: () -> Float = { 0f },
    plateSlide: () -> Float = { 0f },
    wallpaper: String? = null,
    glassAwaitsStop: Boolean = false,
    gridGlass: ImageBitmap? = null,
    middle: @Composable () -> Unit = {},
    hero: @Composable () -> Unit = {},
) {
    val shade = remember { Images.homeShade.asImageBitmap() }
    Box(Modifier.fillMaxSize()) {
        if (shadeMoving) Canvas(Modifier.fillMaxSize().diagLayer("shade")) {
            val stopped = maxOf(filmStopped(), cover())
            if (stopped < 0.01f || cover() < 0.01f || scroll() > collapse * 0.7f) return@Canvas
            drawImage(shade, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = FilterQuality.Low, alpha = stopped)
        }
        Box(Modifier.fillMaxSize().diagLayer("picture")) {
            Pictures(backdrop, gridGlass ?: frosted, filmGlass.takeIf { glassAwaitsStop }, wallpaper, scroll, collapse, cover, filmStopped, toSaver)
        }
        Box(Modifier.fillMaxSize()) { middle() }
        Box(Modifier.fillMaxSize()) { hero() }
        Box(Modifier.fillMaxSize().diagLayer("plate")) {
            val still = Wallpapers.filmGlassless && Wallpapers.moves() && (frosted ?: backdrop) != null
            val film = filmGlass.takeUnless { Wallpapers.filmGlassless }
            Plate(if (still) filmGlass ?: frosted ?: backdrop else frosted ?: backdrop, film, film != null, Wallpapers.filmGlassless, plate, scroll, collapse, cover, filmStopped, toSaver, lift, still, slide = plateSlide)
        }
    }
}

@Composable
private fun Pictures(
    backdrop: ImageBitmap?,
    frosted: ImageBitmap?,
    filmGlass: ImageBitmap?,
    wallpaper: String?,
    scroll: () -> Float,
    collapse: Float,
    cover: () -> Float,
    filmStopped: () -> Float,
    toSaver: () -> Float,
) {
    fun gridGlass(): Float = (((scroll() / collapse).coerceIn(0f, 1f) - 0.3f) / 0.4f).coerceIn(0f, 1f) * (1f - toSaver())
    fun moving(): Boolean = filmGlass != null && cover() > 0.01f && filmStopped() < 0.99f
    var held by remember { mutableStateOf(frosted to wallpaper) }
    LaunchedEffect(frosted, wallpaper) {
        if (wallpaper == held.second) snapshotFlow { gridGlass() < 0.01f || moving() }.first { it }
        held = frosted to wallpaper
    }
    val ambient: @Composable (ImageBitmap?) -> Unit = { bmp ->
        val own = remember(bmp, frosted == null) {
            bmp?.takeIf { frosted == null && it.width <= 256 }?.let(::plateTinted)
        }
        Canvas(Modifier.fillMaxSize()) {
            val film = cover()
            if (film > 0.99f && scroll() < collapse * 0.3f) return@Canvas
            if (bmp == null) {
                drawRect(Color.Black)
                return@Canvas
            }
            val full = IntSize(size.width.roundToInt(), size.height.roundToInt())
            val g = gridGlass()
            if (g < 0.99f && film < 0.99f) drawImage(bmp, dstSize = full, filterQuality = FilterQuality.Low, alpha = 1f - film)
            if (own != null && g > 0.01f) drawImage(own, dstSize = full, filterQuality = FilterQuality.Low, alpha = g)
        }
    }
    Dissolve(
        targetState = backdrop,
        durationMs = 450,
        easing = Motion.EaseOut,
        modifier = Modifier.fillMaxSize(),
        label = "ambient",
    ) { bmp -> ambient(bmp) }
    AnimatedContent(
        targetState = held,
        contentKey = { it.second },
        transitionSpec = {
            if (targetState.first == null) {
                (fadeIn(tween(0)) togetherWith ExitTransition.None).using(null)
            } else {
                (fadeIn(tween(450, easing = Motion.EaseOut)) togetherWith ExitTransition.KeepUntilTransitionsFinished).using(null)
            }
        },
        modifier = Modifier.fillMaxSize(),
        label = "glass",
    ) { (frost, _) ->
        val glass = remember(frost) { frost?.let(::plateTinted) }
        val moved = remember(filmGlass) { filmGlass?.let(::plateTinted) }
        Canvas(Modifier.fillMaxSize()) {
            val g = gridGlass()
            if (g < 0.01f) return@Canvas
            val src = (if (moving()) moved else null) ?: glass ?: return@Canvas
            drawImage(src, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = FilterQuality.Low, alpha = g)
        }
    }
}

@Composable
private fun Plate(glass: ImageBitmap?, film: ImageBitmap?, filmPlate: Boolean, deepSmoke: Boolean, plate: Rect?, scroll: () -> Float, collapse: Float, cover: () -> Float, filmStopped: () -> Float, toSaver: () -> Float, lift: () -> Float, stillOverFilm: Boolean = false, slide: () -> Float = { 0f }) {
    var shown by remember { mutableStateOf(glass) }
    var under by remember { mutableStateOf<ImageBitmap?>(null) }
    val swap = remember { Animatable(1f) }
    fun visibility(): Float {
        val rest = 1f - smooth(((scroll() / collapse).coerceIn(0f, 1f) / 0.55f).coerceIn(0f, 1f))
        return rest * (if (stillOverFilm) 1f else 1f - cover() * (1f - filmStopped())) * (1f - toSaver())
    }
    LaunchedEffect(glass) {
        if (glass === shown) return@LaunchedEffect
        if (visibility() < 0.05f) {
            shown = glass
            under = null
            swap.snapTo(1f)
            return@LaunchedEffect
        }
        under = shown.takeIf { swap.value > 0.5f } ?: under
        shown = glass
        swap.snapTo(0f)
        swap.animateTo(1f, tween(450, easing = Motion.EaseOut))
        under = null
    }
    val density = LocalDensity.current
    var screen by remember { mutableStateOf(Size.Zero) }
    suspend fun baked(glass: ImageBitmap?): ImageBitmap? {
        if (glass == null || plate == null || screen == Size.Zero || filmPlate) return null
        val size = screen
        val result = withContext(Dispatchers.Default) {
            bakePlate(plateTinted(glass), size, plate, with(density) { PlateCorner.toPx() }, with(density) { 1.2.dp.toPx() })
        }
        return result
    }
    val top by produceState<ImageBitmap?>(null, shown, plate, screen) {
        value = baked(shown)
    }
    val bottom by produceState<ImageBitmap?>(null, under, plate, screen) { value = baked(under) }
    var filmTop by remember { mutableStateOf<ImageBitmap?>(null) }
    var filmBottom by remember { mutableStateOf<ImageBitmap?>(null) }
    var smoke by remember { mutableStateOf<ImageBitmap?>(null) }
    val filmSwap = remember { Animatable(1f) }
    LaunchedEffect(plate, screen, deepSmoke) {
        if (plate == null || screen == Size.Zero) return@LaunchedEffect
        smoke = withContext(Dispatchers.Default) { bakePlate(smokeGlass(if (deepSmoke) FILM_SMOKE_ALONE else FILM_SMOKE), screen, plate, with(density) { PlateCorner.toPx() }, with(density) { 1.2.dp.toPx() }) }
    }
    LaunchedEffect(film, plate, screen) {
        if (film == null || plate == null || screen == Size.Zero) {
            filmTop = null
            filmBottom = null
            return@LaunchedEffect
        }
        val baked = withContext(Dispatchers.Default) { bakePlate(plateTinted(film), screen, plate, with(density) { PlateCorner.toPx() }, 0f, edge = false) }
        if (filmTop == null || cover() < 0.01f) {
            filmTop = baked
            filmBottom = null
            filmSwap.snapTo(1f)
            return@LaunchedEffect
        }
        filmBottom = filmTop.takeIf { filmSwap.value > 0.5f } ?: filmBottom
        filmTop = baked
        filmSwap.snapTo(0f)
        filmSwap.animateTo(1f, tween(FILM_SWAP_MS, easing = Motion.EaseOut))
        filmBottom = null
    }
    val frostPaint = remember { androidx.compose.ui.graphics.Paint() }

    Canvas(Modifier.fillMaxSize().onSizeChanged { screen = Size(it.width.toFloat(), it.height.toFloat()) }) {
        if (plate == null) return@Canvas
        val alpha = if (filmPlate) 0f else visibility()
        val base = (1f - smooth(((scroll() / collapse).coerceIn(0f, 1f) / 0.55f).coerceIn(0f, 1f))) * (1f - toSaver())
        val frame = filmTop
        val edge = smoke
        if (!stillOverFilm && edge != null && (filmPlate || cover() > 0.01f) && alpha < 0.99f && base > 0.01f) {
            val p = (scroll() / collapse).coerceIn(0f, 1f)
            val r = plate.translate(0f, -scroll() - lift() + slide()).inflate(PlateSwell.toPx() * smooth((p / 0.55f).coerceIn(0f, 1f)))
            val t = filmSwap.value
            val under = filmBottom
            if (frame != null && t < 1f && under != null) {
                frostPaint.alpha = FILM_FROST * base
                drawContext.canvas.saveLayer(r, frostPaint)
                drawBaked(under, r, 1f)
                drawBaked(frame, r, t)
                drawContext.canvas.restore()
            } else if (frame != null) {
                drawBaked(frame, r, FILM_FROST * base)
            }
            drawBaked(edge, r, base)
        }

        val p = (scroll() / collapse).coerceIn(0f, 1f)
        val rest = 1f - smooth((p / 0.55f).coerceIn(0f, 1f))
        if (alpha < 0.01f) return@Canvas
        val grow = PlateSwell.toPx() * (1f - rest)
        val r = plate.translate(0f, -scroll() - lift() + slide()).inflate(grow)
        val t = swap.value
        val below = bottom.takeIf { under != null }
        if (t < 1f) below?.let { drawBaked(it, r, alpha) }
        top?.let { drawBaked(it, r, if (below != null) alpha * t else alpha) }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawBaked(image: ImageBitmap, at: Rect, alpha: Float) = drawImage(
    image,
    dstOffset = IntOffset(at.left.roundToInt(), at.top.roundToInt()),
    dstSize = IntSize(at.width.roundToInt(), at.height.roundToInt()),
    alpha = alpha,
    filterQuality = FilterQuality.Low,
)

private fun smooth(x: Float) = x * x * (3f - 2f * x)

private fun smokeGlass(color: Int): ImageBitmap =
    android.graphics.Bitmap.createBitmap(1, 1, android.graphics.Bitmap.Config.ARGB_8888)
        .apply { eraseColor(color) }.asImageBitmap()

private const val FILM_FROST = 0.55f

private val FILM_SMOKE = android.graphics.Color.argb(72, 0, 0, 0)

private val FILM_SMOKE_ALONE = android.graphics.Color.argb(38, 255, 255, 255)

private const val FILM_SWAP_MS = 700

val PlatePadH = 26.dp
val PlatePadV = 20.dp
private val PlateCorner = 32.dp

private const val GRID_SHADE = 0.55f

private val PlateSwell = 14.dp

private val PlateTint = floatArrayOf(
    0.8f, 0f, 0f, 0f, 30f,
    0f, 0.8f, 0f, 0f, 30f,
    0f, 0f, 0.8f, 0f, 30f,
    0f, 0f, 0f, 1f, 0f,
)

private fun plateTinted(src: ImageBitmap): ImageBitmap {
    val out = src.asAndroidBitmap().copy(android.graphics.Bitmap.Config.ARGB_8888, true)
    val paint = android.graphics.Paint().apply { colorFilter = android.graphics.ColorMatrixColorFilter(PlateTint) }
    android.graphics.Canvas(out).drawBitmap(src.asAndroidBitmap(), 0f, 0f, paint)
    return out.frozen().asImageBitmap()
}

private fun bakePlate(glass: ImageBitmap, screen: Size, plate: Rect, radius: Float, rim: Float, edge: Boolean = true): ImageBitmap {
    val s = 1f
    val w = (plate.width * s).roundToInt().coerceAtLeast(1)
    val h = (plate.height * s).roundToInt().coerceAtLeast(1)
    val out = android.graphics.Bitmap.createBitmap(w, h, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(out)
    canvas.drawBitmap(
        glass.asAndroidBitmap(),
        Matrix().apply { setScale(screen.width * s / glass.width, screen.height * s / glass.height); postTranslate(-plate.left * s, -plate.top * s) },
        android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG),
    )
    canvas.drawBitmap(smoothMask(w, h, radius * s), 0f, 0f, android.graphics.Paint().apply { xfermode = android.graphics.PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
    if (edge) canvas.drawBitmap(glassEdge(w, h, radius * s, rim * s).asAndroidBitmap(), 0f, 0f, null)
    return out.frozen().asImageBitmap()
}

@Composable
fun PanelBackdrop(frosted: ImageBitmap?, frost: () -> Float, veil: () -> Float) {
    val (shown, under, swap) = rememberFrostSwap(frosted)
    val dark = remember(shown) { shown?.let { darkened(it, GRID_SHADE) } }
    val darkUnder = remember(under) { under?.let { darkened(it, GRID_SHADE) } }
    Canvas(Modifier.fillMaxSize()) {
        val f = frost()
        if (f >= 0.01f) {
            val full = IntSize(size.width.roundToInt(), size.height.roundToInt())
            val t = swap()
            if (dark == null) drawRect(Color.Black, alpha = 0.55f * f)
            else if (darkUnder != null && t < 1f) {
                drawImage(darkUnder, dstSize = full, filterQuality = FilterQuality.Low, alpha = f)
                drawImage(dark, dstSize = full, filterQuality = FilterQuality.Low, alpha = f * t)
            } else drawImage(dark, dstSize = full, filterQuality = FilterQuality.Low, alpha = f)
        }
        val v = veil()
        if (v >= 0.01f) drawRect(Color.Black, alpha = 0.45f * v)
    }
}

private fun darkened(src: ImageBitmap, alpha: Float): ImageBitmap {
    val out = src.asAndroidBitmap().copy(android.graphics.Bitmap.Config.ARGB_8888, true)
    android.graphics.Canvas(out).drawColor(android.graphics.Color.argb((alpha * 255).roundToInt(), 0, 0, 0))
    return out.asImageBitmap()
}

@Composable
fun ShelfClock(
    now: LocalDateTime,
    modifier: Modifier = Modifier,
    alignEnd: Boolean = false,
    toSaver: () -> Float = { 0f },
    saving: Boolean = false,
    away: Boolean = false,
    nightTint: Boolean = false,
) {
    val drift = heldDrift(saving, now)
    val textColor = if (nightTint) clockTextColor(now) else Palette.Label
    val shadow = Shadow(Color.Black.copy(alpha = 0.28f), Offset(0f, 2f), blurRadius = 18f)
    val second = SpanStyle(fontWeight = FontWeight.W500)
    val reduced = LocalReducedMotion.current
    val asideGone by animateFloatAsState(
        if (away) 1f else 0f,
        if (reduced) snap() else if (away) tween(400, easing = Motion.EaseOut) else tween(400, delayMillis = CLOCK_BACK_MS * 7 / 10, easing = Motion.EaseOut),
        label = "clock-aside",
    )
    val aside = Modifier.graphicsLayer {
        alpha = 1f - asideGone
        compositingStrategy = CompositingStrategy.ModulateAlpha
    }
    Column(modifier, horizontalAlignment = if (alignEnd) Alignment.End else Alignment.Start) {
        val lunar = if (Locale.getDefault().language == "zh") Lunar.text(now.toLocalDate()) else null
        BasicText(
            buildAnnotatedString {
                append(dateText(now))
                if (lunar != null) withStyle(second) { append("  $lunar") }
            },
            style = Type.ClockDate.copy(color = textColor, shadow = shadow),
            modifier = aside.capBox(Type.ClockDate.fontSize),
        )
        BasicText(
            timeText(now),
            style = Type.Clock.copy(color = textColor, shadow = shadow),
            modifier = Modifier.padding(top = 16.dp)
                .toSaverClock(toSaver, Type.Clock.fontSize, 0.dp, drift)
                .capBox(Type.Clock.fontSize),
        )
        Weather.now?.takeIf { AerialSettings.showWeather }?.let { w ->
            if (w.forecast.isEmpty()) {
                val place = Weather.place?.takeIf { it.length <= 12 }
                val range = if (w.high != null && w.low != null) stringResource(R.string.weather_high_low, w.high, w.low) else null
                val detail = listOfNotNull(place, stringResource(kindNameRes(w.kind)), range).joinToString(" · ")
                Row(aside.padding(top = 18.dp).capBox(Type.ClockDetail.fontSize), verticalAlignment = Alignment.CenterVertically) {
                    GlyphIcon(glyphFor(w), textColor, 18.dp, Modifier.padding(end = 7.dp))
                    BasicText(
                        buildAnnotatedString {
                            append(stringResource(R.string.weather_temperature, w.celsius))
                            withStyle(second) { append("  $detail") }
                        },
                        style = Type.ClockDetail.copy(color = textColor, shadow = shadow),
                    )
                }
            } else {
                val place = Weather.place?.takeIf { it.length <= 12 }
                val range = if (w.high != null && w.low != null) stringResource(R.string.weather_high_low_qweather, w.high, w.low) else null
                val aqi = w.aqi?.substringBeforeLast(' ')?.takeIf { it.isNotEmpty() }
                val name = w.conditionText?.takeIf { it.isNotEmpty() } ?: stringResource(conditionName(w))
                val detail = listOfNotNull(aqi, range).joinToString(" ")
                Row(aside.padding(top = 18.dp).capBox(Type.ClockDetail.fontSize), verticalAlignment = Alignment.CenterVertically) {
                    place?.let { BasicText(it, style = Type.ClockDetail.copy(color = textColor, fontWeight = FontWeight.W500, shadow = shadow), modifier = Modifier.padding(end = 7.dp)) }
                    BasicText(
                        buildAnnotatedString {
                            withStyle(SpanStyle(fontSize = 18.sp)) { append(emojiFor(w)) }
                            withStyle(second) { append(name) }
                            append(" ${w.celsius}°C")
                            if (detail.isNotEmpty()) withStyle(second) { append(" $detail") }
                        },
                        style = Type.ClockDetail.copy(color = textColor, shadow = shadow),
                    )
                }
            }
            val dayNames = listOf(R.string.severe_day_tomorrow, R.string.severe_day_after, R.string.severe_day_3)
            w.forecast.forEach { d ->
                val dayName = stringResource(dayNames.getOrNull(d.day - 1) ?: R.string.severe_day_tomorrow)
                val dayWord = d.dayText?.takeIf { it.isNotBlank() }
                val temps = listOfNotNull(d.high, d.low)
                val line = buildString {
                    append(dayName)
                    if (dayWord != null) append(" " + dayWord)
                    if (temps.size == 2) append(" " + stringResource(R.string.weather_high_low_qweather, temps[0], temps[1]))
                }
                BasicText(
                    line,
                    style = Type.ClockDetail.copy(color = textColor, shadow = shadow),
                    modifier = aside.padding(top = 10.dp).capBox(Type.ClockDetail.fontSize),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (w.forecast.isNotEmpty()) Weather.alertTitle?.let { alert ->
                Row(aside.padding(top = 10.dp).capBox(Type.ClockDetail.fontSize), verticalAlignment = Alignment.CenterVertically) {
                    GlyphIcon(Glyph.Info, Color(0xFFFFB340), 18.dp, Modifier.padding(end = 7.dp))
                    BasicText(
                        alert,
                        style = Type.ClockDetail.copy(color = textColor, shadow = shadow),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun heldDrift(saving: Boolean, now: LocalDateTime): DpOffset {
    var held by remember { mutableStateOf(DpOffset.Zero) }
    if (saving) held = saverDrift(now)
    return held
}

private const val CAP_TOP = 0.242f

private const val CAP_HEIGHT = 0.727f

private fun Modifier.toSaverClock(progress: () -> Float, fontSize: TextUnit, capTop: Dp, drift: DpOffset = DpOffset.Zero): Modifier = composed {
    var placed by remember { mutableStateOf<Pair<Rect, Float>?>(null) }
    onGloballyPositioned { placed = it.boundsInRoot() to it.findRootCoordinates().size.width.toFloat() }
        .graphicsLayer {
            val p = progress()
            val (box, rootW) = placed ?: return@graphicsLayer
            if (p <= 0f) return@graphicsLayer
            val s = SaverClockStyle.fontSize.toPx() / fontSize.toPx()
            val k = 1f + (s - 1f) * p
            transformOrigin = TransformOrigin(0f, 0f)
            scaleX = k
            scaleY = k
            val targetCapTop = SaverClockTop.toPx() + SaverClockStyle.fontSize.toPx() * CAP_TOP
            val targetLeft = if (AerialSettings.clockRight) rootW - SaverClockSide.toPx() - box.width * s else SaverClockSide.toPx()
            translationX = p * (targetLeft - box.left + drift.x.toPx())
            translationY = p * (targetCapTop - s * capTop.toPx() - box.top + drift.y.toPx())
            if (!AerialSettings.screensaverClock) alpha = 1f - p
            compositingStrategy = CompositingStrategy.ModulateAlpha
        }
}

private fun Modifier.capBox(fontSize: TextUnit) = layout { measurable, constraints ->
    val p = measurable.measure(constraints)
    val baseline = p[FirstBaseline].takeIf { it != AlignmentLine.Unspecified } ?: p.height
    val top = (baseline - fontSize.toPx() * CAP_HEIGHT).roundToInt().coerceAtLeast(0)
    layout(p.width, baseline - top) { p.place(0, -top) }
}

private fun conditionName(w: WeatherNow): Int {
    return kindNameRes(w.kind)
}

@Composable
fun CornerClock(now: LocalDateTime, modifier: Modifier = Modifier, toSaver: () -> Float = { 0f }, saving: Boolean = false) {
    val drift = heldDrift(saving, now)
    val dim = Color.White.copy(alpha = 0.92f)
    val shadow = Shadow(Color.Black.copy(alpha = 0.5f), blurRadius = 8f)
    Row(modifier.height(34.dp), verticalAlignment = Alignment.CenterVertically) {
        BasicText(
            timeText(now), style = Type.SmallClock.copy(fontSize = Type.Title.fontSize, color = dim, shadow = shadow),
            modifier = Modifier.toSaverClock(toSaver, Type.Title.fontSize, Type.Title.fontSize.value.dp * CAP_TOP, drift),
        )
        Weather.now?.takeIf { AerialSettings.showWeather }?.let { w ->
            val aside = Modifier.graphicsLayer {
                alpha = 1f - (toSaver() * 2.5f).coerceIn(0f, 1f)
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
            if (w.forecast.isEmpty()) {
                GlyphIcon(glyphFor(w), dim, 18.dp, aside.padding(start = 16.dp, end = 6.dp))
                val place = Weather.place?.takeIf { it.length <= 12 }
                BasicText(listOfNotNull(place, stringResource(R.string.weather_temperature, w.celsius)).joinToString(" "), style = Type.Headline.copy(color = dim, shadow = shadow), modifier = aside)
            } else {
                BasicText(emojiFor(w), style = Type.Headline.copy(fontSize = 18.sp, shadow = shadow), modifier = aside.padding(start = 16.dp, end = 6.dp))
                val place = Weather.place?.takeIf { it.length <= 12 }
                BasicText(listOfNotNull(place, "${w.celsius}°C").joinToString(" "), style = Type.Headline.copy(color = dim, shadow = shadow), modifier = aside)
            }
        }
    }
}

private fun emojiFor(w: WeatherNow): String = when (w.kind) {
    WeatherKind.Clear -> if (!w.day) "🌙" else "☀️"
    WeatherKind.PartlyCloudy -> "☁️"
    WeatherKind.Cloudy -> "☁️"
    WeatherKind.Fog -> "🌫️"
    WeatherKind.Rain -> "🌧️"
    WeatherKind.LightRain -> "🌧️"
    WeatherKind.ModerateRain -> "🌧️"
    WeatherKind.HeavyRain -> "🌧️"
    WeatherKind.StormRain -> "🌧️"
    WeatherKind.Snow -> "❄️"
    WeatherKind.LightSnow -> "❄️"
    WeatherKind.ModerateSnow -> "❄️"
    WeatherKind.HeavySnow -> "❄️"
    WeatherKind.StormSnow -> "❄️"
    WeatherKind.Thunder -> "⛈️"
    WeatherKind.Haze -> "🌫️"
    WeatherKind.Dust -> "💨"
    WeatherKind.Sand -> "🏜️"
    WeatherKind.Wind -> "🌬️"
    WeatherKind.Hail -> "🧊"
    WeatherKind.Sleet -> "🌨️"
}

private fun glyphFor(w: WeatherNow): Glyph = when (w.kind) {
    WeatherKind.Clear -> if (w.day) Glyph.Sun else Glyph.Moon
    WeatherKind.PartlyCloudy -> if (w.day) Glyph.CloudSun else Glyph.Cloud
    WeatherKind.Cloudy -> Glyph.Cloud
    WeatherKind.Fog -> Glyph.CloudFog
    WeatherKind.Rain, WeatherKind.LightRain, WeatherKind.ModerateRain, WeatherKind.HeavyRain, WeatherKind.StormRain -> Glyph.CloudRain
    WeatherKind.Snow, WeatherKind.LightSnow, WeatherKind.ModerateSnow, WeatherKind.HeavySnow, WeatherKind.StormSnow -> Glyph.CloudSnow
    WeatherKind.Thunder -> Glyph.CloudBolt
    WeatherKind.Haze, WeatherKind.Dust, WeatherKind.Sand -> Glyph.CloudFog
    WeatherKind.Wind -> Glyph.Cloud
    WeatherKind.Hail, WeatherKind.Sleet -> Glyph.CloudRain
}

@Composable
fun VpnBadge(color: Color, modifier: Modifier = Modifier, fontSize: TextUnit = 10.sp) {
    BasicText(
        stringResource(R.string.net_vpn),
        style = Type.Caption.copy(color = color, fontSize = fontSize, fontWeight = FontWeight.W700, letterSpacing = 0.04.em),
        modifier = modifier
            .smoothBorder(1.2.dp, color, 4.dp)
            .padding(horizontal = 4.dp, vertical = 1.dp),
    )
}

@Composable
fun StatusPill(
    now: LocalDateTime,
    network: NetworkStatus,
    showTime: Boolean,
    enabled: Boolean,
    focusRequester: FocusRequester,
    onFocused: (Boolean) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Pressable(
        onClick = onClick,
        enabled = enabled,
        focusRequester = focusRequester,
        focusedScale = 1.08f,
        modifier = modifier,
    ) { state ->
        LaunchedEffect(state.focused) { onFocused(state.focused) }
        val bg by animateColorAsState(
            if (state.focused) Palette.Focus else Color.Transparent,
            tween(160, easing = Motion.EaseOut), label = "pill-bg",
        )
        val fg = if (state.focused) Palette.OnFocus else Color.White.copy(alpha = 0.9f)
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .drawBehind { drawRect(bg) }
                .height(34.dp)
                .padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val glyph = when (network.kind) {
                NetKind.Wifi -> Glyph.Wifi
                NetKind.Ethernet -> Glyph.Ethernet
                NetKind.Offline -> Glyph.Offline
                NetKind.Other -> Glyph.Globe
            }
            GlyphIcon(glyph, fg, 17.dp, level = network.level)
            if (network.vpn) VpnBadge(fg)
            if (showTime) {
                BasicText(timeText(now), style = Type.Headline.copy(color = fg))
                Weather.now?.takeIf { AerialSettings.showWeather }?.let { w ->
                    BasicText(
                        "${emojiFor(w)}${stringResource(R.string.weather_temperature, w.celsius)}",
                        style = Type.Headline.copy(color = fg),
                    )
                }
            }
            GlyphIcon(Glyph.Switches, fg, 17.dp)
        }
    }
}
