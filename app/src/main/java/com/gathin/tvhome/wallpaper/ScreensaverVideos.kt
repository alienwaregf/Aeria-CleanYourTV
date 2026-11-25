package com.gathin.tvhome.wallpaper

import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.UUID

data class ScreensaverVideo(val id: String, val name: String, val file: File)

object ScreensaverVideos {
    const val MAX_FILE_BYTES = 2_000_000_000L
    private const val KEEP_FREE_BYTES = 1024L * 1024 * 1024
    private const val MAX_VIDEOS = 64
    private val idPattern = Regex("[a-f0-9]{32}")

    private fun directory(context: Context) = File(context.filesDir, "screensaver-videos").apply { mkdirs() }

    @Synchronized
    fun all(context: Context): List<ScreensaverVideo> = directory(context).listFiles().orEmpty()
        .filter { it.isDirectory && idPattern.matches(it.name) }
        .mapNotNull { dir ->
            runCatching {
                val file = File(dir, "video.mp4").takeIf { it.isFile && it.length() > 0 } ?: return@runCatching null
                val name = runCatching { JSONObject(File(dir, "info.json").readText()).getString("name") }.getOrNull() ?: dir.name
                ScreensaverVideo(dir.name, name, file)
            }.getOrNull()
        }.sortedBy { it.id }

    private var reservedBytes = 0L
    private var reservedSlots = 0

    @Synchronized
    fun room(context: Context, pending: Long = 0): Long {
        val films = all(context)
        if (films.size + reservedSlots >= MAX_VIDEOS) return 0
        val stored = films.sumOf { it.file.length() }
        val free = directory(context).usableSpace + pending - reservedBytes
        return minOf((free + stored) / 2 - stored, free - KEEP_FREE_BYTES, MAX_FILE_BYTES).coerceAtLeast(0)
    }

    @Synchronized
    private fun reserve(context: Context, length: Long): Boolean {
        if (length > room(context)) return false
        reservedBytes += length
        reservedSlots++
        return true
    }

    @Synchronized
    private fun release(length: Long) {
        reservedBytes = (reservedBytes - length).coerceAtLeast(0)
        reservedSlots = (reservedSlots - 1).coerceAtLeast(0)
    }

    fun receive(context: Context, input: InputStream, length: Long, name: String): ReceiveResult {
        if (titleOf(name) == null) return ReceiveResult.Rejected("bad_name")
        if (length <= 0) return ReceiveResult.Rejected("empty")
        if (!reserve(context, length)) return ReceiveResult.Rejected("no_room")
        val part = File(directory(context), "import-${UUID.randomUUID().toString().replace("-", "")}.part")
        var held = true
        return try {
            part.outputStream().use { out ->
                val buffer = ByteArray(256 * 1024)
                var left = length
                while (left > 0) {
                    val n = input.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
                    if (n < 0) return ReceiveResult.Rejected("interrupted")
                    out.write(buffer, 0, n)
                    left -= n
                }
            }
            held = false
            release(length)
            add(context, part, name)
        } catch (e: IOException) {
            Log.w("AerialVideos", "screensaver video upload broke off", e)
            ReceiveResult.Rejected("interrupted")
        } finally {
            if (held) release(length)
            part.delete()
        }
    }

    sealed interface ReceiveResult {
        data class Saved(val video: ScreensaverVideo) : ReceiveResult
        data class Rejected(val reason: String) : ReceiveResult
    }

    @Synchronized
    private fun add(context: Context, upload: File, name: String): ReceiveResult {
        val root = directory(context)
        root.listFiles()?.filter { it.name.startsWith("import-") && it.lastModified() < System.currentTimeMillis() - 3_600_000 }
            ?.forEach { it.deleteRecursively() }
        val size = upload.length()
        if (size <= 0 || size > room(context, pending = size)) return ReceiveResult.Rejected("no_room")
        val title = titleOf(name) ?: return ReceiveResult.Rejected("bad_name")
        val metadata = MediaMetadataRetriever()
        val valid = (try {
            metadata.setDataSource(upload.absolutePath)
            metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes" &&
                (metadata.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0) > 0
        } catch (_: Exception) { false } finally { runCatching { metadata.release() } }) || hasVideoTrack(upload)
        if (!valid) {
            Log.w("AerialVideos", "screensaver video has no video track: $name")
            return ReceiveResult.Rejected("not_a_video")
        }

        val id = UUID.randomUUID().toString().replace("-", "")
        val staging = File(root, "import-$id")
        val target = File(root, id)
        return try {
            check(staging.mkdir())
            val film = File(staging, "video.mp4")
            if (!upload.renameTo(film)) upload.copyTo(film)
            File(staging, "info.json").writeText(JSONObject().put("name", title).toString())
            check(staging.renameTo(target))
            ReceiveResult.Saved(ScreensaverVideo(id, title, File(target, "video.mp4")))
        } catch (e: Exception) {
            Log.w("AerialVideos", "could not store a screensaver video", e)
            ReceiveResult.Rejected("store_failed")
        } finally { staging.deleteRecursively() }
    }

    private fun hasVideoTrack(file: File): Boolean {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            (0 until extractor.trackCount).any {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
            }
        } catch (_: Exception) {
            false
        } finally {
            runCatching { extractor.release() }
        }
    }

    @Synchronized
    fun delete(context: Context, id: String): Boolean {
        if (!idPattern.matches(id)) return false
        val dir = File(directory(context), id)
        return dir.isDirectory && dir.deleteRecursively()
    }

    private const val MAX_TITLE_LENGTH = 120

    internal fun titleOf(name: String): String? {
        val fileName = name.substringAfterLast('/').substringAfterLast('\\')
        val dot = fileName.lastIndexOf('.')
        if (dot <= 0) return null
        val extension = fileName.substring(dot)
        if (extension.lowercase() !in setOf(".mp4", ".mov")) return null
        var end = minOf(dot, MAX_TITLE_LENGTH - extension.length)
        if (end in 1 until dot && Character.isHighSurrogate(fileName[end - 1])) end--
        return fileName.substring(0, end) + extension
    }
}
