package com.gathin.tvhome.system

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.os.BatteryManager
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

    fun describe(context: Context): String {
        val pm = context.packageManager
        val features = listOf(
            "live_tv" to "android.software.live_tv",
            "leanback" to "android.software.leanback",
            "hdmi_cec" to "android.hardware.hdmi.cec",
            "television" to "android.hardware.type.television",
        ).joinToString(" ") { (name, feature) -> "$name=${runCatching { pm.hasSystemFeature(feature) }.getOrDefault(false)}" }
        fun shown(key: String) = prop(key).orEmpty().ifEmpty { "-" }
        return "playback_device=${isPlaybackDevice()} (hdmi device_type=${shown("ro.hdmi.device_type")}, " +
            "characteristics=${shown("ro.build.characteristics")}) volume_fixed=${volumeFixed(context)}\n" +
            "            features $features; battery ${battery(context)}"
    }

    private fun battery(context: Context): String {
        val b = runCatching { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull() ?: return "-"
        return "present=${b.getBooleanExtra(BatteryManager.EXTRA_PRESENT, false)}" +
            " level=${b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)}/${b.getIntExtra(BatteryManager.EXTRA_SCALE, -1)}" +
            " plugged=${b.getIntExtra(BatteryManager.EXTRA_PLUGGED, -1)} status=${b.getIntExtra(BatteryManager.EXTRA_STATUS, -1)}"
    }

    private fun prop(key: String): String? = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, key) as String
    }.getOrNull()
}
