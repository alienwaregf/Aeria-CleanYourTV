package com.gathin.tvhome.system

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.tv.TvInputInfo
import android.os.Bundle
import androidx.core.content.ContextCompat
import com.gathin.tvhome.settings.AerialSettings

object CecSwitch {
    const val OTP_ACTION = "droidlogic.tv.action.OTP_INPUT_SOURCE_CHANGED"

    @Volatile private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (!com.gathin.tvhome.guard.GuardSetup.isUserUnlocked(context)) return
            AerialSettings.init(context)
            val pending = goAsync()
            Thread {
                try { onOneTouchPlay(context, intent) } finally { pending.finish() }
            }.start()
        }
    }

    fun register(context: Context) {
        if (registered) return
        registered = true
        runCatching {
            ContextCompat.registerReceiver(context.applicationContext, receiver, IntentFilter(OTP_ACTION), ContextCompat.RECEIVER_EXPORTED)
        }.onFailure { registered = false }
    }

    private fun onOneTouchPlay(context: Context, intent: Intent) {
        val extras = intent.extras
        DiagLog.i("cec: one touch play ${describe(extras)}")
        if (!AerialSettings.cecSwitch) return
        val sources = SystemProbe.sources(context)
        val texts = runCatching { texts(extras) }.getOrDefault(emptyList())
        val source = pick(sources.map { it.id }, texts) { parentOf(context, it) }?.let { id -> sources.firstOrNull { it.id == id } }
        if (source == null) {
            DiagLog.i("cec: no input matches")
            return
        }
        DiagLog.i("cec: opening ${source.label}")
        runCatching {
            if (!HisenseSources.open(context, source.intent)) context.startActivity(source.intent)
        }.onFailure { DiagLog.i("cec: open failed ${it.javaClass.simpleName}") }
    }

    fun pick(ids: List<String>, texts: List<String>, parentOf: (String) -> String? = { null }): String? {
        for (t in texts) {
            if (t in ids) return t
            parentOf(t)?.takeIf { it in ids }?.let { return it }
        }
        return null
    }

    private fun texts(extras: Bundle?): List<String> {
        if (extras == null) return emptyList()
        return buildList {
            extras.getString(TvInputInfo.EXTRA_INPUT_ID)?.let(::add)
            for (key in extras.keySet()) (runCatching { @Suppress("DEPRECATION") extras.get(key) }.getOrNull() as? String)?.let(::add)
        }
    }

    private fun parentOf(context: Context, id: String): String? = runCatching {
        (context.getSystemService(Context.TV_INPUT_SERVICE) as? android.media.tv.TvInputManager)?.getTvInputInfo(id)?.parentId
    }.getOrNull()

    private fun describe(extras: Bundle?): String = runCatching {
        @Suppress("DEPRECATION")
        extras?.keySet()?.joinToString(" ") { k -> "$k=${runCatching { extras.get(k) }.getOrNull()}" } ?: "(no extras)"
    }.getOrDefault("(unreadable extras)")
}
