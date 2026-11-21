package com.gathin.tvhome.guard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import android.util.Log
import com.gathin.tvhome.system.DiagLog
import java.io.FileDescriptor
import java.io.PrintWriter

class RevivalListener : NotificationListenerService() {
    private val handler = Handler(Looper.getMainLooper())
    private var recovering = false
    private var destroyed = false
    private var attempt = 0
    private var revivalNoted = false
    private var watchingUnlock = false
    private var outcome = "not started"
    private val check = Runnable { checkGuard() }
    private val unlocked = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            startRecovery("user unlocked")
        }
    }

    override fun onCreate() {
        super.onCreate()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching {
                registerReceiver(unlocked, IntentFilter(Intent.ACTION_USER_UNLOCKED))
                watchingUnlock = true
            }.onFailure {
                trace("could not watch unlock; relying on delayed checks: ${it.message}")
            }
        }
        startRecovery("service created")
    }

    override fun onListenerConnected() {
        if (destroyed) return
        Log.i("AerialGuard", "revived by the notification listener binding")
        trace("notification listener connected")
        if (GuardSetup.isUserUnlocked(this)) {
            runCatching {
                prepareDiagnostics()
                noteRevival()
                GuardSetup.ensureEnabled(this)
                if (GuardSetup.startAfterStopOverStock(this)) trace("stopped over the stock launcher; Aerial started before the guard binds")
            }.onFailure {
                trace("initial recovery failed: ${it.javaClass.simpleName}: ${it.message}")
            }
        }
        startRecovery("listener connected")
    }

    private fun startRecovery(reason: String) {
        if (destroyed || recovering) return
        recovering = true
        attempt = 0
        outcome = reason
        trace("starting checks: $reason")
        handler.postDelayed(check, RETRY_DELAYS_MS[0])
    }

    private fun checkGuard() {
        if (destroyed || !recovering) return
        if (HomeGuardService.isRunning) {
            finishRecovery("guard bound")
            return
        }
        if (attempt >= RETRY_DELAYS_MS.size) {
            finishRecovery("retry window exhausted; guard still unbound")
            return
        }
        attempt++
        if (GuardSetup.isUserUnlocked(this)) {
            runCatching {
                prepareDiagnostics()
                noteRevival()
                trace("attempt $attempt: unlocked, guard unbound; requesting recovery")
                GuardSetup.ensureEnabled(this)
                GuardSetup.rebindIfStalled(this)
            }.onFailure {
                trace("attempt $attempt failed: ${it.javaClass.simpleName}: ${it.message}")
            }
        } else {
            trace("attempt $attempt: waiting for user unlock")
        }
        handler.postDelayed(check, RETRY_DELAYS_MS.getOrElse(attempt) { FINAL_CHECK_DELAY_MS })
    }

    private fun prepareDiagnostics() {
        DiagLog.init(this)
    }

    private fun noteRevival() {
        if (revivalNoted) return
        revivalNoted = true
        GuardSetup.noteRevival(this)
    }

    private fun trace(message: String) = DiagLog.i("revival: $message")

    private fun finishRecovery(result: String) {
        recovering = false
        outcome = result
        trace(result)
    }

    override fun dump(fd: FileDescriptor?, writer: PrintWriter, args: Array<out String>?) {
        writer.println("Aerial revival: active=$recovering attempts=$attempt outcome=$outcome")
        writer.println("userUnlocked=${GuardSetup.isUserUnlocked(this)} guardRunning=${HomeGuardService.isRunning}")
        if (GuardSetup.isUserUnlocked(this)) runCatching { writer.println(HomeGuardService.diagnosticState()) }
        DiagLog.dump(writer)
    }

    override fun onDestroy() {
        destroyed = true
        recovering = false
        handler.removeCallbacks(check)
        if (watchingUnlock) runCatching { unregisterReceiver(unlocked) }
        watchingUnlock = false
        super.onDestroy()
    }

    private companion object {
        val RETRY_DELAYS_MS = longArrayOf(5_000, 5_000, 10_000, 15_000, 30_000, 60_000)
        const val FINAL_CHECK_DELAY_MS = 5_000L
    }
}
