package com.gathin.tvhome.ratings

import android.content.Context
import com.gathin.tvhome.tmdb.TmdbItem
import com.gathin.tvhome.tmdb.TmdbTrending
import com.gathin.tvhome.wallpaper.ScreensaverMode
import com.gathin.tvhome.wallpaper.Wallpapers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

object Ratings {
    private const val RETRY_MS = 3 * 60 * 1000L
    private const val DAY_MS = 24 * 60 * 60 * 1000L

    private val inFlight = HashSet<String>()
    private val failedAt = HashMap<String, Long>()

    @Volatile
    private var omdbLimitDay = -1L

    fun omdbLimitReachedToday(): Boolean =
        omdbLimitDay == System.currentTimeMillis() / DAY_MS

    private fun markOmdbLimitReached() {
        omdbLimitDay = System.currentTimeMillis() / DAY_MS
    }

    fun init(context: Context) {
        OmdbRatings.init(context)
        TraktRatings.init(context)
    }

    fun setOmdbKey(key: String) = OmdbRatings.setKey(key)

    fun setTraktClientId(id: String) = TraktRatings.setClientId(id)

    suspend fun fetchFor(item: TmdbItem) {
        if (!Wallpapers.isTrending() && Wallpapers.screensaver != ScreensaverMode.Trending) return
        val omdbDue = !item.omdbFetched && OmdbRatings.keySet()
        val traktDue = !item.traktFetched && TraktRatings.clientIdSet()
        if (!omdbDue && !traktDue) return
        val idKey = item.mediaType + ":" + item.id
        synchronized(inFlight) { if (!inFlight.add(idKey)) return }
        try {
            val updated = withContext(Dispatchers.IO) { fetchNow(item) }
            if (updated != item) TmdbTrending.updateItem(updated)
        } finally {
            synchronized(inFlight) { inFlight.remove(idKey) }
        }
    }

    suspend fun refreshAll(items: List<TmdbItem>) {
        coroutineScope {
            items.forEach { item ->
                launch { fetchFor(item) }
            }
        }
    }

    private fun fetchNow(item: TmdbItem): TmdbItem {
        var cur = item
        val omdbDue = !cur.omdbFetched && OmdbRatings.keySet() && !recentlyFailed(cur, "omdb")
        val traktDue = !cur.traktFetched && TraktRatings.clientIdSet() && !recentlyFailed(cur, "trakt")
        if (!omdbDue && !traktDue) return cur
        var imdbId = cur.imdbId
        if (imdbId.isBlank()) {
            val tmdbKey = TmdbTrending.tmdbApiKey()
            if (tmdbKey.isBlank()) return cur
            val resolved = OmdbRatings.fetchImdbId(cur, tmdbKey)
            if (resolved == null) {
                if (omdbDue) markFailed(cur, "omdb") else markFailed(cur, "trakt")
                return cur
            }
            if (resolved.isBlank()) return cur.copy(
                omdbFetched = cur.omdbFetched || omdbDue,
                traktFetched = true,
            )
            imdbId = resolved
            cur = cur.copy(imdbId = resolved)
        }
        if (omdbDue) cur = fetchOmdb(cur, imdbId)
        if (!cur.traktFetched && TraktRatings.clientIdSet() && !recentlyFailed(cur, "trakt")) {
            cur = fetchTrakt(cur, imdbId)
        }
        return cur
    }

    private fun fetchOmdb(item: TmdbItem, imdbId: String): TmdbItem {
        if (recentlyFailed(item, "omdb")) return item
        if (omdbLimitReachedToday()) return item
        if (imdbId.isBlank()) return item.copy(omdbFetched = true, traktFetched = true)
        return when (val outcome = OmdbRatings.fetchRatings(imdbId)) {
            is OmdbRatings.OmdbFetch.Found -> item.copy(imdbId = imdbId, imdbRating = outcome.result.imdbRating, rtPercent = outcome.result.rtPercent, omdbFetched = true)
            OmdbRatings.OmdbFetch.NotFound -> item.copy(imdbId = imdbId, omdbFetched = true)
            OmdbRatings.OmdbFetch.BadId -> item.copy(imdbId = imdbId, omdbFetched = true, traktFetched = true)
            OmdbRatings.OmdbFetch.LimitReached -> {
                markOmdbLimitReached()
                item
            }
            OmdbRatings.OmdbFetch.Failed -> {
                markFailed(item, "omdb")
                item
            }
        }
    }

    private fun fetchTrakt(item: TmdbItem, imdbId: String): TmdbItem {
        if (recentlyFailed(item, "trakt")) return item
        if (imdbId.isBlank()) return item.copy(traktFetched = true)
        val result = TraktRatings.fetchRatings(item, imdbId) ?: run {
            markFailed(item, "trakt")
            return item
        }
        return item.copy(imdbId = imdbId, traktRating = result.rating, traktVotes = result.votes, traktFetched = true)
    }

    private fun recentlyFailed(item: TmdbItem, source: String): Boolean {
        val key = item.mediaType + ":" + item.id + ":" + source
        synchronized(failedAt) {
            val at = failedAt[key] ?: return false
            return System.currentTimeMillis() - at < RETRY_MS
        }
    }

    private fun markFailed(item: TmdbItem, source: String) {
        val key = item.mediaType + ":" + item.id + ":" + source
        synchronized(failedAt) { failedAt[key] = System.currentTimeMillis() }
    }
}
