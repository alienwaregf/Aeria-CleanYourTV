package com.gathin.tvhome.system

import android.content.ComponentName
import android.content.Context
import java.io.File

object CoocaaHome {
    fun launcher(context: Context): ComponentName? {
        val named = "${property("persist.service.homepage.pkg").orEmpty()}/${property("persist.service.homepage.cls").orEmpty()}"
        return sequenceOf(defaultHomepage(), named)
            .mapNotNull { it?.let(ComponentName::unflattenFromString) }
            .firstOrNull { isLauncher(context, it) }
    }

    fun isLauncher(context: Context, component: ComponentName): Boolean =
        component.packageName.isNotEmpty() && component.className.isNotEmpty() &&
            component.packageName != context.packageName &&
            runCatching { context.packageManager.getActivityInfo(component, 0) }.isSuccess

    private fun defaultHomepage(): String? = runCatching {
        val model = property("ro.build.skymodel").orEmpty()
        val type = property("ro.build.skytype").orEmpty()
        if (model.isEmpty() || type.isEmpty()) return null
        val base = File("/system/pcfg/${model}_$type")
        val panel = property("persist.sys.panel_size").orEmpty()
        val param = property("third.get.panel.param").orEmpty()
        val dirs = buildList {
            if (panel.isNotEmpty()) {
                if (param.isNotEmpty()) add(File(base, "${panel}_$param"))
                add(File(base, panel))
            }
            add(base)
        }
        val file = dirs.map { File(it, "config/general_config.xml") }.firstOrNull { it.canRead() } ?: return null
        Regex("""name="DEFAULT_HOMEPAGE"\s+value="([^"]+)"""").find(file.readText())?.groupValues?.get(1)
    }.getOrNull()

    private fun property(key: String): String? = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, key) as String
    }.getOrNull()
}
