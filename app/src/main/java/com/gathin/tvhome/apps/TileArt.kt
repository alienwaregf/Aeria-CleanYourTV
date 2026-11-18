package com.gathin.tvhome.apps

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.BitmapDrawable
import androidx.annotation.RequiresApi
import android.os.Build
import android.graphics.drawable.Drawable
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.gathin.tvhome.settings.AerialSettings
import com.gathin.tvhome.ui.theme.Glyph
import com.gathin.tvhome.ui.theme.drawGlyph
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.createBitmap
import androidx.core.graphics.scale
import com.gathin.tvhome.ui.components.smoothRectPath
import com.gathin.tvhome.wallpaper.Images
import kotlin.math.max
import kotlin.math.roundToInt

class TileArt(
    val back: ImageBitmap,
    val fore: ImageBitmap?,
    val foreAt: androidx.compose.ui.geometry.Rect,
    val tint: Color,
    val backdrop: ImageBitmap,
    val placeholder: Boolean = false,
)

enum class SystemTile(top: Long, bottom: Long, val glyph: Glyph) {
    Settings(0xFF8E8E93, 0xFF48484A, Glyph.Gear),
    Photos(0xFFFFB340, 0xFFFF5E3A, Glyph.Photo),
    Media(0xFF3A3A3C, 0xFF111113, Glyph.Play),
    Store(0xFF40B0FF, 0xFF0A5FD8, Glyph.Bag),
    Weather(0xFF5AC8FA, 0xFF1C6FD6, Glyph.CloudSun),
    Calendar(0xFFFF6B5E, 0xFFD02A1E, Glyph.Calendar),
    Cast(0xFF8E8CFF, 0xFF4744D0, Glyph.Cast),
    Security(0xFF3ED97A, 0xFF138A47, Glyph.Shield),
    Browser(0xFF64D2FF, 0xFF0A7BEA, Glyph.Globe),
    Home(0xFF7D7AFF, 0xFF3634A3, Glyph.Home),
    Kids(0xFFFF8FB1, 0xFFE0457B, Glyph.Kids);

    val top = top.toInt()
    val bottom = bottom.toInt()
}

object TileArtFactory {
    @Volatile var W = 352; private set
    @Volatile var H = 198; private set

    private var density = 2f

    fun useCanvasDensity(density: Float) {
        this.density = density
        fitColumns()
    }

    fun fitColumns(): Boolean {
        val dp = if (AerialSettings.homeColumns == 4) 222 else 176
        val w = (dp * density).roundToInt().coerceIn(352, 888)
        if (w == W) return false
        W = w
        H = W * 9 / 16
        return true
    }

    @Volatile var unified = true

    private const val LOGO = 0.5f

    private const val SAMPLE = 256

    private const val EDGE_RADIUS = 2

    private const val GRAPHITE_TOP = 0xFF3A3A3C.toInt()
    private const val GRAPHITE_BOTTOM = 0xFF242426.toInt()

    fun fromBanner(banner: Drawable): TileArt {
        val src = (banner as? BitmapDrawable)?.bitmap
        val w = src?.width?.coerceAtMost(2 * W) ?: W
        val h = w * 9 / 16
        val back = createBitmap(w, h)
        Canvas(back).apply {
            drawColor(GRAPHITE_BOTTOM)
            if (src != null) drawCover(banner, w, h, src.width, src.height) else drawCover(banner, w, h)
        }
        return finish(back, null)
    }

    fun forSource(kind: com.gathin.tvhome.system.SourceKind, label: String?, sub: String?, device: SourceDevice? = null): TileArt {
        val (top, bottom) = if (device != null) device.top to device.bottom else when (kind) {
            com.gathin.tvhome.system.SourceKind.Hdmi -> 0xFF3B6FD8.toInt() to 0xFF1D3A86.toInt()
            com.gathin.tvhome.system.SourceKind.DisplayPort -> 0xFF6E5BD8.toInt() to 0xFF3A2D86.toInt()
            com.gathin.tvhome.system.SourceKind.Tuner -> 0xFF1FA88E.toInt() to 0xFF0E5C55.toInt()
            com.gathin.tvhome.system.SourceKind.Av -> 0xFFE0912F.toInt() to 0xFF9A4F12.toInt()
            com.gathin.tvhome.system.SourceKind.Usb -> 0xFFD8457A.toInt() to 0xFF861E47.toInt()
            com.gathin.tvhome.system.SourceKind.Other -> 0xFF5E6470.toInt() to 0xFF30343C.toInt()
        }
        val data = device?.mark ?: when (kind) {
            com.gathin.tvhome.system.SourceKind.Hdmi, com.gathin.tvhome.system.SourceKind.DisplayPort -> com.gathin.tvhome.ui.theme.GlyphPaths.HDMI
            com.gathin.tvhome.system.SourceKind.Tuner -> com.gathin.tvhome.ui.theme.GlyphPaths.ANTENNA
            com.gathin.tvhome.system.SourceKind.Usb -> com.gathin.tvhome.ui.theme.GlyphPaths.USB
            else -> com.gathin.tvhome.ui.theme.GlyphPaths.COMPONENT
        }
        val path = androidx.core.graphics.PathParser.createPathFromPathData(data)
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
        if (label == null) {
            val g = H * 0.5f
            val box = device?.markBox ?: 960f
            val fore = layer {
                translate((W - g) / 2f, (H - g) / 2f)
                scale(g / box, g / box)
                if (box == 960f) translate(0f, 960f)
                val drop = box * 0.025f
                for ((dy, a) in listOf(drop * 2f to 0x0F, drop to 0x1F)) {
                    translate(0f, dy)
                    drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = a shl 24 })
                    translate(0f, -dy)
                }
                drawPath(path, white)
            }
            return finish(colorTile(top, bottom), fore)
        }
        val back = colorTile(top, bottom)
        Canvas(back).apply {
            val two = sub != null
            val g = H * (if (two) 0.36f else 0.42f)
            val box = device?.markBox ?: 960f
            save()
            translate((W - g) / 2f, H * (if (two) 0.31f else 0.36f) - g / 2f)
            if (box == 960f) {
                scale(g / 960f, g / 960f)
                translate(0f, 960f)
            } else {
                translate(g / 12f, g / 12f)
                scale(g * 5f / 6f / box, g * 5f / 6f / box)
            }
            drawPath(path, white)
            restore()
            val title = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                color = 0xFFFFFFFF.toInt(); textSize = H * 0.15f; textAlign = Paint.Align.CENTER
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD)
            }
            var shown: String = label
            while (title.measureText(shown) > W * 0.88f && title.textSize > H * 0.11f) title.textSize *= 0.92f
            while (title.measureText(shown) > W * 0.88f && shown.length > 1) shown = shown.dropLast(2) + "…"
            drawText(shown, W / 2f, H * (if (two) 0.68f else 0.76f), title)
            if (sub != null) {
                val small = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCCFFFFFF.toInt(); textSize = H * 0.11f; textAlign = Paint.Align.CENTER }
                drawText(sub, W / 2f, H * 0.85f, small)
            }
        }
        return finish(back, null)
    }

    fun forSystem(kind: SystemTile): TileArt = glyphTile(kind.top, kind.bottom, kind.glyph)

    fun glyphTile(top: Int, bottom: Int, glyph: Glyph): TileArt {
        val back = colorTile(top, bottom)
        val g = H * 0.58f
        val fore = layer {
            translate((W - g) / 2f, (H - g) / 2f)
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, androidx.compose.ui.graphics.Canvas(this), Size(g, g)) {
                val drop = g * 0.025f
                for ((dy, a) in listOf(drop * 2f to 0.06f, drop to 0.12f)) {
                    drawContext.transform.translate(0f, dy)
                    paintGlyph(glyph, Color.Black.copy(alpha = a), shadow = true)
                    drawContext.transform.translate(0f, -dy)
                }
                paintGlyph(glyph, Color.White, shadow = false)
            }
        }
        return finish(back, fore)
    }

    private fun androidx.compose.ui.graphics.drawscope.DrawScope.paintGlyph(glyph: Glyph, color: Color, shadow: Boolean) {
        if (glyph != Glyph.CloudSun) return drawGlyph(glyph, color)
        val g = size.minDimension
        drawContext.transform.translate(-g * 0.14f, -g * 0.15f)
        scale(0.78f, pivot = androidx.compose.ui.geometry.Offset(g / 2, g / 2)) {
            drawGlyph(Glyph.Sun, if (shadow) color else Color(0xFFFFD60A))
        }
        drawContext.transform.translate(g * 0.24f, g * 0.27f)
        scale(0.86f, pivot = androidx.compose.ui.geometry.Offset(g / 2, g / 2)) { drawGlyph(Glyph.Cloud, color) }
        drawContext.transform.translate(-g * 0.10f, -g * 0.12f)
    }

    fun forFolder(apps: List<TileArt>): TileArt {
        val back = layer {
            drawRect(0f, 0f, W.toFloat(), H.toFloat(), Paint().apply { color = 0x40FFFFFF })
            drawRect(0f, 0f, W.toFloat(), H.toFloat(), Paint().apply {
                shader = LinearGradient(0f, 0f, 0f, H.toFloat(), 0x22FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
            })
            val pad = H * 0.09f
            val gap = H * 0.045f
            val th = (H - 2 * pad - 2 * gap) / 3f
            val tw = th * 16f / 9f
            val left = (W - 3 * tw - 2 * gap) / 2f
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            apps.take(9).forEachIndexed { i, art ->
                val x = left + (i % 3) * (tw + gap)
                val y = pad + (i / 3) * (th + gap)
                val flat = flatten(art)
                paint.shader = android.graphics.BitmapShader(flat, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                    setLocalMatrix(android.graphics.Matrix().apply { setScale(tw / flat.width, th / flat.height); postTranslate(x, y) })
                }
                drawPath(smoothRect(RectF(x, y, x + tw, y + th), th * 0.14f), paint)
            }
        }
        return finish(back, null)
    }

    private fun colorTile(top: Int, bottom: Int): Bitmap = layer {
        drawRect(0f, 0f, W.toFloat(), H.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, W * 0.4f, H.toFloat(), top, bottom, Shader.TileMode.CLAMP)
        })
        drawRect(0f, 0f, W.toFloat(), H.toFloat(), Paint().apply {
            shader = android.graphics.RadialGradient(W * 0.25f, -H * 0.2f, W * 0.8f, 0x38FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP)
        })
    }

    fun fromIcon(icon: Drawable, recipe: TileRecipe? = null): TileArt {
        if (recipe?.top == null && Build.VERSION.SDK_INT >= 26 && icon is AdaptiveIconDrawable && icon.foreground != null) adaptive(icon)?.let { return it }
        return plate(icon, recipe) ?: legacy(icon, recipe)
    }

    @RequiresApi(26)
    private fun adaptive(icon: AdaptiveIconDrawable): TileArt? {
        val logo = visible(raster(icon.foreground, SAMPLE), SAMPLE) ?: return null
        if (max(logo.width(), logo.height()) > 0.7f) return null
        val bg = icon.background
        val bgPx = bg?.let { raster(it, 32) }
        if (bgPx != null && !isFlat(bgPx)) return null
        val (fore, at) = placeLogo(icon.foreground, ::visible) ?: return null
        val color = bgPx?.get(bgPx.size / 2)?.takeIf { it ushr 24 > 200 } ?: GRAPHITE_BOTTOM
        if (unified && !multiColor(fore, at, color)) return unify(fore, at, color, color)
        val back = layer {
            drawColor(GRAPHITE_BOTTOM)
            bg?.let {
                it.setBounds(0, (H - W) / 2, W, (H - W) / 2 + W)
                it.draw(this)
            }
        }
        return finish(back, fore)
    }

    private fun plate(icon: Drawable, recipe: TileRecipe?): TileArt? {
        val n = SAMPLE
        val px = raster(icon, n)
        val box = solidBox(px, n) ?: return null
        val l = box.left; val t = box.top; val r = box.right - 1; val bt = box.bottom - 1
        val w = box.width()
        val h = box.height()
        var filled = 0
        for (y in t..bt) for (x in l..r) if (solid(px[y * n + x])) filled++
        if (w < n / 2 || kotlin.math.abs(w - h) > w * 0.06f || filled < w * h * 0.9f) return null
        fun band(fromY: Int, toY: Int): Int? {
            val xs = (l + w / 6)..(r - w / 6)
            val rs = ArrayList<Int>(); val gs = ArrayList<Int>(); val bs = ArrayList<Int>()
            for (y in fromY..toY) for (x in xs) {
                val c = px[y * n + x]
                rs += (c shr 16) and 0xFF; gs += (c shr 8) and 0xFF; bs += c and 0xFF
            }
            val median = android.graphics.Color.rgb(rs.sorted()[rs.size / 2], gs.sorted()[gs.size / 2], bs.sorted()[bs.size / 2])
            var near = 0
            for (y in fromY..toY) for (x in xs) if (distance(px[y * n + x], median) <= 48) near++
            return if (near < rs.size * 0.6f) null else median
        }
        val edge = (h * 0.04f).roundToInt()
        val depth = (h * 0.06f).roundToInt()
        val top = band(t + edge, t + edge + depth) ?: return null
        val bottom = band(bt - edge - depth, bt - edge) ?: return null
        if (distance(top, bottom) > 150) return null

        fun logoOnPlate(px: IntArray, n: Int): RectF? {
            val plate = solidBox(px, n) ?: return null
            val inset = (plate.width() * 0.08f).roundToInt()
            return bounds(px, n, android.graphics.Rect(plate.left + inset, plate.top + inset, plate.right - inset, plate.bottom - inset)) { i, c ->
                solid(c) && distance(c, ColorUtils.blendARGB(top, bottom, (i / n - plate.top) / plate.height().toFloat())) > 72
            } ?: RectF(plate.left / n.toFloat(), plate.top / n.toFloat(), plate.right / n.toFloat(), plate.bottom / n.toFloat())
        }

        val (fore, logo) = placeLogo(icon, ::logoOnPlate) ?: return null
        if (recipe?.top != null && recipe.bottom != null) {
            cutOut(fore, logo, top, bottom, white = recipe.white || unified)
            return finish(colorTile(recipe.top, recipe.bottom), fore)
        }
        if (unified && recipe?.keepColors != true && (recipe?.white == true || !multiColor(fore, logo, ColorUtils.blendARGB(top, bottom, 0.5f)))) {
            return unify(fore, logo, top, bottom)
        }
        val back = layer {
            drawRect(0f, 0f, W.toFloat(), H.toFloat(), Paint().apply {
                shader = LinearGradient(0f, 0f, 0f, H.toFloat(), top, bottom, Shader.TileMode.CLAMP)
            })
        }
        if (recipe?.white == true) cutOut(fore, logo, top, bottom, white = true) else feather(fore, logo)
        return finish(back, fore)
    }

    private fun cutOut(layer: Bitmap, logo: RectF, top: Int, bottom: Int, white: Boolean) {
        val px = IntArray(W * H).also { layer.getPixels(it, 0, W, 0, 0, W, H) }
        val d = IntArray(W * H)
        val plates = IntArray(H) { y -> ColorUtils.blendARGB(top, bottom, ((y - logo.top) / logo.height()).coerceIn(0f, 1f)) }
        for (y in 0 until H) for (x in 0 until W) {
            val i = y * W + x
            d[i] = if (px[i] ushr 24 == 0) 0 else distance(px[i], plates[y])
        }
        fun inside(distance: Int) = ((distance - 24) / 72f).coerceIn(0f, 1f)
        val r = EDGE_RADIUS
        for (y in 0 until H) {
            for (x in 0 until W) {
                val i = y * W + x
                val c = px[i]
                if (c ushr 24 == 0) continue
                var nearPlate = false
                var local = 0
                for (yy in maxOf(0, y - r)..minOf(H - 1, y + r)) for (xx in maxOf(0, x - r)..minOf(W - 1, x + r)) {
                    val v = d[yy * W + xx]
                    if (v <= 24) nearPlate = true
                    if (v > local) local = v
                }
                val share = if (nearPlate && local > 24) ((d[i] - 12f) / (local * 0.95f - 12f)).coerceIn(0f, 1f) else 1f
                val keep = if (nearPlate) share * inside(local) else inside(d[i])
                val a = ((c ushr 24) * keep).roundToInt()
                val rgb = when {
                    white -> 0xFFFFFF
                    share > 0f && share < 1f -> unmix(c, plates[y], share)
                    else -> c and 0xFFFFFF
                }
                px[i] = (a shl 24) or rgb
            }
        }
        layer.setPixels(px, 0, W, 0, 0, W, H)
    }

    private fun unmix(c: Int, plate: Int, share: Float): Int {
        fun ch(shift: Int): Int {
            val p = (plate shr shift) and 0xFF
            return (p + (((c shr shift) and 0xFF) - p) / share).roundToInt().coerceIn(0, 255)
        }
        return (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    private fun legacy(icon: Drawable, recipe: TileRecipe?): TileArt {
        val sample = 192
        val iconBitmap = createBitmap(sample, sample)
        icon.setBounds(0, 0, sample, sample)
        icon.draw(Canvas(iconBitmap))
        val square = opaqueCorners(iconBitmap)
        val tint = dominant(iconBitmap)
        iconBitmap.recycle()
        val (top, bottom) = if (recipe?.top != null && recipe.bottom != null) recipe.top to recipe.bottom else tileFromTint(tint)
        if (recipe?.white == true && (square || edgeColors(icon) != null)) {
            placeLogo(icon, ::lightPart)?.let { (fore, _) ->
                keepLight(fore)
                return finish(colorTile(top, bottom), fore)
            }
        }
        if (unified && !square && recipe?.keepColors != true) {
            placeLogo(icon, ::visible)?.let { (fore, at) ->
                if (recipe?.white == true || !multiColor(fore, at, 0)) {
                    whiten(fore)
                    return finish(colorTile(top, bottom), fore)
                }
                fore.recycle()
            }
        }
        if (recipe?.top == null) edgeColors(icon)?.let { (edgeTop, edgeBottom) ->
            placeLogo(icon, ::visible, scale = 1.25f)?.let { (fore, at) ->
                val side = at.width()
                feather(fore, RectF(at.left + side * 0.18f, at.top + side * 0.18f, at.right - side * 0.18f, at.bottom - side * 0.18f), margin = 0f, fade = side * 0.18f)
                val back = layer {
                    drawRect(0f, 0f, W.toFloat(), H.toFloat(), Paint().apply {
                        shader = LinearGradient(0f, at.top, 0f, at.bottom, edgeTop, edgeBottom, Shader.TileMode.CLAMP)
                    })
                }
                return finish(back, fore)
            }
        }
        val fore = placeLogo(icon, ::visible, round = square, scale = if (square) 1.25f else 1f)?.first
        return finish(colorTile(top, bottom), fore)
    }

    private fun edgeColors(icon: Drawable): Pair<Int, Int>? {
        val n = 96
        val px = raster(icon, n)
        val box = solidBox(px, n) ?: return null
        val w = box.width(); val h = box.height()
        if (w < n / 2 || kotlin.math.abs(w - h) > w * 0.06f) return null
        var filled = 0
        for (y in box.top until box.bottom) for (x in box.left until box.right) if (solid(px[y * n + x])) filled++
        if (filled < w * h * 0.88f) return null
        fun band(y0: Int, y1: Int): Int? {
            val cs = ArrayList<Int>()
            for (y in y0..y1) for (x in box.left + w / 5 until box.right - w / 5) cs += px[y * n + x]
            val median = android.graphics.Color.rgb(
                cs.map { (it shr 16) and 0xFF }.sorted()[cs.size / 2],
                cs.map { (it shr 8) and 0xFF }.sorted()[cs.size / 2],
                cs.map { it and 0xFF }.sorted()[cs.size / 2],
            )
            return if (cs.count { distance(it, median) <= 48 } < cs.size * 0.6f) null else median
        }
        val top = band(box.top + h * 2 / 100, box.top + h * 5 / 100) ?: return null
        val bottom = band(box.bottom - 1 - h * 5 / 100, box.bottom - 1 - h * 2 / 100) ?: return null
        return if (distance(top, bottom) > 260) null else top to bottom
    }

    private fun unify(fore: Bitmap, logo: RectF, top: Int, bottom: Int): TileArt {
        val plate = ColorUtils.blendARGB(top, bottom, 0.5f)
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(plate, hsl)
        val colored = hsl[1] > 0.25f && hsl[2] in 0.18f..0.85f
        val tint = if (colored) null else logoTint(fore, logo, plate)
        cutOut(fore, logo, top, bottom, white = true)
        val (a, b) = if (colored) tileFromPlate(top, bottom) else tileFromTint(tint!!)
        return finish(colorTile(a, b), fore)
    }

    private fun multiColor(layer: Bitmap, logo: RectF, plate: Int): Boolean {
        val l = logo.left.toInt().coerceIn(0, W - 1); val t = logo.top.toInt().coerceIn(0, H - 1)
        val r = logo.right.toInt().coerceIn(l + 1, W); val b = logo.bottom.toInt().coerceIn(t + 1, H)
        val px = IntArray((r - l) * (b - t)).also { layer.getPixels(it, 0, r - l, l, t, r - l, b - t) }
        val bins = IntArray(14)
        val hsv = FloatArray(3)
        fun binOf(c: Int): Int {
            android.graphics.Color.colorToHSV(c, hsv)
            return when {
                hsv[1] < 0.25f || hsv[2] < 0.2f -> if (hsv[2] > 0.6f) 12 else 13
                else -> ((hsv[0] + 15f) / 30f).toInt() % 12
            }
        }
        val plateBin = if (plate != 0) binOf(plate).takeIf { it < 12 } else null
        var count = 0
        for (c in px) {
            if (c ushr 24 < 200 || (plate != 0 && distance(c, plate) < 96)) continue
            val bin = binOf(c)
            if (plateBin != null && (bin == plateBin || bin == (plateBin + 1) % 12 || bin == (plateBin + 11) % 12)) continue
            bins[bin]++; count++
        }
        if (count == 0) return false
        val groups = ArrayList<Int>()
        for (i in 0 until 12) {
            val v = bins[i]
            if (v > 0 && v >= bins[(i + 11) % 12] && v >= bins[(i + 1) % 12]) groups += v + bins[(i + 11) % 12] + bins[(i + 1) % 12]
        }
        groups += bins[12]; groups += bins[13]
        return groups.count { it > count * 0.08f } >= 2
    }

    private fun logoTint(layer: Bitmap, logo: RectF, plate: Int): Int {
        val l = logo.left.toInt().coerceIn(0, W - 1); val t = logo.top.toInt().coerceIn(0, H - 1)
        val r = logo.right.toInt().coerceIn(l + 1, W); val b = logo.bottom.toInt().coerceIn(t + 1, H)
        val px = IntArray((r - l) * (b - t)).also { layer.getPixels(it, 0, r - l, l, t, r - l, b - t) }
        var rr = 0.0; var gg = 0.0; var bb = 0.0; var total = 0.0
        val hsv = FloatArray(3)
        for (c in px) {
            if (c ushr 24 < 200 || distance(c, plate) < 96) continue
            android.graphics.Color.colorToHSV(c, hsv)
            val w = hsv[1] * hsv[2] + 0.02
            rr += ((c shr 16) and 0xFF) * w; gg += ((c shr 8) and 0xFF) * w; bb += (c and 0xFF) * w; total += w
        }
        if (total == 0.0) return GRAPHITE_TOP
        return android.graphics.Color.rgb((rr / total).roundToInt(), (gg / total).roundToInt(), (bb / total).roundToInt())
    }

    private fun tileFromPlate(top: Int, bottom: Int): Pair<Int, Int> {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(ColorUtils.blendARGB(top, bottom, 0.5f), hsl)
        val s = hsl[1].coerceIn(0.45f, 0.85f)
        val l = hsl[2].coerceIn(0.4f, 0.56f)
        return ColorUtils.HSLToColor(floatArrayOf(hsl[0], s, l + 0.08f)) to ColorUtils.HSLToColor(floatArrayOf(hsl[0], s, l - 0.08f))
    }

    private fun lightPart(px: IntArray, n: Int): RectF? = bounds(px, n) { _, c -> solid(c) && minOf((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF) > 200 }

    private fun keepLight(layer: Bitmap) {
        val px = IntArray(W * H).also { layer.getPixels(it, 0, W, 0, 0, W, H) }
        for (i in px.indices) {
            val c = px[i]
            val low = minOf((c shr 16) and 0xFF, (c shr 8) and 0xFF, c and 0xFF)
            val keep = ((low - 115) / 115f).coerceIn(0f, 1f)
            px[i] = (((c ushr 24) * keep).roundToInt() shl 24) or 0xFFFFFF
        }
        layer.setPixels(px, 0, W, 0, 0, W, H)
    }

    private fun whiten(layer: Bitmap) {
        val px = IntArray(W * H).also { layer.getPixels(it, 0, W, 0, 0, W, H) }
        for (i in px.indices) px[i] = (px[i] and 0xFF000000.toInt()) or 0xFFFFFF
        layer.setPixels(px, 0, W, 0, 0, W, H)
    }

    private fun opaqueCorners(b: Bitmap): Boolean {
        val i = (b.width * 0.03f).roundToInt()
        val e = b.width - 1 - i
        return listOf(b.getPixel(i, i), b.getPixel(e, i), b.getPixel(i, e), b.getPixel(e, e))
            .all { android.graphics.Color.alpha(it) > 200 }
    }


    private inline fun layer(draw: Canvas.() -> Unit): Bitmap = createBitmap(W, H).also { Canvas(it).draw() }

    private fun raster(d: Drawable, n: Int): IntArray {
        val b = createBitmap(n, n)
        d.setBounds(0, 0, n, n)
        d.draw(Canvas(b))
        return IntArray(n * n).also { b.getPixels(it, 0, n, 0, 0, n, n); b.recycle() }
    }

    private inline fun bounds(
        px: IntArray, n: Int,
        within: android.graphics.Rect = android.graphics.Rect(0, 0, n, n),
        isLogo: (Int, Int) -> Boolean,
    ): RectF? {
        var l = n; var t = n; var r = -1; var b = -1
        for (y in within.top until within.bottom) for (x in within.left until within.right) {
            val i = y * n + x
            if (isLogo(i, px[i])) { l = minOf(l, x); r = maxOf(r, x); t = minOf(t, y); b = maxOf(b, y) }
        }
        return if (r < 0) null else RectF(l / n.toFloat(), t / n.toFloat(), (r + 1) / n.toFloat(), (b + 1) / n.toFloat())
    }

    private fun distance(a: Int, b: Int): Int =
        kotlin.math.abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) +
            kotlin.math.abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) +
            kotlin.math.abs((a and 0xFF) - (b and 0xFF))

    private fun isFlat(px: IntArray): Boolean {
        val first = px[px.size / 2]
        return px.count { distance(it, first) > 24 || kotlin.math.abs((it ushr 24) - (first ushr 24)) > 24 } < px.size / 20
    }

    private fun iconOnTile(logo: RectF, scale: Float = 1f): RectF {
        val aspect = logo.width() / logo.height()
        val h = minOf(scale * LOGO * H / kotlin.math.sqrt(aspect), 0.66f * H, 0.62f * W / aspect)
        val box = h / logo.height()
        val left = W / 2f - box * logo.centerX()
        val top = H / 2f - box * logo.centerY()
        return RectF(left, top, left + box, top + box)
    }

    private fun visible(px: IntArray, n: Int): RectF? = bounds(px, n) { _, c -> c ushr 24 > 24 }

    private fun solid(c: Int) = c ushr 24 > 200

    private fun solidBox(px: IntArray, n: Int): android.graphics.Rect? {
        var l = n; var t = n; var r = -1; var b = -1
        for (y in 0 until n) for (x in 0 until n) if (solid(px[y * n + x])) {
            l = minOf(l, x); r = maxOf(r, x); t = minOf(t, y); b = maxOf(b, y)
        }
        return if (r < 0) null else android.graphics.Rect(l, t, r + 1, b + 1)
    }

    private fun placeLogo(d: Drawable, find: (IntArray, Int) -> RectF?, round: Boolean = false, scale: Float = 1f): Pair<Bitmap, RectF>? {
        val first = find(raster(d, SAMPLE), SAMPLE) ?: return null
        val size = iconOnTile(first, scale).width().roundToInt().coerceIn(16, 4 * H)
        val icon = createBitmap(size, size)
        d.setBounds(0, 0, size, size)
        d.draw(Canvas(icon))
        val px = IntArray(size * size).also { icon.getPixels(it, 0, size, 0, 0, size, size) }
        val logo = find(px, size) ?: first
        val at = iconOnTile(logo, scale)
        val out = layer {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            if (round) {
                drawPath(smoothRect(at, at.width() * 0.225f), paint.apply {
                    shader = android.graphics.BitmapShader(icon, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                        setLocalMatrix(android.graphics.Matrix().apply {
                            setScale(at.width() / size, at.height() / size)
                            postTranslate(at.left, at.top)
                        })
                    }
                })
            } else {
                drawBitmap(icon, null, at, paint)
            }
        }
        icon.recycle()
        val box = at.width()
        return out to RectF(at.left + box * logo.left, at.top + box * logo.top, at.left + box * logo.right, at.top + box * logo.bottom)
    }

    private fun feather(layer: Bitmap, keep: RectF, margin: Float = H * 0.04f, fade: Float = H * 0.22f) {
        val c = Canvas(layer)
        fun mask(from: Float, to: Float, horizontal: Boolean) = Paint().apply {
            val span = to - from + 2 * (margin + fade)
            val f = fade / span
            val start = from - margin - fade
            shader = LinearGradient(
                if (horizontal) start else 0f, if (horizontal) 0f else start,
                if (horizontal) start + span else 0f, if (horizontal) 0f else start + span,
                intArrayOf(0, android.graphics.Color.BLACK, android.graphics.Color.BLACK, 0),
                floatArrayOf(0f, f, 1f - f, 1f), Shader.TileMode.CLAMP,
            )
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
        }
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), mask(keep.left, keep.right, horizontal = true))
        c.drawRect(0f, 0f, W.toFloat(), H.toFloat(), mask(keep.top, keep.bottom, horizontal = false))
    }

    private fun finish(back: Bitmap, fore: Bitmap?): TileArt {
        val composed = if (fore == null) back else back.copy(Bitmap.Config.ARGB_8888, true).also {
            Canvas(it).drawBitmap(fore, 0f, 0f, null)
        }
        val tint = dominant(composed)
        val backdrop = backdropOf(composed)
        if (composed !== back) composed.recycle()
        var foreAt = androidx.compose.ui.geometry.Rect.Zero
        val trimmed = fore?.let { layer ->
            val px = IntArray(W * H).also { layer.getPixels(it, 0, W, 0, 0, W, H) }
            var l = W; var t = H; var r = -1; var b = -1
            for (y in 0 until H) for (x in 0 until W) if (px[y * W + x] ushr 24 != 0) {
                l = minOf(l, x); r = maxOf(r, x); t = minOf(t, y); b = maxOf(b, y)
            }
            if (r < 0) return@let null
            foreAt = androidx.compose.ui.geometry.Rect(l / W.toFloat(), t / H.toFloat(), (r + 1) / W.toFloat(), (b + 1) / H.toFloat())
            Bitmap.createBitmap(layer, l, t, r - l + 1, b - t + 1).also { if (it !== layer) layer.recycle() }
        }
        return TileArt(
            back = back.asImageBitmap(),
            fore = trimmed?.asImageBitmap(),
            foreAt = foreAt,
            tint = Color(tint),
            backdrop = backdrop.asImageBitmap(),
        )
    }

    fun flatten(art: TileArt): Bitmap {
        val out = art.back.asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, true)
        art.fore?.let { f ->
            val r = art.foreAt
            Canvas(out).drawBitmap(
                f.asAndroidBitmap(), null,
                RectF(r.left * out.width, r.top * out.height, r.right * out.width, r.bottom * out.height),
                Paint(Paint.FILTER_BITMAP_FLAG),
            )
        }
        return out
    }

    private fun Canvas.drawCover(
        d: Drawable, w: Int, h: Int,
        iw: Int = d.intrinsicWidth.takeIf { it > 0 } ?: w,
        ih: Int = d.intrinsicHeight.takeIf { it > 0 } ?: h,
    ) {
        val scale = max(w / iw.toFloat(), h / ih.toFloat())
        val dw = (iw * scale).roundToInt()
        val dh = (ih * scale).roundToInt()
        val l = (w - dw) / 2
        val t = (h - dh) / 2
        d.setBounds(l, t, l + dw, t + dh)
        d.draw(this)
    }

    private fun dominant(b: Bitmap): Int {
        val small = b.scale(24, 14)
        var r = 0.0; var g = 0.0; var bl = 0.0; var total = 0.0
        val hsv = FloatArray(3)
        for (y in 0 until small.height) for (x in 0 until small.width) {
            val c = small.getPixel(x, y)
            if (android.graphics.Color.alpha(c) < 128) continue
            android.graphics.Color.colorToHSV(c, hsv)
            val wgt = hsv[1] * hsv[2] * hsv[2] + 0.04
            r += android.graphics.Color.red(c) * wgt
            g += android.graphics.Color.green(c) * wgt
            bl += android.graphics.Color.blue(c) * wgt
            total += wgt
        }
        if (small !== b) small.recycle()
        if (total == 0.0) return GRAPHITE_TOP
        return android.graphics.Color.rgb((r / total).roundToInt(), (g / total).roundToInt(), (bl / total).roundToInt())
    }

    private fun tileFromTint(tint: Int): Pair<Int, Int> {
        val hsl = FloatArray(3)
        ColorUtils.colorToHSL(tint, hsl)
        if (hsl[1] < 0.18f) return 0xFF6E6E74.toInt() to 0xFF3A3A3C.toInt()
        val s = hsl[1].coerceIn(0.5f, 0.85f)
        return ColorUtils.HSLToColor(floatArrayOf(hsl[0], s, 0.56f)) to ColorUtils.HSLToColor(floatArrayOf(hsl[0], s, 0.4f))
    }

    private fun backdropOf(src: Bitmap): Bitmap {
        val w = 64
        val h = 36
        val small = src.scale(w, h)
        val px = IntArray(w * h)
        small.getPixels(px, 0, w, 0, 0, w, h)
        repeat(3) { Images.boxBlur(px, w, h, 4) }
        val blurred = createBitmap(w, h)
        blurred.setPixels(px, 0, w, 0, 0, w, h)
        val hsv = FloatArray(3)
        var sat = 0f; var value = 0f
        for (c in px) { android.graphics.Color.colorToHSV(c, hsv); sat += hsv[1]; value += hsv[2] }
        sat /= px.size; value /= px.size
        val gain = if (sat < 0.2f) 0.82f - 0.5f * value * (1f - sat / 0.2f) else 0.82f
        val cm = ColorMatrix().apply {
            setSaturation(1.5f)
            postConcat(ColorMatrix().apply { setScale(gain, gain, gain, 1f) })
        }
        val bw = 192
        val bh = 108
        val out = createBitmap(bw, bh)
        Canvas(out).apply {
            drawBitmap(
                blurred, null, android.graphics.Rect(0, 0, bw, bh),
                Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(cm) },
            )
            drawRect(0f, 0f, bw.toFloat(), bh.toFloat(), Paint().apply {
                shader = LinearGradient(
                    0f, 0f, 0f, bh.toFloat(),
                    intArrayOf(black(0.10f), black(0.32f), black(0.80f), black(0.94f)),
                    floatArrayOf(0f, 0.42f, 0.72f, 1f), Shader.TileMode.CLAMP,
                )
            })
            drawRect(0f, 0f, bw.toFloat(), bh.toFloat(), Paint().apply {
                shader = LinearGradient(0f, 0f, bw * 0.55f, 0f, black(0.38f), 0, Shader.TileMode.CLAMP)
            })
        }
        small.recycle(); blurred.recycle()
        return out
    }

    private fun black(alpha: Float) = android.graphics.Color.argb((alpha * 255).roundToInt(), 0, 0, 0)
}

private fun smoothRect(r: RectF, radius: Float): android.graphics.Path =
    smoothRectPath(Rect(r.left, r.top, r.right, r.bottom), radius).asAndroidPath()
