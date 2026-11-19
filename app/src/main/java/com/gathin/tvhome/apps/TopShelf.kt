package com.gathin.tvhome.apps

import android.app.ActivityOptions
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.media.tv.TvContract
import android.os.Build
import android.view.View
import com.gathin.tvhome.wallpaper.Images
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

enum class ShelfSource { WATCH_NEXT, PREVIEW }

data class ShelfCard(
    val pkg: String,
    val title: String,
    val imageUri: String?,
    val backdropUri: String?,
    val intentUri: String?,
    val source: ShelfSource,
)

object TopShelfContent {
    private val lock = Any()
    private var showsByPkg: Map<String, List<ShelfCard>> = emptyMap()

    private var appCache: Map<String, ShelfApp?> = emptyMap()

    fun shelfApp(context: Context, pkg: String): ShelfApp? {
        synchronized(lock) {
            if (appCache.containsKey(pkg)) return appCache[pkg]
        }
        val inputs = appArtInputs(context, pkg)
        val app = inputs?.let { ShelfApp(pkg, it.label, it.banner, it.icon, it.pictureIcon) }
        synchronized(lock) { appCache = appCache + (pkg to app) }
        return app
    }

    fun clearAppCache() {
        synchronized(lock) { appCache = emptyMap() }
        clearStageArtCache()
    }

    data class StageArt(val color: Int, val mark: Bitmap?)
    private var stageArtCache: Map<String, StageArt> = emptyMap()

    fun stageArt(context: Context, app: ShelfApp, screenW: Int): StageArt {
        synchronized(lock) { stageArtCache[app.pkg]?.let { return it } }
        val color = edgeColour(app.picture())
        val foreground: android.graphics.drawable.Drawable? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (app.icon as? android.graphics.drawable.AdaptiveIconDrawable)?.foreground
        } else null
        val mark = markOf(context, app.pkg, app.label, foreground, app.icon, app.banner, screenW, color)
        val markPx = (210f / 960f * screenW).toInt().coerceAtLeast(1)
        val markDrawable = foreground ?: app.icon ?: app.banner
        val cacheable = hasTileMark(app.pkg) || markDrawable == null ||
            run {
                val nativePx = pixels(if (foreground != null) foreground else markDrawable)
                val scaled = if (foreground != null) nativePx / MAX_UPSCALE else nativePx.toFloat()
                val need = scaled * MAX_UPSCALE
                need >= markPx || TopShelfLogos.cachedOwn(context, app.pkg, app.label) != null
            }
        if (cacheable) {
            synchronized(lock) { stageArtCache = stageArtCache + (app.pkg to StageArt(color, mark)) }
        }
        return StageArt(color, mark)
    }

    fun clearStageArtCache() {
        synchronized(lock) { stageArtCache = emptyMap() }
    }

    fun reloadAll(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (!hasListingsPermission(context)) {
            synchronized(lock) { showsByPkg = emptyMap() }
            return
        }
        val map = HashMap<String, MutableList<ShelfCard>>()
        val watchNext = read(context, TvContract.WatchNextPrograms.CONTENT_URI, true)
            .sortedByDescending { (key, _) -> key }
        for ((_, card) in watchNext) {
            map.getOrPut(card.pkg) { ArrayList() } += card
        }
        val preview = read(context, TvContract.PreviewPrograms.CONTENT_URI, false)
            .sortedByDescending { (key, _) -> key }
        for ((_, card) in preview) {
            map.getOrPut(card.pkg) { ArrayList() } += card
        }
        synchronized(lock) { showsByPkg = map }
    }

    private fun read(
        context: Context,
        uri: android.net.Uri,
        isWatchNext: Boolean,
    ): List<Pair<Long, ShelfCard>> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return emptyList()
        val cursor = runCatching {
            context.contentResolver.query(uri, null, null, null, null)
        }.getOrNull() ?: return emptyList()
        val out = ArrayList<Pair<Long, ShelfCard>>()
        cursor.use {
            while (it.moveToNext()) {
                try {
                    if (longColumn(it, "browsable", 1L) != 0L) {
                        val title = column(it, "title")
                        val pkg = column(it, "package_name")
                        if (title != null && pkg != null) {
                            var image = column(it, "thumbnail_uri")
                            if (image == null) image = column(it, "poster_art_uri")
                            var backdrop = column(it, "poster_art_uri")
                            if (backdrop == null) backdrop = image
                            val key = if (isWatchNext) {
                                longColumn(it, "last_engagement_time_utc_millis", 0L)
                            } else {
                                longColumn(it, "weight", 0L)
                            }
                            out += key to ShelfCard(
                                pkg = pkg,
                                title = title,
                                imageUri = image,
                                backdropUri = backdrop,
                                intentUri = column(it, "intent_uri"),
                                source = if (isWatchNext) ShelfSource.WATCH_NEXT else ShelfSource.PREVIEW,
                            )
                        }
                    }
                } catch (t: Throwable) {
                    android.util.Log.w("FlowUpNext", "read row failed", t)
                    continue
                }
            }
        }
        return out
    }

    private fun column(cursor: android.database.Cursor, name: String): String? {
        val i = cursor.getColumnIndex(name)
        if (i < 0 || cursor.isNull(i)) return null
        return cursor.getString(i)
    }

    private fun longColumn(cursor: android.database.Cursor, name: String, default: Long): Long {
        val i = cursor.getColumnIndex(name)
        return if (i < 0 || cursor.isNull(i)) default else cursor.getLong(i)
    }

    fun cardsFor(pkg: String): List<ShelfCard> {
        val list = synchronized(lock) { showsByPkg[pkg] } ?: return emptyList()
        android.util.Log.i("AerialDiag", "topshelf $pkg: ${list.size} cards ${list.firstOrNull()?.source}")
        return list
    }

    fun allShows(): Map<String, List<ShelfCard>> = synchronized(lock) { showsByPkg }

    fun shelfEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences("topshelf", Context.MODE_PRIVATE)
        if (!prefs.getBoolean("app_content_enabled", true)) return false
        if (prefs.getBoolean("up_full_screen", false) && !appMarkTvLike()) return false
        return true
    }

    fun appMarkTvLike(): Boolean = true

    fun appArtEnabled(context: Context): Boolean =
        context.getSharedPreferences("topshelf", Context.MODE_PRIVATE)
            .getBoolean("shelf_app_art", false)

    private const val ART_KEEP_MS = 60_000L
    private val artCache = object : android.util.LruCache<String, Bitmap>(3) {
        override fun sizeOf(key: String, value: Bitmap): Int = 1
    }
    private val amazonArt = java.util.regex.Pattern.compile("""^(https://m\.media-amazon\.com/images/S/[^.]+)\._UR(\d+),(\d+)_\..*$""")

    fun appArtBackdrop(context: Context, pkg: String, cards: List<ShelfCard>, w: Int, h: Int): Bitmap? {
        if (!appArtEnabled(context) || cards.isEmpty()) return null
        val prefs = context.getSharedPreferences("topshelf", Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val keepUntil = prefs.getLong("app_art_keep_until_$pkg", 0L)
        val lastIndex = prefs.getInt("app_art_index_$pkg", -1)
        val startIndex = if (now < keepUntil && lastIndex >= 0) lastIndex else (lastIndex + 1) % cards.size
        val small = prefs.getStringSet("featured_small", emptySet()) ?: emptySet()
        var tried = 0
        for (offset in 0 until cards.size) {
            val index = (startIndex + offset) % cards.size
            val card = cards[index]
            val raw = card.backdropUri ?: card.imageUri ?: continue
            val url = cleanArt(raw)
            if (small.contains(url)) continue
            val still = still(context, "p:$url", w, h)
            if (still != null) {
                prefs.edit()
                    .putInt("app_art_index_$pkg", index)
                    .putLong("app_art_keep_until_$pkg", now + ART_KEEP_MS)
                    .apply()
                return still
            }
            tried++
            if (tried >= 4) return null
        }
        return null
    }

    fun freshArt(context: Context) {
        val prefs = context.getSharedPreferences("topshelf", Context.MODE_PRIVATE)
        val edit = prefs.edit()
        for (key in prefs.all.keys) {
            if (key.startsWith("app_art_keep_until_")) edit.remove(key)
        }
        edit.apply()
    }

    private fun cleanArt(url: String): String {
        val m = amazonArt.matcher(url)
        return if (m.matches()) m.group(1) + "._UR" + m.group(2) + "," + m.group(3) + "_.jpg" else url
    }

    private fun still(context: Context, key: String, w: Int, h: Int): Bitmap? {
        artCache.get(key)?.let { return it }
        val file = File(File(context.filesDir, "appart"), key.hashCode().toString() + ".jpg")
        if (file.exists()) {
            runCatching {
                BitmapFactory.decodeFile(file.absolutePath)?.let { bmp ->
                    val out = Images.cover(bmp, w, h).also { if (it !== bmp) bmp.recycle() }
                    artCache.put(key, out)
                    return out
                }
            }
        }
        val uri = key.removePrefix("p:")
        val bytes = readBytes(context, uri) ?: return null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        if (bounds.outWidth < 1280 || bounds.outHeight > bounds.outWidth) {
            val prefs = context.getSharedPreferences("topshelf", Context.MODE_PRIVATE)
            val set = HashSet(prefs.getStringSet("featured_small", emptySet()) ?: emptySet())
            set.add(uri)
            prefs.edit().putStringSet("featured_small", set).apply()
            return null
        }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, null) ?: return null
        val out = Images.cover(decoded, w, h).also { if (it !== decoded) decoded.recycle() }
        runCatching {
            file.parentFile?.mkdirs()
            java.io.FileOutputStream(file).use { fos ->
                out.compress(Bitmap.CompressFormat.JPEG, 92, fos)
            }
        }.onFailure { file.delete() }
        artCache.put(key, out)
        return out
    }

    fun hasListingsPermission(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return true
        return context.checkSelfPermission("android.permission.READ_TV_LISTINGS") ==
            PackageManager.PERMISSION_GRANTED
    }

    private const val TOPSHELF_PREFS = "topshelf"

    fun listingsAsked(context: Context): Boolean =
        context.getSharedPreferences(TOPSHELF_PREFS, Context.MODE_PRIVATE)
            .getBoolean("asked_listings", false)

    fun markListingsAsked(context: Context) {
        context.getSharedPreferences(TOPSHELF_PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("asked_listings", true).apply()
    }

    fun cardIntent(context: Context, card: ShelfCard, pkg: String): Intent? {
        val uri = card.intentUri ?: return null
        val intent = runCatching { Intent.parseUri(uri, Intent.URI_INTENT_SCHEME) }.getOrNull()
            ?: return null
        intent.setSelector(null)
        intent.flags = intent.flags and -196
        val targetPkg = intent.`package` ?: intent.component?.packageName
        if (targetPkg != null && targetPkg != pkg) return null
        if (targetPkg == null) intent.setPackage(pkg)
        if (intent.resolveActivity(context.packageManager) == null) return null
        return intent
    }

    fun launchCard(context: Context, intent: Intent, sourceView: View? = null): Boolean {
        if (context !is android.app.Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val activity = context as? android.app.Activity
        val options = if (activity != null && sourceView != null) {
            val rect = Rect()
            if (sourceView.getGlobalVisibleRect(rect)) {
                runCatching {
                    ActivityOptions.makeScaleUpAnimation(
                        activity.window.decorView, rect.left, rect.top, rect.width(), rect.height(),
                    ).toBundle()
                }.getOrNull()
            } else null
        } else null
        return runCatching {
            if (options != null) activity?.startActivity(intent, options) else context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    fun appLaunchIntent(context: Context, pkg: String): Intent? {
        val pm = context.packageManager
        return runCatching { pm.getLeanbackLaunchIntentForPackage(pkg) }.getOrNull()
            ?: runCatching { pm.getLaunchIntentForPackage(pkg) }.getOrNull()
    }

    private val posterCache = object : android.util.LruCache<String, Bitmap>(16 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    fun loadPoster(context: Context, uri: String, w: Int, h: Int): Bitmap? {
        val key = "${w}x${h}:$uri"
        posterCache.get(key)?.let { return it }
        val out = decodePoster(context, uri, w, h) ?: return null
        posterCache.put(key, out)
        return out
    }

    private fun decodePoster(context: Context, uri: String, w: Int, h: Int): Bitmap? {
        return runCatching {
            val bytes = readBytes(context, uri) ?: return null
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
            val hasAlpha = decoded.hasAlpha()
            if (hasAlpha && decoded.width == w && decoded.height == h) return decoded
            val scale = maxOf(w.toFloat() / decoded.width, h.toFloat() / decoded.height)
            val cropW = minOf(decoded.width, maxOf(1, Math.round(w / scale)))
            val cropH = minOf(decoded.height, maxOf(1, Math.round(h / scale)))
            val left = (decoded.width - cropW) / 2
            val top = (decoded.height - cropH) / 2
            val out = Bitmap.createBitmap(
                w, h, if (!hasAlpha) Bitmap.Config.RGB_565 else Bitmap.Config.ARGB_8888,
            )
            if (hasAlpha) out.setHasAlpha(true)
            Canvas(out).drawBitmap(
                decoded,
                Rect(left, top, cropW + left, cropH + top),
                Rect(0, 0, w, h),
                Paint(6),
            )
            decoded.recycle()
            out
        }.getOrNull()
    }

    private fun readBytes(context: Context, uri: String): ByteArray? {
        return runCatching {
            when {
                uri.startsWith("http://") -> {
                    fetchHttp("https://" + uri.substring(7))
                        ?: fetchHttp(uri)
                }
                uri.startsWith("https://") -> fetchHttp(uri)
                else -> context.contentResolver.openInputStream(android.net.Uri.parse(uri))?.use {
                    it.readBytes()
                }
            }
        }.getOrNull()
    }

    private fun fetchHttp(uri: String): ByteArray? {
        return runCatching {
            val conn = URL(uri).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 10_000
                conn.readTimeout = 30_000
                conn.instanceFollowRedirects = true
                conn.connect()
                if (conn.responseCode !in 200..299) return null
                conn.inputStream.use { it.readBytes() }
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    data class AppArtInputs(
        val label: String,
        val foreground: android.graphics.drawable.Drawable?,
        val icon: android.graphics.drawable.Drawable?,
        val banner: android.graphics.drawable.Drawable?,
        val pictureIcon: android.graphics.drawable.Drawable?,
    )

    fun appArtInputs(context: Context, pkg: String): AppArtInputs? {
        val pm = context.packageManager
        val ai = runCatching { pm.getApplicationInfo(pkg, 0) }.getOrNull() ?: return null
        val icon = markDrawable(pm, ai) ?: runCatching { ai.loadIcon(pm) }.getOrNull()
        val resolve = runCatching {
            pm.queryIntentActivities(
                Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0
            ).firstOrNull { it.activityInfo?.packageName == pkg }
        }.getOrNull()
        val banner = runCatching {
            resolve?.activityInfo?.loadBanner(pm)
                ?: resolve?.activityInfo?.applicationInfo?.loadBanner(pm)
                ?: ai.loadBanner(pm)
        }.getOrNull()
        val pictureIcon = runCatching { resolve?.loadIcon(pm) }.getOrNull()
            ?: runCatching { ai.loadIcon(pm) }.getOrNull()
        val label = runCatching { resolve?.loadLabel(pm)?.toString() }.getOrNull()
            ?: runCatching { ai.loadLabel(pm)?.toString() }.getOrNull().orEmpty()
        val foreground: android.graphics.drawable.Drawable? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            (icon as? android.graphics.drawable.AdaptiveIconDrawable)?.foreground
        } else null
        return AppArtInputs(label, foreground, icon, banner, pictureIcon)
    }

    private fun markDrawable(pm: PackageManager, ai: android.content.pm.ApplicationInfo): android.graphics.drawable.Drawable? {
        if (ai.icon == 0) return null
        return runCatching {
            pm.getResourcesForApplication(ai.packageName).getDrawableForDensity(ai.icon, 640, null)
        }.getOrNull()
    }

    internal fun hasTileMark(pkg: String): Boolean =
        pkg == "com.android.tv.settings" || pkg == "com.android.vending"

    internal fun tileMark(pkg: String, sizePx: Int): Bitmap? {
        if (!hasTileMark(pkg)) return null
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        if (pkg != "com.android.tv.settings") {
            playMark(canvas, sizePx)
            return bmp
        }
        val f = sizePx.toFloat()
        settingsGear(canvas, f / 2f, f / 2f, f * 0.36f)
        return bmp
    }

    private fun settingsGear(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = -1447446
            setShadowLayer(0.035f * r, 0f, 0.02f * r, 1493172224)
        }
        val paint2 = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = -4013371
            setShadowLayer(0.025f * r, 0f, 0.015f * r, 1073741824)
        }
        val inner = 0.55f * r
        val gear1 = gear(cx, cy, inner, 0.66f * r, 24, 0.26, 0.16)
        gear1.addCircle(cx, cy, inner, android.graphics.Path.Direction.CW)
        gear1.addCircle(cx, cy, 0.44f * r, android.graphics.Path.Direction.CCW)
        gear1.fillType = android.graphics.Path.FillType.WINDING
        canvas.drawPath(gear1, paint2)
        val outer = 0.86f * r
        val gear2 = gear(cx, cy, outer, r, 36, 0.3, 0.2)
        val cut = android.graphics.Path().apply {
            addCircle(cx, cy, outer, android.graphics.Path.Direction.CW)
            addCircle(cx, cy, 0.75f * r, android.graphics.Path.Direction.CCW)
        }
        gear2.op(cut, android.graphics.Path.Op.UNION)
        val spoke = 0.085f * r
        for (i in 0 until 3) {
            val rect = android.graphics.Path().apply {
                addRect(0f, -spoke, r * 0.8f, spoke, android.graphics.Path.Direction.CW)
            }
            val matrix = android.graphics.Matrix().apply {
                setRotate(-i * 120f)
                postTranslate(cx, cy)
            }
            rect.transform(matrix)
            gear2.op(rect, android.graphics.Path.Op.UNION)
        }
        val hub = android.graphics.Path().apply {
            addCircle(cx, cy, 0.15f * r, android.graphics.Path.Direction.CW)
        }
        gear2.op(hub, android.graphics.Path.Op.UNION)
        canvas.drawPath(gear2, paint)
        val paint4 = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = -8618880 }
        canvas.drawCircle(cx, cy, 0.06f * r, paint4)
    }

    private fun gear(cx: Float, cy: Float, rInner: Float, rOuter: Float, teeth: Int, toothFrac: Double, tipFrac: Double): android.graphics.Path {
        val path = android.graphics.Path()
        val step = 2.0 * Math.PI / teeth
        for (i in 0 until teeth) {
            val a = i * step
            val pts = arrayOf(
                doubleArrayOf(a - step * toothFrac, rInner.toDouble()),
                doubleArrayOf(a - step * tipFrac, rOuter.toDouble()),
                doubleArrayOf(a + step * tipFrac, rOuter.toDouble()),
                doubleArrayOf(a + step * toothFrac, rInner.toDouble()),
            )
            for (j in 0 until 4) {
                val x = cx + (Math.cos(pts[j][0]) * pts[j][1]).toFloat()
                val y = cy + (Math.sin(pts[j][0]) * pts[j][1]).toFloat()
                if (j == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            path.close()
        }
        return path
    }

    private fun playMark(canvas: Canvas, size: Int) {
        val f = size.toFloat()
        val f3 = f * 0.62f
        val f4 = 0.9f * f3
        val f6 = (f - f4) / 2f
        val f7 = (f - f3) / 2f
        val f8 = f6 + f4
        val f9 = f7 + f3 / 2f
        val f10 = f7 + f3
        val corners = arrayOf(
            floatArrayOf(f6, f7),
            floatArrayOf(f8, f9),
            floatArrayOf(f6, f10),
        )
        val f11 = 0.1f * f3
        val radii = floatArrayOf(f11, 0.16f * f3, f11)
        val path = android.graphics.Path()
        for (i in 0 until 3) {
            val prev = corners[(i + 2) % 3]
            val cur = corners[i]
            val next = corners[(i + 1) % 3]
            val p1 = toward(cur, prev, radii[i])
            val p2 = toward(cur, next, radii[i])
            if (i == 0) path.moveTo(p1[0], p1[1]) else path.lineTo(p1[0], p1[1])
            path.quadTo(cur[0], cur[1], p2[0], p2[1])
        }
        path.close()
        val facets = Bitmap.createBitmap(canvas.width, canvas.height, Bitmap.Config.ARGB_8888)
        val fc = Canvas(facets)
        val f16 = f6 + 0.5f * f4
        val f17 = f4 * 0.62f + f6
        val f18 = f3 * 0.31f
        val f19 = f6 - 2f
        val f20 = f7 - 2f
        val f21 = f10 + 2f
        fillTri(fc, -12417548, f19, f20, f16, f9, f19, f21)
        val f22 = f17 + f4 * 0.2f
        val f23 = f7 + f18
        fillTri(fc, -13326253, f19, f20, f22, f23 - f11, f16, f9)
        val f24 = f10 - f18
        fillTri(fc, -1424587, f19, f21, f16, f9, f22, f24 + f11)
        val edge = android.graphics.Path().apply {
            moveTo(f17, f23)
            val f25 = f8 + 4f
            lineTo(f25, f7)
            lineTo(f25, f10)
            lineTo(f17, f24)
            lineTo(f16, f9)
            close()
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = -279548 }
        fc.drawPath(edge, paint)
        val shaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = android.graphics.BitmapShader(facets, android.graphics.Shader.TileMode.CLAMP, android.graphics.Shader.TileMode.CLAMP)
        }
        canvas.drawPath(path, shaderPaint)
        facets.recycle()
    }

    private fun toward(a: FloatArray, b: FloatArray, d: Float): FloatArray {
        val dx = b[0] - a[0]
        val dy = b[1] - a[1]
        val len = Math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
        return floatArrayOf(a[0] + dx / len * d, a[1] + dy / len * d)
    }

    private fun fillTri(canvas: Canvas, color: Int, x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
        val path = android.graphics.Path().apply {
            moveTo(x1, y1)
            lineTo(x2, y2)
            lineTo(x3, y3)
            close()
        }
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            style = Paint.Style.FILL_AND_STROKE
            strokeWidth = 1f
        }
        canvas.drawPath(path, paint)
    }

    internal fun markOf(
        context: Context,
        pkg: String,
        label: String,
        foreground: android.graphics.drawable.Drawable?,
        icon: android.graphics.drawable.Drawable?,
        banner: android.graphics.drawable.Drawable?,
        screenW: Int,
        stageColour: Int,
    ): Bitmap? {
        val markPx = (210f / 960f * screenW).toInt().coerceAtLeast(1)
        tileMark(pkg, markPx)?.let { return it }
        val markDrawable = foreground ?: icon ?: banner ?: return null
        val isAdaptive = foreground != null
        val nativePx = pixels(if (isAdaptive) foreground else markDrawable)
        val scaled = if (isAdaptive) nativePx / MAX_UPSCALE else nativePx.toFloat()
        val need = scaled * MAX_UPSCALE
        if (need < markPx) {
            TopShelfLogos.cachedOwn(context, pkg, label)?.let { logo ->
                runCatching { logoMark(logo, markPx, stageColour) }.getOrNull()?.let { return it }
            }
            TopShelfLogos.prefetch(context, pkg, label)
            val smallPx = need.toInt().coerceAtLeast(1)
            return drawMarkBitmap(foreground, icon, banner, smallPx)
        }
        return drawMarkBitmap(foreground, icon, banner, markPx)
    }

    internal fun drawMarkBitmap(
        foreground: android.graphics.drawable.Drawable?,
        icon: android.graphics.drawable.Drawable?,
        banner: android.graphics.drawable.Drawable?,
        sizePx: Int,
    ): Bitmap? {
        val markDrawable = foreground ?: icon ?: banner ?: return null
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        if (foreground != null) {
            val q = sizePx / 4
            foreground.setBounds(-q, -q, sizePx + q, sizePx + q)
            foreground.draw(canvas)
            return bmp
        }
        markDrawable.setBounds(0, 0, sizePx, sizePx)
        markDrawable.draw(canvas)
        unback(bmp)
        radialFade(bmp)
        return bmp
    }

    internal fun logoMark(logo: TopShelfLogos.Logo, sizePx: Int, stageColour: Int): Bitmap {
        val bmp = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val stageLum = luminance(stageColour)
        paint.color = if (kotlin.math.abs(luminance(logo.color) - stageLum) > 0.3f) {
            logo.color or -16777216
        } else {
            if (stageLum > 0.6f) -15658476 else -1
        }
        val path = SvgPath.parse(logo.path)
        val bounds = android.graphics.RectF()
        path.computeBounds(bounds, true)
        val scale = (0.6f * sizePx) / kotlin.math.max(0.001f, kotlin.math.max(bounds.width(), bounds.height()))
        val half = sizePx / 2f
        canvas.translate(half, half)
        canvas.scale(scale, scale)
        canvas.translate(-bounds.centerX(), -bounds.centerY())
        canvas.drawPath(path, paint)
        return bmp
    }

    private fun luminance(colour: Int): Float {
        return (android.graphics.Color.red(colour) * 0.2126f +
            android.graphics.Color.green(colour) * 0.7152f +
            android.graphics.Color.blue(colour) * 0.0722f) / 255f
    }

    private fun pixels(d: android.graphics.drawable.Drawable): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return if (d is android.graphics.drawable.BitmapDrawable) d.bitmap?.width ?: Int.MAX_VALUE else Int.MAX_VALUE
        }
        var cur: android.graphics.drawable.Drawable? = d
        while (cur != null) {
            when (cur) {
                is android.graphics.drawable.BitmapDrawable -> {
                    val bmp = cur.bitmap ?: return Int.MAX_VALUE
                    return bmp.width
                }
                is android.graphics.drawable.DrawableWrapper -> cur = cur.drawable
                else -> return Int.MAX_VALUE
            }
        }
        return Int.MAX_VALUE
    }

    private const val MAX_UPSCALE = 1.5f

    internal fun edgeColour(d: android.graphics.drawable.Drawable?): Int {
        if (d == null) return android.graphics.Color.rgb(0x1E, 0x1E, 0x1B)
        return runCatching {
            val drawable = d.constantState?.newDrawable()?.mutate() ?: d
            val small = Bitmap.createBitmap(32, 18, Bitmap.Config.ARGB_8888)
            val c = Canvas(small)
            drawable.setBounds(0, 0, 32, 18)
            drawable.draw(c)
            val px = small.getPixel(1, 9)
            small.recycle()
            if (android.graphics.Color.alpha(px) >= 200) px or -16777216
            else android.graphics.Color.rgb(0x1E, 0x1E, 0x1B)
        }.getOrDefault(android.graphics.Color.rgb(0x1E, 0x1E, 0x1B))
    }

    internal fun shade(colour: Int, factor: Float): Int {
        val r = Math.round(android.graphics.Color.red(colour) * factor).coerceIn(0, 255)
        val g = Math.round(android.graphics.Color.green(colour) * factor).coerceIn(0, 255)
        val b = Math.round(android.graphics.Color.blue(colour) * factor).coerceIn(0, 255)
        return android.graphics.Color.rgb(r, g, b)
    }

    internal fun unback(bitmap: Bitmap) {
        val width = bitmap.width
        val height = bitmap.height
        val px = IntArray(width * height)
        bitmap.getPixels(px, 0, width, 0, 0, width, height)
        val edge = width / 10
        val rowBg = IntArray(height)
        var any = false
        for (y in 0 until height) {
            val row = y * width
            val left = px[row + edge]
            val right = px[row + width - 1 - edge]
            if (android.graphics.Color.alpha(left) < 200 || android.graphics.Color.alpha(right) < 200) {
                rowBg[y] = 0
            } else {
                rowBg[y] = android.graphics.Color.rgb(
                    (android.graphics.Color.red(left) + android.graphics.Color.red(right)) / 2,
                    (android.graphics.Color.green(left) + android.graphics.Color.green(right)) / 2,
                    (android.graphics.Color.blue(left) + android.graphics.Color.blue(right)) / 2,
                )
                any = true
            }
        }
        if (!any) return
        var last = 0
        for (y in 0 until height) {
            if (rowBg[y] != 0) last = rowBg[y] else if (last != 0) rowBg[y] = last
        }
        last = 0
        for (y in height - 1 downTo 0) {
            if (rowBg[y] != 0) last = rowBg[y] else rowBg[y] = last
        }
        for (y in 0 until height) {
            val bg = rowBg[y]
            val br = android.graphics.Color.red(bg)
            val bgG = android.graphics.Color.green(bg)
            val bb = android.graphics.Color.blue(bg)
            for (x in 0 until width) {
                val i = y * width + x
                val p = px[i]
                val a = p ushr 24
                if (a != 0) {
                    val dr = android.graphics.Color.red(p) - br
                    val dg = android.graphics.Color.green(p) - bgG
                    val db = android.graphics.Color.blue(p) - bb
                    val dist = kotlin.math.sqrt((dr * dr + dg * dg + db * db).toFloat())
                    val keep = ((dist - 28f) / 42f).coerceIn(0f, 1f)
                    px[i] = (p and 0x00FFFFFF) or (Math.round(a * keep) shl 24)
                }
            }
        }
        bitmap.setPixels(px, 0, width, 0, 0, width, height)
    }

    internal fun radialFade(bitmap: Bitmap) {
        val size = bitmap.width.toFloat()
        val half = size / 2f
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_IN)
            shader = android.graphics.RadialGradient(
                half, half, half,
                intArrayOf(-16777216, -16777216, 0),
                floatArrayOf(0f, 0.8f, 1f),
                android.graphics.Shader.TileMode.CLAMP,
            )
        }
        Canvas(bitmap).drawRect(0f, 0f, size, size, paint)
    }
}

internal object TopShelfLogos {
    data class Logo(val path: String, val color: Int)

    private val slugs = mapOf(
        "com.android.vending" to "googleplay",
        "com.google.android.youtube.tv" to "youtube",
        "com.google.android.youtube.tvmusic" to "youtubemusic",
        "com.netflix.ninja" to "netflix",
        "com.plexapp.android" to "plex",
        "com.spotify.tv.android" to "spotify",
        "com.disney.disneyplus" to "disneyplus",
        "com.hbo.hbonow" to "hbomax",
        "com.tubitv" to "tubi",
        "tv.twitch.android.app" to "twitch",
    )

    private val titlePat = java.util.regex.Pattern.compile("<title>([^<]*)</title>")
    private val fillPat = java.util.regex.Pattern.compile("fill=\"#([0-9a-fA-F]{6})\"")
    private val dPat = java.util.regex.Pattern.compile("<path[^>]*\\sd=\"([^\"]+)\"")

    private val asked = java.util.Collections.synchronizedSet(mutableSetOf<String>())

    private fun slugFor(pkg: String, label: String): String {
        slugs[pkg]?.let { return it }
        return label.lowercase(java.util.Locale.ROOT)
            .replace("+", "plus").replace(".", "dot").replace("&", "and")
            .replace(Regex("[^a-z0-9]"), "")
    }

    private fun plain(s: String): String =
        s.lowercase(java.util.Locale.ROOT).replace(Regex("[^a-z0-9]"), "")

    private fun logosDir(context: Context): java.io.File {
        val dir = java.io.File(context.filesDir, "logos")
        dir.mkdirs()
        return dir
    }

    fun cachedOwn(context: Context, pkg: String, label: String): Logo? {
        val file = java.io.File(logosDir(context), "$pkg.svg")
        if (!file.exists()) return null
        return runCatching {
            val text = file.readText(Charsets.UTF_8)
            val m = titlePat.matcher(text)
            if (!slugs.containsKey(pkg) && (!m.find() || plain(m.group(1)) != plain(label))) return null
            parse(text)
        }.getOrNull()
    }

    private val logosExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "FlowShelfLogos")
    }

    fun prefetch(context: Context, pkg: String, label: String) {
        if (!asked.add(pkg)) return
        val appContext = context.applicationContext
        logosExecutor.execute {
            runCatching {
                val dir = logosDir(appContext)
                val file = java.io.File(dir, "$pkg.svg")
                val none = java.io.File(dir, "$pkg.none")
                if (file.exists() || none.exists()) return@runCatching
                val slug = slugFor(pkg, label)
                if (slug.isEmpty()) return@runCatching
                val conn = java.net.URL("https://cdn.simpleicons.org/$slug").openConnection() as java.net.HttpURLConnection
                try {
                    conn.connectTimeout = 6000
                    conn.readTimeout = 6000
                    val code = conn.responseCode
                    if (code == 404) {
                        none.createNewFile()
                        return@runCatching
                    }
                    if (code != 200) return@runCatching
                    val bytes = conn.inputStream.use { input ->
                        val out = java.io.ByteArrayOutputStream()
                        val buf = ByteArray(8192)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            if (out.size() > 262144) return@runCatching
                        }
                        out.toByteArray()
                    }
                    val text = String(bytes, Charsets.UTF_8)
                    if (parse(text) == null) return@runCatching
                    java.io.FileOutputStream(file).use { it.write(bytes) }
                } finally {
                    conn.disconnect()
                }
            }
        }
    }

    private fun parse(svg: String): Logo? {
        val d = dPat.matcher(svg)
        if (!d.find()) return null
        val f = fillPat.matcher(svg)
        val color = if (f.find()) {
            runCatching { android.graphics.Color.parseColor("#" + f.group(1)) }.getOrNull() ?: -14013392
        } else -14013392
        return Logo(d.group(1), color)
    }
}
