package com.gathin.tvhome.apps

import android.content.Context
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

data class BiliVideo(
    val bvid: String,
    val aid: Long,
    val title: String,
    val up: String,
    val publishedAt: Long,
    val cover: String,
    val logo: String?,
)

object BilibiliSubs {
    val BILI_PKGS = setOf("com.xiaodianshi.tv.yst", "com.tutu.myblbl")
    private const val TAG = "BilibiliSubs"
    private const val RETRY_MS = 3 * 60 * 1000L
    private const val MAX_ITEMS = 30
    private const val POPULAR_URL = "https://api.bilibili.com/x/web-interface/popular?ps=30"
    private const val MAX_PAGES = 5
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"

    var version by mutableIntStateOf(0)
        private set

    private lateinit var app: Context
    private var started = false
    private var lastAttempt = 0L
    private var failed = false
    private val lock = Any()
    private val fetchMutex = Mutex()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var videos: List<BiliVideo> = emptyList()

    fun init(context: Context) {
        if (started) return
        started = true
        app = context.applicationContext
    }

    fun isBilibili(pkg: String): Boolean = pkg in BILI_PKGS

    private fun isAnyInstalled(): Boolean {
        if (!::app.isInitialized) return false
        val pm = app.packageManager
        return BILI_PKGS.any { pkg -> runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess }
    }

    fun changed() {
        version++
    }

    fun cards(pkg: String): List<ShelfCard> {
        if (!started || !isBilibili(pkg)) return emptyList()
        val list = synchronized(lock) { videos }
        return list.map { v ->
            ShelfCard(
                pkg = pkg,
                title = v.title,
                imageUri = v.cover,
                backdropUri = v.cover,
                intentUri = "https://www.bilibili.com/video/${v.bvid}",
                source = ShelfSource.BILIBILI_POPULAR,
                logoUri = v.logo,
            )
        }
    }

    fun refreshAsync(pkg: String) {
        ioScope.launch { refresh(pkg) }
    }

    suspend fun refresh(pkg: String) {
        if (!started || !isBilibili(pkg)) return
        if (!isAnyInstalled()) return
        val now = System.currentTimeMillis()
        if (failed && now - lastAttempt < RETRY_MS) return
        fetchMutex.withLock {
            val at = System.currentTimeMillis()
            if (failed && at - lastAttempt < RETRY_MS) return
            fetch()
        }
    }

    suspend fun refreshAll() {
        if (!started) return
        refresh(BILI_PKGS.first())
    }

    private suspend fun fetch() {
        lastAttempt = System.currentTimeMillis()
        val fetched = try {
            fetchPopular()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            failed = true
            Log.w(TAG, "bilibili popular fetch failed", t)
            return
        }
        if (fetched.isEmpty()) {
            failed = true
            return
        }
        failed = false
        synchronized(lock) { videos = fetched }
        changed()
    }

    private suspend fun fetchPopular(): List<BiliVideo> {
        val out = ArrayList<BiliVideo>()
        val seen = HashSet<Long>()
        var page = 1
        while (out.size < MAX_ITEMS && page <= MAX_PAGES) {
            currentCoroutineContext().ensureActive()
            val conn = openGet("$POPULAR_URL&pn=$page")
            try {
                val code = conn.responseCode
                if (code !in 200..299) {
                    Log.w(TAG, "Bilibili popular HTTP $code")
                    break
                }
                val body = conn.inputStream.use { readCapped(it, 1024 * 1024).decodeToString() }
                currentCoroutineContext().ensureActive()
                val list = parsePopular(body)
                for (video in list) {
                    if (seen.add(video.aid)) {
                        out += video
                        if (out.size >= MAX_ITEMS) break
                    }
                }
            } finally {
                conn.disconnect()
            }
            page++
        }
        return out.take(MAX_ITEMS)
    }

    private fun parsePopular(body: String): List<BiliVideo> {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        if (root.optInt("code", -1) != 0) return emptyList()
        val list = root.optJSONObject("data")?.optJSONArray("list") ?: return emptyList()
        val out = ArrayList<BiliVideo>()
        for (i in 0 until list.length()) {
            val obj = list.optJSONObject(i) ?: continue
            if (obj.optInt("copyright", 0) != 3) continue
            val bvid = obj.optString("bvid", "")
            val title = obj.optString("title", "")
            if (bvid.isBlank() || title.isBlank()) continue
            val pic = obj.optString("pic", "").let {
                if (it.startsWith("http://")) "https://" + it.substring(7) else it
            }
            if (pic.isBlank()) continue
            val owner = obj.optJSONObject("owner")
            val face = owner?.optString("face", "")?.ifBlank { null }
            out += BiliVideo(
                bvid,
                obj.optLong("aid", 0L),
                title,
                owner?.optString("name", "") ?: "",
                obj.optLong("pubdate", 0L) * 1000L,
                pic,
                face,
            )
        }
        return out
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
}
