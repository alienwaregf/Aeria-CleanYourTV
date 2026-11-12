package com.gathin.tvhome.weather

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import com.gathin.tvhome.R
import com.gathin.tvhome.WeatherClient
import com.gathin.tvhome.QWeatherClient
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.net.ssl.SSLException
import kotlin.math.roundToInt

enum class WeatherKind {
    Clear, PartlyCloudy, Cloudy, Fog, Rain, Thunder,
    LightRain, ModerateRain, HeavyRain, StormRain,
    LightSnow, ModerateSnow, HeavySnow, StormSnow,
    Haze, Dust, Sand, Wind, Hail, Sleet,
}

/** Cloud trend over the next 6 hours, keyed sources (Caiyun, QWeather) only; STEADY everywhere else. */
enum class CloudTrend { STEADY, CLEARING, CLOUDING }

/** [high] and [low] are today's, shown under the large clock; null in a reading saved by an older version. */
data class WeatherNow(val celsius: Int, val kind: WeatherKind, val day: Boolean, val high: Int? = null, val low: Int? = null, val trend: CloudTrend = CloudTrend.STEADY, val severe: String? = null, val aqi: String? = null)

/**
 * The weather beside the home clock, from Open-Meteo: no key, reachable from China. The place is
 * the one the owner names on the phone page, or else a guess from the network's public address.
 * The guess is often wrong behind a proxy (a whole home behind one shows another country), which
 * is why the phone page shows it and lets the owner correct it.
 *
 * Fetched when Home comes to the front, at most once an hour; nothing runs while the TV is idle.
 * Compose state, written on the main thread only.
 *
 * Weather source (merged build): "openmeteo" (default, no key), "caiyun" (the owner's own
 * token, stored in the launcher_settings prefs alongside the slimming cache) or "qweather"
 * (the owner's own API Key plus API Host, same prefs). In either keyed mode the 30-minute
 * tick below drives refreshes, plus a fresh fetch every time Home comes back to the
 * foreground, and the reading comes from WeatherClient (Caiyun v2.6 combined /weather
 * endpoint: realtime + today's high/low + alerts in one call) or QWeatherClient (QWeather
 * v1: daily + current + hourly + warning + air quality, 5 calls per refresh); without the key (and, for
 * QWeather, the host) the weather row stays hidden.
 */
object Weather {
    private const val TAG = "AerialWeather"
    private const val PREFS = "weather"
    private const val REFRESH_MS = 60 * 60 * 1000L
    private const val RETRY_MS = 10 * 60 * 1000L
    private const val RELOCATE_MS = 24 * 60 * 60 * 1000L
    /** A reading older than this is not shown at all. */
    private const val STALE_MS = 3 * 60 * 60 * 1000L
    /** Stands for "no high or low" in the prefs. */
    private const val NONE = Int.MIN_VALUE

    var now by mutableStateOf<WeatherNow?>(null); private set

    /** Where the weather is for, as a name to show; null until known. */
    var place by mutableStateOf<String?>(null); private set

    /** Title of the most severe active alert, e.g. a wind blue alert; null when none. */
    var alertTitle by mutableStateOf<String?>(null); private set

    /** True when the owner named the place; false while it is guessed from the network. */
    var chosen by mutableStateOf(false); private set

    private lateinit var prefs: SharedPreferences
    private lateinit var app: Context
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var started = false
    private var lastAttempt = 0L
    private var job: Job? = null

    fun init(context: Context) {
        if (started) return
        started = true
        app = context.applicationContext
        prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        launcherPrefs = app.getSharedPreferences(LAUNCHER_PREFS, Context.MODE_PRIVATE)
        weatherSource = launcherPrefs.getString("weather_source", "openmeteo") ?: "openmeteo"
        place = prefs.getString("place", null)
        chosen = prefs.getBoolean("chosen", false)
        if (System.currentTimeMillis() - prefs.getLong("fetched_at", 0) < STALE_MS) {
            val kind = runCatching { WeatherKind.valueOf(prefs.getString("kind", null) ?: "") }.getOrNull()
            if (kind != null) {
                now = WeatherNow(
                    prefs.getInt("celsius", 0), kind, prefs.getBoolean("day", true),
                    high = prefs.getInt("high", NONE).takeIf { it != NONE },
                    low = prefs.getInt("low", NONE).takeIf { it != NONE },
                    aqi = prefs.getString("aqi", null),
                )
                alertTitle = prefs.getString("alert_title", null)
            }
        }
    }

    private var failed = false

    // ---- Keyed sources (merged build) ----
    /** At most one keyed fetch per 30 minutes on the tick; the return-to-Home fetch below is separate. */
    private const val KEYED_INTERVAL_MS = 30 * 60 * 1000L
    private const val LAUNCHER_PREFS = "launcher_settings"
    private lateinit var launcherPrefs: SharedPreferences
    /** "openmeteo" (default), "caiyun" or "qweather". */
    var weatherSource by mutableStateOf("openmeteo"); private set
    private fun isKeyedSource() = weatherSource == "caiyun" || weatherSource == "qweather"
    private val keyedHandler = Handler(Looper.getMainLooper())
    private var keyedTicking = false
    private val keyedTick = object : Runnable {
        override fun run() {
            if (!keyedTicking) return
            if (isKeyedSource()) refreshKeyedIfDue()
            keyedHandler.postDelayed(this, KEYED_INTERVAL_MS)
        }
    }

    /** 30-minute tick, owned by Home (MainActivity onResume/onPause), never by the slimming page.
     * The first tick fires 30 minutes after Home comes to the foreground; the immediate fetch on
     * return is refreshKeyedOnResume below. */
    fun startKeyedTick() {
        if (keyedTicking) return
        keyedTicking = true
        keyedHandler.postDelayed(keyedTick, KEYED_INTERVAL_MS)
    }

    fun stopKeyedTick() {
        keyedTicking = false
        keyedHandler.removeCallbacks(keyedTick)
    }

    fun refreshKeyedIfDue() {
        if (!started || !isKeyedSource()) return
        if (System.currentTimeMillis() - lastAttempt >= KEYED_INTERVAL_MS) refresh()
    }

    /** Fresh keyed reading every time Home comes back to the foreground (merged build). A fetch
     * less than a minute ago is fresh enough, so a return right after one does not fetch twice
     * (onStart's refreshIfStale can already have fetched on the way back). */
    fun refreshKeyedOnResume() {
        if (!started || !isKeyedSource()) return
        if (System.currentTimeMillis() - lastAttempt < 60_000L) return
        refresh()
    }

    fun setSource(source: String) {
        val src = when (source) {
            "caiyun" -> "caiyun"
            "qweather" -> "qweather"
            else -> "openmeteo"
        }
        launcherPrefs.edit { putString("weather_source", src) }
        weatherSource = src
        lastAttempt = 0L
        refresh()
    }

    /** Empty clears the token. The value itself is never logged. */
    fun setCaiyunToken(token: String) {
        val t = token.trim()
        launcherPrefs.edit { if (t.isEmpty()) remove("weather_caiyun_token") else putString("weather_caiyun_token", t) }
        lastAttempt = 0L
        refresh()
    }

    fun caiyunTokenSet(): Boolean = ::launcherPrefs.isInitialized && launcherPrefs.getString("weather_caiyun_token", "").orEmpty().isNotBlank()

    fun caiyunTokenMasked(): String {
        val t = if (::launcherPrefs.isInitialized) launcherPrefs.getString("weather_caiyun_token", "").orEmpty() else ""
        return if (t.isEmpty()) "" else if (t.length <= 4) "••••" else "••••" + t.takeLast(4)
    }

    /** Empty clears the key. The value itself is never logged. */
    fun setQWeatherKey(key: String) {
        val k = key.trim()
        launcherPrefs.edit { if (k.isEmpty()) remove("weather_qweather_key") else putString("weather_qweather_key", k) }
        lastAttempt = 0L
        refresh()
    }

    /** Empty clears the host. Only the hostname is kept: a pasted scheme or path is stripped. */
    fun setQWeatherHost(host: String) {
        val h = host.trim().removePrefix("https://").removePrefix("http://").substringBefore("/").trim()
        launcherPrefs.edit { if (h.isEmpty()) remove("weather_qweather_host") else putString("weather_qweather_host", h) }
        lastAttempt = 0L
        refresh()
    }

    fun qweatherKeySet(): Boolean = ::launcherPrefs.isInitialized && launcherPrefs.getString("weather_qweather_key", "").orEmpty().isNotBlank()

    fun qweatherKeyMasked(): String {
        val k = if (::launcherPrefs.isInitialized) launcherPrefs.getString("weather_qweather_key", "").orEmpty() else ""
        return if (k.isEmpty()) "" else if (k.length <= 4) "••••" else "••••" + k.takeLast(4)
    }

    /** The host is not a secret, so the page gets the full value, not a mask. */
    fun qweatherHost(): String = if (::launcherPrefs.isInitialized) launcherPrefs.getString("weather_qweather_host", "").orEmpty() else ""

    fun qweatherHostSet(): Boolean = qweatherHost().isNotBlank()

    fun caiyunCity(): String = if (::launcherPrefs.isInitialized) launcherPrefs.getString("weather_city", "").orEmpty() else ""

    private fun kindFromSkycon(skycon: String): WeatherKind = when (skycon) {
        "LIGHT_RAIN" -> WeatherKind.LightRain
        "MODERATE_RAIN" -> WeatherKind.ModerateRain
        "HEAVY_RAIN" -> WeatherKind.HeavyRain
        "STORM_RAIN" -> WeatherKind.StormRain
        "LIGHT_SNOW" -> WeatherKind.LightSnow
        "MODERATE_SNOW" -> WeatherKind.ModerateSnow
        "HEAVY_SNOW" -> WeatherKind.HeavySnow
        "STORM_SNOW" -> WeatherKind.StormSnow
        "LIGHT_HAZE", "MODERATE_HAZE", "HEAVY_HAZE" -> WeatherKind.Haze
        "DUST" -> WeatherKind.Dust
        "SAND" -> WeatherKind.Sand
        "WIND" -> WeatherKind.Wind
        "HAIL" -> WeatherKind.Hail
        "SLEET" -> WeatherKind.Sleet
        "THUNDER_SHOWER" -> WeatherKind.Thunder
        "FOG" -> WeatherKind.Fog
        "CLOUDY" -> WeatherKind.Cloudy
        else -> when {
            skycon.startsWith("CLEAR") -> WeatherKind.Clear
            skycon.startsWith("PARTLY_CLOUDY") -> WeatherKind.PartlyCloudy
            else -> WeatherKind.Rain
        }
    }

    // Cloudiness of one skycon, ignoring the _DAY/_NIGHT suffix so sunset alone never
    // reads as a trend: clear 0, partly cloudy 1, everything else overcast or precipitating 2.
    private fun cloudScore(skycon: String): Int = when (kindFromSkycon(skycon)) {
        WeatherKind.Clear -> 0
        WeatherKind.PartlyCloudy -> 1
        else -> 2
    }

    // Next-6-hours mean cloud score vs now: at least half a step clearer -> CLEARING,
    // at least half a step cloudier -> CLOUDING, otherwise STEADY. Empty hourly data
    // means no trend rather than a wrong one.
    private fun trendFrom(nowSkycon: String, hourlySkycons: Array<String>): CloudTrend {
        if (hourlySkycons.isEmpty()) return CloudTrend.STEADY
        val now = cloudScore(nowSkycon)
        val mean = hourlySkycons.sumOf { cloudScore(it).toDouble() } / hourlySkycons.size
        return when {
            mean <= now - 0.5 -> CloudTrend.CLEARING
            mean >= now + 0.5 -> CloudTrend.CLOUDING
            else -> CloudTrend.STEADY
        }
    }

    /** A day counts as a sharp temperature drop when its low is at least this much below today's low. */
    private const val TEMP_DROP_C = 6

    // GB/T 28592-2012 12-hour rain grades, in mm per 12h period, applied to the
    // two 12h rain totals of the keyed sources: light 0.1-4.9, moderate 5.0-14.9, heavy
    // 15.0-29.9, torrential >= 30.0. Below 0.1 mm is trace rain (CMA's 微量降雨)
    // and counts as no rain here. Levels: 0 none, 1 light, 2 moderate, 3 heavy,
    // 4 torrential. No level above torrential is invented: Caiyun's rain stops at
    // STORM_RAIN, so 70 mm and 140 mm days grade torrential too.
    private fun rainLevel12h(totalMm: Double): Int = when {
        totalMm < 0.1 -> 0
        totalMm < 5.0 -> 1
        totalMm < 15.0 -> 2
        totalMm < 30.0 -> 3
        else -> 4
    }

    // Single-level rain item text for a GB/T level (1-4).
    private fun rainSingle(level: Int): String = when (level) {
        1 -> app.getString(R.string.severe_item_light_rain)
        2 -> app.getString(R.string.severe_item_moderate_rain)
        3 -> app.getString(R.string.severe_item_heavy_rain)
        else -> app.getString(R.string.severe_item_storm_rain)
    }

    // Range item text for a day whose two 12h periods grade differently: lighter to
    // heavier, only light to moderate, moderate to heavy and heavy to torrential.
    // Any other pair (two levels apart, one period dry, or above torrential) falls
    // back to the heavier single level instead of inventing a phrase.
    private fun rainRange(lo: Int, hi: Int): String = when (lo to hi) {
        1 to 2 -> app.getString(R.string.severe_item_rain_range)
        2 to 3 -> app.getString(R.string.severe_item_rain_range_moderate_heavy)
        3 to 4 -> app.getString(R.string.severe_item_rain_range_heavy_storm)
        else -> rainSingle(hi)
    }

    // One severe-weather item for a single future day, or null when the day is calm.
    // Rain is graded from the two 12h totals (keyed sources, GB/T 28592):
    // same grade both periods shows one level, different grades show the range.
    // Snow, hail, fog, haze, wind and thunder keep their own items from the day's
    // skycon; their amounts, if any, are snow water, not rain. Rain/snow (and the
    // rest) wins over a temperature drop on the same day. A day's item text carries
    // its own advice, e.g. torrential rain says to stay indoors, light rain says to
    // bring an umbrella. Without rain totals (Open-Meteo path, or a missing
    // precipitation block) the old skycon pair still applies: daytime and nighttime
    // skycon one light, one moderate, in either order, shows "light to moderate rain".
    private fun severeItem(kind: WeatherKind?, todayMin: Int?, dayMin: Int?, dayKind: WeatherKind?, nightKind: WeatherKind?, dayRainMm: Double? = null, nightRainMm: Double? = null): String? {
        // Rain from the 12h totals, unless the day's primary phenomenon is snow,
        // hail, fog, haze, wind or thunder. Amounts present and dry (both periods
        // under 0.1 mm) means no rain even if a skycon says rain.
        val rainKinds = setOf(WeatherKind.LightRain, WeatherKind.ModerateRain, WeatherKind.HeavyRain, WeatherKind.StormRain, WeatherKind.Rain)
        if (dayRainMm != null && nightRainMm != null && (kind == null || kind == WeatherKind.Clear || kind == WeatherKind.PartlyCloudy || kind == WeatherKind.Cloudy || kind in rainKinds)) {
            val dayLevel = rainLevel12h(dayRainMm)
            val nightLevel = rainLevel12h(nightRainMm)
            val hi = maxOf(dayLevel, nightLevel)
            if (hi > 0) {
                val lo = minOf(dayLevel, nightLevel)
                return if (lo == hi) rainSingle(hi) else rainRange(lo, hi)
            }
            if (todayMin != null && dayMin != null && todayMin - dayMin >= TEMP_DROP_C) {
                return app.getString(R.string.severe_item_temp_drop, dayMin)
            }
            return null
        }
        if (kind == WeatherKind.LightRain || kind == WeatherKind.ModerateRain) {
            val pair = setOf(dayKind, nightKind)
            if (pair == setOf(WeatherKind.LightRain, WeatherKind.ModerateRain)) {
                return app.getString(R.string.severe_item_rain_range)
            }
        }
        when (kind) {
            WeatherKind.LightRain -> return app.getString(R.string.severe_item_light_rain)
            WeatherKind.ModerateRain -> return app.getString(R.string.severe_item_moderate_rain)
            WeatherKind.HeavyRain -> return app.getString(R.string.severe_item_heavy_rain)
            WeatherKind.StormRain -> return app.getString(R.string.severe_item_storm_rain)
            WeatherKind.LightSnow -> return app.getString(R.string.severe_item_light_snow)
            WeatherKind.ModerateSnow -> return app.getString(R.string.severe_item_moderate_snow)
            WeatherKind.HeavySnow -> return app.getString(R.string.severe_item_heavy_snow)
            WeatherKind.StormSnow -> return app.getString(R.string.severe_item_storm_snow)
            WeatherKind.Rain -> return app.getString(R.string.severe_item_light_rain)
            WeatherKind.Hail -> return app.getString(R.string.severe_item_hail)
            WeatherKind.Fog -> return app.getString(R.string.severe_item_fog)
            WeatherKind.Haze -> return app.getString(R.string.severe_item_haze)
            WeatherKind.Wind -> return app.getString(R.string.severe_item_wind)
            WeatherKind.Thunder -> return app.getString(R.string.severe_item_thunder)
            else -> { /* no severe item; fall through to the temperature check */ }
        }
        if (todayMin != null && dayMin != null && todayMin - dayMin >= TEMP_DROP_C) {
            return app.getString(R.string.severe_item_temp_drop, dayMin)
        }
        return null
    }

    // The 3-day severe-weather line, or null when all three days are calm. kinds/mins hold
    // tomorrow, the day after, and the day after that, in that order. Same item on two days
    // joins them with "and"; the same item on all three days becomes "next 3 days"; items
    // that differ show only the earliest day's.
    private fun severeText(todayMin: Int?, kinds: List<WeatherKind?>, mins: List<Int?>, dayKinds: List<WeatherKind?> = emptyList(), nightKinds: List<WeatherKind?> = emptyList(), dayRainMm: List<Double?> = emptyList(), nightRainMm: List<Double?> = emptyList()): String? {
        val dayNames = listOf(R.string.severe_day_tomorrow, R.string.severe_day_after, R.string.severe_day_3)
        val items = kinds.indices.mapNotNull { i ->
            severeItem(kinds.getOrNull(i), todayMin, mins.getOrNull(i), dayKinds.getOrNull(i), nightKinds.getOrNull(i), dayRainMm.getOrNull(i), nightRainMm.getOrNull(i))?.let { i to it }
        }
        if (items.isEmpty()) return null
        val first = items.first()
        return if (items.all { it.second == first.second }) {
            when (items.size) {
                1 -> app.getString(R.string.severe_single, app.getString(dayNames[first.first]), first.second)
                2 -> app.getString(R.string.severe_two, app.getString(dayNames[items[0].first]), app.getString(dayNames[items[1].first]), first.second)
                else -> app.getString(R.string.severe_three, first.second)
            }
        } else {
            app.getString(R.string.severe_single, app.getString(dayNames[first.first]), first.second)
        }
    }

    private fun caiyunRefresh() {
        lastAttempt = System.currentTimeMillis()
        WeatherClient.load(app) { result ->
            if (result == null) {
                // Failed: keep the last reading on Home (user 2026-11-09); the next
                // scheduled refresh (or a network reconnect) tries again.
                failed = true
                return@load
            }
            applyKeyedResult(result)
        }
    }

    private fun qweatherRefresh() {
        lastAttempt = System.currentTimeMillis()
        QWeatherClient.load(app) { result ->
            if (result == null) {
                // Failed: keep the last reading on Home, same as Caiyun above.
                failed = true
                return@load
            }
            applyKeyedResult(result)
        }
    }

    // One consumer for both keyed sources: they return the same Result shape, so
    // the mapping, trend, severe line and persistence are shared. The day flag
    // comes from the client (Caiyun: skycon _NIGHT suffix; QWeather: sunrise/sunset).
    private fun applyKeyedResult(result: WeatherClient.Result) {
        failed = false
        val kind = kindFromSkycon(result.skycon)
        now = WeatherNow(
            celsius = result.temperatureC.roundToInt(),
            kind = kind,
            day = result.day,
            high = result.highC,
            low = result.lowC,
            trend = trendFrom(result.skycon, result.hourlySkycons),
            severe = severeText(
                todayMin = result.dailyMinC.getOrNull(0)?.takeIf { it != Int.MIN_VALUE },
                kinds = (1..3).map { i -> result.dailySkycons.getOrNull(i)?.takeIf { it.isNotEmpty() }?.let { kindFromSkycon(it) } },
                mins = (1..3).map { i -> result.dailyMinC.getOrNull(i)?.takeIf { it != Int.MIN_VALUE } },
                dayKinds = (1..3).map { i -> result.dailySkyconDay.getOrNull(i)?.takeIf { it.isNotEmpty() }?.let { kindFromSkycon(it) } },
                nightKinds = (1..3).map { i -> result.dailySkyconNight.getOrNull(i)?.takeIf { it.isNotEmpty() }?.let { kindFromSkycon(it) } },
                dayRainMm = (1..3).map { i -> result.dailyRainDayMm.getOrNull(i)?.takeIf { !it.isNaN() } },
                nightRainMm = (1..3).map { i -> result.dailyRainNightMm.getOrNull(i)?.takeIf { !it.isNaN() } },
            ),
            aqi = result.aqi,
        )
        alertTitle = result.alertTitle
        place = result.city
        chosen = caiyunCity().isNotEmpty()
        prefs.edit {
            putLong("fetched_at", System.currentTimeMillis())
            putInt("celsius", result.temperatureC.roundToInt())
            putString("kind", kind.name)
            putBoolean("day", result.day)
            putInt("high", result.highC ?: NONE)
            putInt("low", result.lowC ?: NONE)
            if (result.aqi.isNullOrEmpty()) remove("aqi") else putString("aqi", result.aqi)
            if (result.alertTitle.isNullOrEmpty()) remove("alert_title") else putString("alert_title", result.alertTitle)
        }
    }

    /** Like the daily photos: a failed reading is retried when the network connects (issue #129). */
    fun refreshIfStale(networkCameUp: Boolean = false) {
        if (!started) return
        val since = System.currentTimeMillis() - lastAttempt
        if (since >= REFRESH_MS || (failed && (networkCameUp || since >= RETRY_MS))) refresh()
    }

    private fun refresh() {
        if (weatherSource == "caiyun") { caiyunRefresh(); return }
        if (weatherSource == "qweather") { qweatherRefresh(); return }
        lastAttempt = System.currentTimeMillis()
        job?.cancel()
        job = scope.launch {
            val reading = withContext(Dispatchers.IO) {
                runCatching { fetch() }.onFailure { Log.w(TAG, "weather unavailable", it) }.getOrNull()
            }
            prefs.getString("place", null)?.let { place = it }
            failed = reading == null
            if (reading != null) {
                now = reading
                alertTitle = null
                prefs.edit {
                    putLong("fetched_at", System.currentTimeMillis())
                    putInt("celsius", reading.celsius)
                    putString("kind", reading.kind.name)
                    putBoolean("day", reading.day)
                    putInt("high", reading.high ?: NONE)
                    putInt("low", reading.low ?: NONE)
                    remove("aqi")
                    remove("alert_title")
                }
            }
        }
    }

    /** Off the main thread. */
    private fun fetch(): WeatherNow {
        var lat = prefs.getFloat("lat", Float.NaN)
        var lon = prefs.getFloat("lon", Float.NaN)
        val guessedLongAgo = System.currentTimeMillis() - prefs.getLong("located_at", 0) > RELOCATE_MS
        if (prefs.getBoolean("chosen", false) && !lat.isNaN() && !prefs.getBoolean("checked_against_cities", false)) {
            correctChosenPlace(lat, lon)?.let { lat = it.lat.toFloat(); lon = it.lon.toFloat() }
        }
        if (!prefs.getBoolean("chosen", false) && (lat.isNaN() || guessedLongAgo)) {
            // One location logic for every source: the same ip138/ip.cn + city-list
            // resolution Caiyun uses (WeatherClient.resolveCity). ipwho.is/ipinfo.io are
            // gone; they were reached through the proxy and put the place in the wrong city.
            val city = if (::launcherPrefs.isInitialized) {
                runCatching { WeatherClient.resolveCity(app, launcherPrefs) }.getOrNull()
            } else null
            if (city != null) {
                lat = city[2].toFloat()
                lon = city[3].toFloat()
                prefs.edit {
                    putFloat("lat", lat); putFloat("lon", lon)
                    putString("place", city[1])
                    putLong("located_at", System.currentTimeMillis())
                }
            }
        }
        val json = JSONObject(get("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min,weather_code&forecast_days=4&timezone=auto"))
        val current = json.getJSONObject("current")
        val daily = json.optJSONObject("daily")
        fun today(key: String) = daily?.optJSONArray(key)?.optDouble(0)?.takeIf { !it.isNaN() }?.roundToInt()
        fun dayMin(i: Int) = daily?.optJSONArray("temperature_2m_min")?.optDouble(i)?.takeIf { !it.isNaN() }?.roundToInt()
        fun dayKind(i: Int) = daily?.optJSONArray("weather_code")?.optInt(i, -1)?.takeIf { it >= 0 }?.let { kindOf(it) }
        return WeatherNow(
            celsius = current.getDouble("temperature_2m").roundToInt(),
            kind = kindOf(current.getInt("weather_code")),
            day = current.optInt("is_day", 1) == 1,
            high = today("temperature_2m_max"),
            low = today("temperature_2m_min"),
            severe = severeText(dayMin(0), listOf(dayKind(1), dayKind(2), dayKind(3)), listOf(dayMin(1), dayMin(2), dayMin(3))),
        )
    }

    /**
     * A place chosen before 1.29 came from Open-Meteo's geocoder, which took 安庆 for a village in
     * Jiangsu. If a listed city of that name is not where the chosen point is, the city is meant.
     * Checked once.
     */
    private fun correctChosenPlace(lat: Float, lon: Float): Cities.City? {
        val name = prefs.getString("place", null)
        val city = name?.let { runCatching { Cities.find(app, it) }.getOrNull() }
            ?.takeIf { Cities.near(app, it.name, lat.toDouble(), lon.toDouble(), km = 30.0) == null }
        prefs.edit {
            putBoolean("checked_against_cities", true)
            if (city != null) {
                Log.i(TAG, "chosen place $name moved to the city of that name")
                putFloat("lat", city.lat.toFloat()); putFloat("lon", city.lon.toFloat())
            }
        }
        return city
    }

    /**
     * Names the place (from the phone page) and fetches its weather at once. Chinese names are
     * looked up in Aerial's own list first, others (pinyin, places abroad) in Open-Meteo's geocoder.
     * False when there is no such place.
     */
    suspend fun choose(name: String): Boolean {
        val hit = withContext(Dispatchers.IO) {
            runCatching { Cities.find(app, name) }.onFailure { Log.w(TAG, "could not read the city list", it) }.getOrNull()
                ?.let { Triple(it.lat, it.lon, it.name) }
                ?: runCatching {
                    val q = URLEncoder.encode(name.trim(), "UTF-8")
                    val results = JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=10&language=zh&format=json"))
                        .optJSONArray("results")
                    val all = (0 until (results?.length() ?: 0)).map { results!!.getJSONObject(it) }
                    // The most populous of the name, a seat before a village where neither says: GeoNames
                    // codes some cities as plain places, so "jinzhou" took 晋州, a county seat in Hebei
                    // (PPLA3, no population), over 锦州 in Liaoning (PPL, 1.5 million; issue #1367).
                    fun seat(it: JSONObject) = it.optString("feature_code").let { c -> c.startsWith("PPLA") || c == "PPLC" }
                    val best = all.maxWithOrNull(compareBy<JSONObject>({ it.optLong("population") }, { seat(it) }, { -all.indexOf(it) }))
                    best?.let { Triple(it.getDouble("latitude"), it.getDouble("longitude"), it.optString("name").ifBlank { name.trim() }) }
                }.onFailure { Log.w(TAG, "could not look up $name", it) }.getOrNull()
        } ?: return false
        prefs.edit {
            putFloat("lat", hit.first.toFloat())
            putFloat("lon", hit.second.toFloat())
            putString("place", hit.third)
            putBoolean("chosen", true)
            putBoolean("checked_against_cities", true)
        }
        if (::launcherPrefs.isInitialized && WeatherClient.isKnownCity(app, hit.third)) {
            launcherPrefs.edit { putString("weather_city", hit.third) }
        }
        chosen = true
        place = prefs.getString("place", null)
        refresh()
        return true
    }

    /** Back to guessing the place from the network. */
    fun useNetworkLocation() {
        prefs.edit { putBoolean("chosen", false); remove("lat"); remove("lon"); remove("located_at") }
        if (::launcherPrefs.isInitialized) launcherPrefs.edit { remove("weather_city") }
        chosen = false
        refresh()
    }

    /** WMO weather interpretation codes, as Open-Meteo reports them. */
    private fun kindOf(code: Int): WeatherKind = when (code) {
        0 -> WeatherKind.Clear
        1, 2 -> WeatherKind.PartlyCloudy
        3 -> WeatherKind.Cloudy
        45, 48 -> WeatherKind.Fog
        51, 53, 55, 56, 57, 61, 80 -> WeatherKind.LightRain // drizzle, freezing drizzle, light rain/showers
        63, 66, 67, 81 -> WeatherKind.ModerateRain // moderate rain, freezing rain, moderate showers
        65 -> WeatherKind.HeavyRain
        82 -> WeatherKind.StormRain // violent rain showers
        71, 77, 85 -> WeatherKind.LightSnow // light snow, snow grains, light snow showers
        73 -> WeatherKind.ModerateSnow
        75, 86 -> WeatherKind.HeavySnow // heavy snow, heavy snow showers
        in 95..99 -> WeatherKind.Thunder
        else -> WeatherKind.Rain
    }

    /**
     * Android before 7.1 does not trust the Let's Encrypt root these hosts use; they answer the
     * same over plain HTTP (allowed for them in network_security_config.xml).
     */
    private fun get(url: String): String = try {
        read(url)
    } catch (e: SSLException) {
        read(url.replaceFirst("https://", "http://"))
    }

    private fun read(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8_000
        c.readTimeout = 10_000
        return try {
            c.inputStream.use { it.readBytes().decodeToString() }
        } finally {
            c.disconnect()
        }
    }
}
