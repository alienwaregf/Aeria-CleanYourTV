package com.gathin.tvhome.tmdb

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import androidx.annotation.OptIn
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSink
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.CacheEvictor
import androidx.media3.datasource.cache.CacheSpan
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.datasource.cache.ContentMetadata
import androidx.media3.datasource.cache.SimpleCache
import com.gathin.tvhome.ratings.Ratings
import com.gathin.tvhome.wallpaper.ScreensaverMode
import com.gathin.tvhome.wallpaper.Wallpapers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

data class TmdbItem(
    val id: Int,
    val mediaType: String,
    val title: String,
    val overview: String,
    val voteAverage: Double,
    val backdropUrl: String,
    val logoUrl: String = "",
    val year: String,
    val providerUrls: List<String> = emptyList(),
    val providersFetched: Boolean = false,
    val imdbId: String = "",
    val imdbRating: String = "",
    val rtPercent: Int = -1,
    val omdbFetched: Boolean = false,
    val traktRating: Double = -1.0,
    val traktVotes: Int = 0,
    val traktFetched: Boolean = false,
)

object TmdbTrending {
    private const val TAG = "TmdbTrending"
    private const val PREFS = "tmdb"
    private const val LAUNCHER_PREFS = "launcher_settings"
    private const val KEY_TMDB_KEY = "tmdb_key"
    private const val KEY_FETCHED_AT = "fetched_at"
    private const val KEY_ITEMS_JSON = "items_json"
    private const val KEY_LAST_ERROR = "last_error"
    private const val TTL_MS = 30 * 60_000L
    private const val RETRY_MS = 3 * 60 * 1000L
    private const val MAX_ITEMS = 20
    private const val IMAGE_BASE = "https://image.tmdb.org/t/p/original"
    private const val PROVIDER_BASE = "https://media.themoviedb.org/t/p/h60"
    private const val TRENDING_URL =
        "https://api.themoviedb.org/3/trending/all/day?language=zh-CN&page=1"

    var items by mutableStateOf<List<TmdbItem>>(emptyList())
        private set

    var currentIndex by mutableIntStateOf(0)

    val hasContent: Boolean get() = items.isNotEmpty()

    fun trendingGateOpen(): Boolean =
        Wallpapers.isTrending() || Wallpapers.screensaver == ScreensaverMode.Trending

    private lateinit var app: Context
    private lateinit var tmdbPrefs: SharedPreferences
    private lateinit var launcherPrefs: SharedPreferences
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var started = false
    private var lastAttempt = 0L
    private var failed = false
    private var job: Job? = null

    fun init(context: Context) {
        if (started) return
        started = true
        app = context.applicationContext
        TrendingImages.init(app)
        tmdbPrefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        launcherPrefs = app.getSharedPreferences(LAUNCHER_PREFS, Context.MODE_PRIVATE)
        val cached = tmdbPrefs.getString(KEY_ITEMS_JSON, null)
        if (!cached.isNullOrEmpty()) {
            items = parseItemsJson(cached)
        }
        if (keySet() && System.currentTimeMillis() - tmdbPrefs.getLong(KEY_FETCHED_AT, 0) >= TTL_MS) {
            refresh()
        } else if (keySet() && items.any { it.logoUrl.isBlank() || !it.providersFetched }) {
            scope.launch {
                val original = items
                val updated = withContext(Dispatchers.IO) { backfillDetails(original) }
                if (updated !== original && items == original) {
                    items = updated
                    tmdbPrefs.edit { putString(KEY_ITEMS_JSON, itemsToJson(updated)) }
                }
                prefetch(items)
            }
        } else if (keySet() && items.isNotEmpty()) {
            prefetch(items)
        }
    }

    private var prefetchJob: Job? = null
    private fun prefetch(list: List<TmdbItem>) {
        if (list.isEmpty()) return
        if (!trendingGateOpen()) return
        prefetchJob?.cancel()
        prefetchJob = scope.launch {
            withContext(Dispatchers.IO) {
                TrendingImages.prune(list.flatMapTo(HashSet()) { item ->
                    buildList {
                        add(item.backdropUrl)
                        if (item.logoUrl.isNotBlank()) add(item.logoUrl)
                        addAll(item.providerUrls)
                    }
                })
                prefetchHeroImages(list)
            }
        }
    }

    fun refreshIfStale() {
        if (!started) return
        val now = System.currentTimeMillis()
        if (now - tmdbPrefs.getLong(KEY_FETCHED_AT, 0) < TTL_MS) return
        if (failed && now - lastAttempt < RETRY_MS) return
        refresh()
    }

    fun refreshClockTick() {
        if (!started) return
        refresh(fromTick = true)
    }

    fun setKey(key: String) {
        if (!::launcherPrefs.isInitialized) return
        val k = key.trim()
        launcherPrefs.edit { if (k.isEmpty()) remove(KEY_TMDB_KEY) else putString(KEY_TMDB_KEY, k) }
        lastAttempt = 0L
        failed = false
        refresh()
    }

    fun keySet(): Boolean =
        ::launcherPrefs.isInitialized && launcherPrefs.getString(KEY_TMDB_KEY, "").orEmpty().isNotBlank()

    fun tmdbApiKey(): String =
        if (::launcherPrefs.isInitialized) launcherPrefs.getString(KEY_TMDB_KEY, "").orEmpty().trim() else ""

    fun updateItem(updated: TmdbItem) {
        if (!started) return
        scope.launch {
            val current = items
            val index = current.indexOfFirst { it.id == updated.id && it.mediaType == updated.mediaType }
            if (index < 0) return@launch
            val base = current[index]
            val merged = base.copy(
                imdbId = updated.imdbId,
                imdbRating = updated.imdbRating,
                rtPercent = updated.rtPercent,
                omdbFetched = updated.omdbFetched,
                traktRating = updated.traktRating,
                traktVotes = updated.traktVotes,
                traktFetched = updated.traktFetched,
            )
            if (base == merged) return@launch
            val next = current.toMutableList()
            next[index] = merged
            items = next
            tmdbPrefs.edit { putString(KEY_ITEMS_JSON, itemsToJson(next)) }
        }
    }

    private fun refresh(fromTick: Boolean = false) {
        if (!::launcherPrefs.isInitialized) return
        if (!trendingGateOpen()) return
        val key = launcherPrefs.getString(KEY_TMDB_KEY, "").orEmpty().trim()
        if (key.isEmpty()) return
        lastAttempt = System.currentTimeMillis()
        job?.cancel()
        job = scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { fetchTrending(key) }.onFailure { if (it is CancellationException) throw it }
            }
            result.onFailure { e ->
                failed = true
                Log.w(TAG, "trending fetch failed", e)
                tmdbPrefs.edit { putString(KEY_LAST_ERROR, (e.message ?: e.toString()).take(256)) }
            }
            result.onSuccess { fetched ->
                failed = false
                val old = items.associateBy { it.mediaType + ":" + it.id }
                val merged = fetched.map { n ->
                    val o = old[n.mediaType + ":" + n.id]
                    if (o == null) n else n.copy(
                        imdbId = o.imdbId,
                        imdbRating = o.imdbRating,
                        rtPercent = o.rtPercent,
                        omdbFetched = if (fromTick) false else o.omdbFetched,
                        traktRating = o.traktRating,
                        traktVotes = o.traktVotes,
                        traktFetched = if (fromTick) false else o.traktFetched,
                    )
                }
                items = merged
                tmdbPrefs.edit {
                    putLong(KEY_FETCHED_AT, System.currentTimeMillis())
                    putString(KEY_ITEMS_JSON, itemsToJson(merged))
                    remove(KEY_LAST_ERROR)
                }
                prefetch(merged)
                if (fromTick) scope.launch { Ratings.refreshAll(merged) }
            }
        }
    }

    private suspend fun fetchTrending(key: String): List<TmdbItem> {
        val isV4 = key.startsWith("eyJ")
        val url = if (isV4) TRENDING_URL else "$TRENDING_URL&api_key=${URLEncoder.encode(key, "UTF-8")}"
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8_000
        c.readTimeout = 8_000
        c.setRequestProperty("Accept", "application/json")
        if (isV4) c.setRequestProperty("Authorization", "Bearer $key")
        try {
            val code = c.responseCode
            if (code !in 200..299) {
                val retryAfter = c.getHeaderField("Retry-After")
                throw IOException(
                    "HTTP $code" + if (code == 429 && !retryAfter.isNullOrBlank()) " Retry-After=$retryAfter" else "",
                )
            }
            val body = c.inputStream.use { it.readBytes().decodeToString() }
            val filtered = parseTrending(JSONObject(body))
            if (filtered.isEmpty()) throw IOException("no usable trending items")
            return filtered.map {
                currentCoroutineContext().ensureActive()
                val (logo, bestBackdrop) = fetchImageDetails(key, it)
                val providers = fetchProviderUrls(key, it)
                it.copy(
                    logoUrl = logo,
                    backdropUrl = bestBackdrop.ifBlank { it.backdropUrl },
                    providerUrls = providers ?: emptyList(),
                    providersFetched = providers != null,
                )
            }
        } finally {
            c.disconnect()
        }
    }

    private fun fetchImageDetails(key: String, item: TmdbItem): Pair<String, String> {
        return runCatching {
            val isV4 = key.startsWith("eyJ")
            val base = if (item.mediaType == "tv") {
                "https://api.themoviedb.org/3/tv/${item.id}/images"
            } else {
                "https://api.themoviedb.org/3/movie/${item.id}/images"
            }
            val url = "$base?include_image_language=zh-CN,zh-SG,zh-HK,zh-TW,zh,en-US,en,null" +
                if (isV4) "" else "&api_key=${URLEncoder.encode(key, "UTF-8")}"
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 5_000
            c.readTimeout = 5_000
            c.setRequestProperty("Accept", "application/json")
            if (isV4) c.setRequestProperty("Authorization", "Bearer $key")
            try {
                if (c.responseCode !in 200..299) return Pair("", "")
                val body = c.inputStream.use { it.readBytes().decodeToString() }
                val root = JSONObject(body)
                Pair(
                    pickLogo(root.optJSONArray("logos")) ?: "",
                    pickBackdrop(root.optJSONArray("backdrops")) ?: "",
                )
            } finally {
                c.disconnect()
            }
        }.getOrDefault(Pair("", ""))
    }

    private fun pickBackdrop(backdrops: JSONArray?): String? {
        if (backdrops == null) return null
        var bestPath: String? = null
        var bestArea = -1L
        for (i in 0 until backdrops.length()) {
            val obj = backdrops.optJSONObject(i) ?: continue
            val path = obj.optString("file_path", "")
            if (path.isBlank()) continue
            val w = obj.optInt("width", 0)
            val h = obj.optInt("height", 0)
            if (w <= 0 || h <= 0) continue
            val area = w.toLong() * h.toLong()
            if (area > bestArea) {
                bestArea = area
                bestPath = path
            }
        }
        return bestPath?.let { IMAGE_BASE + it }
    }

    private fun pickLogo(logos: JSONArray?): String? {
        if (logos == null) return null
        var bestPath: String? = null
        var bestRank = Int.MAX_VALUE
        for (i in 0 until logos.length()) {
            val obj = logos.optJSONObject(i) ?: continue
            val path = obj.optString("file_path", "")
            if (path.isBlank()) continue
            val lang = obj.optString("iso_639_1", "")
            val region = obj.optString("iso_3166_1", "")
            val rank = when {
                lang == "zh" && (region == "CN" || region.isEmpty()) -> 0
                lang == "zh" && region == "SG" -> 1
                lang == "zh" && region == "HK" -> 2
                lang == "zh" && region == "TW" -> 3
                lang == "en" -> 4
                else -> null
            } ?: continue
            if (rank < bestRank) {
                bestRank = rank
                bestPath = path
            }
        }
        return bestPath?.let { IMAGE_BASE + it }
    }

    private fun fetchProviderUrls(key: String, item: TmdbItem): List<String>? {
        return runCatching {
            val isV4 = key.startsWith("eyJ")
            val base = if (item.mediaType == "tv") {
                "https://api.themoviedb.org/3/tv/${item.id}/watch/providers"
            } else {
                "https://api.themoviedb.org/3/movie/${item.id}/watch/providers"
            }
            val url = base + if (isV4) "" else "?api_key=${URLEncoder.encode(key, "UTF-8")}"
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 5_000
            c.readTimeout = 5_000
            c.setRequestProperty("Accept", "application/json")
            if (isV4) c.setRequestProperty("Authorization", "Bearer $key")
            try {
                if (c.responseCode !in 200..299) return null
                val body = c.inputStream.use { it.readBytes().decodeToString() }
                val results = JSONObject(body).optJSONObject("results") ?: return emptyList()
                val best = HashMap<Int, Triple<String, Int, Int>>()
                val countries = results.keys()
                while (countries.hasNext()) {
                    val flatrate = results.optJSONObject(countries.next())?.optJSONArray("flatrate") ?: continue
                    for (i in 0 until flatrate.length()) {
                        val obj = flatrate.optJSONObject(i) ?: continue
                        val id = obj.optInt("provider_id", 0)
                        if (id == 0) continue
                        val canonicalId = when (id) {
                            1796 -> 8
                            10, 119, 2100 -> 9
                            1899, 31 -> 384
                            2 -> 350
                            2303, 2616, 1770, 2304 -> 531
                            387 -> 386
                            else -> id
                        }
                        val path = obj.optString("logo_path", "")
                        if (path.isBlank()) continue
                        val priority = obj.optInt("display_priority", Int.MAX_VALUE)
                        val prev = best[canonicalId]
                        if (prev == null || priority < prev.second || (priority == prev.second && id < prev.third)) {
                            best[canonicalId] = Triple(PROVIDER_BASE + path, priority, id)
                        }
                    }
                }
                best.entries
                    .sortedWith(compareBy({ it.value.second }, { it.key }))
                    .map { it.value.first }
            } finally {
                c.disconnect()
            }
        }.getOrNull()
    }

    private fun backfillDetails(list: List<TmdbItem>): List<TmdbItem> {
        if (!trendingGateOpen()) return list
        val key = launcherPrefs.getString(KEY_TMDB_KEY, "").orEmpty().trim()
        if (key.isEmpty()) return list
        var changed = false
        val out = list.map {
            var cur = it
            if (cur.logoUrl.isBlank()) {
                val (url, _) = fetchImageDetails(key, cur)
                if (url.isNotBlank()) {
                    changed = true
                    cur = cur.copy(logoUrl = url)
                }
            }
            if (!cur.providersFetched) {
                val providers = fetchProviderUrls(key, cur)
                if (providers != null) {
                    changed = true
                    cur = cur.copy(providerUrls = providers, providersFetched = true)
                }
            }
            cur
        }
        return if (changed) out else list
    }

    private fun parseTrending(root: JSONObject): List<TmdbItem> {
        val results = root.optJSONArray("results") ?: return emptyList()
        val out = ArrayList<TmdbItem>()
        for (i in 0 until results.length()) {
            if (out.size >= MAX_ITEMS) break
            val obj = results.optJSONObject(i) ?: continue
            val id = obj.optInt("id", 0)
            if (id == 0) continue
            val mediaType = obj.optString("media_type", "")
            if (mediaType != "movie" && mediaType != "tv") continue
            val backdropPath = obj.optString("backdrop_path", "")
            if (backdropPath.isBlank()) continue
            val title = if (mediaType == "movie") {
                obj.optString("title", "").ifBlank { obj.optString("original_title", "") }
            } else {
                obj.optString("name", "").ifBlank { obj.optString("original_name", "") }
            }.trim()
            if (title.isBlank()) continue
            val date = if (mediaType == "movie") obj.optString("release_date", "") else obj.optString("first_air_date", "")
            val year = date.take(4).takeIf { it.length == 4 && it.all { ch -> ch.isDigit() } } ?: ""
            out += TmdbItem(
                id = id,
                mediaType = mediaType,
                title = title,
                overview = obj.optString("overview", "").trim(),
                voteAverage = obj.optDouble("vote_average", 0.0),
                backdropUrl = IMAGE_BASE + backdropPath,
                year = year,
            )
        }
        return out
    }

    private fun itemsToJson(list: List<TmdbItem>): String {
        val arr = JSONArray()
        for (item in list) {
            arr.put(
                JSONObject().apply {
                    put("id", item.id)
                    put("media_type", item.mediaType)
                    put("title", item.title)
                    put("overview", item.overview)
                    put("vote_average", item.voteAverage)
                    put("backdrop_url", item.backdropUrl)
                    put("logo_url", item.logoUrl)
                    put("provider_urls", JSONArray(item.providerUrls))
                    put("providers_fetched", item.providersFetched)
                    put("imdb_id", item.imdbId)
                    put("imdb_rating", item.imdbRating)
                    put("rt_percent", item.rtPercent)
                    put("omdb_fetched", item.omdbFetched)
                    put("trakt_rating", item.traktRating)
                    put("trakt_votes", item.traktVotes)
                    put("trakt_fetched", item.traktFetched)
                    put("year", item.year)
                },
            )
        }
        return arr.toString()
    }

    private fun parseItemsJson(json: String): List<TmdbItem> {
        return runCatching {
            val arr = JSONArray(json)
            val out = ArrayList<TmdbItem>()
            for (i in 0 until arr.length()) {
                if (out.size >= MAX_ITEMS) break
                val obj = arr.optJSONObject(i) ?: continue
                out += TmdbItem(
                    id = obj.optInt("id", 0),
                    mediaType = obj.optString("media_type", ""),
                    title = obj.optString("title", ""),
                    overview = obj.optString("overview", ""),
                    voteAverage = obj.optDouble("vote_average", 0.0),
                    backdropUrl = obj.optString("backdrop_url", "").replace("/t/p/w1280/", "/t/p/original/"),
                    logoUrl = obj.optString("logo_url", "").replace("/t/p/w500/", "/t/p/original/"),
                    providerUrls = obj.optJSONArray("provider_urls")?.let { arr ->
                        (0 until arr.length()).mapNotNull { j ->
                            arr.optString(j, "").takeIf { it.isNotBlank() }
                        }
                    } ?: emptyList(),
                    providersFetched = obj.optBoolean("providers_fetched", false),
                    imdbId = obj.optString("imdb_id", ""),
                    imdbRating = obj.optString("imdb_rating", ""),
                    rtPercent = obj.optInt("rt_percent", -1),
                    omdbFetched = obj.optBoolean("omdb_fetched", false),
                    traktRating = obj.optDouble("trakt_rating", -1.0),
                    traktVotes = obj.optInt("trakt_votes", 0),
                    traktFetched = obj.optBoolean("trakt_fetched", false),
                    year = obj.optString("year", ""),
                )
            }
            out
        }.onFailure { Log.w(TAG, "cached items unreadable", it) }.getOrDefault(emptyList())
    }
}

@OptIn(UnstableApi::class)
internal object TrendingImages {
    private const val TAG = "TrendingImages"
    private const val DIR_NAME = "trending"

    private const val CACHE_BYTES = 64L * 1024 * 1024

    private const val KEEP_MAX_BYTES = 3_000_000_000L

    @Volatile
    var keepBytes = CACHE_BYTES
        private set

    @Volatile
    private var cache: SimpleCache? = null

    @Volatile
    private var app: Context? = null

    private val evictor = TrendingEvictor { keepBytes }

    fun init(context: Context) {
        if (app != null) return
        app = context.applicationContext
        cache()
    }

    private fun cache(): SimpleCache? {
        val ctx = app ?: return null
        return cache ?: synchronized(this) {
            cache ?: run {
                val dir = File(ctx.cacheDir, DIR_NAME)
                val kept = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
                keepBytes = ((ctx.cacheDir.usableSpace + kept) / 4).coerceIn(CACHE_BYTES, KEEP_MAX_BYTES)
                SimpleCache(dir, evictor, StandaloneDatabaseProvider(ctx)).also { cache = it }
            }
        }
    }

    private fun http() = DefaultHttpDataSource.Factory()
        .setConnectTimeoutMs(5_000)
        .setReadTimeoutMs(8_000)

    fun isCached(url: String): Boolean {
        val cache = cache() ?: return false
        val length = ContentMetadata.getContentLength(cache.getContentMetadata(url))
        return length > 0 && cache.isCached(url, 0, length)
    }

    fun bytesFor(url: String): ByteArray? {
        val cache = cache() ?: return null
        return runCatching {
            val source = CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(http())
                .setCacheWriteDataSinkFactory(CacheDataSink.Factory().setCache(cache))
                .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)
                .createDataSource()
            val spec = DataSpec.Builder().setUri(Uri.parse(url)).setKey(url).build()
            source.open(spec)
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            try {
                while (true) {
                    val n = source.read(buf, 0, buf.size)
                    if (n == C.RESULT_END_OF_INPUT) break
                    if (n > 0) out.write(buf, 0, n)
                }
            } finally {
                source.close()
            }
            out.toByteArray()
        }.onFailure { Log.w(TAG, "trending image read failed: $url", it) }.getOrNull()
    }

    fun prefetch(url: String) {
        if (url.isBlank()) return
        val cache = cache() ?: return
        if (isCached(url)) return
        runCatching {
            val writer = CacheDataSource.Factory()
                .setCache(cache)
                .setUpstreamDataSourceFactory(http())
                .setCacheWriteDataSinkFactory(CacheDataSink.Factory().setCache(cache))
            val spec = DataSpec.Builder().setUri(Uri.parse(url)).setKey(url).build()
            CacheWriter(writer.createDataSource(), spec, null, null).cache()
        }.onFailure { Log.w(TAG, "could not prefetch $url", it) }
    }

    fun prune(keepUrls: Set<String>) {
        val cache = cache() ?: return
        runCatching {
            cache.keys.filter { it !in keepUrls }.forEach { cache.removeResource(it) }
        }.onFailure { Log.w(TAG, "trending prune failed", it) }
    }
}

@OptIn(UnstableApi::class)
private class TrendingEvictor(private val limit: () -> Long) : CacheEvictor {
    private val spans = java.util.TreeSet<CacheSpan> { a, b ->
        if (a.lastTouchTimestamp != b.lastTouchTimestamp) a.lastTouchTimestamp.compareTo(b.lastTouchTimestamp) else a.compareTo(b)
    }
    private var size = 0L

    override fun requiresCacheSpanTouches() = true
    override fun onCacheInitialized() = Unit

    override fun onStartFile(cache: Cache, key: String, position: Long, length: Long) {
        if (length != C.LENGTH_UNSET.toLong()) evict(cache, length)
    }

    override fun onSpanAdded(cache: Cache, span: CacheSpan) {
        spans.add(span)
        size += span.length
        evict(cache, 0)
    }

    override fun onSpanRemoved(cache: Cache, span: CacheSpan) {
        spans.remove(span)
        size -= span.length
    }

    override fun onSpanTouched(cache: Cache, oldSpan: CacheSpan, newSpan: CacheSpan) {
        onSpanRemoved(cache, oldSpan)
        onSpanAdded(cache, newSpan)
    }

    fun evict(cache: Cache, required: Long) {
        val max = limit()
        while (size + required > max && spans.isNotEmpty()) cache.removeSpan(spans.first())
    }
}

internal suspend fun prefetchHeroImages(items: List<TmdbItem>) {
    for (item in items) {
        TrendingImages.prefetch(item.backdropUrl)
        yield()
        if (item.logoUrl.isNotBlank()) TrendingImages.prefetch(item.logoUrl)
        yield()
        for (u in item.providerUrls) {
            TrendingImages.prefetch(u)
            yield()
        }
    }
}
