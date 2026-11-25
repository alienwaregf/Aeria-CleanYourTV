package com.gathin.tvhome.wallpaper

import android.content.Context
import android.os.Build
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.edit
import com.gathin.tvhome.ui.components.UhdCanvas
import com.gathin.tvhome.BuildConfig
import com.gathin.tvhome.R
import com.gathin.tvhome.guard.GuardSetup
import com.gathin.tvhome.tmdb.TmdbTrending
import com.gathin.tvhome.ui.home.HeroPosters
import com.gathin.tvhome.system.DiagLog
import com.gathin.tvhome.ui.home.fileFramesJam
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

enum class WallpaperKind { Auto, Live, Video, Source, Trending }

data class Wallpaper(
    val id: String,
    val kind: WallpaperKind,
    val title: String,
    val caption: String? = null,
)

enum class ScreensaverMode(val key: String) {
    Aerial("aerial"), Videos("videos"), Trending("trending"), System("system");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: Aerial
    }
}

object Wallpapers {
    private const val TAG = "AerialWallpaper"
    private const val POSTER_W = 960
    private const val POSTER_H = 540
    const val AUTO = "auto"
    const val LIVE = "aerial:live"
    const val VIDEOS = "videos:mine"
    const val TRENDING = "trending:tmdb"
    const val VIDEO_PREFIX = "video:"
    const val SOURCE_PREFIX = "source:"
    private const val SOURCE_PICTURE = "hills_night"

    private const val SAME_SPOT_MS = 2_000L

    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val prefs by lazy { app.getSharedPreferences("wallpaper", Context.MODE_PRIVATE) }
    private val liveFrame by lazy { File(app.filesDir, "live-frame.jpg") }
    private val videoFrame by lazy { File(app.filesDir, "video-frame.jpg") }

    private var sources: List<Pair<String, String>> = emptyList()
    private val bakedDir by lazy { File(app.filesDir, "backdrop").apply { mkdirs() } }
    private val thumbs = HashMap<String, Bitmap>()

    var items by mutableStateOf<List<Wallpaper>>(emptyList()); private set
    var selectedId by mutableStateOf(AUTO); private set
    private var chosenScreensaver by mutableStateOf(ScreensaverMode.Aerial)

    val screensaver: ScreensaverMode
        get() = chosenScreensaver

    fun moves(id: String = selectedId) = id == LIVE || isVideo(id)

    fun isVideo(id: String = selectedId) = id == VIDEOS || id.startsWith(VIDEO_PREFIX)

    fun isTrending(id: String = selectedId) = id == TRENDING

    fun videoFilms(id: String = selectedId): List<ScreensaverVideo> {
        if (!ready()) return emptyList()
        val all = ScreensaverVideos.all(app)
        return if (id.startsWith(VIDEO_PREFIX)) all.filter { it.id == id.removePrefix(VIDEO_PREFIX) } else all
    }

    private fun videoFrameIsOf(id: String): Boolean {
        if (!videoFrame.exists()) return false
        if (!id.startsWith(VIDEO_PREFIX)) return true
        return prefs.getString("video_film", null) == id.removePrefix(VIDEO_PREFIX)
    }

    fun isSource(id: String = selectedId) = id.startsWith(SOURCE_PREFIX)

    val sourceInput: String? get() = selectedId.takeIf(::isSource)?.removePrefix(SOURCE_PREFIX)

    var backdrop by mutableStateOf<ImageBitmap?>(null); private set

    var frosted by mutableStateOf<ImageBitmap?>(null); private set

    var filmGlassless by mutableStateOf(false)


    var pictureVersion by mutableIntStateOf(0); private set

    private var backdropJob: Job? = null
    @Volatile
    private var started = false

    var screenW = 1920; private set
    var screenH = 1080; private set

    @Synchronized
    fun init(context: Context) {
        if (started) return
        app = context.applicationContext
        val scale = UhdCanvas.scale(context)
        context.resources.displayMetrics.let {
            screenW = (maxOf(it.widthPixels, it.heightPixels) * scale).roundToInt()
            screenH = (minOf(it.widthPixels, it.heightPixels) * scale).roundToInt()
        }
        selectedId = runCatching {
            (prefs.getString("selected", AUTO) ?: AUTO).let {
                if (it.startsWith("daily:") || it.startsWith("grad:") || it.startsWith("photo:") || it == "photos:rotate") AUTO else it
            }
        }.getOrDefault(AUTO)
        chosenScreensaver = runCatching { ScreensaverMode.of(prefs.getString("screensaver", null)) }.getOrDefault(ScreensaverMode.Aerial)
        rebuildList()
        scope.launch {
            loadBaked()
            rebuildBackdrop()
        }
        scope.launch(Dispatchers.IO) { clearStaleUploads() }
        scope.launch(Dispatchers.IO) { keepForLockedBoot() }
        started = true
    }

    private fun ready() = ::app.isInitialized

    private fun clearStaleUploads() {
        val before = System.currentTimeMillis() - 60 * 60 * 1000L
        app.cacheDir.listFiles { f ->
            f.isFile && f.lastModified() < before &&
                (f.name.startsWith("upload") || f.name.startsWith("icon") || f.name.startsWith("NanoHTTPD-"))
        }?.forEach { val kb = it.length() / 1024; if (it.delete()) Log.i(TAG, "removed a stale upload, $kb KB") }
    }

    fun select(id: String) {
        if (!ready()) return
        if (items.isNotEmpty() && items.none { it.id == id }) return
        selectedId = id
        prefs.edit { putString("selected", id) }
        rebuildBackdrop()
        if (id == TRENDING) TmdbTrending.refreshIfStale()
    }

    fun chooseScreensaver(mode: ScreensaverMode) {
        if (!ready()) return
        chosenScreensaver = mode
        prefs.edit { putString("screensaver", mode.key) }
        GuardSetup.screensaverModeChanged(app)
    }

    fun resolve(id: String = selectedId): Wallpaper? =
        items.firstOrNull { it.id == id }
            ?: if (isSource(id)) Wallpaper(id, WallpaperKind.Source, id.substringAfterLast('/')) else null


    fun picture(wp: Wallpaper, w: Int, h: Int): Bitmap? {
        if (!ready()) return null
        return when (wp.kind) {
        WallpaperKind.Auto -> null
        WallpaperKind.Live -> liveFrame.takeIf { it.exists() }?.let { Images.decodeCover(it, w, h) }
            ?: Gradients.render(Gradients.presets.first(), w, h)
        WallpaperKind.Video -> videoPicture(wp.id, w, h) ?: Gradients.render(Gradients.presets.first(), w, h)
        WallpaperKind.Source -> Gradients.byKey(SOURCE_PICTURE)?.let { Gradients.render(it, w, h) }
        WallpaperKind.Trending -> trendingPicture(w, h) ?: transparentPicture(w, h)
    }
    }

    private fun trendingPicture(w: Int, h: Int): Bitmap? {
        val item = TmdbTrending.items.firstOrNull() ?: return null
        HeroPosters.peek(item.backdropUrl, w, h)?.let { return it }
        return HeroPosters.peek(item.backdropUrl, screenW, screenH)?.let { Images.cover(it, w, h) }
    }

    private fun transparentPicture(w: Int, h: Int) =
        Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

    private fun videoPicture(id: String, w: Int, h: Int): Bitmap? =
        videoFrame.takeIf { videoFrameIsOf(id) }?.let { Images.decodeCover(it, w, h) } ?: firstVideoFrame(id, w, h)

    private fun posterOf(film: ScreensaverVideo) = File(film.file.parentFile, "poster.jpg")

    fun videoPoster(film: String, w: Int, h: Int): Bitmap? =
        videoFilms(VIDEOS).firstOrNull { it.id == film }?.let(::posterOf)?.takeIf { it.exists() }?.let { Images.decodeCover(it, w, h) }

    private fun keepPoster(film: ScreensaverVideo, frame: Bitmap) {
        val poster = posterOf(film)
        if (poster.exists()) return

        val part = File(poster.path + ".part")

        runCatching {
            part.outputStream().use { Images.cover(frame, POSTER_W, POSTER_H).compress(Bitmap.CompressFormat.JPEG, 85, it) }
            check(part.renameTo(poster))
        }.onFailure { part.delete(); Log.w(TAG, "could not keep a video's poster", it) }
    }

    private fun firstVideoFrame(id: String, w: Int, h: Int): Bitmap? {
        val film = videoFilms(id).firstOrNull() ?: return null
        val poster = posterOf(film)
        if (w <= POSTER_W && poster.exists()) Images.decodeCover(poster, w, h)?.let { return it }
        if (filmGlassless || fileFramesJam(app)) return null
        val mmr = android.media.MediaMetadataRetriever()
        return try {
            mmr.setDataSource(film.file.path)
            val frame = mmr.getFrameAtTime(0) ?: return null.also { DiagLog.i("video poster: no frame of ${film.id}") }
            keepPoster(film, frame)
            val covered = Images.cover(frame, w, h)
            if (covered !== frame) frame.recycle()
            covered
        } catch (e: Exception) {
            DiagLog.i("video poster: ${film.id} not read (${e.javaClass.simpleName})")
            null
        } finally { runCatching { mmr.release() } }
    }

    /** Drops the thumbnails (about 330 KB each); the picker makes them again when it opens. */
    fun trimMemory() = synchronized(thumbs) { thumbs.clear() }

    fun thumbnail(id: String): Bitmap? {
        if (!ready()) return null
        synchronized(thumbs) { thumbs[id] }?.let { return it }
        val version = pictureVersion
        val wp = items.firstOrNull { it.id == id } ?: return null
        val b = when (wp.kind) {
            WallpaperKind.Auto -> Gradients.render(Gradients.autoThumb, 384, 216)
            WallpaperKind.Video -> videoPicture(id, 384, 216) ?: Gradients.render(Gradients.presets.first(), 384, 216)
            else -> picture(wp, 384, 216)
        }
        if (b != null) synchronized(thumbs) { if (version == pictureVersion) thumbs[id] = b }
        return b
    }

    private fun rebuildBackdrop() {
        backdropJob?.cancel()
        val id = selectedId
        backdropJob = scope.launch {
            if (id == AUTO) {
                backdrop = null
                frosted = null
                return@launch
            }
            if (id == TRENDING && TmdbTrending.items.isNotEmpty()) return@launch
            val wp = resolve(id)
            val key = wp?.let(::bakedKey)
            if (key != null && key == bakedKey && backdrop != null) return@launch
            val (bmp, frost) = withContext(Dispatchers.Default) {
                val picture = wp?.let { picture(it, screenW, screenH) }
                val frost = picture?.let(Images::frost)
                if (wp?.kind == WallpaperKind.Live || wp?.kind == WallpaperKind.Video || wp?.kind == WallpaperKind.Trending) picture to frost
                else picture?.let(Images::shadeForHome) to frost
            }
            backdrop = bmp?.asImageBitmap()
            frosted = frost?.asImageBitmap()
            if (key != null && bmp != null && frost != null) saveBaked(key, bmp, frost)
        }
    }

    fun refreshTrendingStill(index: Int) {
        if (!isTrending()) return
        val item = TmdbTrending.items.getOrNull(index) ?: return
        backdropJob?.cancel()
        backdropJob = scope.launch {
            val entry = withContext(Dispatchers.IO) {
                HeroPosters.peekEntry(item.backdropUrl, screenW, screenH)
                    ?: HeroPosters.load(item.backdropUrl, screenW, screenH)?.let {
                        HeroPosters.peekEntry(item.backdropUrl, screenW, screenH)
                    }
            } ?: return@launch
            if (!isTrending()) return@launch
            val current = TmdbTrending.items.getOrNull(index) ?: return@launch
            if (current.id != item.id || current.mediaType != item.mediaType) return@launch
            backdrop = entry.still.asImageBitmap()
            frosted = entry.frost.asImageBitmap()
        }
    }

    @Volatile
    private var bakedKey: String? = null

    private fun bakedKey(wp: Wallpaper): String {
        val stamp = when (wp.kind) {
            WallpaperKind.Live -> liveFrame.lastModified()
            WallpaperKind.Video -> if (videoFrameIsOf(wp.id)) videoFrame.lastModified() else 0L
            else -> 0L
        }
        val film = if (wp.kind == WallpaperKind.Live || wp.kind == WallpaperKind.Video) "|clear" else ""
        return "${wp.id}|$stamp|${screenW}x$screenH|${BuildConfig.VERSION_CODE}$film"
    }

    /**
     * Puts the last baked backdrop up at once, on the main thread, so Home's first frame has it. Even
     * one that is out of date (a new day's photo): it is the picture Home last showed, and the fresh
     * one crossfades in once baked. Not in "Follow App", which has no backdrop.
     *
     */
    private fun loadBaked() {
        if (selectedId == AUTO) return

        runCatching {
            val key = File(bakedDir, "key").takeIf { it.exists() }?.readText() ?: return
            val home = BitmapFactory.decodeFile(File(bakedDir, "home.jpg").path) ?: return
            val frost = BitmapFactory.decodeFile(File(bakedDir, "frost.png").path) ?: return
            if (home.width != screenW || home.height != screenH) return

            backdrop = home.asImageBitmap()
            frosted = frost.asImageBitmap()
            bakedKey = key
        }.onFailure { Log.w(TAG, "could not read the baked backdrop", it) }
    }

    private suspend fun saveBaked(key: String, home: Bitmap, frost: Bitmap) {
        bakedKey = key
        withContext(Dispatchers.IO) {
            runCatching {
                // The key last, so a write cut short is never taken for a good one.
                File(bakedDir, "key").delete()
                File(bakedDir, "home.jpg").outputStream().use { home.compress(Bitmap.CompressFormat.JPEG, 95, it) }
                File(bakedDir, "frost.png").outputStream().use { frost.compress(Bitmap.CompressFormat.PNG, 100, it) }
                File(bakedDir, "key").writeText(key)
            }.onFailure { Log.w(TAG, "could not keep the baked backdrop", it) }
            keepForLockedBoot()
        }
    }

    fun lockedBootBackdrop(context: Context): File? =
        if (Build.VERSION.SDK_INT >= 24) File(context.createDeviceProtectedStorageContext().filesDir, "boot-backdrop.jpg") else null

    private fun keepForLockedBoot() {
        val copy = lockedBootBackdrop(app) ?: return
        runCatching {
            val home = File(bakedDir, "home.jpg")
            if (selectedId == AUTO || !home.exists()) { copy.delete(); return }
            if (copy.exists() && copy.lastModified() >= home.lastModified()) return
            val part = File(copy.parentFile, copy.name + ".part")
            home.copyTo(part, overwrite = true)
            check(part.renameTo(copy))
        }.onFailure { Log.w(TAG, "could not keep the backdrop for a locked boot", it) }
    }

    /**
     * [frame] shows as the picture at once, before [useLiveFrame] has kept and frosted it: the
     * aerial film let go into an app shows its last frame rather than black (LiveWallpaper).
     */
    fun showLiveFrame(frame: Bitmap) {
        if (!moves()) return
        backdropJob?.cancel()
        backdrop = frame.asImageBitmap()
    }

    class PreparedFrame(val frame: Bitmap, val frost: Bitmap)

    suspend fun prepareLiveFrame(frame: Bitmap): PreparedFrame =
        withContext(Dispatchers.Default) { PreparedFrame(frame, Images.frost(frame)) }

    /**
     * [prepared] becomes the moving wallpaper's picture at once, as film [film] at [atMs]; its file
     * and the baked Home are written after, apart, so Home coming back on it does not wait for them
     * (the screensaver's pictures: LiveWallpaper.SaverFrames for the aerials, VideoSaverFrames for
     * the owner's videos). Not a film the wallpaper does not play (another video than the one chosen).
     *
     */
    fun applyLiveFrame(prepared: PreparedFrame, film: String, atMs: Long): Boolean {
        val id = selectedId

        if (!moves()) return false

        val video = isVideo(id)

        if (id.startsWith(VIDEO_PREFIX) && film != id.removePrefix(VIDEO_PREFIX)) return false

        backdropJob?.cancel()

        if (video) videoLeftHere = null else leftHere = null

        val key = if (video) "video" else "live"

        prefs.edit { putString("${key}_film", film).putLong("${key}_at", atMs).remove("${key}_left_film").remove("${key}_left_at") }

        // The frame is both the film's own card and the card that plays them all in turn (useLiveFrame).
        synchronized(thumbs) { thumbs.remove(id); if (video) { thumbs.remove(VIDEOS); thumbs.remove(VIDEO_PREFIX + film) } }

        backdrop = prepared.frame.asImageBitmap()

        frosted = prepared.frost.asImageBitmap()

        pictureVersion++

        val target = if (video) videoFrame else liveFrame

        scope.launch(Dispatchers.IO) {
            val part = File(target.path + ".part")

            runCatching { part.outputStream().use { prepared.frame.compress(Bitmap.CompressFormat.JPEG, 90, it) } }
                .onFailure { Log.w(TAG, "could not keep the film's frame", it) }

            if (part.exists() && !part.renameTo(target)) part.delete()

            if (video) videoFilms(VIDEOS).firstOrNull { it.id == film }?.let { keepPoster(it, prepared.frame) }

            if (selectedId == id) resolve(id)?.let { saveBaked(bakedKey(it), prepared.frame, prepared.frost) }
        }

        return true
    }

    suspend fun useLiveFrame(frame: Bitmap, film: String, atMs: Long, wanted: () -> Boolean = { true }): Boolean {
        if (!ready()) {
            frame.recycle()
            return false
        }
        if (!moves()) {
            frame.recycle()
            return false
        }
        val id = selectedId
        val prepared = prepareLiveFrame(frame)
        if (!wanted() || selectedId != id) {
            prepared.frame.recycle()
            prepared.frost.recycle()
            return false
        }
        return applyLiveFrame(prepared, film, atMs)
    }

    fun liveSpot(): Pair<String, Long>? {
        if (!ready()) return null
        return leftLiveSpot() ?: livePictureSpot()
    }

    fun livePictureSpot(): Pair<String, Long>? {
        if (!ready()) return null
        val film = prefs.getString("live_film", null)?.takeIf { it.isNotEmpty() } ?: return null
        return film to prefs.getLong("live_at", 0L)
    }

    fun leftLiveSpot(): Pair<String, Long>? {
        if (!ready()) return null
        leftHere?.let { return it }
        val film = prefs.getString("live_left_film", null)?.takeIf { it.isNotEmpty() } ?: return null
        return film to prefs.getLong("live_left_at", 0L)
    }

    @Volatile
    private var leftHere: Pair<String, Long>? = null

    fun dropLeftLiveSpot() {
        leftHere = null
        if (!ready()) return
        prefs.edit { remove("live_left_film").remove("live_left_at") }
    }

    fun leaveLiveFilm(film: String, atMs: Long, persist: Boolean = true) {
        if (!persist) { leftHere = film to atMs; return }
        if (!ready()) return
        val picture = livePictureSpot()
        if (picture != null && picture.first == film && abs(picture.second - atMs) < SAME_SPOT_MS) {
            leftHere = null
            prefs.edit { remove("live_left_film").remove("live_left_at") }
        } else {
            leftHere = film to atMs
            prefs.edit { putString("live_left_film", film).putLong("live_left_at", atMs) }
        }
    }

    @Volatile
    private var videoLeftHere: Pair<String, Long>? = null

    fun leaveVideo(film: String, atMs: Long) {
        videoLeftHere = film to atMs
        if (ready()) prefs.edit { putString("video_left_film", film).putLong("video_left_at", atMs) }
    }

    fun videoLeftSpot(): Pair<String, Long>? {
        videoLeftHere?.let { return it }
        if (!ready()) return null
        val film = prefs.getString("video_left_film", null)?.takeIf { it.isNotEmpty() } ?: return null
        return film to prefs.getLong("video_left_at", 0L)
    }

    fun videoSpot(): Pair<String, Long>? {
        if (!ready()) return null
        val film = prefs.getString("video_film", null)?.takeIf { it.isNotEmpty() } ?: return null
        return film to prefs.getLong("video_at", 0L)
    }

    fun videosChanged() {
        if (!ready()) return
        synchronized(thumbs) { thumbs.keys.removeAll { isVideo(it) } }
        rebuildList()
        val spot = prefs.getString("video_film", null)
        if (spot != null && items.none { it.id == VIDEO_PREFIX + spot }) {
            videoFrame.delete()
            prefs.edit { remove("video_film").remove("video_at") }
        }
        if (isVideo() && items.none { it.id == selectedId }) select(if (items.any { it.id == VIDEOS }) VIDEOS else AUTO)
        else if (isVideo()) rebuildBackdrop()
    }

    fun setSources(list: List<Pair<String, String>>) {
        if (!ready()) return
        if (list == sources) return
        sources = list
        rebuildList()
    }

    suspend fun useLiveFrost(frame: Bitmap) {
        if (selectedId != LIVE) return
        backdropJob?.cancel()
        frosted = filmFrost?.takeIf { frame === filmFrostOf }
            ?: withContext(Dispatchers.Default) { Images.frost(frame) }.asImageBitmap()
    }

    var filmFrost by mutableStateOf<ImageBitmap?>(null); private set
    private var filmFrostOf: Bitmap? = null

    suspend fun useFilmFrost(frame: Bitmap) {
        if (!moves()) return
        filmFrost = withContext(Dispatchers.Default) { Images.frost(frame) }.asImageBitmap()
        filmFrostOf = frame
    }

    /**
     * Where no frame can be taken while the film moves (LiveWallpaper.sampleGlass): the glass of the
     * frame it started from ([frosted]), blurred down to its broad colours, so the shapes that move
     * on behind the plate do not show twice through it.
     *
     */
    suspend fun useStillFilmFrost() {
        val start = frosted ?: return

        if (!moves()) return

        filmFrostOf = null

        filmFrost = withContext(Dispatchers.Default) {
            val src = start.asAndroidBitmap()

            val quarter = Bitmap.createScaledBitmap(src, src.width / 4, src.height / 4, true)

            Images.frost(Bitmap.createScaledBitmap(quarter, 16, 9, true))
        }.asImageBitmap()
    }

    fun dropFilmFrost() {
        filmFrost = null
        filmFrostOf = null
    }

    private val clipGlassDir by lazy { File(app.filesDir, "clip-glass").apply { mkdirs() } }
    private val clipGlassMade = mutableSetOf<String>()

    var clipGlass by mutableStateOf<Pair<String, ImageBitmap>?>(null); private set

    suspend fun showClipGlass(clip: String?) {
        if (!ready()) return
        if (clip == null || clipGlass?.first == clip) return
        val file = File(clipGlassDir, "$clip.png")
        val bmp = withContext(Dispatchers.IO) { if (file.exists()) BitmapFactory.decodeFile(file.path) else null }
        if (bmp != null) clipGlass = clip to bmp.asImageBitmap()
    }

    suspend fun keepClipGlass(clip: String, frame: Bitmap, showing: () -> String?) {
        if (!ready()) return
        val fresh = synchronized(clipGlassMade) { clipGlassMade.add(clip) }
        if (!fresh) return
        val file = File(clipGlassDir, "$clip.png")
        val (bmp, kept) = withContext(Dispatchers.IO) {
            if (file.exists()) {
                BitmapFactory.decodeFile(file.path)?.let { return@withContext it to true }
                file.delete()
            }
            val started = System.nanoTime()
            val frost = Images.frost(frame)
            DiagLog.i("clip glass made in ${(System.nanoTime() - started) / 1_000_000} ms")
            var kept = false
            runCatching { file.outputStream().use { kept = frost.compress(Bitmap.CompressFormat.PNG, 100, it) } }
                .onFailure { Log.w(TAG, "could not keep the clip's glass", it) }
            frost to kept
        }
        if (!kept) synchronized(clipGlassMade) { clipGlassMade -= clip }
        if (showing() == clip) clipGlass = clip to bmp.asImageBitmap()
    }

    fun restoreFrost(before: ImageBitmap?) {
        if (selectedId == LIVE && before != null) frosted = before
    }

    private fun rebuildList() {
        val zh = Locale.getDefault().language == "zh"
        val auto = Wallpaper(AUTO, WallpaperKind.Auto, "Top Shelf")
        val live = Wallpaper(LIVE, WallpaperKind.Live, if (zh) "航拍" else "Aerial")
        val trending = Wallpaper(TRENDING, WallpaperKind.Trending, app.getString(R.string.st_hero))
        val films = ScreensaverVideos.all(app)
        val videos = if (films.isEmpty()) emptyList() else
            listOf(Wallpaper(VIDEOS, WallpaperKind.Video, if (zh) "我的视频 · 轮播" else "My Videos, In Turn")) +
                films.map { Wallpaper(VIDEO_PREFIX + it.id, WallpaperKind.Video, it.name.substringBeforeLast('.').ifBlank { it.name }) }
        val inputs = sources.map { (id, name) -> Wallpaper(SOURCE_PREFIX + id, WallpaperKind.Source, name) }
        items = listOf(live) + trending + videos + auto + inputs
    }

}
