package com.gathin.tvhome.system

object HomeReopen {
    const val WINDOW_MS = 10_000L

    private var lastAt = Long.MIN_VALUE / 2

    @Synchronized
    fun mayReopen(now: Long): Boolean {
        if (now - lastAt < WINDOW_MS) return false
        lastAt = now
        return true
    }

    @Synchronized
    internal fun reset() {
        lastAt = Long.MIN_VALUE / 2
    }
}
