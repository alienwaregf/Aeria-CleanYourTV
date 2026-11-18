package com.gathin.tvhome.ui.home

import android.os.SystemClock
import com.gathin.tvhome.system.DiagLog
import kotlinx.coroutines.delay

object SourcePreview {
    @Volatile
    private var endedAt = 0L

    fun noteEnded() {
        endedAt = SystemClock.uptimeMillis()
    }

    suspend fun awaitDecoderFree() {
        val at = endedAt
        if (at == 0L) return
        endedAt = 0L
        val elapsed = SystemClock.uptimeMillis() - at
        if (elapsed >= WAIT_MS) return
        DiagLog.i("wallpaper film waits ${WAIT_MS - elapsed} ms for the source preview just left to free the decoder")
        delay(WAIT_MS - elapsed)
    }

    private const val WAIT_MS = 800L
}
