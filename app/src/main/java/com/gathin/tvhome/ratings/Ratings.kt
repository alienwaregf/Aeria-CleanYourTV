package com.gathin.tvhome.ratings

import android.content.Context
import com.gathin.tvhome.R
import com.gathin.tvhome.tmdb.TmdbItem
import com.gathin.tvhome.tmdb.TmdbTrending
import com.gathin.tvhome.wallpaper.ScreensaverMode
import com.gathin.tvhome.wallpaper.Wallpapers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

object Ratings {
    private const val RETRY_MS = 3 * 60 * 1000L
    private const val DAY_MS = 24 * 60 * 60 * 1000L
    private const val OMDB_TTL_MS = 30 * 60 * 1000L
    private const val TRAKT_TTL_MS = 10 * 60 * 1000L

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

    fun rtCriticIcon(state: String, percent: Int): Int {
        return when (state.lowercase()) {
            "certified_fresh" -> R.drawable.ic_rt_certified_fresh
            "fresh" -> R.drawable.ic_rt_fresh
            "rotten" -> R.drawable.ic_rt_rotten
            else -> if (percent >= 60) R.drawable.ic_rt_fresh else R.drawable.ic_rt_rotten
        }
    }

    fun rtAudienceIcon(state: String, percent: Int): Int {
        return when (state.lowercase()) {
            "verified_hot" -> R.drawable.ic_rt_verified_hot
            "upright" -> R.drawable.ic_rt_popcorn
            "spilled" -> R.drawable.ic_rt_popcorn_spilled
            else -> if (percent >= 60) R.drawable.ic_rt_popcorn else R.drawable.ic_rt_popcorn_spilled
        }
    }

    suspend fun fetchFor(item: TmdbItem, allowOmdb: Boolean = false) {
        if (!Wallpapers.isTrending() && Wallpapers.screensaver != ScreensaverMode.Trending) return
        val now = System.currentTimeMillis()
        val omdbDue = allowOmdb && now - item.omdbFetchedAt >= OMDB_TTL_MS && OmdbRatings.keySet()
        val traktDue = now - item.traktFetchedAt >= TRAKT_TTL_MS && TraktRatings.clientIdSet()
        if (!omdbDue && !traktDue) return
        val idKey = item.mediaType + ":" + item.id
        synchronized(inFlight) { if (!inFlight.add(idKey)) return }
        try {
            val updated = withContext(Dispatchers.IO) { fetchNow(item, allowOmdb) }
            if (updated != item) TmdbTrending.updateItem(updated)
        } finally {
            synchronized(inFlight) { inFlight.remove(idKey) }
        }
    }

    suspend fun refreshAll(items: List<TmdbItem>, allowOmdb: Boolean = false) {
        items.forEachIndexed { index, item ->
            if (index > 0) delay(1000)
            fetchFor(item, allowOmdb)
        }
    }

    private fun fetchNow(item: TmdbItem, allowOmdb: Boolean): TmdbItem {
        var cur = item
        val now = System.currentTimeMillis()
        val omdbDue = allowOmdb && now - cur.omdbFetchedAt >= OMDB_TTL_MS && OmdbRatings.keySet() && !recentlyFailed(cur, "omdb")
        val traktDue = now - cur.traktFetchedAt >= TRAKT_TTL_MS && TraktRatings.clientIdSet() && !recentlyFailed(cur, "trakt")
        if (!omdbDue && !traktDue) return cur
        var imdbId = cur.imdbId
        if (imdbId.isBlank()) {
            val tmdbKey = TmdbTrending.tmdbApiKey()
            if (tmdbKey.isBlank()) return cur
            val resolved = OmdbRatings.fetchImdbId(cur, tmdbKey)
            if (resolved == null) {
                if (omdbDue) markFailed(cur, "omdb")
                if (traktDue) markFailed(cur, "trakt")
                return cur
            }
            if (resolved.isBlank()) return cur.copy(
                omdbFetchedAt = if (omdbDue) now else cur.omdbFetchedAt,
                traktFetchedAt = if (traktDue) now else cur.traktFetchedAt,
            )
            imdbId = resolved
            cur = cur.copy(imdbId = resolved)
        }
        if (omdbDue) cur = fetchOmdb(cur, imdbId)
        if (now - cur.traktFetchedAt >= TRAKT_TTL_MS && TraktRatings.clientIdSet() && !recentlyFailed(cur, "trakt")) {
            cur = fetchTrakt(cur, imdbId)
        }
        return cur
    }

    private fun fetchOmdb(item: TmdbItem, imdbId: String): TmdbItem {
        if (recentlyFailed(item, "omdb")) return item
        if (omdbLimitReachedToday()) return item
        if (imdbId.isBlank()) return item.copy(omdbFetchedAt = System.currentTimeMillis(), traktFetchedAt = System.currentTimeMillis())
        return when (val outcome = OmdbRatings.fetchRatings(imdbId)) {
            is OmdbRatings.OmdbFetch.Found -> item.copy(imdbId = imdbId, imdbRating = outcome.result.imdbRating, omdbFetchedAt = System.currentTimeMillis())
            OmdbRatings.OmdbFetch.NotFound -> item.copy(imdbId = imdbId, omdbFetchedAt = System.currentTimeMillis())
            OmdbRatings.OmdbFetch.BadId -> item.copy(imdbId = imdbId, omdbFetchedAt = System.currentTimeMillis(), traktFetchedAt = System.currentTimeMillis())
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
        if (imdbId.isBlank()) return item.copy(traktFetchedAt = System.currentTimeMillis())
        return when (val outcome = TraktRatings.fetchRatings(item, imdbId)) {
            is TraktRatings.TraktFetch.Found -> {
                val result = outcome.result
                item.copy(
                    imdbId = imdbId,
                    traktRating = result.rating,
                    traktVotes = result.votes,
                    rtPercent = result.rtPercent,
                    rtState = result.rtState,
                    rtAudiencePercent = result.rtAudiencePercent,
                    rtAudienceState = result.rtAudienceState,
                    traktFetchedAt = System.currentTimeMillis(),
                )
            }
            TraktRatings.TraktFetch.NoRating -> item.copy(imdbId = imdbId, traktFetchedAt = System.currentTimeMillis())
            TraktRatings.TraktFetch.Failed -> {
                markFailed(item, "trakt")
                item
            }
        }
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
