package com.gathin.tvhome.system

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.os.Build
import android.provider.Settings
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.gathin.tvhome.BuildConfig
import com.gathin.tvhome.guard.GuardSetup
import com.gathin.tvhome.guard.HomeGuardService
import com.gathin.tvhome.settings.AerialSettings
import com.gathin.tvhome.wallpaper.PhoneServer
import com.gathin.tvhome.wallpaper.Wallpapers
import org.json.JSONArray
import org.json.JSONObject

object Diagnostics {

    private val VENDOR_PACKAGES = listOf(
        "com.mitv.tvhome", "com.xiaomi.mitv.settings", "com.xiaomi.mitv.tvplayer", "com.xiaomi.mitv.hyper.screensaver",
        "com.tcl.settings", "com.tcl.tv", "com.tcl.MultiScreenInteraction_TV", "com.tcl.appmarket2",
        "com.hisense.vidaaassistant", "com.jamdeo.tv.vod",
        "com.tianci.tv", "com.skyworth.tv", "com.coocaa.os.launcher",
        "com.konka.tvsettings", "com.stv.deskplatform",
        "com.sony.dtv.configsettings",
        "com.google.android.tvlauncher", "com.google.android.apps.tv.launcherx", "com.android.tv.settings",
        "com.dangbeimarket",
    )

    private val LOG_TAGS = listOf("AerialGuard", "AerialUpdates", "AerialStream", "AerialWallpaper", "AerialKonkaSources", "AndroidRuntime")

    private fun compiled(context: Context): String {
        val profile = runCatching {
            val s = androidx.profileinstaller.ProfileVerifier.getCompilationStatusAsync().get(2, java.util.concurrent.TimeUnit.SECONDS)
            if (s.profileInstallResultCode == androidx.profileinstaller.ProfileVerifier.CompilationStatus.RESULT_CODE_ERROR_UNSUPPORTED_API_VERSION)
                "profile state not readable on this Android"
            else "with profile=${s.isCompiledWithProfile}, profile waiting=${s.hasProfileEnqueuedForCompilation()}, code=${s.profileInstallResultCode}"
        }.getOrElse { "profile state unknown (${it.javaClass.simpleName})" }
        val oat = com.gathin.tvhome.update.SelfCompile.odexBytes(context)?.div(1024)
        return "$profile; odex ${oat?.let { "$it KB" } ?: "-"}"
    }

    fun report(context: Context): String = buildString {
        val pm = context.packageManager
        appendLine("### Aerial diagnostics")
        appendLine()
        appendLine("```")
        appendLine("Aerial      ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE}) ${BuildConfig.FLAVOR}, package ${context.packageName}, installer ${installer(context) ?: "-"}")
        appendLine("Device      ${Build.MANUFACTURER} / ${Build.BRAND} / ${Build.MODEL} (device ${Build.DEVICE}, product ${Build.PRODUCT}, hardware ${Build.HARDWARE}, board ${Build.BOARD})")
        appendLine("Android     ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})" + if (Build.VERSION.SDK_INT >= 23) ", patch ${Build.VERSION.SECURITY_PATCH}" else "")
        appendLine("Build       ${Build.DISPLAY}")
        appendLine("Fingerprint ${Build.FINGERPRINT}")
        listOf("ro.board.platform", "ro.product.cpu.abi", "ro.config.low_ram", "ro.build.characteristics", "persist.vt.use_home_pkg")
            .forEach { key -> prop(key)?.takeIf { it.isNotEmpty() }?.let { appendLine("  $key = $it") } }
        appendLine("Screen      ${screen(context)}")
        appendLine("Memory      ${memory(context)}")
        appendLine("Network     ${SystemProbe.network(context).kind}")
        appendLine("```")

        appendLine()
        appendLine("**Home**")
        appendLine("```")
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val resolved = pm.resolveActivity(homeIntent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo
        appendLine("Resolves to ${resolved?.let { "${it.packageName}/${it.name}" } ?: "-"}  (${SystemProbe.homeStatus(context)})")
        runCatching { pm.queryIntentActivities(homeIntent, 0) }.getOrDefault(emptyList()).forEach {
            appendLine("  candidate ${it.activityInfo.packageName}/${it.activityInfo.name} priority=${it.priority}")
        }
        HomeGuardService.stockHomes()?.forEach { (pkg, classes) -> appendLine("  guard keeps away $pkg: ${classes.joinToString()}") }
        appendLine("```")

        appendLine()
        appendLine("**Takeover**")
        appendLine("```")
        val accessibility = secure(context, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        appendLine("Home Guard  enabled=${accessibility?.contains("${context.packageName}/") == true} running=${HomeGuardService.isRunning}")
        appendLine("  accessibility services: ${accessibility ?: "-"}")
        appendLine("Revival     notification listener allowed=${NotificationManagerCompat.getEnabledListenerPackages(context).contains(context.packageName)}, revivals in 10 min=${GuardSetup.revivedOften(context).let { if (it) "3 or more" else "fewer than 3" }}")
        appendLine("Grants      WRITE_SECURE_SETTINGS=${granted(context, Manifest.permission.WRITE_SECURE_SETTINGS)}" +
            " overlay=${Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(context)}" +
            " WRITE_SETTINGS=${Build.VERSION.SDK_INT < 23 || Settings.System.canWrite(context)}" +
            " install=${Build.VERSION.SDK_INT < 26 || pm.canRequestPackageInstalls()}" +
            " location=${granted(context, Manifest.permission.ACCESS_FINE_LOCATION)}" +
            " usage=${HomeGuardService.usageStatsAllowed(context)}" + restrictedSettings(context))
        appendLine("ADB         adb_enabled=${global(context, Settings.Global.ADB_ENABLED)} keep_adb=${GuardSetup.keepAdb(context)} guard_off_in_standby=${GuardSetup.offInStandby(context)}")
        appendLine("Screensaver enabled=${secure(context, "screensaver_enabled")} components=${secure(context, "screensaver_components")} Aerial's=${Wallpapers.screensaver}")
        appendLine("Sounds      sound_effects_enabled=${system(context, Settings.System.SOUND_EFFECTS_ENABLED)}")
        appendLine("Effects     ${AerialSettings.effects.key}, lite=${AerialSettings.lite}; ${DeviceClass.describe(context)}")
        appendLine("Compiled    ${compiled(context)}; self compile ${com.gathin.tvhome.update.SelfCompile.diagnostics(context)}")
        appendLine("Frames      ${FrameStats.summary()}")
        appendLine("Settings    focus=${AerialSettings.focusStyle.key} one_row=${AerialSettings.oneRowHome} columns=${AerialSettings.homeColumns} peek=${AerialSettings.peekNextRow} names=${AerialSettings.showAllNames} boot_source=${AerialSettings.bootSource != null} boot_app=${AerialSettings.bootApp}")
        appendLine("            volume_fixed=${OutputDevice.volumeFixed(context)} playback_device=${OutputDevice.isPlaybackDevice()}")
        runCatching { com.gathin.tvhome.wallpaper.AerialStream.usage(context) }.getOrNull()?.let { (used, limit) ->
            appendLine("Aerials     quality=${AerialSettings.aerialQuality.key} films=${com.gathin.tvhome.wallpaper.AerialSet.films(context).joinToString(",") { it.id.take(8) }} cache=${used / 1_048_576}/${limit / 1_048_576} MB")
        }
        appendLine("            wallpaper=${com.gathin.tvhome.wallpaper.Wallpapers.selectedId.substringBefore('/')} always_moves=${AerialSettings.wallpaperAlwaysMoves} quality=${AerialSettings.aerialQuality.key} weather=${AerialSettings.showWeather} saver_clock=${AerialSettings.screensaverClock}")
        runCatching { com.gathin.tvhome.ui.home.filmGlassReport(context) }.getOrNull()?.let { appendLine("            film glass $it") }
        appendLine("```")


        appendLine()
        appendLine("**Brightness**")
        appendLine("```")
        appendLine("Aerial can set it: ${Brightness.level(context) != null}")
        appendLine("  global picture_backlight=${global(context, "picture_backlight")}  system screen_brightness=${system(context, Settings.System.SCREEN_BRIGHTNESS)} mode=${system(context, Settings.System.SCREEN_BRIGHTNESS_MODE)}")
        appendLine("```")

        appendLine()
        appendLine("**Inputs**")
        appendLine("```")
        inputs(context).forEach { appendLine(it) }
        SystemProbe.sources(context).forEach { s ->
            appendLine("  shown: ${s.label} [${s.kind}] connected=${s.connected} -> ${s.intent.component?.flattenToShortString() ?: s.intent.data}" +
                (s.xiaomiInput?.let { " input=$it" } ?: "") + " id=${s.id}")
        }
        if (Build.MANUFACTURER.equals("xiaomi", ignoreCase = true)) {
            appendLine("  hdmiN_order: " + (1..4).joinToString(" ") { "$it=${SystemProbe.xiaomiHdmiOrder(it)}" })
        }
        appendLine("```")

        appendLine()
        appendLine("**Device profile** (${DeviceProfile.origin(context)})")
        appendLine("```json")
        appendLine(JSONObject().put("models", JSONArray(listOf(Build.MODEL))).apply {
            val profile = DeviceProfile.current(context).toJson()
            profile.keys().forEach { put(it, profile.get(it)) }
        }.toString())
        appendLine("```")

        appendLine()
        appendLine("**Packages**")
        appendLine("```")
        VENDOR_PACKAGES.forEach { pkg ->
            val info = runCatching { pm.getPackageInfo(pkg, 0) }.getOrNull() ?: return@forEach
            appendLine("$pkg ${info.versionName}" + if (info.applicationInfo?.enabled == false) " (disabled)" else "")
        }
        appendLine("```")

        appendLine()
        appendLine("<details><summary>Log</summary>")
        appendLine()
        appendLine("```")
        DiagLog.recent(150).forEach { appendLine(mask(it)) }
        appendLine("--- logcat")
        logcat().forEach { appendLine(mask(it)) }
        appendLine("```")
        appendLine()
        appendLine("</details>")
    }

    private fun inputs(context: Context): List<String> {
        val tim = context.getSystemService<TvInputManager>() ?: return listOf("no TvInputManager")
        val list = runCatching { tim.tvInputList }.getOrNull() ?: return listOf("TvInputManager refused the list")
        if (list.isEmpty()) return listOf("TvInputManager lists no inputs")
        return list.map { i ->
            val state = runCatching { tim.getInputState(i.id) }.getOrDefault(-1)
            val label = runCatching { i.loadLabel(context).toString() }.getOrDefault("?")
            "${i.id} type=${inputType(i.type)} \"$label\" state=$state" +
                (i.parentId?.let { " parent=$it" } ?: "") + if (i.isPassthroughInput) " passthrough" else ""
        }
    }

    private fun inputType(type: Int) = when (type) {
        TvInputInfo.TYPE_TUNER -> "tuner"
        TvInputInfo.TYPE_HDMI -> "hdmi"
        TvInputInfo.TYPE_COMPOSITE -> "composite"
        TvInputInfo.TYPE_COMPONENT -> "component"
        TvInputInfo.TYPE_SVIDEO -> "svideo"
        TvInputInfo.TYPE_VGA -> "vga"
        TvInputInfo.TYPE_DISPLAY_PORT -> "dp"
        TvInputInfo.TYPE_DVI -> "dvi"
        else -> type.toString()
    }

    @Suppress("DEPRECATION")
    private fun screen(context: Context): String {
        val display = context.getSystemService<WindowManager>()?.defaultDisplay ?: return "-"
        val m = DisplayMetrics().also { display.getRealMetrics(it) }
        val modes = if (Build.VERSION.SDK_INT >= 23) display.supportedModes.joinToString { "${it.physicalWidth}x${it.physicalHeight}@${"%.0f".format(it.refreshRate)}" } else "-"
        return "${m.widthPixels}x${m.heightPixels} ${m.densityDpi}dpi ${"%.0f".format(display.refreshRate)}Hz; modes $modes"
    }

    private fun memory(context: Context): String {
        val am = context.getSystemService<ActivityManager>() ?: return "-"
        val info = ActivityManager.MemoryInfo().also { am.getMemoryInfo(it) }
        return "${info.totalMem / (1 shl 20)} MB, low-RAM device=${am.isLowRamDevice}"
    }

    @Suppress("DEPRECATION")
    private fun installer(context: Context): String? = runCatching {
        if (Build.VERSION.SDK_INT >= 30) context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
        else context.packageManager.getInstallerPackageName(context.packageName)
    }.getOrNull()

    private fun restrictedSettings(context: Context): String {
        if (Build.VERSION.SDK_INT < 33) return ""
        val source = runCatching { context.packageManager.getInstallSourceInfo(context.packageName).packageSource }.getOrNull()
        return " package_source=${source ?: "unknown"}"
    }

    private fun granted(context: Context, permission: String) =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private fun secure(context: Context, key: String) = runCatching { Settings.Secure.getString(context.contentResolver, key) }.getOrElse { "unreadable" }
    private fun global(context: Context, key: String) = runCatching { Settings.Global.getString(context.contentResolver, key) }.getOrElse { "unreadable" }
    private fun system(context: Context, key: String) = runCatching { Settings.System.getString(context.contentResolver, key) }.getOrElse { "unreadable" }

    private fun prop(key: String): String? = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, key) as String
    }.getOrNull()

    private fun logcat(): List<String> = runCatching {
        val args = listOf("logcat", "-d", "-v", "time", "-t", "400") + LOG_TAGS.map { "$it:V" } + "*:S"
        val p = ProcessBuilder(args).redirectErrorStream(true).start()
        val lines = p.inputStream.bufferedReader().use { it.readLines() }
        p.destroy()
        lines.takeLast(200)
    }.getOrElse { listOf("logcat unreadable: $it") }

    private val IPV4 = Regex("""(?<![\w.])(\d{1,3}\.){3}\d{1,3}(?![\w.])""")
    private val MAC = Regex("""(?i)\b([0-9a-f]{2}:){5}[0-9a-f]{2}\b""")
    private val PHONE_PAGE = Regex(""":${PhoneServer.PORT}/\w+""")

    fun mask(line: String): String = line
        .replace(PHONE_PAGE, ":${PhoneServer.PORT}/<token>")
        .replace(IPV4, "<ip>")
        .replace(MAC, "<mac>")
}
