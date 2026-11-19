package com.gathin.tvhome.system

import android.content.ComponentName
import android.content.Context
import kotlin.concurrent.thread

object WhaleyBoot {
    private const val PREFS = "system"
    private const val LIGHT_BOOT_ID = "whaley_light_boot_id"
    private const val LED_OFF = 1

    private val PLAYER = ComponentName("com.whaley.tv.tvplayer.ui", "com.whaley.tv.tvplayer.ui.RootActivity")

    fun isWhaley(context: Context): Boolean =
        runCatching { context.packageManager.getActivityInfo(PLAYER, 0) }.isSuccess

    fun newBoot(bootId: String?, handled: String?): Boolean = !bootId.isNullOrBlank() && bootId != handled

    fun turnOffPowerLight(context: Context) {
        if (!isWhaley(context)) return
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val bootId = BootOpen.BOOT_ID
        if (!newBoot(bootId, prefs.getString(LIGHT_BOOT_ID, null))) return
        prefs.edit().putString(LIGHT_BOOT_ID, bootId).commit()
        thread(name = "WhaleyLight") {
            runCatching {
                val tv = Class.forName("com.mstar.android.tvapi.common.TvManager")
                val manager = tv.getMethod("getInstance").invoke(null)
                tv.getMethod("setLedMode", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
                    .invoke(manager, LED_OFF, 0)
            }.onSuccess { DiagLog.i("whaley: power light turned off") }
                .onFailure { DiagLog.i("whaley: power light not turned off: $it") }
        }
    }
}
