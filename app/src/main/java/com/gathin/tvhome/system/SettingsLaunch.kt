package com.gathin.tvhome.system

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.SystemClock

internal fun settingsLaunchIntent(context: Context, intent: Intent): Intent {
    var caller = context
    while (caller !is Activity && caller is ContextWrapper) {
        val base = caller.baseContext
        if (base === caller) return intent
        caller = base
    }
    if (caller !is Activity) return intent
    val info = runCatching { context.packageManager.resolveActivity(intent, 0)?.activityInfo }
        .getOrNull() ?: return intent
    if (info.packageName != "com.android.tv.settings" ||
        info.name != "com.android.tv.settings.MainSettings" ||
        info.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM == 0
    ) return intent
    return Intent(intent).apply { flags = flags and Intent.FLAG_ACTIVITY_NEW_TASK.inv() }
}

internal object LaunchFallback {
    private var rest: List<Intent> = emptyList()
    private var armedAt = 0L

    fun arm(rest: List<Intent>) {
        this.rest = rest
        armedAt = SystemClock.uptimeMillis()
    }

    fun next(): Intent? {
        val pages = rest
        rest = emptyList()
        if (pages.isEmpty() || SystemClock.uptimeMillis() - armedAt > BOUNCE_MS) return null
        arm(pages.drop(1))
        return pages.first()
    }

    private const val BOUNCE_MS = 800L
}
