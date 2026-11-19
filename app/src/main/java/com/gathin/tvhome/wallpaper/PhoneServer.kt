package com.gathin.tvhome.wallpaper

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.util.Log
import androidx.core.content.getSystemService
import com.gathin.tvhome.apps.AppBackgrounds
import com.gathin.tvhome.apps.AppCatalog
import com.gathin.tvhome.apps.FOLDER_KEY_PREFIX
import com.gathin.tvhome.apps.AppEntry
import com.gathin.tvhome.apps.AppNames
import com.gathin.tvhome.apps.CustomIcons
import com.gathin.tvhome.apps.IconLibrary
import com.gathin.tvhome.apps.SourceDevice
import com.gathin.tvhome.apps.SourceLooks
import com.gathin.tvhome.apps.TileArtFactory
import com.gathin.tvhome.settings.AerialSettings
import com.gathin.tvhome.ratings.Ratings
import com.gathin.tvhome.system.Diagnostics
import com.gathin.tvhome.system.currentNetwork
import com.gathin.tvhome.settings.FocusStyle
import com.gathin.tvhome.SystemSlimmingActivity
import com.gathin.tvhome.tmdb.TmdbTrending
import com.gathin.tvhome.weather.Weather
import androidx.core.graphics.createBitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.Inet4Address
import java.security.SecureRandom

class PhoneServer private constructor(private val context: Context, private val token: String) : NanoHTTPD(PORT) {

    override fun serve(session: IHTTPSession): Response {
        val prefix = "/$token"
        val uri = session.uri
        if (!uri.startsWith(prefix)) return text(Response.Status.NOT_FOUND, "Not found")
        val path = uri.removePrefix(prefix).ifEmpty { "/" }
        return runCatching {
            when {
                path == "/" -> newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", page())
                path == "/api/state" -> json(state())
                path == "/api/diagnostics" -> text(Response.Status.OK, Diagnostics.report(context))
                path.startsWith("/thumb/") -> thumb(path.removePrefix("/thumb/"))
                session.method == Method.POST && path == "/api/wallpaper" -> {
                    val id = form(session)["id"].orEmpty()
                    onMain { Wallpapers.select(id) }
                    json(state())
                }
                session.method == Method.POST && path == "/api/screensaver" -> {
                    val mode = form(session)["mode"]
                    onMain { Wallpapers.chooseScreensaver(ScreensaverMode.of(mode)) }
                    json(state())
                }
                session.method == Method.POST && path == "/api/setting" -> {
                    val f = form(session)
                    val on = f["on"] == "1"
                    val known = when (f["key"]) {
                        "wallpaperAlwaysMoves" -> { onMain { AerialSettings.chooseWallpaperAlwaysMoves(on) }; true }
                        "showWeather" -> { onMain { AerialSettings.chooseShowWeather(on) }; true }
                        "screensaverClock" -> { onMain { AerialSettings.chooseScreensaverClock(on) }; true }
                        else -> false
                    }
                    json(state().put("saved", known))
                }
                session.method == Method.POST && path == "/api/focus" -> {
                    val style = FocusStyle.of(form(session)["style"])
                    onMain { AerialSettings.chooseFocusStyle(style) }
                    json(state())
                }
                session.method == Method.POST && path == "/api/videos/send" -> video(session)
                session.method == Method.POST && path == "/api/videos/delete" -> {
                    val removed = ScreensaverVideos.delete(context, form(session)["id"].orEmpty())
                    if (removed) onMain { Wallpapers.videosChanged() }
                    json(state().put("removed", removed))
                }
                path.startsWith("/tile/") -> tile(java.net.URLDecoder.decode(path.removePrefix("/tile/"), "UTF-8"), own = session.parameters["art"]?.firstOrNull() == "own")
                path.startsWith("/library/") -> libraryPicture(java.net.URLDecoder.decode(path.removePrefix("/library/"), "UTF-8"))
                session.method == Method.POST && path == "/api/icon" -> icon(session)
                session.method == Method.POST && path == "/api/app/background" -> background(session)
                session.method == Method.POST && path == "/api/app/name" -> {
                    val f = form(session)
                    val key = f["app"].orEmpty()
                    val source = AppCatalog.pinnedSources.firstOrNull { it.key == key }?.source
                    val saved = when {
                        source != null -> { runBlocking(Dispatchers.Main) { SourceLooks.rename(source.id, f["name"]) }; true }
                        AppCatalog.loaded.any { it.key == key } -> { runBlocking(Dispatchers.Main) { AppNames.rename(key, f["name"]) }; true }
                        else -> false
                    }
                    json(state().put("saved", saved))
                }
                session.method == Method.POST && path == "/api/layout" -> layout(form(session))
                session.method == Method.POST && path == "/api/source/device" -> {
                    val f = form(session)
                    val source = AppCatalog.pinnedSources.firstOrNull { it.key == f["app"] }?.source
                    if (source != null) runBlocking(Dispatchers.Main) { SourceLooks.chooseDevice(source.id, SourceDevice.of(f["device"])) }
                    json(state().put("saved", source != null))
                }
                session.method == Method.POST && path == "/api/source/pin" -> {
                    val f = form(session)
                    val id = f["id"].orEmpty()
                    val pin = AppCatalog.pinSource
                    val known = AppCatalog.sources.any { it.id == id }
                    if (known && pin != null) runBlocking(Dispatchers.Main) { pin(id, f["pinned"] == "1") }
                    json(state().put("saved", known && pin != null))
                }
                session.method == Method.POST && path == "/api/folder/name" -> {
                    val f = form(session)
                    val id = f["id"].orEmpty()
                    val name = f["name"].orEmpty().trim().take(FOLDER_NAME_MAX)
                    val rename = AppCatalog.renameFolder
                    val known = AppCatalog.folders.any { it.folder?.id == id }
                    if (known && name.isNotEmpty() && rename != null) runBlocking(Dispatchers.Main) { rename(id, name) }
                    json(state().put("saved", known && name.isNotEmpty() && rename != null))
                }
                session.method == Method.POST && path == "/api/icon/pick" -> {
                    val f = form(session)
                    val pkg = f["app"].orEmpty()
                    val pick = when (f["pick"]) {
                        "library" -> IconLibrary.Pick.Library
                        "own" -> IconLibrary.Pick.Own
                        else -> null
                    }
                    val ok = installed(pkg) && pick != null && (pick != IconLibrary.Pick.Library || IconLibrary.has(pkg))
                    if (ok) {
                        CustomIcons.clear(pkg)
                        IconLibrary.choose(pkg, pick.takeIf { it != IconLibrary.Pick.Library })
                        runBlocking(Dispatchers.Main) { CustomIcons.changed(); IconLibrary.changed() }
                    }
                    json(state().put("saved", ok))
                }
                session.method == Method.POST && path == "/api/icon/reset" -> {
                    val pkg = form(session)["app"].orEmpty()
                    if (installed(pkg)) { CustomIcons.clear(pkg); onMain { CustomIcons.changed() } }
                    json(state())
                }
                session.method == Method.POST && path == "/api/weather/city" -> {
                    val name = form(session)["name"].orEmpty()
                    val found = name.isNotBlank() && runBlocking(Dispatchers.Main) { Weather.choose(name) }
                    json(state().put("found", found))
                }
                session.method == Method.POST && path == "/api/weather/auto" -> {
                    onMain { Weather.useNetworkLocation() }
                    json(state())
                }
                session.method == Method.POST && path == "/api/weather/source" -> {
                    val source = form(session)["source"].orEmpty()
                    onMain { Weather.setSource(source) }
                    json(state())
                }
                session.method == Method.POST && path == "/api/weather/qweather-key" -> {
                    val key = form(session)["key"].orEmpty()
                    onMain { Weather.setQWeatherKey(key) }
                    json(state())
                }
                session.method == Method.POST && path == "/api/tmdb-key" -> {
                    val key = form(session)["key"].orEmpty()
                    onMain { TmdbTrending.setKey(key) }
                    json(state())
                }
                session.method == Method.POST && path == "/api/omdb-key" -> {
                    val key = form(session)["key"].orEmpty()
                    onMain { Ratings.setOmdbKey(key) }
                    json(state())
                }
                session.method == Method.POST && path == "/api/trakt-client-id" -> {
                    val id = form(session)["id"].orEmpty()
                    onMain { Ratings.setTraktClientId(id) }
                    json(state())
                }
                session.method == Method.POST && path == "/api/weather/qweather-host" -> {
                    val host = form(session)["host"].orEmpty()
                    onMain { Weather.setQWeatherHost(host) }
                    json(state())
                }
                session.method == Method.POST && path == "/api/slimming/refresh" -> {
                    SystemSlimmingActivity.refreshSlimmingCache(context.applicationContext)
                    json(state())
                }
                else -> text(Response.Status.NOT_FOUND, "Not found")
            }
        }.getOrElse {
            Log.w(TAG, "request failed", it)
            text(Response.Status.INTERNAL_ERROR, "error")
        }
    }

    private fun page(): String = context.assets.open("web/index.html").bufferedReader().use { it.readText() }
        .replace("{{DEVICE}}", Build.MODEL.replace("_", " "))

    private fun state(): JSONObject {
        val list = JSONArray()
        val selected = Wallpapers.selectedId
        Wallpapers.items.forEach { w ->
            list.put(JSONObject().put("id", w.id).put("kind", w.kind.name.lowercase()).put("title", w.title).put("caption", w.caption ?: ""))
        }
        return JSONObject()
            .put("selected", selected)
            .put("screensaver", Wallpapers.screensaver.key)
            .put("videos", JSONArray().apply {
                ScreensaverVideos.all(context).forEach { put(JSONObject().put("id", it.id).put("name", it.name)) }
            })
            .put("videoRoom", ScreensaverVideos.room(context))
            .put("focusStyle", AerialSettings.focusStyle.key)
            .put("wallpaperAlwaysMoves", AerialSettings.wallpaperAlwaysMoves)
            .put("showWeather", AerialSettings.showWeather)
            .put("screensaverClock", AerialSettings.screensaverClock)
            .put("wallpapers", list)
            .put("apps", JSONArray().apply {
                val apps = AppCatalog.loaded.map { it to (AppNames.of(it.key) ?: it.label) }.sortedBy { it.second }
                AppCatalog.pinnedSources.forEach { a ->
                    val src = a.source ?: return@forEach
                    val look = SourceLooks.of(src.id)
                    val label = look?.let { it.name ?: it.device?.let { d -> context.getString(d.label) } } ?: src.label
                    put(JSONObject().put("key", a.key).put("label", label).put("default", src.label).put("custom", CustomIcons.has(a.key)).put("stamp", CustomIcons.stamp(a.key))
                        .put("source", true).put("port", src.label).put("name", look?.name ?: "").put("device", look?.device?.key ?: ""))
                }
                apps.forEach { (a, label) ->
                    val icon = when {
                        CustomIcons.has(a.key) -> "custom"
                        IconLibrary.has(a.key) && IconLibrary.uses(a.key) -> "library"
                        else -> "own"
                    }
                    put(JSONObject().put("key", a.key).put("label", label).put("default", a.label).put("name", AppNames.of(a.key) ?: "")
                        .put("custom", CustomIcons.has(a.key)).put("library", IconLibrary.has(a.key)).put("icon", icon)
                        .put("stamp", CustomIcons.stamp(a.key) + IconLibrary.stamp(a.key) + icon.hashCode())
                        .put("backgroundUrl", AppBackgrounds.url(a.key)))
                }
            })
            .put("layout", layoutState())
            .put("devices", JSONArray().apply { SourceDevice.entries.forEach { put(JSONObject().put("key", it.key).put("label", context.getString(it.label))) } })
            .put("inputs", JSONArray().apply {
                AppCatalog.sources.forEach { src ->
                    val look = SourceLooks.of(src.id)
                    put(JSONObject().put("id", src.id).put("port", src.label).put("device", src.deviceName ?: "")
                        .put("name", look?.let { it.name ?: it.device?.let { d -> context.getString(d.label) } } ?: "")
                        .put("pinned", src.id in AerialSettings.pinnedSources))
                }
            })
            .put("weather", JSONObject().apply {
                put("place", Weather.place ?: "")
                put("chosen", Weather.chosen)
                put("source", Weather.weatherSource)
                put("qweatherKeyMasked", Weather.qweatherKeyMasked())
                put("qweatherKeySet", Weather.qweatherKeySet())
                put("qweatherHost", Weather.qweatherHost())
                put("qweatherHostSet", Weather.qweatherHostSet())
                Weather.now?.let { put("celsius", it.celsius); put("kind", it.kind.name.lowercase()); it.aqi?.let { a -> put("aqi", a) } }
            })
            .put("tmdb", JSONObject().apply {
                val tmdbKey = context.getSharedPreferences("launcher_settings", Context.MODE_PRIVATE).getString("tmdb_key", "").orEmpty()
                put("keyMasked", if (tmdbKey.isEmpty()) "" else if (tmdbKey.length <= 4) "••••" else "••••" + tmdbKey.takeLast(4))
                put("keySet", tmdbKey.isNotBlank())
            })
            .put("ratings", JSONObject().apply {
                val prefs = context.getSharedPreferences("launcher_settings", Context.MODE_PRIVATE)
                val omdbKey = prefs.getString("omdb_key", "").orEmpty()
                put("omdbKeyMasked", if (omdbKey.isEmpty()) "" else if (omdbKey.length <= 4) "••••" else "••••" + omdbKey.takeLast(4))
                put("omdbKeySet", omdbKey.isNotBlank())
                val traktId = prefs.getString("trakt_client_id", "").orEmpty()
                put("traktClientIdMasked", if (traktId.isEmpty()) "" else if (traktId.length <= 4) "••••" else "••••" + traktId.takeLast(4))
                put("traktClientIdSet", traktId.isNotBlank())
            })
            .put("slimming", JSONObject().apply {
                val slim = context.getSharedPreferences("launcher_settings", Context.MODE_PRIVATE)
                put("label", slim.getString("slimming_label", "") ?: "")
                put("date", slim.getString("slimming_date", "") ?: "")
                put("notes", slim.getString("slimming_notes", "") ?: "")
                put("daily", slim.getBoolean("slimming_daily_check", true))
                put("list", slim.getString("slimming_list", "") ?: "")
            })
    }

    private fun layoutState(): JSONObject {
        fun tile(e: AppEntry) = JSONObject().put("key", e.key).put("label", e.label)
            .put("kind", when { e.folder != null -> "folder"; e.source != null -> "source"; else -> "app" })
            .put("stamp", if (e.folder != null || e.source != null) e.art.hashCode().toLong() else CustomIcons.stamp(e.key) + IconLibrary.stamp(e.key))
            .apply { e.folder?.let { put("id", it.id) }; e.source?.let { put("port", it.label) } }
        val folders = JSONObject()
        AppCatalog.folderApps.forEach { (id, apps) -> folders.put(id, JSONArray().apply { apps.forEach { put(tile(it)) } }) }
        val locked = AerialSettings.locked
        return JSONObject()
            .put("home", JSONArray().apply { AppCatalog.home.forEach { put(tile(it)) } })
            .put("folders", folders)
            .put("hidden", JSONArray().apply { if (!locked) AppCatalog.hiddenApps.forEach { put(tile(it)) } })
            .put("hiddenCount", AppCatalog.hiddenApps.size)
            .put("columns", AerialSettings.homeColumns)
            .put("locked", locked)
            .put("ready", AppCatalog.layout != null)
    }

    private fun layout(f: Map<String, String>): Response {
        val ops = AppCatalog.layout ?: return json(state().put("saved", false))
        val key = f["key"].orEmpty()
        val target = f["target"].orEmpty()
        val folder = f["folder"].orEmpty()
        val known = (AppCatalog.home + AppCatalog.folderApps.values.flatten() + AppCatalog.hiddenApps).map { it.key }.toSet()
        val step: (() -> Unit)? = when (f["op"]) {
            "move" -> if (key in known && target in known) ({ ops.move(key, target) }) else null
            "movein" -> if (key in known && target in known) ({ ops.moveInFolder(key, target) }) else null
            "hide" -> if (key in known) ({ ops.hide(key) }) else null
            "unhide" -> if (key in known && !AerialSettings.locked) ({ ops.unhide(key) }) else null
            "folder" -> if (key in known && !key.startsWith(FOLDER_KEY_PREFIX)) ({ ops.toFolder(key, folder.ifEmpty { null }) }) else null
            "unfolder" -> if (key in known) ({ ops.outOfFolder(key) }) else null
            "ungroup" -> if (folder in AppCatalog.folderApps) ({ ops.ungroup(folder) }) else null
            else -> null
        }
        if (step != null) runBlocking(Dispatchers.Main) { step() }
        return json(state().put("saved", step != null))
    }

    private fun installed(pkg: String) =
        pkg.isNotEmpty() && (AppCatalog.loaded.any { it.key == pkg } || AppCatalog.pinnedSources.any { it.key == pkg })

    private fun tile(pkg: String, own: Boolean = false): Response {
        val app = (AppCatalog.pinnedSources + AppCatalog.loaded + AppCatalog.folders).firstOrNull { it.key == pkg } ?: return text(Response.Status.NOT_FOUND, "")
        val custom = if (own) null else CustomIcons.load(pkg)
        val bmp = custom ?: run {
            val art = if (own && app.source == null && app.folder == null) AppCatalog.ownArt(context, app) ?: app.art else app.art
            TileArtFactory.flatten(art)
        }
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        bmp.recycle()
        val bytes = out.toByteArray()
        return newFixedLengthResponse(Response.Status.OK, "image/jpeg", ByteArrayInputStream(bytes), bytes.size.toLong()).apply {
            addHeader("Cache-Control", "no-store")
        }
    }

    private fun libraryPicture(pkg: String): Response {
        val f = IconLibrary.file(pkg) ?: return text(Response.Status.NOT_FOUND, "")
        val type = if (f.name.endsWith(".png")) "image/png" else "image/jpeg"
        return newFixedLengthResponse(Response.Status.OK, type, f.inputStream(), f.length()).apply {
            addHeader("Cache-Control", "max-age=600")
        }
    }

    private fun icon(session: IHTTPSession): Response {
        if (tooLarge(session)) return text(Response.Status.BAD_REQUEST, "too large")
        val files = HashMap<String, String>()
        session.parseBody(files)
        val pkg = session.parameters["app"]?.firstOrNull().orEmpty()
        if (!installed(pkg)) return text(Response.Status.BAD_REQUEST, "unknown app")
        val f = files.values.map(::File).filter { it.exists() && it.length() > 0 }.maxWithOrNull(compareBy({ it.length() }, { it.name }))
            ?: return text(Response.Status.BAD_REQUEST, "no picture")
        val saved = f.inputStream().use { CustomIcons.save(pkg, it, context.cacheDir) }
        if (saved) onMain { CustomIcons.changed() }
        return json(state().put("saved", saved))
    }

    private fun background(session: IHTTPSession): Response {
        val f = form(session)
        val pkg = f["app"].orEmpty()
        val url = f["url"].orEmpty().trim()
        if (!installed(pkg)) return json(state().put("saved", false))
        val saved = AppBackgrounds.setUrl(context, pkg, url)
        if (saved) onMain { AppBackgrounds.changed() }
        return json(state().put("saved", saved))
    }

    private fun thumb(id: String): Response {
        val bmp = Wallpapers.thumbnail(java.net.URLDecoder.decode(id, "UTF-8")) ?: return text(Response.Status.NOT_FOUND, "")
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
        val bytes = out.toByteArray()
        return newFixedLengthResponse(Response.Status.OK, "image/jpeg", ByteArrayInputStream(bytes), bytes.size.toLong()).apply {
            addHeader("Cache-Control", "max-age=600")
        }
    }

    private fun video(session: IHTTPSession): Response {
        val length = session.headers["content-length"]?.toLongOrNull() ?: 0L
        val name = session.parameters["name"]?.firstOrNull().orEmpty()
        val saved = ScreensaverVideos.receive(context, session.inputStream, length, name) != null
        if (saved) onMain { Wallpapers.videosChanged() }
        return json(state().put("saved", saved)).apply { if (!saved) closeConnection(true) }
    }

    private fun tooLarge(session: IHTTPSession): Boolean =
        (session.headers["content-length"]?.toLongOrNull() ?: 0L) > MAX_UPLOAD

    private fun form(session: IHTTPSession): Map<String, String> {
        session.parseBody(HashMap())
        return session.parameters.mapValues { it.value.firstOrNull().orEmpty() }
    }

    private fun onMain(block: () -> Unit) = runBlocking(Dispatchers.Main) { block() }

    private fun json(o: JSONObject) = newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", o.toString())

    private fun text(status: Response.Status, msg: String) = newFixedLengthResponse(status, "text/plain; charset=utf-8", msg)

    companion object {
        const val PORT = 8799
        private const val TAG = "AerialPhone"
        private const val MAX_UPLOAD = 200L * 1024 * 1024
        private const val FOLDER_NAME_MAX = 24

        @Volatile
        private var running: PhoneServer? = null

        @Synchronized
        fun listen(context: Context): PhoneServer? {
            running?.let { if (it.isAlive) return it }
            return runCatching {
                PhoneServer(context.applicationContext, token(context)).also {
                    it.start(SOCKET_READ_TIMEOUT, true)
                    running = it
                }
            }.onFailure { Log.w(TAG, "could not listen", it) }.getOrNull()
        }

        fun start(context: Context): String? {
            val ip = lanAddress(context) ?: return null
            val server = listen(context) ?: return null
            return "http://$ip:$PORT/${server.token}/"
        }

        private fun token(context: Context): String {
            val prefs = context.getSharedPreferences("phone_page", Context.MODE_PRIVATE)
            prefs.getString("token", null)?.takeIf { it.length == 8 }?.let { return it }
            return newToken().also { prefs.edit().putString("token", it).apply() }
        }

        private fun newToken(): String {
            val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
            val rnd = SecureRandom()
            return (1..8).map { alphabet[rnd.nextInt(alphabet.length)] }.joinToString("")
        }

        fun lanAddress(context: Context): String? {
            val cm = context.getSystemService<ConnectivityManager>() ?: return null
            val current = cm.currentNetwork()
            val networks = runCatching { cm.allNetworks.toList() }.getOrDefault(emptyList())
            val lan = networks.filter { n ->
                val caps = runCatching { cm.getNetworkCapabilities(n) }.getOrNull() ?: return@filter false
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                    (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
            }.sortedByDescending { it == current }
            return (lan + listOfNotNull(current)).firstNotNullOfOrNull { n ->
                runCatching { cm.getLinkProperties(n) }.getOrNull()?.linkAddresses?.map { it.address }
                    ?.firstOrNull { it is Inet4Address && !it.isLoopbackAddress && !it.isLinkLocalAddress && !isBenchmark(it) }
            }?.hostAddress
        }

        private fun isBenchmark(address: java.net.InetAddress): Boolean {
            val b = address.address
            return b.size == 4 && (b[0].toInt() and 0xff) == 198 && (b[1].toInt() and 0xfe) == 18
        }

        fun qr(text: String, scale: Int = 8): Bitmap {
            val matrix = QRCodeWriter().encode(
                text, BarcodeFormat.QR_CODE, 0, 0,
                mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M),
            )
            val n = matrix.width
            val bmp = createBitmap(n * scale, n * scale)
            for (y in 0 until n) for (x in 0 until n) {
                if (matrix[x, y]) for (dy in 0 until scale) for (dx in 0 until scale) bmp.setPixel(x * scale + dx, y * scale + dy, Color.BLACK)
                else for (dy in 0 until scale) for (dx in 0 until scale) bmp.setPixel(x * scale + dx, y * scale + dy, Color.WHITE)
            }
            return bmp
        }
    }
}
