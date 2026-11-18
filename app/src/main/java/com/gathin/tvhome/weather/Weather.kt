package com.gathin.tvhome.weather

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import com.gathin.tvhome.R
import com.gathin.tvhome.CityLookup
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
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import javax.net.ssl.SSLException
import kotlin.math.roundToInt

enum class WeatherKind {
    Clear, PartlyCloudy, Cloudy, Fog, Rain, Snow, Thunder,
    LightRain, ModerateRain, HeavyRain, StormRain,
    LightSnow, ModerateSnow, HeavySnow, StormSnow,
    Haze, Dust, Sand, Wind, Hail, Sleet,
}

enum class CloudTrend { STEADY, CLEARING, CLOUDING }

private fun cloudBand(cover: Double): Int = when {
    cover <= 0.2 -> 0
    cover <= 0.5 -> 1
    cover <= 0.8 -> 2
    else -> 3
}

internal fun cloudTrend(dayCover: Double, nightCover: Double): CloudTrend {
    if (dayCover.isNaN() || nightCover.isNaN()) return CloudTrend.STEADY
    val day = cloudBand(dayCover)
    val night = cloudBand(nightCover)
    return when {
        night > day -> CloudTrend.CLOUDING
        night < day -> CloudTrend.CLEARING
        else -> CloudTrend.STEADY
    }
}

internal fun kindNameRes(kind: WeatherKind): Int = when (kind) {
    WeatherKind.Clear -> R.string.weather_clear
    WeatherKind.PartlyCloudy -> R.string.weather_partly_cloudy
    WeatherKind.Cloudy -> R.string.weather_cloudy
    WeatherKind.Fog -> R.string.weather_fog
    WeatherKind.Rain -> R.string.weather_rain
    WeatherKind.Snow -> R.string.weather_snow
    WeatherKind.Thunder -> R.string.weather_thunder
    WeatherKind.LightRain -> R.string.weather_light_rain
    WeatherKind.ModerateRain -> R.string.weather_moderate_rain
    WeatherKind.HeavyRain -> R.string.weather_heavy_rain
    WeatherKind.StormRain -> R.string.weather_storm_rain
    WeatherKind.LightSnow -> R.string.weather_light_snow
    WeatherKind.ModerateSnow -> R.string.weather_moderate_snow
    WeatherKind.HeavySnow -> R.string.weather_heavy_snow
    WeatherKind.StormSnow -> R.string.weather_storm_snow
    WeatherKind.Haze -> R.string.weather_haze
    WeatherKind.Dust -> R.string.weather_dust
    WeatherKind.Sand -> R.string.weather_sand
    WeatherKind.Wind -> R.string.weather_wind
    WeatherKind.Hail -> R.string.weather_hail
    WeatherKind.Sleet -> R.string.weather_sleet
}

data class WeatherNow(val celsius: Int, val kind: WeatherKind, val day: Boolean, val high: Int? = null, val low: Int? = null, val aqi: String? = null, val forecast: List<DayForecast> = emptyList(), val trend: CloudTrend = CloudTrend.STEADY, val conditionText: String? = null)

data class DayForecast(val day: Int, val dayText: String?, val high: Int?, val low: Int?)

object Weather {
    private const val TAG = "AerialWeather"
    private const val PREFS = "weather"
    private const val REFRESH_MS = 60 * 60 * 1000L
    private const val RETRY_MS = 3 * 60 * 1000L
    private const val RELOCATE_MS = 24 * 60 * 60 * 1000L
    private const val STALE_MS = 3 * 60 * 60 * 1000L
    private const val NONE = Int.MIN_VALUE

    var now by mutableStateOf<WeatherNow?>(null); private set

    var place by mutableStateOf<String?>(null); private set

    var alertTitle by mutableStateOf<String?>(null); private set

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
                    aqi = prefs.getString("aqi", null).takeIf { isKeyedSource() },
                    forecast = if (isKeyedSource()) (1..3).mapNotNull { i ->
                        val dayText = prefs.getString("forecast_${i}_text", null)
                        val high = prefs.getInt("forecast_${i}_high", NONE).takeIf { it != NONE }
                        val low = prefs.getInt("forecast_${i}_low", NONE).takeIf { it != NONE }
                        if (dayText == null && high == null && low == null) null
                        else DayForecast(day = i, dayText = dayText, high = high, low = low)
                    } else emptyList(),
                    trend = if (isKeyedSource()) runCatching { CloudTrend.valueOf(prefs.getString("trend", null) ?: "") }.getOrNull() ?: CloudTrend.STEADY else CloudTrend.STEADY,
                    conditionText = prefs.getString("condition_text", null).takeIf { isKeyedSource() },
                )
                if (isKeyedSource()) alertTitle = prefs.getString("alert_title", null)
            }
        }
    }

    private var failed = false

    private const val KEYED_INTERVAL_MS = 30 * 60 * 1000L
    private const val LAUNCHER_PREFS = "launcher_settings"
    private lateinit var launcherPrefs: SharedPreferences
    var weatherSource by mutableStateOf("openmeteo"); private set
    private fun isKeyedSource() = weatherSource == "qweather"
    private val keyedHandler = Handler(Looper.getMainLooper())
    private var keyedTicking = false
    private val keyedTick = object : Runnable {
        override fun run() {
            if (!keyedTicking) return
            if (isKeyedSource()) refreshKeyedIfDue()
            keyedHandler.postDelayed(this, KEYED_INTERVAL_MS)
        }
    }

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

    fun refreshKeyedOnResume() {
        if (!started || !isKeyedSource()) return
        if (System.currentTimeMillis() - lastAttempt < 60_000L) return
        refresh()
    }

    fun setSource(source: String) {
        val src = when (source) {
            "qweather" -> "qweather"
            else -> "openmeteo"
        }
        launcherPrefs.edit { putString("weather_source", src) }
        weatherSource = src
        lastAttempt = 0L
        refresh()
    }

    fun setQWeatherKey(key: String) {
        val k = key.trim()
        launcherPrefs.edit { if (k.isEmpty()) remove("weather_qweather_key") else putString("weather_qweather_key", k) }
        lastAttempt = 0L
        refresh()
    }

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

    fun qweatherHost(): String = if (::launcherPrefs.isInitialized) launcherPrefs.getString("weather_qweather_host", "").orEmpty() else ""

    fun qweatherHostSet(): Boolean = qweatherHost().isNotBlank()

    fun chosenCity(): String = if (::launcherPrefs.isInitialized) launcherPrefs.getString("weather_city", "").orEmpty() else ""

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

    private fun qweatherRefresh() {
        lastAttempt = System.currentTimeMillis()
        QWeatherClient.load(app) { result ->
            if (result == null) {
                failed = true
                return@load
            }
            applyQWeatherResult(result)
        }
    }

    private fun forecastDays(result: QWeatherClient.Result): List<DayForecast> {
        return (1..3).mapNotNull { i ->
            val dayText = result.dailyTextDay.getOrNull(i)?.takeIf { it.isNotBlank() }
                ?: result.dailySkyconDay.getOrNull(i)?.takeIf { it.isNotEmpty() }?.let { app.getString(kindNameRes(kindFromSkycon(it))) }
            val high = result.dailyMaxC.getOrNull(i)?.takeIf { it != Int.MIN_VALUE }
            val low = result.dailyMinC.getOrNull(i)?.takeIf { it != Int.MIN_VALUE }
            if (dayText == null && high == null && low == null) null
            else DayForecast(day = i, dayText = dayText, high = high, low = low)
        }
    }

    private fun applyQWeatherResult(result: QWeatherClient.Result) {
        failed = false
        val kind = kindFromSkycon(result.skycon)
        val forecast = forecastDays(result)
        val trend = cloudTrend(result.dayCloudCover, result.nightCloudCover)
        now = WeatherNow(
            celsius = result.temperatureC.roundToInt(),
            kind = kind,
            day = result.day,
            high = result.highC,
            low = result.lowC,
            forecast = forecast,
            aqi = result.aqi,
            trend = trend,
            conditionText = result.nowText.takeIf { it.isNotEmpty() },
        )
        alertTitle = result.alertTitle
        place = result.city
        chosen = chosenCity().isNotEmpty()
        prefs.edit {
            putLong("fetched_at", System.currentTimeMillis())
            putInt("celsius", result.temperatureC.roundToInt())
            putString("kind", kind.name)
            putBoolean("day", result.day)
            putInt("high", result.highC ?: NONE)
            putInt("low", result.lowC ?: NONE)
            (1..3).forEach { i ->
                val row = forecast.getOrNull(i - 1)
                if (row == null) {
                    remove("forecast_${i}_text")
                    remove("forecast_${i}_high")
                    remove("forecast_${i}_low")
                } else {
                    if (row.dayText.isNullOrEmpty()) remove("forecast_${i}_text") else putString("forecast_${i}_text", row.dayText)
                    putInt("forecast_${i}_high", row.high ?: NONE)
                    putInt("forecast_${i}_low", row.low ?: NONE)
                }
            }
            putString("trend", trend.name)
            putString("place", result.city)
            if (result.aqi.isNullOrEmpty()) remove("aqi") else putString("aqi", result.aqi)
            if (result.nowText.isEmpty()) remove("condition_text") else putString("condition_text", result.nowText)
            if (result.alertTitle.isNullOrEmpty()) remove("alert_title") else putString("alert_title", result.alertTitle)
        }
    }

    fun refreshIfStale(networkCameUp: Boolean = false) {
        if (!started) return
        val since = System.currentTimeMillis() - lastAttempt
        if (since >= REFRESH_MS || (failed && (networkCameUp || since >= RETRY_MS))) refresh()
    }

    private fun refresh() {
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
                Log.i(TAG, "weather read")
                now = reading
                prefs.edit {
                    putLong("fetched_at", System.currentTimeMillis())
                    putInt("celsius", reading.celsius)
                    putString("kind", reading.kind.name)
                    putBoolean("day", reading.day)
                    putInt("high", reading.high ?: NONE)
                    putInt("low", reading.low ?: NONE)
                }
            }
        }
    }

    private fun fetch(): WeatherNow {
        var lat = prefs.getFloat("lat", Float.NaN)
        var lon = prefs.getFloat("lon", Float.NaN)
        val guessedLongAgo = System.currentTimeMillis() - prefs.getLong("located_at", 0) > RELOCATE_MS
        if (prefs.getBoolean("chosen", false) && !lat.isNaN() && !prefs.getBoolean("checked_against_cities", false)) {
            correctChosenPlace(lat, lon)?.let { lat = it.lat.toFloat(); lon = it.lon.toFloat() }
        }
        if (!prefs.getBoolean("chosen", false) && (lat.isNaN() || guessedLongAgo)) {
            val guess = locate()
            if (guess != null) {
                lat = guess.lat
                lon = guess.lon
                val city = guess.city
                val zh = city?.takeIf { guess.cn && Locale.getDefault().language == "zh" }
                    ?.let {
                        (Cities.near(app, it, lat.toDouble(), lon.toDouble(), km = 80.0, ascii = true)
                            ?: Cities.mainNear(app, lat.toDouble(), lon.toDouble(), km = 20.0))?.name
                    }
                prefs.edit {
                    putFloat("lat", lat); putFloat("lon", lon)
                    putString("place", zh ?: city)
                    putLong("located_at", System.currentTimeMillis())
                }
            }
        }
        val json = JSONObject(get("https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min&forecast_days=1&timezone=auto"))
        val current = json.getJSONObject("current")
        val daily = json.optJSONObject("daily")
        fun today(key: String) = daily?.optJSONArray(key)?.optDouble(0)?.takeIf { !it.isNaN() }?.roundToInt()
        return WeatherNow(
            celsius = current.getDouble("temperature_2m").roundToInt(),
            kind = kindOf(current.getInt("weather_code")),
            day = current.optInt("is_day", 1) == 1,
            high = today("temperature_2m_max"),
            low = today("temperature_2m_min"),
        )
    }

    private class Guess(val lat: Float, val lon: Float, val city: String?, val cn: Boolean)

    private fun locate(): Guess? {
        val v4 = runCatching { get("https://ipv4.icanhazip.com").trim() }.getOrNull()
            ?.takeIf { Regex("""^\d{1,3}(\.\d{1,3}){3}$""").matches(it) }
        runCatching {
            val ip = JSONObject(get(if (v4 != null) "https://ipwho.is/$v4" else "https://ipwho.is/"))
            if (ip.optBoolean("success", true)) {
                return Guess(
                    ip.getDouble("latitude").toFloat(),
                    ip.getDouble("longitude").toFloat(),
                    ip.optString("city").ifBlank { null },
                    ip.optString("country_code").equals("CN", ignoreCase = true),
                )
            }
        }.onFailure { Log.w(TAG, "ipwho.is", it) }
        runCatching {
            val ip = JSONObject(get("https://ipinfo.io/json"))
            val loc = ip.getString("loc").split(',')
            return Guess(
                loc[0].toFloat(),
                loc[1].toFloat(),
                ip.optString("city").ifBlank { null },
                ip.optString("country") == "CN",
            )
        }.onFailure { Log.w(TAG, "ipinfo.io", it) }
        return null
    }

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

    suspend fun choose(name: String): Boolean {
        val hit = withContext(Dispatchers.IO) {
            runCatching { Cities.find(app, name) }.onFailure { Log.w(TAG, "could not read the city list", it) }.getOrNull()
                ?.let { Triple(it.lat, it.lon, it.name) }
                ?: runCatching {
                    val q = URLEncoder.encode(name.trim(), "UTF-8")
                    val results = JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?name=$q&count=10&language=zh&format=json"))
                        .optJSONArray("results")
                    val all = (0 until (results?.length() ?: 0)).map { results!!.getJSONObject(it) }
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
        if (::launcherPrefs.isInitialized && CityLookup.isKnownCity(app, hit.third)) {
            launcherPrefs.edit { putString("weather_city", hit.third) }
        }
        chosen = true
        place = prefs.getString("place", null)
        refresh()
        return true
    }

    fun useNetworkLocation() {
        prefs.edit { putBoolean("chosen", false); remove("lat"); remove("lon"); remove("located_at") }
        if (::launcherPrefs.isInitialized) launcherPrefs.edit { remove("weather_city") }
        chosen = false
        refresh()
    }

    private fun kindOf(code: Int): WeatherKind = when (code) {
        0 -> WeatherKind.Clear
        1, 2 -> WeatherKind.PartlyCloudy
        3 -> WeatherKind.Cloudy
        45, 48 -> WeatherKind.Fog
        in 71..77, 85, 86 -> WeatherKind.Snow
        in 95..99 -> WeatherKind.Thunder
        else -> WeatherKind.Rain
    }

    private fun get(url: String): String = try {
        read(url)
    } catch (e: IOException) {
        if (e !is SSLException && e !is SocketTimeoutException) throw e
        read(url.replaceFirst("https://", "http://"))
    }

    private fun read(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 8_000
        c.readTimeout = 10_000
        return try {
            val code = c.responseCode
            if (code !in 200..299) throw HttpStatus(code)
            c.inputStream.use { it.readBytes().decodeToString() }
        } finally {
            c.disconnect()
        }
    }

    private class HttpStatus(code: Int) : java.io.IOException("HTTP $code")
}
