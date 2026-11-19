package com.gathin.tvhome.apps

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

object StandIn {
    fun find(context: Context, blocked: ComponentName): ComponentName? {
        val pm = context.packageManager
        val entries = runCatching {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).setPackage(blocked.packageName), 0)
        }.getOrNull().orEmpty().mapNotNull { r ->
            val a = r.activityInfo ?: return@mapNotNull null
            val open = a.exported && (a.permission == null ||
                ContextCompat.checkSelfPermission(context, a.permission) == PackageManager.PERMISSION_GRANTED)
            if (open) a.name else null
        }
        return pick(blocked.className, entries)?.let { ComponentName(blocked.packageName, it) }
    }

    private fun pick(blocked: String, open: List<String>): String? =
        open.filter { it != blocked && blocked.startsWith(it) }.maxByOrNull { it.length }
}
