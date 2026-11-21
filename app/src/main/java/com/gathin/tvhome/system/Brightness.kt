package com.gathin.tvhome.system

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

object Brightness {
    private const val XIAOMI_BACKLIGHT = "picture_backlight"


    private fun usesXiaomiBacklight(context: Context) =
        !DeviceProfile.current(context).ignoresPictureBacklight &&
            Settings.Global.getString(context.contentResolver, XIAOMI_BACKLIGHT) != null

    private fun isTcl(context: Context) =
        runCatching { context.packageManager.getPackageInfo("com.tcl.settings", 0) }.isSuccess ||
            TclPq.backlight(context) != null

    private fun isXiaomi(context: Context) =
        runCatching { context.packageManager.getPackageInfo("com.xiaomi.mitv.settings", 0) }.isSuccess

    private fun isLetv(context: Context) =
        runCatching { context.packageManager.getPackageInfo("com.stv.globalsetting", 0) }.isSuccess

    private fun isSony(context: Context) =
        runCatching { context.packageManager.getPackageInfo("com.sony.dtv.configsettings", 0) }.isSuccess ||
            Build.MANUFACTURER.equals("Sony", ignoreCase = true)

    private fun isVidaa(context: Context) =
        listOf("com.jamdeo.tv.vod", "com.hisense.vidaaassistant").any {
            runCatching { context.packageManager.getPackageInfo(it, 0) }.isSuccess
        }

    private var mitvLevel: Int? = null

    private const val PREFS = "aerial"
    private const val KEY_MITV_LEVEL = "mitv_backlight"

    private fun storedLevel(context: Context): Int? {
        val value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_MITV_LEVEL, -1)
        return if (value in 1..100) value else null
    }

    private fun stepPercent(percent: Int, direction: Int): Int =
        (if (direction > 0) (percent / 5 + 1) * 5 else ((percent + 4) / 5 - 1) * 5).coerceIn(1, 100)

    fun level(context: Context): Float? = level(context, probeSkyworth = true)

    private fun level(context: Context, probeSkyworth: Boolean): Float? {
        if (SystemProbe.hidesBrightness(context)) return null
        HisenseLaserPanel.level(context)?.let { return it }
        SystemProbe.xgimiBrightnessLevel()?.let { return it }
        if (ChanghongPicture.available(context)) {
            if (ChanghongPicture.failed) return null
            ChanghongPicture.backlight(context)?.let { return it / 100f }
        }
        val r = context.contentResolver
        if (isTcl(context)) TclPicture.backlight(context)?.let { return it / 100f }
        return when {
            usesXiaomiBacklight(context) -> {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_SECURE_SETTINGS) != PackageManager.PERMISSION_GRANTED) return null
                Settings.Global.getInt(r, XIAOMI_BACKLIGHT, 50) / 100f
            }
            MitvPanel.usable(context) -> (mitvLevel ?: MitvPanel.level(context) ?: storedLevel(context) ?: 50) / 100f
            isXiaomi(context) -> null
            isTcl(context) || isSony(context) || isVidaa(context) || isLetv(context) || SystemProbe.skyworthPicturePanel(context) ||
                DeviceProfile.current(context).ignoresScreenBrightness ||
                Build.VERSION.SDK_INT >= 23 && !Settings.System.canWrite(context) -> null
            else -> {
                if (probeSkyworth) {
                    TianciBacklight.backlight(context)?.let { return it / 100f }
                    if (TianciBacklight.supported) return null
                }
                val skyworth = if (probeSkyworth) SkyworthPicture.backlight(context) else null
                skyworth?.let { it / 100f } ?: Settings.System.getInt(r, Settings.System.SCREEN_BRIGHTNESS, 128) / 255f
            }
        }
    }

    fun xgimiStep(current: Float, direction: Int): Float? {
        if (!SystemProbe.hasXgimiBrightness) return null
        val max = SystemProbe.xgimiBrightnessMax
        val now = Math.round(current * max)
        val next = (now + if (direction > 0) 1 else -1).coerceIn(1, max)
        return next / max.toFloat()
    }

    fun adjust(context: Context, direction: Int): Float? {
        if (SystemProbe.hidesBrightness(context)) return null
        HisenseLaserPanel.level(context)?.let { current ->
            val next = HisenseLaserPanel.step(current, direction)
            HisenseLaserPanel.setLevel(context, next)
            return next
        }
        SystemProbe.xgimiBrightnessLevel()?.let { current ->
            val next = xgimiStep(current, direction) ?: return current
            val level = Math.round(next * SystemProbe.xgimiBrightnessMax)
            return if (SystemProbe.setXgimiBrightnessLevel(level)) next else current
        }
        val current = level(context, probeSkyworth = false) ?: return null
        val percent = Math.round(current * 100)
        val next = stepPercent(percent, direction)
        val r = context.contentResolver
        val changhong = ChanghongPicture.usable(context)
        if (!changhong && !isTcl(context) && !usesXiaomiBacklight(context) && MitvPanel.usable(context)) {
            runCatching {
                val from = mitvLevel ?: MitvPanel.level(context) ?: storedLevel(context) ?: percent
                MitvPanel.setBacklight(context, from, next)
                mitvLevel = next
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                    .edit().putInt(KEY_MITV_LEVEL, next).apply()
            }
            return next / 100f
        }
        runCatching {
            if (changhong) {
                ChanghongPicture.setBacklight(context, next)
            } else if (isTcl(context)) {
                TclPicture.setBacklight(context, next)
            } else if (usesXiaomiBacklight(context)) {
                Settings.Global.putInt(r, XIAOMI_BACKLIGHT, next)
            } else {
                val tianci = TianciBacklight.backlight(context)
                if (tianci != null) {
                    TianciBacklight.setBacklight(context, stepPercent(tianci, direction))
                    return TianciBacklight.backlight(context)?.let { it / 100f }
                }
                if (TianciBacklight.supported) return null
                val skyworth = SkyworthPicture.backlight(context)
                if (skyworth != null) {
                    SkyworthPicture.setBacklight(context, stepPercent(skyworth, direction))
                    return SkyworthPicture.backlight(context)?.let { it / 100f }
                }
                Settings.System.putInt(r, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
                Settings.System.putInt(r, Settings.System.SCREEN_BRIGHTNESS, (next * 255 / 100).coerceAtLeast(1))
            }
        }
        return level(context, probeSkyworth = false)
    }
}

private object TclPicture {
    private fun manager(context: Context): Any? = runCatching {
        Class.forName("com.tcl.tvmanager.TTvPictureManager")
            .getMethod("getInstance", Context::class.java)
            .invoke(null, context.applicationContext)
    }.getOrNull()

    private fun graphicBacklight(context: Context): Int? {
        val pm = manager(context) ?: return null
        return runCatching { pm.javaClass.getMethod("getGraphicBacklight").invoke(pm) as Int }
            .getOrNull()?.takeIf { it in 1..100 }
    }

    fun backlight(context: Context): Int? = graphicBacklight(context) ?: TclPq.backlight(context)

    fun setBacklight(context: Context, percent: Int) {
        if (graphicBacklight(context) == null && TclPq.backlight(context) != null) {
            TclPq.setBacklight(context, percent.coerceIn(1, 100))
            return
        }
        val pm = manager(context) ?: return
        pm.javaClass.getMethod("setGraphicBacklight", Int::class.javaPrimitiveType).invoke(pm, percent.coerceIn(1, 100))
    }
}

private object TclPq {
    private class Api(val manager: Any, val get: java.lang.reflect.Method, val set: java.lang.reflect.Method, val current: Any, val save: Any)

    private var failed = false

    private fun api(context: Context): Api? {
        if (failed) return null
        return runCatching {
            val loader = context.applicationContext.classLoader
            val managerClass = Class.forName("com.tcl.tv.pq.TvBacklightManager", true, loader)
            val applyClass = Class.forName("com.tcl.tv.pq.EnApplyType", true, loader)
            val actClass = Class.forName("com.tcl.tv.pq.EnTclActType", true, loader)
            Api(
                manager = managerClass.getMethod("getInstance").invoke(null) ?: return null,
                get = managerClass.getMethod("getBacklight"),
                set = managerClass.getMethod("setBacklight", applyClass, Int::class.javaPrimitiveType, actClass),
                current = applyClass.getField("EN_APPLY_CURRENT").get(null)!!,
                save = actClass.getField("EN_TCL_ACT_EXEC_SAVE").get(null)!!,
            )
        }.getOrNull()
    }

    fun backlight(context: Context): Int? {
        val api = api(context) ?: return null
        return runCatching { api.get.invoke(api.manager) as Int }.getOrNull()?.takeIf { it in 1..100 }
    }

    fun setBacklight(context: Context, percent: Int) {
        val api = api(context) ?: return
        val ok = runCatching {
            api.set.invoke(api.manager, api.current, percent, api.save) == true && api.get.invoke(api.manager) as Int == percent
        }.getOrDefault(false)
        if (!ok) failed = true
    }
}

private object SkyworthPicture {
    private class Api(val api: Any, val get: java.lang.reflect.Method, val set: java.lang.reflect.Method)
    private var failed = false
    private var cached: Api? = null

    fun isSkyworth(context: Context): Boolean =
        android.os.Build.MANUFACTURER.equals("Skyworth", ignoreCase = true) ||
            android.os.Build.BRAND.equals("Skyworth", ignoreCase = true) ||
            runCatching { context.packageManager.getPackageInfo("com.tianci.system", 0) }.isSuccess

    @Synchronized
    private fun api(context: Context): Api? {
        if (failed) return null
        cached?.let { return it }
        return runCatching {
            val paths = listOf(
                "/system/app/SkySystemService/SkySystemService.apk",
                "/system/app/SkySetting/SkySetting.apk",
            ).filter { java.io.File(it).exists() }
            check(paths.isNotEmpty()) { "No Tianci system APK found" }
            val dexPath = paths.joinToString(":")
            val loader = dalvik.system.PathClassLoader(dexPath, context.classLoader)
            val clazz = Class.forName("com.tianci.system.api.TCHotelApi", true, loader)
            val instance = runCatching {
                clazz.getMethod("getInstance", Context::class.java).invoke(null, context.applicationContext)
            }.getOrNull() ?: clazz.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }.newInstance(context.applicationContext)
            val get = clazz.getMethod("getBacklightAdjust")
            val set = clazz.getMethod("setBacklightAdjust", Int::class.javaPrimitiveType)
            Api(instance, get, set).also {
                cached = it
                android.util.Log.i("SkyworthPicture", "TCHotelApi initialized successfully from $dexPath")
            }
        }.getOrElse { e ->
            android.util.Log.w("SkyworthPicture", "API initialization failed: ${(e.cause ?: e).javaClass.simpleName}")
            failed = true
            null
        }
    }
    fun backlight(context: Context): Int? {
        if (!isSkyworth(context)) return null
        val a = api(context) ?: return null
        return runCatching {
            val v = a.get.invoke(a.api) as Int
            v.takeIf { it in 1..100 }
        }.getOrNull()
    }

    fun setBacklight(context: Context, percent: Int) {
        val a = api(context) ?: return
        runCatching { a.set.invoke(a.api, percent.coerceIn(1, 100)) }
    }
}
