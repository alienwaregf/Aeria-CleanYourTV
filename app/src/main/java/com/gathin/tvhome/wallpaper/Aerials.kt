package com.gathin.tvhome.wallpaper

import android.content.Context
import com.gathin.tvhome.settings.AerialSettings
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import android.view.Display
import android.view.WindowManager
import androidx.core.content.getSystemService
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale

class Aerial(
    val id: String,
    val label: String,
    val category: String,
    val url: String,
    val uhd: String,
    val dolbyVision: String,
    val captions: List<Pair<Int, String>>,
) {
    fun captionAt(seconds: Int): String = captions.lastOrNull { it.first <= seconds }?.second ?: label

    fun url(quality: AerialQuality): String = when (quality) {
        AerialQuality.DolbyVision -> dolbyVision
        AerialQuality.Uhd -> uhd
        else -> url
    }
}

enum class AerialQuality(val key: String) {
    Auto("auto"), Hd("1080p"), Uhd("4k"), DolbyVision("dolby_vision");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: Auto
    }
}

object AerialFormats {
    @Volatile
    private var probed: Set<AerialQuality>? = null

    fun supported(context: Context): Set<AerialQuality> = probed ?: probe(context).also { probed = it }

    fun resolve(context: Context, chosen: AerialQuality): AerialQuality {
        val ok = supported(context)
        return if (chosen != AerialQuality.Auto && chosen in ok) chosen
        else listOf(AerialQuality.DolbyVision, AerialQuality.Uhd).firstOrNull { it in ok && (chosen == AerialQuality.Auto || it.ordinal < chosen.ordinal) }
            ?: AerialQuality.Hd
    }

    fun forWallpaper(context: Context): AerialQuality =
        resolve(context, AerialSettings.aerialQuality).let { if (it == AerialQuality.DolbyVision) AerialQuality.Uhd else it }

    fun screensaverCarriesWallpaper(context: Context): Boolean =
        !(AerialSettings.aerialQuality == AerialQuality.DolbyVision && AerialQuality.DolbyVision in supported(context))

    fun forScreensaver(context: Context, overWallpaper: Boolean): AerialQuality =
        if (overWallpaper && screensaverCarriesWallpaper(context)) forWallpaper(context)
        else resolve(context, AerialSettings.aerialQuality)

    fun lower(quality: AerialQuality): AerialQuality = when (quality) {
        AerialQuality.DolbyVision -> AerialQuality.Uhd
        else -> AerialQuality.Hd
    }

    private fun probe(context: Context): Set<AerialQuality> {
        val found = mutableSetOf(AerialQuality.Hd)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return found
        val codecs = runCatching { MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.filter { !it.isEncoder } }.getOrDefault(emptyList())
        fun caps(mime: String) = codecs.mapNotNull { info ->
            if (info.supportedTypes.none { it.equals(mime, ignoreCase = true) }) null
            else runCatching { info.getCapabilitiesForType(mime) }.getOrNull()
        }
        val uhd = caps(MediaFormat.MIMETYPE_VIDEO_HEVC).any { c ->
            runCatching { c.videoCapabilities.areSizeAndRateSupported(3840, 2160, 30.0) }.getOrDefault(false)
        }
        if (uhd) found += AerialQuality.Uhd
        val dvDecoder = caps(MediaFormat.MIMETYPE_VIDEO_DOLBY_VISION).any { c ->
            c.profileLevels.any {
                it.profile == MediaCodecInfo.CodecProfileLevel.DolbyVisionProfileDvheStn &&
                    it.level >= MediaCodecInfo.CodecProfileLevel.DolbyVisionLevelUhd30
            }
        }
        val dvDisplay = runCatching {
            @Suppress("DEPRECATION")
            context.getSystemService<WindowManager>()?.defaultDisplay?.hdrCapabilities?.supportedHdrTypes
                ?.contains(Display.HdrCapabilities.HDR_TYPE_DOLBY_VISION) == true
        }.getOrDefault(false)
        if (uhd && dvDecoder && dvDisplay) found += AerialQuality.DolbyVision
        return found
    }
}

object Aerials {
    @Volatile
    private var cache: List<Aerial>? = null

    fun all(context: Context): List<Aerial> = cache ?: synchronized(this) {
        cache ?: load(context).also { cache = it }
    }

    fun refreshIfStale(context: Context) {
        val app = context.applicationContext
        val prefs = app.getSharedPreferences("aerials_catalog", Context.MODE_PRIVATE)
        if (System.currentTimeMillis() - prefs.getLong("checked_at", 0) < 24 * 60 * 60 * 1000L) return
        Thread {
            runCatching {
                val c = URL(CATALOG_URL).openConnection() as HttpURLConnection
                c.connectTimeout = 8_000
                c.readTimeout = 10_000
                try {
                    if (c.responseCode !in 200..299) return@runCatching
                    val text = c.inputStream.use { it.readBytes().decodeToString() }
                    val films = parse(text)
                    if (films.isEmpty()) return@runCatching
                    val dir = app.filesDir
                    val tmp = File(dir, "aerials.json.tmp")
                    tmp.writeText(text)
                    if (!tmp.renameTo(File(dir, "aerials.json"))) tmp.delete()
                    cache = null
                    prefs.edit().putLong("checked_at", System.currentTimeMillis()).apply()
                } finally {
                    c.disconnect()
                }
            }
        }.start()
    }

    private const val CATALOG_URL = "https://raw.githubusercontent.com/pengchujin/aerial-tv/main/app/src/main/assets/aerials.json"

    private fun load(context: Context): List<Aerial> {
        val cached = File(context.filesDir, "aerials.json")
        val text = if (cached.exists()) {
            runCatching { cached.readText() }.getOrNull()?.takeIf { runCatching { parse(it) }.getOrNull()?.isNotEmpty() == true }
                ?: run { cached.delete(); null }
        } else null
        return parse(
            text ?: context.assets.open("aerials.json").bufferedReader().use { it.readText() },
        )
    }

    private fun parse(text: String): List<Aerial> {
        val zh = Locale.getDefault().language == "zh"
        val root = JSONObject(text)
        val arr = root.getJSONArray("videos")
        return (0 until arr.length()).mapNotNull { i ->
            runCatching {
                val o = arr.getJSONObject(i)
                val url = o.getString("url")
                if (!url.startsWith("https://sylvan.apple.com/")) null
                else {
                    val caps = o.getJSONArray("captions")
                    Aerial(
                        id = o.getString("id"),
                        label = o.getString("label"),
                        category = o.getString("category"),
                        url = url,
                        uhd = o.optString("uhd").ifEmpty { o.getString("url") },
                        dolbyVision = o.optString("dv").ifEmpty { o.getString("url") },
                        captions = (0 until caps.length()).map { j ->
                            val c = caps.getJSONArray(j)
                            c.getInt(0) to (if (zh) c.getString(1) else c.getString(2)).replace(' ', ' ')
                        },
                    )
                }
            }.getOrNull()
        }
    }
}
