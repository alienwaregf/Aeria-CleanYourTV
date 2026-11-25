package com.gathin.tvhome.ratings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.gathin.tvhome.R
import com.gathin.tvhome.tmdb.TmdbItem
import com.gathin.tvhome.tmdb.TmdbTrending
import com.gathin.tvhome.wallpaper.ScreensaverMode
import com.gathin.tvhome.wallpaper.Wallpapers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

object Ratings {
    private const val TAG = "Ratings"
    private const val RETRY_MS = 3 * 60 * 1000L
    private const val DAY_MS = 24 * 60 * 60 * 1000L
    private const val OMDB_TTL_MS = 30 * 60 * 1000L
    private const val TRAKT_TTL_MS = 10 * 60 * 1000L

    private val inFlight = HashSet<String>()
    private val failedAt = HashMap<String, Long>()
    private val refreshMutex = Mutex()

    private var diagOmdbDue = 0
    private var diagOmdbFound = 0
    private var diagOmdbFoundBlank = 0
    private var diagOmdbNotFound = 0
    private var diagOmdbBadId = 0
    private var diagOmdbLimit = 0
    private var diagOmdbFailed = 0
    private var diagOmdbNoId = 0
    private var diagTraktDue = 0
    private var diagTraktFound = 0
    private var diagTraktNoRt = 0
    private var diagTraktNoRating = 0
    private var diagTraktFailed = 0
    private var diagTraktNoId = 0

    @Volatile
    private var omdbLimitDay = -1L

    private const val KEY_OMDB_LIMIT_DAY = "omdb_limit_day"

    private lateinit var prefs: SharedPreferences

    fun omdbLimitReachedToday(): Boolean =
        omdbLimitDay == System.currentTimeMillis() / DAY_MS

    private fun markOmdbLimitReached() {
        omdbLimitDay = System.currentTimeMillis() / DAY_MS
        if (::prefs.isInitialized) prefs.edit().putLong(KEY_OMDB_LIMIT_DAY, omdbLimitDay).apply()
    }

    fun init(context: Context) {
        prefs = context.applicationContext.getSharedPreferences("launcher_settings", Context.MODE_PRIVATE)
        omdbLimitDay = prefs.getLong(KEY_OMDB_LIMIT_DAY, -1L)
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
        if (omdbDue) diagOmdbDue++
        if (traktDue) diagTraktDue++
        val idKey = item.mediaType + ":" + item.id
        synchronized(inFlight) { if (!inFlight.add(idKey)) return }
        try {
            val updated = withContext(Dispatchers.IO) { fetchNow(item, allowOmdb) }
            if (updated != item) TmdbTrending.updateItem(updated)
        } finally {
            synchronized(inFlight) { inFlight.remove(idKey) }
        }
    }

    suspend fun refreshAll(items: List<TmdbItem>, allowOmdb: Boolean = false) = refreshMutex.withLock {
        // Drop retry entries too old to matter: recentlyFailed already ignores them.
        val now = System.currentTimeMillis()
        synchronized(failedAt) {
            val entryIt = failedAt.entries.iterator()
            while (entryIt.hasNext()) {
                if (now - entryIt.next().value >= RETRY_MS) entryIt.remove()
            }
        }
        diagOmdbDue = 0
        diagOmdbFound = 0
        diagOmdbFoundBlank = 0
        diagOmdbNotFound = 0
        diagOmdbBadId = 0
        diagOmdbLimit = 0
        diagOmdbFailed = 0
        diagOmdbNoId = 0
        diagTraktDue = 0
        diagTraktFound = 0
        diagTraktNoRt = 0
        diagTraktNoRating = 0
        diagTraktFailed = 0
        diagTraktNoId = 0
        Log.i(TAG, "ratings refreshAll: ${items.size} items, omdbKeySet=${OmdbRatings.keySet()}, traktIdSet=${TraktRatings.clientIdSet()}, allowOmdb=$allowOmdb")
        items.forEachIndexed { index, item ->
            if (index > 0) delay(1000)
            fetchFor(item, allowOmdb)
        }
        Log.i(TAG, "ratings done: omdb due $diagOmdbDue found $diagOmdbFound foundBlank $diagOmdbFoundBlank notFound $diagOmdbNotFound badId $diagOmdbBadId limit $diagOmdbLimit failed $diagOmdbFailed noId $diagOmdbNoId | trakt due $diagTraktDue found $diagTraktFound noRt $diagTraktNoRt noRating $diagTraktNoRating failed $diagTraktFailed noId $diagTraktNoId")
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
                return cur
            }
            if (resolved.isBlank()) {
                if (omdbDue) diagOmdbNoId++
                if (traktDue) diagTraktNoId++
                return cur.copy(
                    omdbFetchedAt = if (omdbDue) now else cur.omdbFetchedAt,
                    traktFetchedAt = if (traktDue) now else cur.traktFetchedAt,
                )
            }
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
            is OmdbRatings.OmdbFetch.Found -> {
                if (outcome.result.imdbRating.isBlank()) diagOmdbFoundBlank++ else diagOmdbFound++
                item.copy(imdbId = imdbId, imdbRating = outcome.result.imdbRating.ifBlank { item.imdbRating }, omdbFetchedAt = System.currentTimeMillis())
            }
            OmdbRatings.OmdbFetch.NotFound -> {
                diagOmdbNotFound++
                item.copy(imdbId = imdbId, omdbFetchedAt = System.currentTimeMillis())
            }
            OmdbRatings.OmdbFetch.BadId -> {
                diagOmdbBadId++
                item.copy(imdbId = imdbId, omdbFetchedAt = System.currentTimeMillis())
            }
            OmdbRatings.OmdbFetch.LimitReached -> {
                diagOmdbLimit++
                markOmdbLimitReached()
                item
            }
            OmdbRatings.OmdbFetch.Failed -> {
                diagOmdbFailed++
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
                diagTraktFound++
                if (result.rtPercent < 0) diagTraktNoRt++
                item.copy(
                    imdbId = imdbId,
                    traktRating = result.rating,
                    traktVotes = result.votes,
                    rtPercent = if (result.rtPercent >= 0) result.rtPercent else item.rtPercent,
                    rtState = result.rtState.ifBlank { item.rtState },
                    rtAudiencePercent = if (result.rtAudiencePercent >= 0) result.rtAudiencePercent else item.rtAudiencePercent,
                    rtAudienceState = result.rtAudienceState.ifBlank { item.rtAudienceState },
                    traktFetchedAt = System.currentTimeMillis(),
                )
            }
            TraktRatings.TraktFetch.NoRating -> {
                diagTraktNoRating++
                item.copy(imdbId = imdbId, traktFetchedAt = System.currentTimeMillis())
            }
            TraktRatings.TraktFetch.Failed -> {
                diagTraktFailed++
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
