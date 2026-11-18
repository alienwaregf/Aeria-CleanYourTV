package com.gathin.tvhome.system

import android.content.Context
import android.media.AudioManager
import android.os.Build

object OutputDevice {
    fun isPlaybackDevice(): Boolean {
        val types = prop("ro.hdmi.device_type").orEmpty().split(',').map { it.trim() }
        if ("4" in types && "0" !in types) return true
        return prop("ro.build.characteristics").orEmpty().let { "stb" in it || "mbx" in it }
    }

    fun volumeFixed(context: Context): Boolean = Build.VERSION.SDK_INT >= 21 && runCatching {
        (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).isVolumeFixed
    }.getOrDefault(false)

    fun showsVolume(context: Context): Boolean = !volumeFixed(context)

    fun showsBrightness(): Boolean = !isPlaybackDevice()

    private fun prop(key: String): String? = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, key) as String
    }.getOrNull()
}
