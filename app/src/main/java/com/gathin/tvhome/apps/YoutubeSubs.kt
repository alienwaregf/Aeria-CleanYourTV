package com.gathin.tvhome.apps

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import android.util.Xml
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

data class YtVideo(
    val id: String,
    val title: String,
    val channel: String,
    val publishedAt: Long,
    val logo: String? = null,
    val handle: String = "",
    val cover: String = "",
)

object YoutubeSubs {
    val YOUTUBE_PKGS = setOf("com.google.android.youtube.tv", "com.google.android.youtube.tvmusic")
    private const val TAG = "YoutubeSubs"
    private const val LAUNCHER_PREFS = "launcher_settings"
    private const val TTL_MS = 30 * 60_000L
    private const val RETRY_MS = 3 * 60 * 1000L
    private const val MAX_CHANNELS = 30
    private const val MAX_ITEMS = 30
    private const val RSS_MAX = 15
    private const val MAX_KEYWORDS = 10
    private const val KW_MAX = 15
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"

    var version by mutableIntStateOf(0)
        private set

    private lateinit var app: Context
    private lateinit var prefs: SharedPreferences
    private var started = false
    private var lastAttempt = 0L
    private var failed = false
    @Volatile
    private var blockedUntil = 0L
    private val lock = Any()
    private val videosByPkg = HashMap<String, List<YtVideo>>()
    private val kwVideosByPkg = HashMap<String, List<YtVideo>>()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val diffMutex = Mutex()

    fun init(context: Context) {
        if (started) return
        started = true
        app = context.applicationContext
        prefs = app.getSharedPreferences(LAUNCHER_PREFS, Context.MODE_PRIVATE)
        for (pkg in YOUTUBE_PKGS) {
            val videos = readVideos(pkg)
            if (videos.isNotEmpty()) {
                synchronized(lock) { videosByPkg[pkg] = videos }
            }
            val kwVideos = readVideos(pkg, "yt_kw_cards")
            if (kwVideos.isNotEmpty()) {
                synchronized(lock) { kwVideosByPkg[pkg] = kwVideos }
            }
        }
    }

    fun isYoutube(pkg: String): Boolean = pkg in YOUTUBE_PKGS

    fun changed() {
        version++
    }

    fun subs(pkg: String): List<String> {
        if (!::prefs.isInitialized) return emptyList()
        return prefs.getString("yt_subs_$pkg", "").orEmpty().split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun hasSubs(pkg: String): Boolean = subs(pkg).isNotEmpty()

    fun mode(pkg: String): String {
        if (!::prefs.isInitialized) return "subs"
        return prefs.getString("yt_mode_$pkg", "subs").orEmpty().takeIf { it == "keywords" } ?: "subs"
    }

    fun keywords(pkg: String): List<String> {
        if (!::prefs.isInitialized) return emptyList()
        return prefs.getString("yt_keywords_$pkg", "").orEmpty().split("\n").map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun setMode(pkg: String, mode: String): Boolean {
        if (!::prefs.isInitialized) return false
        val value = if (mode == "keywords") "keywords" else "subs"
        prefs.edit().putString("yt_mode_$pkg", value).apply()
        ioScope.launch { refreshIfStale(pkg) }
        return true
    }

    fun setKeywords(pkg: String, raw: String): Boolean {
        if (!::prefs.isInitialized) return false
        val seen = LinkedHashSet<String>()
        for (line in raw.split("\n")) {
            val kw = line.trim()
            if (kw.isEmpty() || kw.any { it.isWhitespace() }) continue
            seen += kw
            if (seen.size >= MAX_KEYWORDS) break
        }
        val value = seen.joinToString("\n")
        prefs.edit().apply {
            if (value.isEmpty()) remove("yt_keywords_$pkg") else putString("yt_keywords_$pkg", value)
        }.apply()
        if (mode(pkg) == "keywords") ioScope.launch { refreshKeywords(pkg) }
        return true
    }

    fun titles(pkg: String): Map<String, String> {
        if (!::prefs.isInitialized) return emptyMap()
        val all = readHandleTitles()
        val out = LinkedHashMap<String, String>()
        for (handle in subs(pkg)) {
            val title = all[handle]
            if (!title.isNullOrBlank()) out[handle] = title
        }
        return out
    }

    fun setSubs(pkg: String, raw: String): Boolean {
        if (!::prefs.isInitialized) return false
        val seen = LinkedHashSet<String>()
        for (line in raw.split("\n")) {
            val canonical = normalizeHandle(line) ?: continue
            seen += canonical
            if (seen.size >= MAX_CHANNELS) break
        }
        val value = seen.joinToString("\n")
        prefs.edit().apply {
            if (value.isEmpty()) remove("yt_subs_$pkg") else putString("yt_subs_$pkg", value)
        }.apply()
        ioScope.launch { applySubsDiff(pkg, seen.toList()) }
        return true
    }

    fun normalizeHandle(raw: String): String? {
        val s = raw.trim().substringBefore("?").substringBefore("#")
        if (s.isEmpty()) return null
        val at = s.indexOf("@")
        if (at < 0) {
            val handle = runCatching { URLDecoder.decode(s, "UTF-8") }.getOrNull() ?: return null
            if (handle.isEmpty() || handle.any { it.isWhitespace() || it == '/' || it.code < 0x20 }) return null
            return "https://www.youtube.com/@$handle"
        }
        var handle = s.substring(at + 1).substringBefore("/")
        if (handle.isEmpty()) return null
        handle = runCatching { URLDecoder.decode(handle, "UTF-8") }.getOrNull() ?: return null
        if (handle.any { it.isWhitespace() || it == '/' || it.code < 0x20 }) return null
        return "https://www.youtube.com/@$handle"
    }

    fun cards(pkg: String): List<ShelfCard> {
        if (!started) return emptyList()
        val videos = synchronized(lock) { if (mode(pkg) == "keywords") kwVideosByPkg[pkg] ?: emptyList() else videosByPkg[pkg] ?: emptyList() }
        return videos.map { v ->
            ShelfCard(
                pkg = pkg,
                title = v.title,
                imageUri = v.cover.ifBlank { "https://i.ytimg.com/vi/${v.id}/maxresdefault.jpg" },
                backdropUri = v.cover.ifBlank { "https://i.ytimg.com/vi/${v.id}/maxresdefault.jpg" },
                intentUri = "https://www.youtube.com/watch?v=${v.id}",
                source = ShelfSource.YOUTUBE_SUBS,
                logoUri = v.logo,
            )
        }
    }

    private fun fetchedAt(pkg: String, prefix: String = "yt_cards"): Long =
        if (::prefs.isInitialized) prefs.getLong("${prefix}_at_$pkg", 0L) else 0L

    fun refreshAsync(pkg: String) {
        ioScope.launch { refreshIfStale(pkg) }
    }

    suspend fun refreshIfStale(pkg: String) {
        if (!started) return
        if (!isYoutube(pkg)) return
        if (mode(pkg) == "keywords") {
            refreshKeywords(pkg)
            return
        }
        val handles = subs(pkg)
        if (handles.isEmpty()) return
        val now = System.currentTimeMillis()
        val cached = synchronized(lock) { videosByPkg[pkg] ?: emptyList() }
        if (now - fetchedAt(pkg) < TTL_MS && cached.isNotEmpty()) return
        if (failed && now - lastAttempt < RETRY_MS) return
        lastAttempt = now
        val perChannel = runCatching {
            fetchByRss(handles)
        }.getOrElse {
            failed = true
            Log.w(TAG, "youtube subs fetch failed", it)
            return
        }
        val pooled = poolVideos(perChannel)
        if (pooled.isEmpty()) {
            failed = true
            return
        }
        val checked = checkMaxres(pooled, perChannel)
        if (checked.isEmpty()) {
            failed = true
            return
        }
        failed = false
        saveVideos(pkg, applyChineseTitles(checked))
        changed()
    }

    suspend fun refreshAllIfStale() {
        if (!started) return
        for (pkg in YOUTUBE_PKGS) refreshIfStale(pkg)
    }

    suspend fun refreshKeywords(pkg: String) {
        if (!started) return
        val words = keywords(pkg)
        if (words.isEmpty()) return
        val now = System.currentTimeMillis()
        val cached = synchronized(lock) { kwVideosByPkg[pkg] ?: emptyList() }
        if (now - fetchedAt(pkg, "yt_kw_cards") < TTL_MS && cached.isNotEmpty()) return
        if (failed && now - lastAttempt < RETRY_MS) return
        lastAttempt = now
        val perKeyword = runCatching {
            fetchBySearch(words)
        }.getOrElse {
            failed = true
            Log.w(TAG, "youtube keyword fetch failed", it)
            return
        }
        val pooled = poolVideos(perKeyword)
        if (pooled.isEmpty()) {
            failed = true
            return
        }
        val checked = checkMaxres(pooled, perKeyword)
        if (checked.isEmpty()) {
            failed = true
            return
        }
        failed = false
        saveVideos(pkg, checked, "yt_kw_cards")
        changed()
    }

    private suspend fun applySubsDiff(pkg: String, newHandles: List<String>) {
        if (!started) return
        diffMutex.withLock {
            val cached = synchronized(lock) { videosByPkg[pkg] ?: readVideos(pkg) }
            if (newHandles.isEmpty()) {
                if (cached.isEmpty()) return
                saveVideos(pkg, emptyList())
                changed()
                return
            }
            val newSet = newHandles.toSet()
            val cachedHandles = cached.mapNotNull { it.handle.ifBlank { null } }.toSet()
            val removed = cachedHandles - newSet
            val added = newHandles.filter { it !in cachedHandles }
            if (removed.isEmpty() && added.isEmpty()) return
            val kept = cached.filter { it.handle.isBlank() || it.handle !in removed }
            val logosAtBefore = prefs.getLong("yt_logos_at_", 0L)
            val fetched: List<List<YtVideo>> = if (added.isEmpty()) {
                emptyList()
            } else {
                try {
                    fetchByRss(added)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "youtube subs incremental fetch failed", e)
                    failed = true
                    lastAttempt = System.currentTimeMillis()
                    return
                }
            }
            if (added.isNotEmpty() && added.size < newHandles.size) {
                prefs.edit().putLong("yt_logos_at_", logosAtBefore).apply()
            }
            val perChannel = groupKeptByHandle(kept) + fetched
            val pooled = poolVideos(perChannel)
            if (pooled.isEmpty()) {
                failed = true
                lastAttempt = System.currentTimeMillis()
                return
            }
            val checked = checkMaxres(pooled, perChannel, kept.map { it.id }.toSet())
            if (checked.isEmpty()) {
                failed = true
                lastAttempt = System.currentTimeMillis()
                return
            }
            failed = false
            saveVideos(pkg, applyChineseTitles(checked))
            changed()
        }
    }

    private fun groupKeptByHandle(kept: List<YtVideo>): List<List<YtVideo>> {
        val byHandle = LinkedHashMap<String, MutableList<YtVideo>>()
        val unknown = ArrayList<YtVideo>()
        for (video in kept) {
            if (video.handle.isBlank()) unknown += video else byHandle.getOrPut(video.handle) { ArrayList() } += video
        }
        val out = ArrayList<List<YtVideo>>(byHandle.values)
        if (unknown.isNotEmpty()) out += unknown
        return out
    }

    private fun saveVideos(pkg: String, videos: List<YtVideo>, prefix: String = "yt_cards") {
        val arr = JSONArray()
        for (video in videos) {
            arr.put(JSONObject().apply {
                put("id", video.id)
                put("title", video.title)
                put("channel", video.channel)
                put("published", video.publishedAt)
                put("logo", video.logo ?: "")
                put("handle", video.handle)
                put("cover", video.cover)
            })
        }
        prefs.edit().apply {
            putString("${prefix}_$pkg", arr.toString())
            putLong("${prefix}_at_$pkg", System.currentTimeMillis())
        }.apply()
        synchronized(lock) { if (prefix == "yt_cards") videosByPkg[pkg] = videos else kwVideosByPkg[pkg] = videos }
    }

    private fun readVideos(pkg: String, prefix: String = "yt_cards"): List<YtVideo> {
        if (!::prefs.isInitialized) return emptyList()
        val arr = runCatching { JSONArray(prefs.getString("${prefix}_$pkg", "") ?: "") }.getOrNull() ?: return emptyList()
        val out = ArrayList<YtVideo>()
        for (i in 0 until arr.length()) {
            if (out.size >= MAX_ITEMS) break
            val obj = arr.optJSONObject(i) ?: continue
            val id = obj.optString("id", "")
            if (id.isBlank()) continue
            out += YtVideo(id, obj.optString("title", ""), obj.optString("channel", ""), obj.optLong("published", 0L), obj.optString("logo", "").ifBlank { null }, obj.optString("handle", ""), obj.optString("cover", ""))
        }
        return out
    }

    private fun blocked(): Boolean = System.currentTimeMillis() < blockedUntil

    private fun markBlocked(conn: HttpURLConnection) {
        val retryAfter = conn.getHeaderField("Retry-After")?.toLongOrNull()?.coerceIn(1, 60) ?: 10
        blockedUntil = System.currentTimeMillis() + retryAfter * 1000
        Log.w(TAG, "YouTube 429, backing off ${retryAfter}s")
    }

    private suspend fun fetchByRss(handles: List<String>): List<List<YtVideo>> {
        val out = ArrayList<List<YtVideo>>()
        val refreshLogos = logosDue(System.currentTimeMillis())
        for ((index, handle) in handles.withIndex()) {
            currentCoroutineContext().ensureActive()
            if (index > 0) delay(1500)
            val resolved = runCatching { resolveChannelIdByPage(handle, refreshLogos) }.getOrNull() ?: continue
            val videos = runCatching { fetchRss(handle, resolved.first) }.getOrNull() ?: continue
            out += videos.map { it.copy(logo = resolved.second, handle = handle) }
        }
        if (refreshLogos) prefs.edit().putLong("yt_logos_at_", System.currentTimeMillis()).apply()
        return out
    }

    private suspend fun fetchBySearch(keywords: List<String>): List<List<YtVideo>> {
        val out = ArrayList<List<YtVideo>>()
        for ((index, kw) in keywords.withIndex()) {
            currentCoroutineContext().ensureActive()
            if (index > 0) delay(1500)
            val videos = runCatching { searchKeyword(kw) }.getOrNull() ?: continue
            out += videos
        }
        return out
    }

    private fun searchKeyword(kw: String): List<YtVideo>? {
        val q = URLEncoder.encode(kw, "UTF-8")
        val conn = openGet("https://www.youtube.com/results?search_query=$q&sp=CAI%3D&hl=zh-CN")
        try {
            val code = conn.responseCode
            if (code == 429) {
                markBlocked(conn)
                return null
            }
            if (code !in 200..299) {
                Log.w(TAG, "YouTube search HTTP $code")
                return null
            }
            val page = conn.inputStream.use { readCapped(it, 2 * 1024 * 1024).decodeToString() }
            return parseSearchResults(page, kw)
        } finally {
            conn.disconnect()
        }
    }

    private fun parseSearchResults(page: String, kw: String): List<YtVideo> {
        val out = ArrayList<YtVideo>()
        val seen = HashSet<String>()
        val blockPattern = Regex("\"videoRenderer\":\\{")
        val idPattern = Regex("\"videoId\":\"([A-Za-z0-9_-]{11})\"")
        val titlePattern = Regex("\"title\":\\{\"runs\":\\[\\{\"text\":\"((?:[^\"\\\\]|\\\\.)*)\"")
        val timePattern = Regex("\"publishedTimeText\":\\{\"simpleText\":\"([^\"]+)\"")
        val channelPattern = Regex("\"ownerText\":\\{\"runs\":\\[\\{\"text\":\"((?:[^\"\\\\]|\\\\.)*)\"")
        var from = 0
        while (from < page.length) {
            val block = blockPattern.find(page, from) ?: break
            from = block.range.last + 1
            val end = minOf(page.length, block.range.first + 6000)
            val seg = page.substring(block.range.first, end)
            val id = idPattern.find(seg)?.groupValues?.get(1) ?: continue
            if (!seen.add(id)) continue
            val title = titlePattern.find(seg)?.groupValues?.get(1)?.let { unescapeJson(it) }?.trim().orEmpty()
            if (title.isEmpty()) continue
            val timeRaw = timePattern.find(seg)?.groupValues?.get(1).orEmpty()
            val channel = channelPattern.find(seg)?.groupValues?.get(1)?.let { unescapeJson(it) }?.trim().orEmpty()
            out += YtVideo(id, title, channel, parseRelativeTime(timeRaw), null, "kw:$kw")
            if (out.size >= KW_MAX) break
        }
        return out
    }

    private fun parseRelativeTime(raw: String): Long {
        val m = Regex("(\\d+)\\s*(mo|y|w|d|h|m|s)\\s*ago").find(raw) ?: return 0L
        val n = m.groupValues[1].toLongOrNull() ?: return 0L
        val unitMs = when (m.groupValues[2]) {
            "s" -> 1_000L
            "m" -> 60_000L
            "h" -> 3_600_000L
            "d" -> 86_400_000L
            "w" -> 7 * 86_400_000L
            "mo" -> 30 * 86_400_000L
            "y" -> 365 * 86_400_000L
            else -> return 0L
        }
        return System.currentTimeMillis() - n * unitMs
    }

    private fun logosDue(now: Long): Boolean {
        if (!::prefs.isInitialized) return false
        val last = prefs.getLong("yt_logos_at_", 0L)
        val start = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis
        return last < start
    }

    private fun poolVideos(perChannel: List<List<YtVideo>>): List<YtVideo> {
        val selected = ArrayList<YtVideo>()
        val seen = HashSet<String>()
        for (list in perChannel) {
            val newest = list.maxByOrNull { it.publishedAt } ?: continue
            if (seen.add(newest.id)) selected += newest
        }
        val rest = perChannel.flatten().filter { it.id !in seen }.sortedByDescending { it.publishedAt }
        for (video in rest) {
            if (selected.size >= MAX_ITEMS) break
            if (seen.add(video.id)) selected += video
        }
        return selected.sortedByDescending { it.publishedAt }
    }

    private suspend fun checkMaxres(pooled: List<YtVideo>, perChannel: List<List<YtVideo>>, skipCheck: Set<String> = emptySet()): List<YtVideo> {
        val pooledIds = pooled.map { it.id }.toSet()
        val rest = perChannel.flatten().filter { it.id !in pooledIds }.distinctBy { it.id }.sortedByDescending { it.publishedAt }
        val kept = ArrayList<YtVideo>()
        val tried = HashSet<String>()
        for (video in pooled + rest) {
            if (kept.size >= MAX_ITEMS) break
            if (!tried.add(video.id)) continue
            currentCoroutineContext().ensureActive()
            if (video.id in skipCheck) {
                kept += video
                continue
            }
            delay(1500)
            if (isShort(video.id)) continue
            val cover = if (maxresOk(video.id)) "https://i.ytimg.com/vi/${video.id}/maxresdefault.jpg" else "https://i.ytimg.com/vi/${video.id}/hqdefault.jpg"
            kept += video.copy(cover = cover)
        }
        return kept.sortedByDescending { it.publishedAt }
    }

    private fun needsChineseTitle(title: String): Boolean {
        return title.none { it.code in 0x4E00..0x9FFF }
    }

    private fun applyChineseTitles(videos: List<YtVideo>): List<YtVideo> {
        val byHandle = videos.groupBy { it.handle }
        val translated = HashMap<String, String>()
        for ((handle, list) in byHandle) {
            if (handle.isBlank()) continue
            if (list.none { needsChineseTitle(it.title) }) continue
            translated += fetchChineseTitles(handle)
        }
        if (translated.isEmpty()) return videos
        return videos.map { video ->
            val zh = translated[video.id]
            if (zh != null && zh.isNotBlank() && needsChineseTitle(video.title)) video.copy(title = zh) else video
        }
    }

    private fun fetchChineseTitles(handle: String): Map<String, String> {
        return try {
            val name = handle.substringAfterLast("@")
            val conn = openGet("https://www.youtube.com/@$name/videos?hl=zh-CN")
            try {
                val code = conn.responseCode
                if (code == 429) {
                    markBlocked(conn)
                    return emptyMap()
                }
                if (code !in 200..299) return emptyMap()
                val page = conn.inputStream.use { readCapped(it, 2 * 1024 * 1024).decodeToString() }
                parseChineseTitles(page)
            } finally {
                conn.disconnect()
            }
        } catch (e: Exception) {
            Log.w(TAG, "youtube zh titles fetch failed", e)
            emptyMap()
        }
    }

    private fun parseChineseTitles(page: String): Map<String, String> {
        val out = HashMap<String, String>()
        val idPattern = Regex("\"contentId\":\"([A-Za-z0-9_-]{11})\"")
        val titlePattern = Regex("\"lockupMetadataViewModel\":\\{\"title\":\\{\"content\":\"((?:[^\"\\\\]|\\\\.)*)\"")
        var from = 0
        while (from < page.length) {
            val idMatch = idPattern.find(page, from) ?: break
            val id = idMatch.groupValues[1]
            from = idMatch.range.last + 1
            if (id in out) continue
            val end = minOf(page.length, idMatch.range.last + 15000)
            val titleMatch = titlePattern.find(page, idMatch.range.first)?.takeIf { it.range.first < end } ?: continue
            val title = unescapeJson(titleMatch.groupValues[1])
            if (title.isNotBlank()) out[id] = title
        }
        return out
    }

    private fun unescapeJson(s: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c == '\\' && i + 1 < s.length) {
                when (s[i + 1]) {
                    'n' -> { out.append('\n'); i += 2 }
                    't' -> { out.append('\t'); i += 2 }
                    'r' -> { out.append('\r'); i += 2 }
                    '"' -> { out.append('"'); i += 2 }
                    '\\' -> { out.append('\\'); i += 2 }
                    '/' -> { out.append('/'); i += 2 }
                    'u' -> {
                        if (i + 5 < s.length) {
                            val code = s.substring(i + 2, i + 6).toIntOrNull(16)
                            if (code != null) {
                                out.appendCodePoint(code)
                                i += 6
                            } else {
                                out.append(c)
                                i++
                            }
                        } else {
                            out.append(c)
                            i++
                        }
                    }
                    else -> { out.append(s[i + 1]); i += 2 }
                }
            } else {
                out.append(c)
                i++
            }
        }
        return out.toString()
    }

    private fun resolveChannelIdByPage(handle: String, refreshLogos: Boolean): Pair<String, String?>? {
        val cachedId = readHandleIds()[handle]
        if (cachedId != null && readCheckedIds().contains(handle)) {
            val cachedLogo = readHandleLogos()[handle]?.ifBlank { null }
            if (!refreshLogos && cachedLogo != null) return cachedId to cachedLogo
            val body = fetchHandlePage(handle) ?: return cachedId to cachedLogo
            saveTitleFromBody(handle, body)
            val logo = findLogo(body)
            if (!logo.isNullOrBlank()) saveHandleLogo(handle, logo)
            return cachedId to (logo ?: cachedLogo)
        }
        val body = fetchHandlePage(handle) ?: return cachedId?.let { it to readHandleLogos()[handle]?.ifBlank { null } }
        saveTitleFromBody(handle, body)
        val id = findChannelId(body)
        if (id != null) {
            if (id != cachedId) saveHandleId(handle, id)
            markCheckedId(handle)
        }
        val logo = findLogo(body)
        if (!logo.isNullOrBlank()) saveHandleLogo(handle, logo)
        val finalId = id ?: cachedId ?: return null
        return finalId to (logo ?: readHandleLogos()[handle]?.ifBlank { null })
    }

    private fun fetchHandlePage(handle: String): String? {
        val name = handle.substringAfterLast("@")
        val conn = openGet("https://www.youtube.com/@$name")
        try {
            val code = conn.responseCode
            if (code == 429) {
                markBlocked(conn)
                return null
            }
            if (code !in 200..299) {
                Log.w(TAG, "YouTube handle page HTTP $code")
                return null
            }
            return conn.inputStream.use { readCapped(it, 2 * 1024 * 1024).decodeToString() }
        } finally {
            conn.disconnect()
        }
    }

    private fun findChannelId(body: String): String? {
        val patterns = listOf(
            Regex("<link rel=\"canonical\" href=\"https://www.youtube.com/channel/(UC[0-9A-Za-z_-]{22})\""),
            Regex("\"channelId\":\"(UC[0-9A-Za-z_-]{22})\""),
            Regex("\"externalId\":\"(UC[0-9A-Za-z_-]{22})\""),
        )
        for (pattern in patterns) {
            pattern.find(body)?.let { return it.groupValues[1] }
        }
        return null
    }

    private fun findLogo(body: String): String? {
        val tag = Regex("<meta[^>]*property=\"og:image\"[^>]*>").find(body)?.value ?: return null
        val url = Regex("content=\"([^\"]+)\"").find(tag)?.groupValues?.get(1)?.trim().orEmpty()
        if (url.isEmpty()) return null
        return Regex("=s\\d+").replaceFirst(url, "=s176")
    }

    private fun findTitle(body: String): String? {
        val raw = Regex("<title>(.*?)</title>", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1)?.trim().orEmpty()
        if (raw.isEmpty()) return null
        val name = decodeEntities(raw).removeSuffix(" - YouTube").trim()
        return name.ifBlank { null }
    }

    private fun decodeEntities(s: String): String {
        var out = s.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'").replace("&nbsp;", " ")
        out = Regex("&#(\\d+);").replace(out) { m -> m.groupValues[1].toIntOrNull()?.let { code -> if (code in 0..0x10FFFF) String(Character.toChars(code)) else null } ?: m.value }
        out = Regex("&#x([0-9a-fA-F]+);").replace(out) { m -> m.groupValues[1].toIntOrNull(16)?.let { code -> if (code in 0..0x10FFFF) String(Character.toChars(code)) else null } ?: m.value }
        return out.replace("&amp;", "&")
    }

    private fun saveTitleFromBody(handle: String, body: String) {
        val title = findTitle(body) ?: return
        saveHandleTitle(handle, title)
    }

    private fun fetchRss(handle: String, channelId: String): List<YtVideo>? {
        val url = "https://www.youtube.com/feeds/videos.xml?channel_id=$channelId"
        val conn = openGet(url)
        try {
            val code = conn.responseCode
            if (code == 429) {
                markBlocked(conn)
                return null
            }
            if (code !in 200..299) {
                Log.w(TAG, "YouTube RSS HTTP $code")
                return null
            }
            val parsed = conn.inputStream.use { parseRss(it) }
            val feedTitle = parsed.second
            if (!feedTitle.isNullOrBlank()) saveHandleTitle(handle, feedTitle)
            val videos = parsed.first
            val out = ArrayList<YtVideo>()
            for (video in videos) {
                if (out.size >= RSS_MAX) break
                if (video.publishedAt <= 0L) continue
                out += video
            }
            return out
        } finally {
            conn.disconnect()
        }
    }

    private fun parseRss(stream: InputStream): Pair<List<YtVideo>, String?> {
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(stream, "UTF-8")
        val out = ArrayList<YtVideo>()
        var feedTitle = ""
        var feedAuthor = ""
        var inEntry = false
        var id = ""
        var title = ""
        var channel = ""
        var published = 0L
        var text = ""
        while (true) {
            val event = parser.next()
            if (event == XmlPullParser.END_DOCUMENT) break
            when (event) {
                XmlPullParser.START_TAG -> {
                    if (parser.name == "entry") {
                        inEntry = true
                        id = ""
                        title = ""
                        channel = ""
                        published = 0L
                    }
                }
                XmlPullParser.TEXT -> text = parser.text
                XmlPullParser.END_TAG -> {
                    if (!inEntry) {
                        when (parser.name) {
                            "title" -> if (feedTitle.isEmpty()) feedTitle = text.trim()
                            "name" -> if (feedAuthor.isEmpty()) feedAuthor = text.trim()
                        }
                        continue
                    }
                    when (parser.name) {
                        "yt:videoId" -> id = text.trim()
                        "title" -> title = text.trim()
                        "name" -> channel = text.trim()
                        "published" -> published = parseTime(text.trim())
                        "entry" -> {
                            inEntry = false
                            if (id.isNotEmpty() && title.isNotEmpty()) out += YtVideo(id, title, channel, published)
                        }
                    }
                }
            }
        }
        return out to feedTitle.ifBlank { feedAuthor }.ifBlank { null }
    }

    private fun isShort(id: String): Boolean {
        return runCatching {
            val conn = URL("https://www.youtube.com/shorts/$id").openConnection() as HttpURLConnection
            conn.requestMethod = "HEAD"
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000
            conn.instanceFollowRedirects = false
            try {
                conn.responseCode == 200
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(false)
    }

    private fun maxresOk(id: String): Boolean {
        return runCatching {
            val conn = URL("https://i.ytimg.com/vi/$id/maxresdefault.jpg").openConnection() as HttpURLConnection
            conn.requestMethod = "HEAD"
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000
            conn.instanceFollowRedirects = true
            try {
                conn.responseCode == 200
            } finally {
                conn.disconnect()
            }
        }.getOrDefault(false)
    }

    private fun openGet(url: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 8_000
        conn.readTimeout = 8_000
        conn.instanceFollowRedirects = true
        conn.setRequestProperty("Accept", "application/json")
        conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9")
        conn.setRequestProperty("User-Agent", UA)
        return conn
    }

    private fun readCapped(stream: InputStream, max: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val buf = ByteArray(8192)
        var total = 0
        while (total < max) {
            val n = stream.read(buf, 0, minOf(buf.size, max - total))
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
        }
        return out.toByteArray()
    }

    private fun parseTime(raw: String): Long {
        if (raw.isBlank()) return 0L
        val formats = listOf("yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ssXXX")
        for (pattern in formats) {
            val parsed = runCatching {
                SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(raw)
            }.getOrNull()
            if (parsed != null) return parsed.time
        }
        return 0L
    }

    private fun readHandleIds(): MutableMap<String, String> {
        val map = LinkedHashMap<String, String>()
        if (!::prefs.isInitialized) return map
        val obj = runCatching { JSONObject(prefs.getString("yt_handle_ids", "") ?: "") }.getOrNull() ?: return map
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            map[k] = obj.optString(k, "")
        }
        return map
    }

    private fun saveHandleId(handle: String, channelId: String) {
        if (!::prefs.isInitialized) return
        val map = readHandleIds()
        map[handle] = channelId
        val obj = JSONObject()
        for ((k, v) in map) obj.put(k, v)
        prefs.edit().putString("yt_handle_ids", obj.toString()).apply()
    }

    private fun readCheckedIds(): Set<String> {
        if (!::prefs.isInitialized) return emptySet()
        return prefs.getStringSet("yt_ids_checked", emptySet()) ?: emptySet()
    }

    private fun markCheckedId(handle: String) {
        if (!::prefs.isInitialized) return
        val set = HashSet(readCheckedIds())
        set += handle
        prefs.edit().putStringSet("yt_ids_checked", set).apply()
    }

    private fun readHandleLogos(): MutableMap<String, String> {
        val map = LinkedHashMap<String, String>()
        if (!::prefs.isInitialized) return map
        val obj = runCatching { JSONObject(prefs.getString("yt_handle_logos", "") ?: "") }.getOrNull() ?: return map
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            map[k] = obj.optString(k, "")
        }
        return map
    }

    private fun saveHandleLogo(handle: String, logo: String) {
        if (!::prefs.isInitialized) return
        val map = readHandleLogos()
        map[handle] = logo
        val obj = JSONObject()
        for ((k, v) in map) obj.put(k, v)
        prefs.edit().putString("yt_handle_logos", obj.toString()).apply()
    }

    private fun readHandleTitles(): MutableMap<String, String> {
        val map = LinkedHashMap<String, String>()
        if (!::prefs.isInitialized) return map
        val obj = runCatching { JSONObject(prefs.getString("yt_handle_titles", "") ?: "") }.getOrNull() ?: return map
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            map[k] = obj.optString(k, "")
        }
        return map
    }

    private fun saveHandleTitle(handle: String, title: String) {
        if (!::prefs.isInitialized) return
        val map = readHandleTitles()
        map[handle] = title
        val obj = JSONObject()
        for ((k, v) in map) obj.put(k, v)
        prefs.edit().putString("yt_handle_titles", obj.toString()).apply()
    }
}
