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

/**
 * Ready-made 16:9 tile pictures for popular apps. Merged build: the remote icon index is gone,
 * so no new pictures are fetched; pictures already on this TV (files/library, from the owner's
 * earlier installs) keep working, and the phone page can still pick one per app. Only this object
 * writes files/library, and it removes a file only when its app is no longer installed.
 */
object IconLibrary {
    /** Bumped when pictures arrive or the library is turned on or off, so Home redraws its tiles. */
    var version by mutableIntStateOf(0); private set

    private lateinit var dir: File
    private lateinit var prefs: SharedPreferences

    /** Package → path on the site ("p/<package>-<hash>.jpg"), from the last index read. */
    @Volatile private var index: Map<String, String> = emptyMap()
    private val refreshing = Mutex()

    fun init(context: Context) {
        if (::dir.isInitialized) return
        dir = File(context.filesDir, "library").apply { mkdirs() }
        prefs = context.applicationContext.getSharedPreferences("icon_library", Context.MODE_PRIVATE)
        index = parse(File(dir, "index.json").takeIf { it.exists() }?.readText())
    }

    private fun fileFor(pkg: String): File? {
        val path = index[pkg] ?: return null
        return File(dir, path.substringAfterLast('/'))
    }

    /** The library's picture of [pkg] is on this TV (whether or not its tile uses it). */
    fun has(pkg: String): Boolean = ::dir.isInitialized && fileFor(pkg)?.exists() == true

    /** The library's picture of [pkg] as stored, for the phone page; null without one. */
    fun file(pkg: String): File? = if (::dir.isInitialized) fileFor(pkg)?.takeIf { it.exists() } else null

    /** Whether [pkg]'s tile uses the library: unless the phone page picked the app's own art. */
    fun uses(pkg: String): Boolean = when (pick(pkg)) {
        Pick.Library -> true
        Pick.Own -> false
        null -> true
    }

    private fun usable(pkg: String): File? = if (uses(pkg)) file(pkg) else null

    enum class Pick { Library, Own }

    /** What the phone page picked for [pkg]; null for the library. */
    fun pick(pkg: String): Pick? = when {
        !::prefs.isInitialized -> null
        pkg in prefs.getStringSet("use_library", emptySet())!! -> Pick.Library
        pkg in prefs.getStringSet("use_own", emptySet())!! -> Pick.Own
        else -> null
    }

    /** From the phone page; the caller calls [changed] on the main thread after. */
    fun choose(pkg: String, pick: Pick?) {
        val library = prefs.getStringSet("use_library", emptySet())!! - pkg
        val own = prefs.getStringSet("use_own", emptySet())!! - pkg
        prefs.edit {
            putStringSet("use_library", if (pick == Pick.Library) library + pkg else library)
            putStringSet("use_own", if (pick == Pick.Own) own + pkg else own)
        }
    }

    /** When the app's picture arrived; 0 without one. Part of the tile cache's key. */
    fun stamp(pkg: String): Long = usable(pkg)?.lastModified() ?: 0L

    /** The app's picture, or null. Off the main thread. */
    fun load(pkg: String): Bitmap? = usable(pkg)?.let { BitmapFactory.decodeFile(it.path) }

    /** Called on the main thread when a pick changed or [refresh] brought something new. */
    fun changed() {
        version++
    }

    /**
     * Local-only: prunes pictures of apps no longer installed. Never fetches anything: the
     * remote icon index was removed in the merged build, so [installed] only decides what stays.
     */
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
        buildMap { icons.keys().forEach { pkg -> if (CustomIcons.isValidKey(pkg)) put(pkg, icons.getString(pkg)) } }
    }.getOrDefault(emptyMap())

}
