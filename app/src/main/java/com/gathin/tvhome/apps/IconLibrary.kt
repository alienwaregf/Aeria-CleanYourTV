package com.gathin.tvhome.apps

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import com.gathin.tvhome.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object IconLibrary {
    private const val TAG = "AerialIcons"
    private const val BASE = "https://aerial.qust.me/icons/"
    private const val CHECK_EVERY_MS = 12 * 60 * 60 * 1000L
    private const val RETRY_MS = 10 * 60 * 1000L

    var version by mutableIntStateOf(0); private set

    class Choice(val id: String, val path: String, val by: String) {
        val fileName: String get() = path.substringAfterLast('/')
    }

    private lateinit var dir: File
    private lateinit var prefs: SharedPreferences

    @Volatile private var index: Map<String, List<Choice>> = emptyMap()
    @Volatile var credits: JSONObject = JSONObject(); private set
    private val refreshing = Mutex()
    @Volatile private var lastAttempt = 0L
    private val failedAt = HashMap<String, Long>()

    fun init(context: Context) {
        if (::dir.isInitialized) return
        dir = File(context.filesDir, "library").apply { mkdirs() }
        prefs = context.applicationContext.getSharedPreferences("icon_library", Context.MODE_PRIVATE)
        read(File(dir, "index.json").takeIf { it.exists() }?.readText())?.let { (i, c) -> index = i; credits = c }
    }

    private fun here(c: Choice): File? = File(dir, c.fileName).takeIf { it.exists() }

    fun catalog(): Map<String, List<Choice>> = index

    const val PICTURES = BASE

    fun borrowed(pkg: String): Pair<String, Choice>? {
        if (!::prefs.isInitialized) return null
        val (from, id) = prefs.getString("borrow:$pkg", null)?.split('/', limit = 2)?.takeIf { it.size == 2 } ?: return null
        return index[from]?.firstOrNull { it.id == id }?.let { from to it }
    }

    fun choices(pkg: String): List<Choice> =
        if (::dir.isInitialized) index[pkg].orEmpty().filter { here(it) != null } else emptyList()

    fun chosen(pkg: String): Choice? {
        borrowed(pkg)?.second?.takeIf { here(it) != null }?.let { return it }
        val here = choices(pkg)
        val id = if (::prefs.isInitialized) prefs.getString("choice:$pkg", null) else null
        return here.firstOrNull { it.id == id } ?: here.firstOrNull()
    }

    fun has(pkg: String): Boolean = chosen(pkg) != null

    fun file(pkg: String, id: String? = null): File? {
        val c = if (id == null) chosen(pkg) else choices(pkg).firstOrNull { it.id == id }
            ?: borrowed(pkg)?.second?.takeIf { it.id == id }
        return c?.let(::here)
    }

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

    fun choose(pkg: String, pick: Pick?, choice: String? = null, from: String? = null) {
        if (!::prefs.isInitialized) return
        val library = prefs.getStringSet("use_library", emptySet())!! - pkg
        val own = prefs.getStringSet("use_own", emptySet())!! - pkg
        val default = index[pkg]?.firstOrNull()?.id
        prefs.edit {
            putStringSet("use_library", if (pick == Pick.Library) library + pkg else library)
            putStringSet("use_own", if (pick == Pick.Own) own + pkg else own)
            if (pick == Pick.Library && from != null && from != pkg && choice != null) {
                putString("borrow:$pkg", "$from/$choice")
                return@edit
            }
            remove("borrow:$pkg")
            if (pick == Pick.Own) return@edit
            if (choice == null || choice == default) remove("choice:$pkg") else putString("choice:$pkg", choice)
        }
    }

    fun stamp(pkg: String): Long = (usable(pkg)?.let { it.lastModified() + it.name.hashCode() } ?: 0L) +
        when (pick(pkg)) { Pick.Own -> 1L; Pick.Library -> 2L; null -> 0L }

    fun fetch(c: Choice): Boolean {
        if (!::dir.isInitialized) return false
        val file = File(dir, c.fileName)
        if (file.exists()) return true
        return runCatching { download(BASE + c.path, file) }
            .onFailure { Log.w(TAG, "could not fetch ${c.path}", it) }
            .isSuccess
    }

    fun load(pkg: String): Bitmap? = usable(pkg)?.let { BitmapFactory.decodeFile(it.path) }

    fun changed() {
        version++
    }

    suspend fun refresh(installed: Set<String>): Boolean = withContext(Dispatchers.IO) {
        if (!::dir.isInitialized) return@withContext false
        if (!refreshing.tryLock()) return@withContext false
        try {
            val now = System.currentTimeMillis()
            var changed = false
            val due = now - prefs.getLong("checked_at", 0) > CHECK_EVERY_MS ||
                prefs.getInt("checked_by", 0) != BuildConfig.VERSION_CODE
            if (due && now - lastAttempt > RETRY_MS) {
                lastAttempt = now
                runCatching { fetch(BASE + "index.json") }
                    .onFailure { Log.w(TAG, "could not read the icon index", it) }
                    .getOrNull()
                    ?.let { text ->
                        read(text)?.let { (fresh, c) ->
                            File(dir, "index.json").writeText(text)
                            changed = fresh.mapValues { e -> e.value.map { it.path } } != index.mapValues { e -> e.value.map { it.path } }
                            index = fresh
                            credits = c
                            prefs.edit { putLong("checked_at", now); putInt("checked_by", BuildConfig.VERSION_CODE) }
                            Log.i(TAG, "icon index: ${fresh.size} apps, ${fresh.values.sumOf { it.size }} pictures")
                        }
                    }
            }
            val keep = HashSet<String>()
            for (pkg in installed) {
                for (c in index[pkg].orEmpty() + listOfNotNull(borrowed(pkg)?.second)) {
                    val file = File(dir, c.fileName)
                    keep += file.name
                    if (file.exists() || now - (failedAt[file.name] ?: 0L) < RETRY_MS) continue
                    runCatching { download(BASE + c.path, file) }
                        .onSuccess { changed = true; failedAt -= file.name }
                        .onFailure { failedAt[file.name] = now; Log.w(TAG, "could not fetch ${c.path}", it) }
                }
            }
            dir.listFiles()?.forEach { if (it.name != "index.json" && it.name !in keep) it.delete() }
            changed
        } finally {
            refreshing.unlock()
        }
    }

    private fun read(text: String?): Pair<Map<String, List<Choice>>, JSONObject>? = runCatching {
        val root = JSONObject(text ?: return null)
        val sets = root.optJSONObject("choices")
        val icons = root.getJSONObject("icons")
        val map = buildMap {
            if (sets != null) {
                sets.keys().forEach { pkg ->
                    if (!CustomIcons.isValidKey(pkg)) return@forEach
                    val list = sets.getJSONArray(pkg)
                    val choices = (0 until list.length()).mapNotNull { i ->
                        val o = list.getJSONObject(i)
                        val path = o.optString("p")
                        Choice(o.optString("id"), path, o.optString("by")).takeIf { safe(path) && it.id.isNotEmpty() }
                    }
                    if (choices.isNotEmpty()) put(pkg, choices)
                }
            } else {
                icons.keys().forEach { pkg ->
                    val path = icons.getString(pkg)
                    if (CustomIcons.isValidKey(pkg) && safe(path)) put(pkg, listOf(Choice("default", path, "")))
                }
            }
        }
        map to (root.optJSONObject("credits") ?: JSONObject())
    }.getOrNull()

    private fun safe(path: String): Boolean = path.startsWith("p/") && path.removePrefix("p/").let { n ->
        n.isNotEmpty() && n.none { it == '/' || it == '\\' } && !n.startsWith(".")
    }

    private fun connect(url: String): HttpURLConnection = (URL(url).openConnection() as HttpURLConnection).apply {
        connectTimeout = 8_000
        readTimeout = 15_000
    }

    private fun fetch(url: String): String {
        val c = connect(url)
        return try {
            c.inputStream.use { it.readBytes().decodeToString() }
        } finally {
            c.disconnect()
        }
    }

    private fun download(url: String, to: File) {
        val tmp = File(dir, to.name + ".part")
        val c = connect(url)
        try {
            c.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
        } finally {
            c.disconnect()
        }
        val ok = BitmapFactory.Options().apply { inJustDecodeBounds = true }.also { BitmapFactory.decodeFile(tmp.path, it) }.outWidth > 0
        if (!ok || !tmp.renameTo(to)) {
            tmp.delete()
            error("not a picture")
        }
    }
}
