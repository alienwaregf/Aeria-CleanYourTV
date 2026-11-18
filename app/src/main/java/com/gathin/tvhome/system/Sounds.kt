package com.gathin.tvhome.system

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.SoundPool
import android.os.Build
import android.os.SystemClock
import android.provider.Settings
import android.view.KeyEvent
import com.gathin.tvhome.R
import com.gathin.tvhome.settings.AerialSettings
import com.gathin.tvhome.settings.UiSounds

object Sounds {
    private var audio: AudioManager? = null
    private var app: Context? = null
    private var pool: SoundPool? = null
    private var move = 0
    private var select = 0
    private var back = 0
    private var limit = 0
    private var lastDpadAt = 0L
    private var lastMove = AudioManager.FX_FOCUS_NAVIGATION_DOWN
    private var tickedFor = -1L

    fun init(context: Context) {
        if (audio == null) audio = context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        if (app == null) app = context.applicationContext
        prepare()
    }

    fun prepare() {
        if (AerialSettings.uiSounds == UiSounds.Aerial) ownPool()
    }

    private fun ownPool(): SoundPool? {
        pool?.let { return it }
        val context = app ?: return null
        val attrs = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        return SoundPool.Builder().setMaxStreams(3).setAudioAttributes(attrs).build().also {
            move = it.load(context, R.raw.ui_move, 1)
            select = it.load(context, R.raw.ui_select, 1)
            back = it.load(context, R.raw.ui_back, 1)
            limit = it.load(context, R.raw.ui_limit, 1)
            pool = it
        }
    }

    fun onKey(event: KeyEvent) {
        if (event.action != KeyEvent.ACTION_DOWN) return
        val move = when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> AudioManager.FX_FOCUS_NAVIGATION_UP
            KeyEvent.KEYCODE_DPAD_DOWN -> AudioManager.FX_FOCUS_NAVIGATION_DOWN
            KeyEvent.KEYCODE_DPAD_LEFT -> AudioManager.FX_FOCUS_NAVIGATION_LEFT
            KeyEvent.KEYCODE_DPAD_RIGHT -> AudioManager.FX_FOCUS_NAVIGATION_RIGHT
            KeyEvent.KEYCODE_BACK -> {
                if (event.repeatCount == 0) play(if (Build.VERSION.SDK_INT >= 33) AudioManager.FX_BACK else AudioManager.FX_KEY_CLICK, Kind.Back)
                return
            }
            else -> return
        }
        lastDpadAt = event.eventTime
        lastMove = move
    }

    fun focusMoved() {
        val at = lastDpadAt
        if (at == tickedFor || SystemClock.uptimeMillis() - at > 250) return
        tickedFor = at
        play(lastMove, Kind.Move)
    }

    fun limit() {
        tickedFor = lastDpadAt
        play(AudioManager.FX_KEYPRESS_INVALID, Kind.Limit)
    }

    fun select() = play(AudioManager.FX_KEY_CLICK, Kind.Select)

    fun step() = play(lastMove, Kind.Move)

    private enum class Kind { Move, Select, Back, Limit }

    private fun playTvEffect(effect: Int) {
        val context = app ?: return
        val on = runCatching { Settings.System.getInt(context.contentResolver, Settings.System.SOUND_EFFECTS_ENABLED, 1) != 0 }
            .getOrDefault(true)
        if (on) audio?.playSoundEffect(effect, -1f)
    }

    private fun play(effect: Int, kind: Kind) {
        when (AerialSettings.uiSounds) {
            UiSounds.Off -> Unit
            UiSounds.Tv -> playTvEffect(effect)
            UiSounds.Aerial -> {
                val p = ownPool() ?: return
                val (id, volume) = when (kind) {
                    Kind.Move -> move to 0.7f
                    Kind.Select -> select to 1f
                    Kind.Back -> back to 0.9f
                    Kind.Limit -> limit to 0.9f
                }
                if (id != 0) p.play(id, volume, volume, 1, 0, 1f)
            }
        }
    }
}
