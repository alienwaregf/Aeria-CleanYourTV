package com.gathin.tvhome.ratings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.gathin.tvhome.tmdb.TmdbItem
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

object TraktRatings {
    private const val PREFS = "launcher_settings"
    private const val KEY_TRAKT_CLIENT_ID = "trakt_client_id"

    private lateinit var launcherPrefs: SharedPreferences

    fun init(context: Context) {
        if (::launcherPrefs.isInitialized) return
        launcherPrefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    fun setClientId(id: String) {
        if (!::launcherPrefs.isInitialized) return
        val v = id.trim()
        launcherPrefs.edit { if (v.isEmpty()) remove(KEY_TRAKT_CLIENT_ID) else putString(KEY_TRAKT_CLIENT_ID, v) }
    }

    fun clientIdSet(): Boolean =
        ::launcherPrefs.isInitialized && launcherPrefs.getString(KEY_TRAKT_CLIENT_ID, "").orEmpty().isNotBlank()

    fun clientId(): String =
        if (::launcherPrefs.isInitialized) launcherPrefs.getString(KEY_TRAKT_CLIENT_ID, "").orEmpty().trim() else ""

    data class TraktResult(
        val rating: Double,
        val votes: Int,
        val rtPercent: Int = -1,
        val rtState: String = "",
        val rtAudiencePercent: Int = -1,
        val rtAudienceState: String = "",
    )

    fun fetchRatings(item: TmdbItem, imdbId: String): TraktResult? {
        val id = clientId()
        if (id.isEmpty() || imdbId.isBlank()) return null
        return runCatching {
            val kind = if (item.mediaType == "tv") "shows" else "movies"
            val url = "https://api.trakt.tv/$kind/${URLEncoder.encode(imdbId, "UTF-8")}/ratings?extended=all"
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = 5_000
            c.readTimeout = 5_000
            c.setRequestProperty("Accept", "application/json")
            c.setRequestProperty("trakt-api-version", "2")
            c.setRequestProperty("trakt-api-key", id)
            try {
                if (c.responseCode !in 200..299) return@runCatching null
                val body = c.inputStream.use { it.readBytes().decodeToString() }
                val root = JSONObject(body)
                val traktObj = root.optJSONObject("trakt")
                val rating = traktObj?.optDouble("rating", -1.0) ?: root.optDouble("rating", -1.0)
                if (rating < 0.0) return@runCatching null
                val votes = traktObj?.optInt("votes", 0) ?: root.optInt("votes", 0)
                val rt = root.optJSONObject("rotten_tomatoes")
                TraktResult(
                    rating = rating,
                    votes = votes,
                    rtPercent = rt?.optInt("rating", -1) ?: -1,
                    rtState = rt?.optString("state", "") ?: "",
                    rtAudiencePercent = rt?.optInt("user_rating", -1) ?: -1,
                    rtAudienceState = rt?.optString("user_state", "") ?: "",
                )
            } finally {
                c.disconnect()
            }
        }.getOrNull()
    }
}
