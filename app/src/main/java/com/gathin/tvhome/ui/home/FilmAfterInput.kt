package com.gathin.tvhome.ui.home

import android.content.Context
import android.content.Intent
import android.os.Build
import com.gathin.tvhome.system.DiagLog
import kotlinx.coroutines.delay

object FilmAfterInput {
    @Volatile
    private var inputOpened = false

    fun noteOpened(context: Context, intent: Intent) {
        if (!applies(context)) return
        inputOpened = intent.component?.packageName == XIAOMI_PLAYER || intent.data?.host == "android.media.tv"
    }

    suspend fun await() {
        if (!inputOpened) return
        inputOpened = false
        DiagLog.i("wallpaper film waits ${WAIT_MS} ms for the input just left to close")
        delay(WAIT_MS)
    }

    private fun applies(context: Context): Boolean =
        Build.VERSION.SDK_INT <= Build.VERSION_CODES.M &&
            runCatching { context.packageManager.getPackageInfo(XIAOMI_PLAYER, 0) }.isSuccess

    private const val XIAOMI_PLAYER = "com.xiaomi.mitv.tvplayer"

    private const val WAIT_MS = 2_500L
}
