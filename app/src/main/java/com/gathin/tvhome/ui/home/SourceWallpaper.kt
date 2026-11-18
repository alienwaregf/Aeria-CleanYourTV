package com.gathin.tvhome.ui.home

import android.media.tv.TvContract
import android.media.tv.TvInputManager
import android.media.tv.TvView
import android.view.View
import android.view.ViewGroup
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.viewinterop.AndroidView
import com.gathin.tvhome.settings.AerialSettings
import com.gathin.tvhome.system.DiagLog
import com.gathin.tvhome.ui.theme.Motion
import kotlinx.coroutines.delay

@Composable
fun SourceWallpaper(
    inputId: String,
    show: Boolean,
    cover: Animatable<Float, *>? = null,
) {
    var available by remember(inputId) { mutableStateOf(false) }
    var generation by remember(inputId) { mutableIntStateOf(0) }
    var tries by remember(inputId) { mutableIntStateOf(0) }
    var weak by remember(inputId) { mutableStateOf(false) }
    val soundOn = AerialSettings.sourceSound

    LaunchedEffect(available, show) {
        val c = cover ?: return@LaunchedEffect
        if (show && available) c.animateTo(1f, tween(900, easing = Motion.EaseOut))
        else if (!show) c.snapTo(0f)
        else c.animateTo(0f, tween(400, easing = Motion.EaseOut))
    }
    LaunchedEffect(weak, show, generation) {
        if (!weak || !show || tries >= RETRY_DELAYS_MS.size) return@LaunchedEffect
        delay(RETRY_DELAYS_MS[tries])
        tries++
        weak = false
        generation++
    }
    if (!show) {
        LaunchedEffect(Unit) { available = false; tries = 0; weak = false }
        return
    }
    AndroidView(
        factory = { context ->
            TvView(context).apply {
                isFocusable = false
                isFocusableInTouchMode = false
                descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
                setStreamVolume(if (soundOn) 1.0f else 0.0f)
                setCallback(object : TvView.TvInputCallback() {
                    override fun onVideoAvailable(id: String) {
                        available = true
                        tries = 0
                    }

                    override fun onVideoUnavailable(id: String, reason: Int) {
                        DiagLog.i("input wallpaper: no picture from $id (reason $reason)")
                        available = false
                        weak = reason == TvInputManager.VIDEO_UNAVAILABLE_REASON_WEAK_SIGNAL
                    }

                    override fun onConnectionFailed(id: String) {
                        DiagLog.i("input wallpaper: could not connect to $id")
                        available = false
                    }

                    override fun onDisconnected(id: String) {
                        available = false
                    }
                })
            }
        },
        modifier = Modifier.fillMaxSize().focusProperties { canFocus = false },
        update = { view ->
            view.alpha = cover?.value ?: 1f
            view.setStreamVolume(if (soundOn) 1.0f else 0.0f)
            view.invalidateOutline()
            val key = "$inputId#$generation"
            if (view.tag != key) {
                view.tag = key
                view.reset()
                if (view.isAttachedToWindow) {
                    view.tune(inputId, TvContract.buildChannelUriForPassthroughInput(inputId))
                } else {
                    view.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                        override fun onViewAttachedToWindow(v: View) {
                            v.removeOnAttachStateChangeListener(this)
                            if (view.tag == key) view.tune(inputId, TvContract.buildChannelUriForPassthroughInput(inputId))
                        }

                        override fun onViewDetachedFromWindow(v: View) = Unit
                    })
                }
            }
        },
        onRelease = { view ->
            view.tag = null
            view.reset()
            SourcePreview.noteEnded()
        },
    )
}

private val RETRY_DELAYS_MS = longArrayOf(3_000L, 5_000L, 10_000L, 15_000L, 20_000L, 30_000L)
