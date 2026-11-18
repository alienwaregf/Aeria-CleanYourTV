package com.gathin.tvhome.system

import android.content.Context

internal object HisenseLaserPanel {
    const val LEVEL_MAX = 10

    private const val BACKLIGHT_MODE_CUSTOM = 0

    private val tvManager by lazy { runCatching { Class.forName("com.jamdeo.tv.TvManager") }.getOrNull() }

    @Volatile
    private var cached: Any? = null

    private fun pictureManager(context: Context): Any? {
        cached?.let { return it }
        synchronized(this) {
            cached?.let { return it }
            val cls = tvManager ?: return null
            val pm = runCatching {
                val tv = cls.getMethod("getInstance").invoke(null) ?: return null
                tv.javaClass.getMethod("getPictureManager", Context::class.java)
                    .invoke(tv, context.applicationContext)
            }.getOrNull() ?: return null
            cached = pm
            return pm
        }
    }

    private fun method(on: Any, name: String, vararg args: Class<*>): java.lang.reflect.Method? =
        runCatching { on.javaClass.getMethod(name, *args) }.getOrNull()

    private fun rawLevel(context: Context): Int? {
        val pm = pictureManager(context) ?: return null
        if (method(pm, "getLaserOverLapOrCL0Mode") == null) return null
        val level = runCatching { method(pm, "getCustomBacklight")?.invoke(pm) as? Int }.getOrNull()
        return level?.takeIf { it in 0..LEVEL_MAX }
    }

    fun usable(context: Context): Boolean = rawLevel(context) != null

    fun level(context: Context): Float? = rawLevel(context)?.let { it / LEVEL_MAX.toFloat() }

    fun step(current: Float, direction: Int): Float {
        val now = Math.round(current * LEVEL_MAX)
        val next = (now + if (direction > 0) 1 else -1).coerceIn(0, LEVEL_MAX)
        return next / LEVEL_MAX.toFloat()
    }

    fun setLevel(context: Context, level: Float) {
        val pm = pictureManager(context) ?: return
        val target = Math.round(level.coerceIn(0f, 1f) * LEVEL_MAX)
        val int = Int::class.javaPrimitiveType ?: return
        runCatching {
            method(pm, "setBacklightMode", int)?.invoke(pm, BACKLIGHT_MODE_CUSTOM)
            method(pm, "setCustomBacklight", int)?.invoke(pm, target)
        }
    }

}
