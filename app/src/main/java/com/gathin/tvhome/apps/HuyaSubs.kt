package com.gathin.tvhome.apps

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
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
import java.util.UUID
import java.util.zip.GZIPInputStream

data class HuyaRoom(
    val roomId: String,
    val uid: String = "",
    val title: String,
    val nick: String,
    val cover: String,
    val logo: String?,
    val totalCount: Long = 0L,
)

object HuyaSubs {
    val HUYA_PKGS = setOf("com.huya.nftv")
    private const val TAG = "HuyaSubs"
    private const val RETRY_MS = 3 * 60 * 1000L
    private const val MAX_ITEMS = 30
    private const val LIVELIST_URL = "https://www.huya.com/cache.php?m=LiveList&do=getLiveListByPage&tagAll=0"
    private const val MAX_PAGES = 3
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0 Safari/537.36"
    private const val LAUNCHER_PREFS = "launcher_settings"
    private const val KEY_CARDS = "huya_cards"
    private const val WUP_URL = "https://wup.huya.com"
    private const val KEY_GUID = "huya_wup_guid"

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

    private fun saveRooms(list: List<HuyaRoom>) {
        if (!::prefs.isInitialized) return
        val arr = JSONArray()
        for (room in list) {
            arr.put(JSONObject().apply {
                put("roomId", room.roomId)
                put("uid", room.uid)
                put("title", room.title)
                put("nick", room.nick)
                put("cover", room.cover)
                put("logo", room.logo ?: "")
                put("totalCount", room.totalCount)
            })
        }
        prefs.edit { putString(KEY_CARDS, arr.toString()) }
    }

    private fun readRooms(): List<HuyaRoom> {
        if (!::prefs.isInitialized) return emptyList()
        val arr = runCatching { JSONArray(prefs.getString(KEY_CARDS, "") ?: "") }.getOrNull() ?: return emptyList()
        val out = ArrayList<HuyaRoom>()
        for (i in 0 until arr.length()) {
            if (out.size >= MAX_ITEMS) break
            val obj = arr.optJSONObject(i) ?: continue
            val roomId = obj.optString("roomId", "")
            if (roomId.isBlank()) continue
            out += HuyaRoom(roomId, obj.optString("uid", ""), obj.optString("title", ""), obj.optString("nick", ""), obj.optString("cover", ""), obj.optString("logo", "").ifBlank { null }, obj.optLong("totalCount", 0L))
        }
        return out
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
                title = if (v.nick.isBlank()) v.title else "${v.nick}：${v.title}",
                imageUri = v.cover,
                backdropUri = v.cover,
                intentUri = "intent:#Intent;component=com.huya.nftv/com.duowan.kiwitv.tv.activity.TVTransferActivity;S.huya_action=living;S.presenter_id=${v.uid};end",
                source = ShelfSource.HUYA_POPULAR,
                logoUri = v.logo,
            )
        }
    }

    fun refreshAsync(pkg: String) {
        ioScope.launch { refresh(pkg) }
    }

    suspend fun refresh(pkg: String) {
        if (!started || !isHuya(pkg)) return
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
        refresh(HUYA_PKGS.first())
    }

    private suspend fun fetch() {
        lastAttempt = System.currentTimeMillis()
        val final = ArrayList<HuyaRoom>()
        var page = 1
        while (final.size < MAX_ITEMS && page <= MAX_PAGES) {
            val fetched = try {
                fetchLiveListPage(page)
            } catch (e: CancellationException) {
                throw e
            } catch (t: Throwable) {
                if (page == 1) {
                    failed = true
                    Log.w(TAG, "huya livelist fetch failed", t)
                    return
                }
                break
            }
            if (fetched.isEmpty()) break
            final += filterLive(fetched, MAX_ITEMS - final.size)
            page++
        }
        if (final.isEmpty()) {
            Log.w(TAG, "huya live check filtered all rooms")
        }
        val sorted = final.sortedByDescending { it.totalCount }.take(MAX_ITEMS)
        failed = false
        synchronized(lock) { rooms = sorted }
        saveRooms(sorted)
        changed()
    }

    private suspend fun filterLive(list: List<HuyaRoom>, limit: Int = MAX_ITEMS): List<HuyaRoom> {
        if (list.isEmpty()) return list
        val live = ArrayList<HuyaRoom>()
        var idx = 0
        while (live.size < limit && idx < list.size) {
            val batch = list.subList(idx, minOf(idx + MAX_ITEMS, list.size))
            val results = coroutineScope {
                batch.map { room -> async { room to wupIsLiving(room.uid.toLongOrNull() ?: 0L) } }.awaitAll()
            }
            live.addAll(results.filter { it.second }.map { it.first })
            idx += batch.size
        }
        return live.take(limit)
    }

    private fun wupGuid(): String {
        var g: String? = null
        if (::prefs.isInitialized) g = prefs.getString(KEY_GUID, null)
        if (g.isNullOrBlank()) {
            g = UUID.randomUUID().toString()
            if (::prefs.isInitialized) prefs.edit { putString(KEY_GUID, g) }
        }
        return g ?: UUID.randomUUID().toString()
    }

    private suspend fun wupIsLiving(uid: Long): Boolean {
        if (uid == 0L) return true
        val req = livingInfoReqBytes(uid)
        val body = wupPacket("getNFTVLivingInfo", "tReq", req, 1)
        val conn = try {
            val c = URL(WUP_URL).openConnection() as HttpURLConnection
            c.connectTimeout = 5_000
            c.readTimeout = 8_000
            c.requestMethod = "POST"
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/octet-stream")
            c.setRequestProperty("User-Agent", UA)
            c
        } catch (t: Throwable) {
            return true
        }
        try {
            conn.outputStream.use { it.write(body) }
            val code = conn.responseCode
            if (code !in 200..299) return true
            val raw = conn.inputStream.use { readCapped(it, 1024 * 1024) }
            val data = if (raw.size >= 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte()) {
                GZIPInputStream(raw.inputStream()).use { readCapped(it, 1024 * 1024) }
            } else raw
            return parseBIsLiving(data)
        } catch (t: Throwable) {
            return true
        } finally {
            conn.disconnect()
        }
    }

    private fun livingInfoReqBytes(pid: Long): ByteArray {
        val guid = wupGuid()
        val ua = "huya&1.0.0&official&${android.os.Build.VERSION.SDK_INT}"
        val deviceInfo = android.os.Build.MODEL ?: ""
        val out = JceOut()
        out.writeStruct(0) {
            writeStruct(0) {
                writeLong(0L, 0)
                writeString(guid, 1)
                writeString("", 2)
                writeString(ua, 3)
                writeInt(0, 5)
                writeString(deviceInfo, 6)
            }
            writeLong(pid, 1)
            writeInt(0, 2)
        }
        return out.toByteArray()
    }

    private fun wupPacket(func: String, reqKey: String, reqData: ByteArray, requestId: Int): ByteArray {
        val sbuf = JceOut().apply { writeMapStrBytes(mapOf(reqKey to reqData), 0) }.toByteArray()
        val pkt = JceOut().apply {
            writeShort(3, 1)
            writeByte(0, 2)
            writeInt(0, 3)
            writeInt(requestId, 4)
            writeString("nftvui", 5)
            writeString(func, 6)
            writeBytes(sbuf, 7)
            writeInt(0, 8)
            writeMapStrStr(emptyMap(), 9)
            writeMapStrStr(emptyMap(), 10)
        }.toByteArray()
        val out = ByteArrayOutputStream()
        val total = pkt.size + 4
        out.write(total shr 24 and 0xFF)
        out.write(total shr 16 and 0xFF)
        out.write(total shr 8 and 0xFF)
        out.write(total and 0xFF)
        out.write(pkt)
        return out.toByteArray()
    }

    private fun parseBIsLiving(data: ByteArray): Boolean {
        if (data.size < 4) return true
        val body = data.copyOfRange(4, data.size)
        val r = JceReader(body)
        var sbuf: ByteArray? = null
        while (r.pos < body.size) {
            val (type, tag) = r.readHead()
            if (type == 11) break
            when (type) {
                12 -> { }
                0, 1, 2, 3 -> r.readIntVal(type)
                6, 7 -> r.readStringVal(type)
                13 -> {
                    r.readHead()
                    val (tl, _) = r.readHead()
                    val ln = r.readIntVal(tl).toInt()
                    val bytes = body.copyOfRange(r.pos, r.pos + ln)
                    r.pos += ln
                    if (tag == 7) sbuf = bytes
                }
                8 -> r.skip(type)
                10 -> r.readStructFields()
                else -> r.skip(type)
            }
        }
        val sb = sbuf ?: return true
        val mr = JceReader(sb)
        val (mt, _) = mr.readHead()
        if (mt != 8) return true
        val (t2, _) = mr.readHead()
        val n = mr.readIntVal(t2).toInt()
        var rspBytes: ByteArray? = null
        repeat(n) {
            val (tk, _) = mr.readHead()
            val kk = if (tk in 6..7) mr.readStringVal(tk) else mr.readIntVal(tk).toString()
            val (tv, _) = mr.readHead()
            if (tv == 13) {
                mr.readHead()
                val (tl, _) = mr.readHead()
                val ln = mr.readIntVal(tl).toInt()
                val bytes = sb.copyOfRange(mr.pos, mr.pos + ln)
                mr.pos += ln
                if (kk == "tRsp") rspBytes = bytes
            } else {
                mr.skip(tv)
            }
        }
        val rb = rspBytes ?: return true
        val rr = JceReader(rb)
        val (ht, _) = rr.readHead()
        if (ht != 10) return true
        val fields = rr.readStructFields()
        return (fields[1] as? Long) == 1L
    }

    private class JceOut {
        private val out = ByteArrayOutputStream()

        fun toByteArray(): ByteArray = out.toByteArray()

        fun head(type: Int, tag: Int) {
            if (tag < 15) {
                out.write((tag shl 4) or type)
            } else {
                out.write(0xF0 or type)
                out.write(tag)
            }
        }

        fun writeByte(v: Int, tag: Int) {
            if (v == 0) {
                head(12, tag)
            } else {
                head(0, tag)
                out.write(v and 0xFF)
            }
        }

        fun writeShort(v: Int, tag: Int) {
            if (v in -128..127) {
                writeByte(v, tag)
            } else {
                head(1, tag)
                out.write(v shr 8 and 0xFF)
                out.write(v and 0xFF)
            }
        }

        fun writeInt(v: Int, tag: Int) {
            if (v in -32768..32767) {
                writeShort(v, tag)
            } else {
                head(2, tag)
                out.write(v shr 24 and 0xFF)
                out.write(v shr 16 and 0xFF)
                out.write(v shr 8 and 0xFF)
                out.write(v and 0xFF)
            }
        }

        fun writeLong(v: Long, tag: Int) {
            if (v >= Int.MIN_VALUE && v <= Int.MAX_VALUE) {
                writeInt(v.toInt(), tag)
            } else {
                head(3, tag)
                var x = v
                for (i in 7 downTo 0) {
                    out.write(((x shr (i * 8)) and 0xFF).toInt())
                }
            }
        }

        fun writeString(s: String, tag: Int) {
            val b = s.toByteArray(Charsets.UTF_8)
            if (b.size <= 255) {
                head(6, tag)
                out.write(b.size)
            } else {
                head(7, tag)
                out.write(b.size shr 24 and 0xFF)
                out.write(b.size shr 16 and 0xFF)
                out.write(b.size shr 8 and 0xFF)
                out.write(b.size and 0xFF)
            }
            out.write(b)
        }

        fun writeBytes(b: ByteArray, tag: Int) {
            head(13, tag)
            head(0, 0)
            writeInt(b.size, 0)
            out.write(b)
        }

        fun writeStruct(tag: Int, block: JceOut.() -> Unit) {
            head(10, tag)
            block()
            head(11, 0)
        }

        fun writeMapStrBytes(m: Map<String, ByteArray>, tag: Int) {
            head(8, tag)
            writeInt(m.size, 0)
            for ((k, v) in m) {
                writeString(k, 0)
                writeBytes(v, 1)
            }
        }

        fun writeMapStrStr(m: Map<String, String>, tag: Int) {
            head(8, tag)
            writeInt(m.size, 0)
            for ((k, v) in m) {
                writeString(k, 0)
                writeString(v, 1)
            }
        }
    }

    private class JceReader(private val data: ByteArray) {
        var pos = 0

        fun readHead(): Pair<Int, Int> {
            val b = data[pos++].toInt() and 0xFF
            val type = b and 0x0F
            val tag = b shr 4
            if (tag == 15) return Pair(type, data[pos++].toInt() and 0xFF)
            return Pair(type, tag)
        }

        fun readIntVal(type: Int): Long {
            return when (type) {
                12 -> 0L
                0 -> {
                    val v = data[pos++].toInt()
                    if (v > 127) (v - 256).toLong() else v.toLong()
                }
                1 -> {
                    var v = ((data[pos].toInt() and 0xFF) shl 8) or (data[pos + 1].toInt() and 0xFF)
                    pos += 2
                    if (v > 32767) v -= 65536
                    v.toLong()
                }
                2 -> {
                    var v = 0L
                    for (i in 0..3) v = (v shl 8) or (data[pos + i].toInt() and 0xFF).toLong()
                    pos += 4
                    if (v > Int.MAX_VALUE) v - 4294967296L else v
                }
                else -> {
                    var v = 0L
                    for (i in 0..7) v = (v shl 8) or (data[pos + i].toInt() and 0xFF).toLong()
                    pos += 8
                    v
                }
            }
        }

        fun readStringVal(type: Int): String {
            val len = when (type) {
                6 -> data[pos++].toInt() and 0xFF
                else -> {
                    var v = 0
                    for (i in 0..3) v = (v shl 8) or (data[pos + i].toInt() and 0xFF)
                    pos += 4
                    v
                }
            }
            val s = String(data, pos, len, Charsets.UTF_8)
            pos += len
            return s
        }

        fun readStructFields(): Map<Int, Any?> {
            val fields = HashMap<Int, Any?>()
            while (pos < data.size) {
                val (type, tag) = readHead()
                if (type == 11) break
                when (type) {
                    12 -> fields[tag] = 0L
                    0, 1, 2, 3 -> fields[tag] = readIntVal(type)
                    4 -> {
                        var v = 0
                        for (i in 0..3) v = (v shl 8) or (data[pos + i].toInt() and 0xFF)
                        pos += 4
                        fields[tag] = java.lang.Float.intBitsToFloat(v)
                    }
                    5 -> {
                        var v = 0L
                        for (i in 0..7) v = (v shl 8) or (data[pos + i].toInt() and 0xFF).toLong()
                        pos += 8
                        fields[tag] = java.lang.Double.longBitsToDouble(v)
                    }
                    6, 7 -> fields[tag] = readStringVal(type)
                    8 -> fields[tag] = readMapVal()
                    9 -> fields[tag] = readListVal()
                    10 -> fields[tag] = readStructFields()
                    13 -> {
                        readHead()
                        val (tl, _) = readHead()
                        val ln = readIntVal(tl).toInt()
                        val bytes = data.copyOfRange(pos, pos + ln)
                        pos += ln
                        fields[tag] = bytes
                    }
                    else -> throw IllegalStateException("unknown type " + type)
                }
            }
            return fields
        }

        private fun readMapVal(): Map<String, Any?> {
            val (t, _) = readHead()
            val n = readIntVal(t).toInt()
            val m = HashMap<String, Any?>()
            repeat(n) {
                val (tk, _) = readHead()
                val k = if (tk in 6..7) readStringVal(tk) else readIntVal(tk).toString()
                val (tv, _) = readHead()
                m[k] = when (tv) {
                    12 -> 0L
                    0, 1, 2, 3 -> readIntVal(tv)
                    6, 7 -> readStringVal(tv)
                    13 -> {
                        readHead()
                        val (tl, _) = readHead()
                        val ln = readIntVal(tl).toInt()
                        val bytes = data.copyOfRange(pos, pos + ln)
                        pos += ln
                        bytes
                    }
                    8 -> readMapVal()
                    10 -> readStructFields()
                    else -> {
                        skip(tv)
                        null
                    }
                }
            }
            return m
        }

        private fun readListVal(): List<Any?> {
            val (t, _) = readHead()
            val n = readIntVal(t).toInt()
            val list = ArrayList<Any?>()
            repeat(n) {
                val (tv, _) = readHead()
                list.add(
                    when (tv) {
                        12 -> 0L
                        0, 1, 2, 3 -> readIntVal(tv)
                        6, 7 -> readStringVal(tv)
                        13 -> {
                            readHead()
                            val (tl, _) = readHead()
                            val ln = readIntVal(tl).toInt()
                            val bytes = data.copyOfRange(pos, pos + ln)
                            pos += ln
                            bytes
                        }
                        8 -> readMapVal()
                        10 -> readStructFields()
                        else -> {
                            skip(tv)
                            null
                        }
                    }
                )
            }
            return list
        }

        fun skip(type: Int) {
            when (type) {
                12 -> { }
                0 -> pos += 1
                1 -> pos += 2
                2 -> pos += 4
                3 -> pos += 8
                4 -> pos += 4
                5 -> pos += 8
                6 -> {
                    val ln = data[pos++].toInt() and 0xFF
                    pos += ln
                }
                7 -> {
                    var ln = 0
                    for (i in 0..3) ln = (ln shl 8) or (data[pos + i].toInt() and 0xFF)
                    pos += 4 + ln
                }
                8 -> {
                    val (t, _) = readHead()
                    val n = readIntVal(t).toInt()
                    repeat(n) {
                        val (tk, _) = readHead()
                        skip(tk)
                        val (tv, _) = readHead()
                        skip(tv)
                    }
                }
                9 -> {
                    val (t, _) = readHead()
                    val n = readIntVal(t).toInt()
                    repeat(n) {
                        val (tv, _) = readHead()
                        skip(tv)
                    }
                }
                10 -> readStructFields()
                13 -> {
                    readHead()
                    val (tl, _) = readHead()
                    val ln = readIntVal(tl).toInt()
                    pos += ln
                }
            }
        }
    }

    private suspend fun fetchLiveListPage(page: Int): List<HuyaRoom> {
        currentCoroutineContext().ensureActive()
        val conn = openGet("$LIVELIST_URL&page=$page")
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
            val totalCount = obj.optString("totalCount", "").toLongOrNull() ?: 0L
            out += HuyaRoom(roomId, uid, title, nick, cover, face, totalCount)
        }
        return out.sortedByDescending { it.totalCount }
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
