package com.gathin.tvhome.apps

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

object IconLibrary {
    var version by mutableIntStateOf(0); private set

    private lateinit var dir: File
    private lateinit var prefs: SharedPreferences

    @Volatile private var index: Map<String, String> = emptyMap()
    private val refreshing = Mutex()

    fun init(context: Context) {
        if (::dir.isInitialized) return
        dir = File(context.filesDir, "library").apply { mkdirs() }
        prefs = context.applicationContext.getSharedPreferences("icon_library", Context.MODE_PRIVATE)
        index = parse(runCatching { File(dir, "index.json").takeIf { it.exists() }?.readText() }.getOrNull())
    }

    private fun fileFor(pkg: String): File? {
        val path = index[pkg] ?: return null
        return File(dir, path.substringAfterLast('/'))
    }

    fun has(pkg: String): Boolean = ::dir.isInitialized && fileFor(pkg)?.exists() == true

    fun file(pkg: String): File? = if (::dir.isInitialized) fileFor(pkg)?.takeIf { it.exists() } else null

    fun uses(pkg: String): Boolean = when (pick(pkg)) {
        Pick.Library -> true
        Pick.Own -> false
        null -> true
    }

    private fun usable(pkg: String): File? = if (uses(pkg)) file(pkg) else null

    enum class Pick { Library, Own }

    fun pick(pkg: String): Pick? = when {
        !::prefs.isInitialized -> null
        pkg in prefs.getStringSet("use_library", emptySet())!! -> Pick.Library
        pkg in prefs.getStringSet("use_own", emptySet())!! -> Pick.Own
        else -> null
    }

    fun choose(pkg: String, pick: Pick?) {
        if (!::prefs.isInitialized) return
        val library = prefs.getStringSet("use_library", emptySet())!! - pkg
        val own = prefs.getStringSet("use_own", emptySet())!! - pkg
        prefs.edit {
            putStringSet("use_library", if (pick == Pick.Library) library + pkg else library)
            putStringSet("use_own", if (pick == Pick.Own) own + pkg else own)
        }
    }

    fun stamp(pkg: String): Long = usable(pkg)?.lastModified() ?: 0L

    fun load(pkg: String): Bitmap? = usable(pkg)?.let { BitmapFactory.decodeFile(it.path) }

    fun changed() {
        version++
    }

    suspend fun refresh(installed: Set<String>): Boolean = withContext(Dispatchers.IO) {
        if (!::dir.isInitialized) return@withContext false
        if (!refreshing.tryLock()) return@withContext false
        try {
            val keep = HashSet<String>()
            for (pkg in installed) {
                val file = fileFor(pkg) ?: continue
                if (file.exists()) keep += file.name
            }
            var changed = false
            dir.listFiles()?.forEach {
                if (it.name != "index.json" && it.name !in keep) { it.delete(); changed = true }
            }
            changed
        } finally {
            refreshing.unlock()
        }
    }

    private fun parse(text: String?): Map<String, String> = runCatching {
        val icons = JSONObject(text ?: return emptyMap()).getJSONObject("icons")
        buildMap { icons.keys().forEach { pkg -> if (CustomIcons.isValidKey(pkg)) runCatching { put(pkg, icons.getString(pkg)) } } }
    }.getOrDefault(emptyMap())

}
