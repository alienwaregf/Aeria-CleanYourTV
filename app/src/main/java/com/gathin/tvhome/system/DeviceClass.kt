package com.gathin.tvhome.system

import android.app.ActivityManager
import android.content.Context
import android.os.Build

object DeviceClass {
    @Volatile private var known: Boolean? = null

    fun lowEnd(context: Context): Boolean = known ?: run {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
        val mb = info.totalMem / (1 shl 20)
        (am.isLowRamDevice || mb < 1800 || Build.VERSION.SDK_INT < 24 || Runtime.getRuntime().availableProcessors() <= 2)
            .also { known = it }
    }

    fun describe(context: Context): String {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo().also(am::getMemoryInfo)
        return "low-end=${lowEnd(context)} (low-RAM flag ${am.isLowRamDevice}, ${info.totalMem / (1 shl 20)} MB, " +
            "${Runtime.getRuntime().availableProcessors()} cores, memory class ${am.memoryClass} MB)"
    }
}
