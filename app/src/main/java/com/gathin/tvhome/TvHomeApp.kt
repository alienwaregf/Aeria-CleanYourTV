package com.gathin.tvhome

import android.app.Application
import com.gathin.tvhome.apps.BilibiliSubs
import com.gathin.tvhome.apps.HuyaSubs
import com.gathin.tvhome.apps.YoutubeSubs
import com.gathin.tvhome.ratings.Ratings
import com.gathin.tvhome.tmdb.TmdbTrending
import com.gathin.tvhome.wallpaper.Wallpapers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class TvHomeApp : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        Wallpapers.init(this)
        TmdbTrending.init(this)
        Ratings.init(this)
        YoutubeSubs.init(this)
        BilibiliSubs.init(this)
        HuyaSubs.init(this)
        startClockTick()
        startBiliHuyaTick()
    }

    private fun startClockTick() {
        appScope.launch {
            while (true) {
                val now = System.currentTimeMillis()
                val next = ((now / CLOCK_TICK_MS) + 1) * CLOCK_TICK_MS
                delay(next - now)
                if (TmdbTrending.trendingGateOpen()) {
                    TmdbTrending.refreshClockTick()
                }
                if (Wallpapers.selectedId == Wallpapers.AUTO) {
                    launch(Dispatchers.IO) { YoutubeSubs.refreshAllIfStale() }
                }
            }
        }
    }

    private fun startBiliHuyaTick() {
        appScope.launch {
            while (true) {
                val now = System.currentTimeMillis()
                val next = ((now / BILI_HUYA_TICK_MS) + 1) * BILI_HUYA_TICK_MS
                delay(next - now)
                if (Wallpapers.selectedId == Wallpapers.AUTO) {
                    launch(Dispatchers.IO) { BilibiliSubs.refreshAll() }
                    launch(Dispatchers.IO) { HuyaSubs.refreshAll() }
                }
            }
        }
    }

    companion object {
        private const val CLOCK_TICK_MS = 30 * 60 * 1000L
        private const val BILI_HUYA_TICK_MS = 10 * 60 * 1000L
    }
}
