package com.gathin.tvhome.apps

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.gathin.tvhome.wallpaper.Images
import java.io.File
import java.io.InputStream

object CustomIcons {
    private const val W = 1280
    private const val H = 720

    var version by mutableIntStateOf(0); private set

    private val listeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    fun onChange(listener: () -> Unit) { listeners += listener }

    fun removeOnChange(listener: () -> Unit) { listeners -= listener }

    private lateinit var dir: File

    fun init(context: Context) {
        if (::dir.isInitialized) return
        dir = File(context.filesDir, "icons").apply { mkdirs() }
    }

    private fun fileFor(pkg: String) = if (isValidKey(pkg)) File(dir, "$pkg.png") else File(dir, "input-${hash(pkg)}.png")

    private fun hash(key: String): String =
        java.security.MessageDigest.getInstance("SHA-1").digest(key.toByteArray()).joinToString("") { "%02x".format(it) }

    fun has(pkg: String): Boolean = ::dir.isInitialized && fileFor(pkg).exists()

    fun stamp(pkg: String): Long = if (::dir.isInitialized) fileFor(pkg).lastModified() else 0L

    fun load(pkg: String): Bitmap? = if (has(pkg)) BitmapFactory.decodeFile(fileFor(pkg).path) else null

    fun save(pkg: String, input: InputStream, cacheDir: File): Boolean {
        if (!::dir.isInitialized) return false
        val tmp = runCatching { File.createTempFile("icon", ".bin", cacheDir) }.getOrNull() ?: return false
        return try {
            tmp.outputStream().use { input.copyTo(it) }
            val bmp = Images.decodeCover(tmp, W, H) ?: return false
            try {
                fileFor(pkg).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally {
                bmp.recycle()
            }
            true
        } finally {
            tmp.delete()
        }
    }

    fun clear(pkg: String) {
        if (!::dir.isInitialized) return
        fileFor(pkg).delete()
    }

    fun changed() {
        version++
        for (listener in listeners) listener()
    }

    fun isValidKey(pkg: String): Boolean = Regex("^[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+$").matches(pkg)
}
