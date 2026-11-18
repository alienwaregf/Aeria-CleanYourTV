package com.gathin.tvhome.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

internal object ChanghongSettings {
    const val PACKAGE = "com.changhong.easysetting"
    const val NETWORK = "StatusBar_to_netset"
    const val PICTURE = "StatusBar_to_videoset"
    val component = ComponentName(PACKAGE, "$PACKAGE.MainService")

    fun available(context: Context): Boolean = runCatching {
        val info = context.packageManager.resolveService(Intent().setComponent(component), 0)?.serviceInfo
        info != null && info.exported && info.enabled && info.applicationInfo.enabled &&
            (info.permission.isNullOrEmpty() || context.checkCallingOrSelfPermission(info.permission) == PackageManager.PERMISSION_GRANTED)
    }.getOrDefault(false)

    fun intent(context: Context, page: String? = null): Intent? {
        if (!available(context)) return null
        val intent = Intent("Changhong.EasySetting").setComponent(component)
        if (page != null) intent.putExtra(page, true)
        return intent
    }

    fun open(context: Context, intent: Intent): Boolean =
        intent.component == component && available(context) && runCatching { context.startService(intent) }.getOrNull() != null
}

internal object ChanghongPicture {
    @Volatile
    var failed = false
        private set

    private fun managerClass(context: Context): Class<*>? = runCatching {
        Class.forName("com.changhong.tvos.common.TVManager", false, context.classLoader)
    }.getOrNull()

    fun available(context: Context): Boolean = managerClass(context) != null

    fun usable(context: Context): Boolean = backlight(context) != null

    private fun manager(context: Context): Any? {
        if (failed) return null
        val type = managerClass(context) ?: return null
        return runCatching {
            val tv = type.getMethod("getInstance", Context::class.java).invoke(null, context.applicationContext)
                ?: return null
            type.getMethod("getPictureManager").invoke(tv)
        }.getOrNull()
    }

    fun backlight(context: Context): Int? {
        val picture = manager(context) ?: return null
        return runCatching { picture.javaClass.getMethod("getBackLight").invoke(picture) as Int }
            .getOrNull()?.takeIf { it in 0..100 }
    }

    fun setBacklight(context: Context, percent: Int) {
        val picture = manager(context) ?: return
        val level = percent.coerceIn(1, 100)
        val accepted = runCatching {
            picture.javaClass.getMethod("setBackLight", Int::class.javaPrimitiveType).invoke(picture, level) == true &&
                picture.javaClass.getMethod("getBackLight").invoke(picture) as Int == level
        }.getOrDefault(false)
        if (!accepted) failed = true
    }
}
