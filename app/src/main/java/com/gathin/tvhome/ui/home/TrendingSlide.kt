package com.gathin.tvhome.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import com.gathin.tvhome.ratings.Ratings
import com.gathin.tvhome.tmdb.TmdbItem
import com.gathin.tvhome.ui.theme.Motion
import com.gathin.tvhome.wallpaper.Wallpapers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

internal class TrendingSlideState(item: TmdbItem, index: Int, val infoSlidePx: Float) {
    var frontBitmap by mutableStateOf<ImageBitmap?>(null)
        private set
    var backBitmap by mutableStateOf<ImageBitmap?>(null)
        private set
    val frontX = Animatable(0f)
    val frontAlpha = Animatable(1f)
    val backX = Animatable(0f)
    val backAlpha = Animatable(0f)
    var displayedItem by mutableStateOf(item)
        private set
    var displayedIndex by mutableStateOf(index)
        private set
    val infoX = Animatable(0f)
    val infoAlpha = Animatable(1f)
    val screenW = Wallpapers.screenW.toFloat()

    fun sync(item: TmdbItem, index: Int) {
        if (displayedIndex == index && item.id == displayedItem.id && item.mediaType == displayedItem.mediaType) {
            displayedItem = item
        }
    }

    suspend fun advance(items: List<TmdbItem>, index: Int, dir: Int) {
        val target = items.getOrNull(index.coerceIn(items.indices)) ?: return
        val isIndexChange = index != displayedIndex
        val d = if (isIndexChange) dir else 0
        val bmp = withContext(Dispatchers.IO) {
            HeroPosters.load(target.backdropUrl, Wallpapers.screenW, Wallpapers.screenH)
        }
        if (bmp == null) {
            if (d != 0 && frontBitmap != null) {
                infoX.snapTo(infoSlidePx * d)
                infoAlpha.snapTo(0f)
                displayedItem = target
                displayedIndex = index
                coroutineScope {
                    launch { infoX.animateTo(0f, tween(520, easing = Motion.Ease)) }
                    launch { infoAlpha.animateTo(1f, tween(520, easing = Motion.Ease)) }
                }
            } else {
                infoX.snapTo(0f)
                infoAlpha.snapTo(1f)
                displayedItem = target
                displayedIndex = index
            }
        } else if (d == 0 || frontBitmap == null) {
            frontX.snapTo(0f)
            frontAlpha.snapTo(1f)
            backX.snapTo(0f)
            backAlpha.snapTo(0f)
            infoX.snapTo(0f)
            infoAlpha.snapTo(1f)
            frontBitmap = bmp.asImageBitmap()
            backBitmap = null
            displayedItem = target
            displayedIndex = index
        } else {
            backX.snapTo(screenW * 0.12f * d)
            backAlpha.snapTo(0f)
            frontX.snapTo(0f)
            frontAlpha.snapTo(1f)
            infoX.snapTo(infoSlidePx * d)
            infoAlpha.snapTo(0f)
            backBitmap = bmp.asImageBitmap()
            displayedItem = target
            displayedIndex = index
            coroutineScope {
                launch { backX.animateTo(0f, tween(650, easing = Motion.Ease)) }
                launch { backAlpha.animateTo(1f, tween(650, easing = Motion.Ease)) }
                launch { frontX.animateTo(-screenW * 0.06f * d, tween(650, easing = Motion.Ease)) }
                launch { frontAlpha.animateTo(0f, tween(650, easing = Motion.Ease)) }
                launch { infoX.animateTo(0f, tween(520, easing = Motion.Ease)) }
                launch { infoAlpha.animateTo(1f, tween(520, easing = Motion.Ease)) }
            }
            frontBitmap = backBitmap
            backBitmap = null
            frontX.snapTo(0f)
            frontAlpha.snapTo(1f)
            backX.snapTo(0f)
            backAlpha.snapTo(0f)
        }
        val next = items.getOrNull((index + 1) % items.size)
        if (next != null && next.backdropUrl != target.backdropUrl) {
            withContext(Dispatchers.IO) {
                HeroPosters.load(next.backdropUrl, Wallpapers.screenW, Wallpapers.screenH)
            }
        }
        val nextProviders = next?.providerUrls.orEmpty()
        if (nextProviders.isNotEmpty()) {
            withContext(Dispatchers.IO) { nextProviders.forEach { ProviderLogos.load(it) } }
        }
        Ratings.fetchFor(target, allowOmdb = false)
        next?.let { Ratings.fetchFor(it, allowOmdb = false) }
    }
}

@Composable
internal fun rememberTrendingSlideState(item: TmdbItem, index: Int, infoSlidePx: Float): TrendingSlideState =
    remember { TrendingSlideState(item, index, infoSlidePx) }

@Composable
internal fun TrendingSlideImages(state: TrendingSlideState) {
    state.backBitmap?.let { bmp ->
        Image(
            bitmap = bmp,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                translationX = state.backX.value
                alpha = state.backAlpha.value
            },
        )
    }
    state.frontBitmap?.let { bmp ->
        Image(
            bitmap = bmp,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                translationX = state.frontX.value
                alpha = state.frontAlpha.value
            },
        )
    }
}
