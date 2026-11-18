package com.gathin.tvhome.guard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import com.gathin.tvhome.MainActivity
import com.gathin.tvhome.system.DiagLog
import kotlin.concurrent.thread

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_LOCKED_BOOT_COMPLETED) return
        val pending = goAsync()
        val start = SystemClock.elapsedRealtime()
        thread(name = "AerialBoot") {
            try {
                onBoot(context, action)
            } finally {
                pending.finish()
                DiagLog.i("${action.substringAfterLast('.')} handled in ${SystemClock.elapsedRealtime() - start} ms")
            }
        }
    }

    private fun onBoot(context: Context, action: String) {
        GuardSetup.ensureEnabled(context)
        if (action == Intent.ACTION_LOCKED_BOOT_COMPLETED) return
        GuardSetup.rescanIfMissing(context)
        if (HomeGuardService.onBootCompleted()) return
        if (GuardSetup.startsOnSignalSource(context)) {
            DiagLog.i("boot: the TV starts on a signal source; leaving it")
            return
        }
        if (MainActivity.leftForAnApp) {
            DiagLog.i("boot: an app opened from Aerial is in front; leaving it")
            return
        }
        runCatching {
            context.startActivity(MainActivity.bringBackIntent(context))
        }
    }
}
