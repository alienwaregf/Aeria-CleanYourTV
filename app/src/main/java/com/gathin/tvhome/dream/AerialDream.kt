package com.gathin.tvhome.dream

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.service.dreams.DreamService
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.gathin.tvhome.MainActivity
import com.gathin.tvhome.system.DiagLog
import com.gathin.tvhome.system.MissingClipboard
import com.gathin.tvhome.ui.theme.canvasDensity
import com.gathin.tvhome.weather.Weather
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.gathin.tvhome.ui.home.LiveFilm
import com.gathin.tvhome.ui.home.Screensaver
import com.gathin.tvhome.ui.home.ScreensaverSkips
import com.gathin.tvhome.ui.home.rememberNow
import com.gathin.tvhome.wallpaper.Wallpapers

class AerialDream : DreamService(), LifecycleOwner, SavedStateRegistryOwner {
    private val registry = LifecycleRegistry(this)
    private val savedState = SavedStateRegistryController.create(this)
    private val skips = ScreensaverSkips()

    override val lifecycle: Lifecycle get() = registry
    override val savedStateRegistry: SavedStateRegistry get() = savedState.savedStateRegistry

    override fun getSystemService(name: String): Any? =
        super.getSystemService(name) ?: if (name == CLIPBOARD_SERVICE) MissingClipboard.standIn else null

    override fun onCreate() {
        super.onCreate()
        savedState.performRestore(null)
        registry.currentState = Lifecycle.State.CREATED
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        isInteractive = true
        isFullscreen = true
        LiveFilm.onHome?.letDecoderGo()
        Wallpapers.init(this)
        Weather.init(this)
        val canvas = canvasDensity(resources.displayMetrics)
        setContentView(
            ComposeView(this).apply {
                setContent {
                    CompositionLocalProvider(LocalDensity provides Density(canvas, LocalDensity.current.fontScale)) {
                        val now by rememberNow()
                        Screensaver(
                            visible = true, apps = emptyList(), now = now, skips = skips,
                            carry = null,
                        )
                    }
                }
            },
        )
        window.decorView.setViewTreeLifecycleOwner(this)
        window.decorView.setViewTreeSavedStateRegistryOwner(this)
    }

    private val keysDown = HashSet<Int>()

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (skips.onKey(event)) return true
        if (event.action == KeyEvent.ACTION_DOWN) keysDown += event.keyCode
        else if (!event.isCanceled && !keysDown.remove(event.keyCode) && event.keyCode != MainActivity.dreamWokeKey) {
            DiagLog.i("dream: ${KeyEvent.keyCodeToString(event.keyCode)} lifted with no press; ending it")
            finish()
            return true
        }
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            MainActivity.dreamWokeAt = event.eventTime
            MainActivity.dreamWokeKey = event.keyCode
            DiagLog.i("dream woken by ${KeyEvent.keyCodeToString(event.keyCode)} at ${event.eventTime}")
            finish()
        }
        if (event.action == KeyEvent.ACTION_UP && !event.isCanceled && event.keyCode == MainActivity.dreamWokeKey) MainActivity.dreamWokeKey = -1
        return true
    }

    private val screenOff = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            DiagLog.i("screen off; ending the dream")
            finish()
        }
    }

    override fun onDreamingStarted() {
        super.onDreamingStarted()
        running = this
        registry.currentState = Lifecycle.State.RESUMED
        runCatching { ContextCompat.registerReceiver(this, screenOff, IntentFilter(Intent.ACTION_SCREEN_OFF), ContextCompat.RECEIVER_NOT_EXPORTED) }
    }

    override fun onDreamingStopped() {
        if (running === this) running = null
        runCatching { unregisterReceiver(screenOff) }
        if (registry.currentState.isAtLeast(Lifecycle.State.CREATED)) registry.currentState = Lifecycle.State.CREATED
        super.onDreamingStopped()
    }

    override fun onDetachedFromWindow() {
        if (running === this) running = null
        registry.currentState = Lifecycle.State.DESTROYED
        super.onDetachedFromWindow()
    }

    companion object {
        private var running: AerialDream? = null

        fun endForHome(): Boolean {
            val dream = running ?: return false
            DiagLog.i("dream ended by Home")
            dream.finish()
            return true
        }
    }
}
