package com.gathin.tvhome.apps

import android.content.Context
import android.content.SharedPreferences
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
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL

data class HuyaRoom(
    val roomId: String,
    val uid: String = "",
    val title: String,
    val nick: String,
    val cover: String,
    val logo: String?,
)

object HuyaSubs {
    val HUYA_PKGS = setOf("com.huya.nftv")
    private const val TAG = "HuyaSubs"
    private const val LAUNCHER_PREFS = "launcher_settings"
    private const val TTL_MS = 30 * 60_000L
    private const val FETCH_INTERVAL_MS = 10 * 60_000L
    private const val RETRY_MS = 3 * 60 * 1000L
    private const val MAX_ITEMS = 30
    private const val LIVELIST_URL = "https://www.huya.com/cache.php?m=LiveList&do=getLiveListByPage&tagAll=0&page=1"
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"

    var version by mutableIntStateOf(0)
        private set

    private lateinit var app: Context
    private lateinit var prefs: SharedPreferences
    private var started = false
    private var lastAttempt = 0L
    private var failed = false
    private val lock = Any()
    private val fetchMutex = Mutex()
    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var rooms: List<HuyaRoom> = emptyList()

    fun init(context: Context) {
        if (started) return
        started = true
        app = context.applicationContext
        prefs = app.getSharedPreferences(LAUNCHER_PREFS, Context.MODE_PRIVATE)
        val cached = readRooms()
        if (cached.isNotEmpty()) {
            synchronized(lock) { rooms = cached }
        }
    }

    fun isHuya(pkg: String): Boolean = pkg in HUYA_PKGS

    private fun isAnyInstalled(): Boolean {
        if (!::app.isInitialized) return false
        val pm = app.packageManager
        return HUYA_PKGS.any { pkg -> runCatching { pm.getPackageInfo(pkg, 0) }.isSuccess }
    }

    fun changed() {
        version++
    }

    fun cards(pkg: String): List<ShelfCard> {
        if (!started || !isHuya(pkg)) return emptyList()
        val list = synchronized(lock) { rooms }
        return list.map { v ->
            ShelfCard(
                pkg = pkg,
                title = v.title,
                imageUri = v.cover,
                backdropUri = v.cover,
                intentUri = "intent:#Intent;component=com.huya.nftv/com.duowan.kiwitv.tv.activity.TVTransferActivity;S.huya_action=living;S.presenter_id=${v.uid};end",
                source = ShelfSource.HUYA_POPULAR,
                logoUri = v.logo,
            )
        }
    }

    private fun fetchedAt(): Long =
        if (::prefs.isInitialized) prefs.getLong("huya_cards_at", 0L) else 0L

    private fun isFresh(now: Long): Boolean {
        if (now - fetchedAt() >= TTL_MS) return false
        return synchronized(lock) { rooms }.isNotEmpty()
    }

    fun refreshAsync(pkg: String) {
        ioScope.launch { refreshIfStale(pkg) }
    }

    suspend fun refreshIfStale(pkg: String) {
        if (!started || !isHuya(pkg)) return
        if (!isAnyInstalled()) return
        val now = System.currentTimeMillis()
        if (isFresh(now) && now - fetchedAt() < FETCH_INTERVAL_MS) return
        if (failed && now - lastAttempt < RETRY_MS) return
        fetchMutex.withLock {
            val at = System.currentTimeMillis()
            if (isFresh(at) && at - fetchedAt() < FETCH_INTERVAL_MS) return
            if (failed && at - lastAttempt < RETRY_MS) return
            fetchAndSave()
        }
    }

    suspend fun refreshAllIfStale() {
        if (!started) return
        refreshIfStale(HUYA_PKGS.first())
    }

    private suspend fun fetchAndSave() {
        lastAttempt = System.currentTimeMillis()
        val fetched = try {
            fetchLiveList()
        } catch (e: CancellationException) {
            throw e
        } catch (t: Throwable) {
            failed = true
            Log.w(TAG, "huya livelist fetch failed", t)
            return
        }
        if (fetched.isEmpty()) {
            failed = true
            return
        }
        failed = false
        saveRooms(fetched)
        changed()
    }

    private suspend fun fetchLiveList(): List<HuyaRoom> {
        currentCoroutineContext().ensureActive()
        val conn = openGet(LIVELIST_URL)
        try {
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "Huya livelist HTTP $code")
                return emptyList()
            }
            val body = conn.inputStream.use { readCapped(it, 1024 * 1024).decodeToString() }
            currentCoroutineContext().ensureActive()
            return parseLiveList(body)
        } finally {
            conn.disconnect()
        }
    }

    private fun parseLiveList(body: String): List<HuyaRoom> {
        val root = runCatching { JSONObject(body) }.getOrNull() ?: return emptyList()
        if (root.optInt("status", -1) != 200) return emptyList()
        val list = root.optJSONObject("data")?.optJSONArray("datas") ?: return emptyList()
        val out = ArrayList<HuyaRoom>()
        for (i in 0 until list.length()) {
            if (out.size >= MAX_ITEMS) break
            val obj = list.optJSONObject(i) ?: continue
            val roomId = obj.optString("profileRoom", "")
            if (roomId.isBlank()) continue
            val uid = obj.optString("uid", "")
            if (uid.isBlank()) continue
            val nick = obj.optString("nick", "")
            val title = obj.optString("introduction", "").ifBlank { nick }
            if (title.isBlank()) continue
            val cover = obj.optString("screenshot", "").let {
                if (it.startsWith("http://")) "https://" + it.substring(7) else it
            }
            if (cover.isBlank()) continue
            val face = obj.optString("avatar180", "").let {
                if (it.startsWith("http://")) "https://" + it.substring(7) else it
            }.ifBlank { null }
            out += HuyaRoom(roomId, uid, title, nick, cover, face)
        }
        return out
    }

    private fun saveRooms(list: List<HuyaRoom>) {
        val arr = JSONArray()
        for (room in list) {
            arr.put(JSONObject().apply {
                put("room", room.roomId)
                put("uid", room.uid)
                put("title", room.title)
                put("nick", room.nick)
                put("cover", room.cover)
                put("face", room.logo ?: "")
            })
        }
        prefs.edit().apply {
            putString("huya_cards", arr.toString())
            putLong("huya_cards_at", System.currentTimeMillis())
        }.apply()
        synchronized(lock) { rooms = list }
    }

    private fun readRooms(): List<HuyaRoom> {
        if (!::prefs.isInitialized) return emptyList()
        val arr = runCatching { JSONArray(prefs.getString("huya_cards", "") ?: "") }.getOrNull() ?: return emptyList()
        val out = ArrayList<HuyaRoom>()
        for (i in 0 until arr.length()) {
            if (out.size >= MAX_ITEMS) break
            val obj = arr.optJSONObject(i) ?: continue
            val roomId = obj.optString("room", "")
            if (roomId.isBlank()) continue
            out += HuyaRoom(
                roomId,
                obj.optString("uid", ""),
                obj.optString("title", ""),
                obj.optString("nick", ""),
                obj.optString("cover", ""),
                obj.optString("face", "").ifBlank { null },
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
