package com.gathin.tvhome.apps

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.gathin.tvhome.wallpaper.Images
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object AppBackgrounds {
    private const val MAX_W = 3840
    private const val MAX_H = 2160

    var version by mutableIntStateOf(0); private set

    private lateinit var dir: File

    private lateinit var prefs: SharedPreferences

    fun init(context: Context) {
        if (::dir.isInitialized) return
        dir = File(context.filesDir, "app-backgrounds").apply { mkdirs() }
        prefs = context.getSharedPreferences("launcher_settings", Context.MODE_PRIVATE)
    }

    private fun fileFor(pkg: String) = File(dir, "$pkg.jpg")

    fun url(pkg: String): String = if (::prefs.isInitialized) prefs.getString("app_bg_url_$pkg", "") ?: "" else ""

    fun has(pkg: String): Boolean = ::dir.isInitialized && fileFor(pkg).exists()

    fun stamp(pkg: String): Long = if (::dir.isInitialized) fileFor(pkg).lastModified() else 0L

    private fun load(pkg: String, w: Int, h: Int): Bitmap? = if (has(pkg)) Images.decodeCover(fileFor(pkg), w, h) else null

    private data class Cached(val stamp: Long, val bitmap: Bitmap, val frosted: Bitmap)

    private val cache = LinkedHashMap<String, Cached>(16, 0.75f, true)

    private const val CACHE_MAX = 4

    fun loadCached(pkg: String, w: Int, h: Int): Pair<Bitmap, Bitmap>? {
        val stamp = stamp(pkg)
        synchronized(cache) {
            val hit = cache[pkg]
            if (hit != null && hit.stamp == stamp) return hit.bitmap to hit.frosted
        }
        val bmp = load(pkg, w, h) ?: return null
        val frosted = Images.frost(bmp)
        val fresh = stamp(pkg)
        synchronized(cache) {
            val hit = cache[pkg]
            if (hit != null && hit.stamp == fresh) {
                bmp.recycle()
                frosted.recycle()
                return hit.bitmap to hit.frosted
            }
            if (fresh != stamp) {
                bmp.recycle()
                frosted.recycle()
                return null
            }
            cache[pkg] = Cached(stamp, bmp, frosted)
            while (cache.size > CACHE_MAX) {
                cache.remove(cache.entries.first().key)?.let { evicted ->
                    evicted.bitmap.recycle()
                    evicted.frosted.recycle()
                }
            }
            return bmp to frosted
        }
    }

    fun peekCached(pkg: String): Pair<Bitmap, Bitmap>? {
        synchronized(cache) {
            val hit = cache[pkg] ?: return null
            if (hit.stamp != stamp(pkg)) return null
            return hit.bitmap to hit.frosted
        }
    }

    fun dropCached(pkg: String) {
        synchronized(cache) {
            cache.remove(pkg)?.let { evicted ->
                evicted.bitmap.recycle()
                evicted.frosted.recycle()
            }
        }
    }

    fun setUrl(context: Context, pkg: String, url: String): Boolean {
        if (!::dir.isInitialized) return false
        if (url.isBlank()) {
            clear(pkg)
            return true
        }
        val u = runCatching { URL(url) }.getOrNull() ?: return false
        if (u.protocol != "http" && u.protocol != "https") return false
        val tmp = File.createTempFile("appbg", ".bin", context.cacheDir)
        return try {
            val c = u.openConnection() as HttpURLConnection
            c.connectTimeout = 10_000
            c.readTimeout = 30_000
            try {
                if (c.responseCode !in 200..299) return false
                tmp.outputStream().use { c.inputStream.copyTo(it) }
            } finally {
                c.disconnect()
            }
            val bmp = Images.decodeCover(tmp, MAX_W, MAX_H) ?: return false
            try {
                fileFor(pkg).outputStream().use { bmp.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            } finally {
                bmp.recycle()
            }
            prefs.edit().putString("app_bg_url_$pkg", url).apply()
            dropCached(pkg)
            true
        } catch (_: Exception) {
            false
        } finally {
            tmp.delete()
        }
    }

    fun clear(pkg: String) {
        if (!::dir.isInitialized) return
        fileFor(pkg).delete()
        dropCached(pkg)
        if (::prefs.isInitialized) prefs.edit().remove("app_bg_url_$pkg").apply()
    }

    fun changed() {
        version++
    }
}
