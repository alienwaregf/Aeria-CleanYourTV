package com.gathin.tvhome.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.util.Log

internal object KonkaSources {
    private const val TAG = "AerialKonkaSources"
    private const val MANAGER = "com.konka.android.tv.KKCommonManager"
    private const val SOURCE_ENUM = "com.konka.android.tv.KKCommonManager\$EN_KK_INPUT_SOURCE"
    private val player = ComponentName("com.konka.tvsettings", "com.konka.tvsettings.RootActivity")

    fun load(context: Context): List<TvSource> {
        val activity = runCatching { context.packageManager.getActivityInfo(player, 0) }.getOrNull()
            ?: return emptyList()
        if (!activity.exported || !activity.enabled || !activity.applicationInfo.enabled) return emptyList()
        return runCatching {
            val managerClass = Class.forName(MANAGER)
            val sourceClass = Class.forName(SOURCE_ENUM)
            val manager = managerClass.getMethod("getInstance", Context::class.java)
                .invoke(null, context.applicationContext)
            val reported = managerClass.getMethod("getTVInputSourceList").invoke(manager) as? List<*>
                ?: return emptyList()
            val connected = runCatching {
                managerClass.getMethod("getAvaliableInputSourceList").invoke(manager) as? List<*>
            }.getOrNull().orEmpty()
            val labelMethod = runCatching { managerClass.getMethod("getInputSourceName", sourceClass) }.getOrNull()
            reported.filterIsInstance<Enum<*>>().map { source ->
                val label = runCatching { labelMethod?.invoke(manager, source) as? String }.getOrNull()
                    ?.takeIf { it.isNotBlank() } ?: source.name
                val kind = when {
                    source.name.startsWith("HDMI") || source.name == "EXTERNAL_8K_HDMI" -> SourceKind.Hdmi
                    source.name == "DP" || source.name == "TYPE_C" || source.name == "EXTERNAL_8K_DP" -> SourceKind.DisplayPort
                    source.name.startsWith("AV") || source.name.startsWith("YPBPR") || source.name.startsWith("SVIDEO") -> SourceKind.Av
                    source.name == "ATV" || source.name == "DTMB" || source.name.startsWith("DVB_") ||
                        source.name == "ATSC" || source.name == "ISDB_T" -> SourceKind.Tuner
                    else -> SourceKind.Other
                }
                TvSource(
                    id = "konka:${source.name}",
                    label = label,
                    kind = kind,
                    connected = android.os.Build.VERSION.SDK_INT >= 23 && source in connected,
                    deviceName = null,
                    intent = Intent("com.konka.tvsettings.intent.action.RootActivity")
                        .setComponent(player)
                        .putExtra("source", source.ordinal)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                )
            }.distinctBy { it.id }.sortedWith(compareBy({ it.kind.ordinal }, { it.label }))
        }.onFailure { Log.w(TAG, "Could not read the native Konka input list", it) }.getOrDefault(emptyList())
    }

    fun needsSwitch(intent: Intent): Boolean =
        android.os.Build.VERSION.SDK_INT < 23 && intent.component == player && intent.hasExtra("source")

    fun switchTo(context: Context, intent: Intent) {
        val ordinal = intent.getIntExtra("source", -1)
        runCatching {
            val managerClass = Class.forName(MANAGER)
            val sourceClass = Class.forName(SOURCE_ENUM)
            val source = sourceClass.enumConstants?.getOrNull(ordinal) ?: error("no input $ordinal")
            val manager = managerClass.getMethod("getInstance", Context::class.java).invoke(null, context.applicationContext)
            val switched = managerClass.getMethod("setInputSource", sourceClass).invoke(manager, source)
            DiagLog.i("konka: switched to $source before its page: $switched")
        }.onFailure { Log.w(TAG, "Could not switch the Konka input to $ordinal", it) }
    }
}
