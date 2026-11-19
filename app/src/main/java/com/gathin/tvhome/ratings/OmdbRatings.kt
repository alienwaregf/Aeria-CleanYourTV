package com.gathin.tvhome.ratings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.gathin.tvhome.tmdb.TmdbItem
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object OmdbRatings {
    private const val PREFS = "launcher_settings"
    private const val KEY_OMDB_KEY = "omdb_key"
    private const val OMDB_URL = "https://www.omdbapi.com/"

    private lateinit var launcherPrefs: SharedPreferences

    fun init(context: Context) {
        if (::launcherPrefs.isInitialized) return
        launcherPrefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun setKey(key: String) {
        if (!::launcherPrefs.isInitialized) return
        val k = key.trim()
        launcherPrefs.edit { if (k.isEmpty()) remove(KEY_OMDB_KEY) else putString(KEY_OMDB_KEY, k) }
    }

    fun keySet(): Boolean =
        ::launcherPrefs.isInitialized && launcherPrefs.getString(KEY_OMDB_KEY, "").orEmpty().isNotBlank()

    fun key(): String =
        if (::launcherPrefs.isInitialized) launcherPrefs.getString(KEY_OMDB_KEY, "").orEmpty().trim() else ""

    fun fetchImdbId(item: TmdbItem, tmdbKey: String): String? {
        return runCatching {
            val isV4 = tmdbKey.startsWith("eyJ")
            val base = if (item.mediaType == "tv") {
                "https://api.themoviedb.org/3/tv/${item.id}/external_ids"
            } else {
                "https://api.themoviedb.org/3/movie/${item.id}/external_ids"
            }
            val url = base + if (isV4) "" else "?api_key=${URLEncoder.encode(tmdbKey, "UTF-8")}"
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 5_000
            c.readTimeout = 5_000
            c.setRequestProperty("Accept", "application/json")
            if (isV4) c.setRequestProperty("Authorization", "Bearer $tmdbKey")
            try {
                if (c.responseCode !in 200..299) return@runCatching null
                val body = c.inputStream.use { it.readBytes().decodeToString() }
                JSONObject(body).optString("imdb_id", "")
            } finally {
                c.disconnect()
            }
        }.getOrNull()
    }

    data class OmdbResult(val imdbRating: String, val rtPercent: Int)

    sealed interface OmdbFetch {
        data class Found(val result: OmdbResult) : OmdbFetch
        data object NotFound : OmdbFetch
        data object BadId : OmdbFetch
        data object Failed : OmdbFetch
    }

    fun fetchRatings(imdbId: String): OmdbFetch {
        val key = key()
        if (key.isEmpty()) return OmdbFetch.Failed
        return runCatching {
            val url = "$OMDB_URL?apikey=${URLEncoder.encode(key, "UTF-8")}&i=${URLEncoder.encode(imdbId, "UTF-8")}"
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 5_000
            c.readTimeout = 5_000
            c.setRequestProperty("Accept", "application/json")
            try {
                if (c.responseCode !in 200..299) return@runCatching OmdbFetch.Failed
                val body = c.inputStream.use { it.readBytes().decodeToString() }
                val root = JSONObject(body)
                if (root.optString("Response", "") != "True") {
                    val error = root.optString("Error", "").trim()
                    return@runCatching when {
                        error.equals("Movie not found!", ignoreCase = true) ||
                            error.equals("Series not found!", ignoreCase = true) ||
                            error.equals("Series or season not found!", ignoreCase = true) ||
                            error.equals("Series or episode not found!", ignoreCase = true) -> OmdbFetch.NotFound
                        error.equals("Incorrect IMDb ID.", ignoreCase = true) -> OmdbFetch.BadId
                        else -> OmdbFetch.Failed
                    }
                }
                val imdbRating = root.optString("imdbRating", "").takeIf { it.isNotBlank() && it != "N/A" } ?: ""
                var rtPercent = -1
                val ratings = root.optJSONArray("Ratings")
                if (ratings != null) {
                    for (i in 0 until ratings.length()) {
                        val obj = ratings.optJSONObject(i) ?: continue
                        if (obj.optString("Source", "") == "Rotten Tomatoes") {
                            val value = obj.optString("Value", "")
                            if (value.endsWith("%")) {
                                rtPercent = value.dropLast(1).toIntOrNull() ?: -1
                            }
                            break
                        }
                    }
                }
                OmdbFetch.Found(OmdbResult(imdbRating, rtPercent))
            } finally {
                c.disconnect()
            }
        }.getOrDefault(OmdbFetch.Failed)
    }
}
