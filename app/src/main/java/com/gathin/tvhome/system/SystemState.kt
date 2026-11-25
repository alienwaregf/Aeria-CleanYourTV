package com.gathin.tvhome.system

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.tv.TvContract
import android.media.tv.TvInputInfo
import android.media.tv.TvInputManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.getSystemService
import com.gathin.tvhome.BuildConfig
import com.gathin.tvhome.R
import com.gathin.tvhome.guard.HomeGuardService

enum class NetKind { Wifi, Ethernet, Offline, Other }

fun ConnectivityManager.currentNetwork(): Network? {
    if (Build.VERSION.SDK_INT >= 23) return activeNetwork
    @Suppress("DEPRECATION")
    val type = activeNetworkInfo?.takeIf { it.isConnected }?.type ?: return null
    @Suppress("DEPRECATION")
    return allNetworks.firstOrNull { getNetworkInfo(it)?.type == type }
}

data class NetworkStatus(val kind: NetKind = NetKind.Offline, val name: String? = null, val level: Float = 1f, val vpn: Boolean = false)

data class VolumeStatus(val level: Int = 0, val max: Int = 100, val muted: Boolean = false, val external: Boolean = false) {
    val fraction: Float get() = if (max == 0) 0f else level / max.toFloat()
}

enum class SourceKind { Hdmi, DisplayPort, Tuner, Av, Usb, Other }

data class TvSource(
    val id: String,
    val label: String,
    val kind: SourceKind,
    val connected: Boolean,
    val deviceName: String?,
    val intent: Intent,
    val hardware: Int? = null,
    val firmwareLabel: String? = null,
    val hidden: Boolean = false,
) {
    val xiaomiInput: Int? get() = intent.takeIf { it.component?.packageName == SystemProbe.XIAOMI_PLAYER && it.hasExtra("input") }?.getIntExtra("input", 0)
}

enum class HomeStatus { Default, Guarded, NotDefault }

object SystemProbe {

    fun network(context: Context): NetworkStatus {
        val cm = context.getSystemService<ConnectivityManager>() ?: return NetworkStatus()
        var caps = cm.getNetworkCapabilities(cm.currentNetwork() ?: return NetworkStatus()) ?: return NetworkStatus()
        @Suppress("DEPRECATION")
        val vpn = caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) || runCatching {
            cm.allNetworks.any { cm.getNetworkCapabilities(it)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true && cm.getNetworkInfo(it)?.isConnected == true }
        }.getOrDefault(false)
        if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
        ) {
            @Suppress("DEPRECATION")
            val physicalNetworks = runCatching { cm.allNetworks }.getOrDefault(emptyArray()).mapNotNull { network ->
                runCatching {
                    cm.getNetworkCapabilities(network)?.takeIf {
                        !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                            (it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) || it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) &&
                            cm.getNetworkInfo(network)?.isConnected == true
                    }
                }.getOrNull()
            }
            caps = physicalNetworks.firstOrNull { it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) }
                ?: physicalNetworks.firstOrNull() ?: caps
        }
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkStatus(NetKind.Ethernet, vpn = vpn)
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> {
                val wm = context.applicationContext.getSystemService<WifiManager>()
                @Suppress("DEPRECATION")
                val info = wm?.connectionInfo
                val ssid = info?.ssid?.trim('"')?.takeUnless { it.isBlank() || it == WifiManager.UNKNOWN_SSID.trim('<', '>') || it == "<unknown ssid>" }
                val level = if (info == null) 1f else if (Build.VERSION.SDK_INT >= 30) {
                    wm.calculateSignalLevel(info.rssi) / wm.maxSignalLevel.coerceAtLeast(1).toFloat()
                } else {
                    @Suppress("DEPRECATION")
                    WifiManager.calculateSignalLevel(info.rssi, 4) / 3f
                }
                NetworkStatus(NetKind.Wifi, ssid, level, vpn)
            }
            else -> NetworkStatus(NetKind.Other, vpn = vpn)
        }
    }

    fun volume(context: Context): VolumeStatus {
        val am = context.getSystemService<AudioManager>() ?: return VolumeStatus()
        return VolumeStatus(
            level = am.getStreamVolume(AudioManager.STREAM_MUSIC),
            max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC),
            muted = if (Build.VERSION.SDK_INT >= 23) am.isStreamMute(AudioManager.STREAM_MUSIC) else legacyMuted,
            external = externalVolume,
        )
    }

    private var stuckSteps = 0

    @Volatile private var externalVolume = false

    internal fun stuckStep(before: Int, after: Int, max: Int, muted: Boolean, direction: Int): Boolean =
        before == after && !muted && (if (direction > 0) before < max else before > 0)

    private var legacyMuted = false

    private fun volumeFlags(context: Context, fromKey: Boolean, showUi: Boolean = false) =
        if (showUi || (fromKey && isInstalled(context, "com.tcl.settings"))) AudioManager.FLAG_SHOW_UI else 0

    fun adjustVolume(context: Context, direction: Int, fromKey: Boolean = false, showUi: Boolean = false) {
        val am = context.getSystemService<AudioManager>() ?: return
        val flags = volumeFlags(context, fromKey, showUi)
        val before = runCatching { am.getStreamVolume(AudioManager.STREAM_MUSIC) }.getOrNull()
        val stepped = stepVolume(am, direction, flags)
        if (!stepped) {
            stuckSteps = 0
            return
        }
        if (before != null) runCatching {
            val after = am.getStreamVolume(AudioManager.STREAM_MUSIC)
            val muted = Build.VERSION.SDK_INT >= 23 && am.isStreamMute(AudioManager.STREAM_MUSIC)
            stuckSteps = if (stuckStep(before, after, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), muted, direction)) stuckSteps + 1 else 0
            val external = stuckSteps >= 2 || (externalVolume && before == after)
            if (external != externalVolume) DiagLog.i("volume: steps ${if (external) "go to a sound system; the level stays at $after" else "move the level again"}")
            externalVolume = external
        }
    }

    private fun stepVolume(am: AudioManager, direction: Int, flags: Int): Boolean {
        try {
            am.adjustStreamVolume(
                AudioManager.STREAM_MUSIC,
                if (direction > 0) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER,
                flags,
            )
            return true
        } catch (e: SecurityException) {
            return runCatching {
                val level = am.getStreamVolume(AudioManager.STREAM_MUSIC) + if (direction > 0) 1 else -1
                am.setStreamVolume(AudioManager.STREAM_MUSIC, level.coerceIn(0, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)), flags)
            }.isSuccess
        }
    }

    fun toggleMute(context: Context) {
        val am = context.getSystemService<AudioManager>() ?: return
        if (Build.VERSION.SDK_INT >= 23) {
            runCatching { am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_TOGGLE_MUTE, volumeFlags(context, fromKey = false)) }
        } else {
            legacyMuted = !legacyMuted
            @Suppress("DEPRECATION")
            am.setStreamMute(AudioManager.STREAM_MUSIC, legacyMuted)
        }
    }

    fun homeStatus(context: Context): HomeStatus {
        val resolved = context.packageManager.resolveActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME),
            PackageManager.MATCH_DEFAULT_ONLY,
        )?.activityInfo?.packageName
        return when {
            resolved == context.packageName -> HomeStatus.Default
            HomeGuardService.isRunning -> HomeStatus.Guarded
            else -> HomeStatus.NotDefault
        }
    }


    fun isSettingsApp(context: Context, pkg: String): Boolean =
        pkg in SETTINGS_PACKAGES ||
            context.packageManager.resolveActivity(Intent(Settings.ACTION_SETTINGS), 0)?.activityInfo?.packageName == pkg

    private val SETTINGS_PACKAGES = setOf("com.android.settings", "com.android.tv.settings", "com.xiaomi.mitv.settings", "com.tcl.settings", "com.helios.setting", "com.haier.tv.menusetting", SKYWORTH_DIGITAL_SETTINGS, BAOFENG_SETTINGS, ChanghongSettings.PACKAGE)

    /**
     * Skyworth Digital's settings, the settings app of the boxes it builds: Tencent Aurora Box 7
     * (BA102, Amlogic, Android 14) answers ACTION_SETTINGS with AOSP's TV Settings, which lack the
     * box's wired-network page (issues #1597 #1614, both reporters named the package; the main and
     * Ethernet activities from the second's adb).
     */
    private const val SKYWORTH_DIGITAL_SETTINGS = "com.skyworthdigital.settings"

    /**
     * Baofeng TV's settings (AML_T962, Android 9): nothing on the firmware answers ACTION_SETTINGS,
     * and its network page is a fragment inside this activity (issue #1339, opened with am start
     * by the reporter).
     */
    private const val BAOFENG_SETTINGS = "com.baofengtv.settings"

    fun settingsTileIntent(context: Context, pkg: String): Intent? =
        if (pkg in setOf("com.android.settings", "com.android.tv.settings", ChanghongSettings.PACKAGE)) {
            ChanghongSettings.intent(context)
        } else null

    fun settingsIntent(context: Context): Intent =
        ChanghongSettings.intent(context)
            ?: fengOsSettingsIntent(context)
            ?: xgimiSettingsIntent(context, "Settings://com.xgimi.misckey.main/mainPage")
            ?: konkaSettingsIntent(context)
            ?: settingsActivityIntent(context)

    private val KONKA_SETTINGS = ComponentName("com.konka.tvsettings", "com.konka.service.SettingService")

    private val XIAOMI_SETTINGS_POPUP = Intent("com.xiaomi.intent.action.show.globalsetting").setPackage("com.xiaomi.mitv.settings")

    fun settingsFallbacks(context: Context): List<Intent> =
        listOfNotNull(
            XIAOMI_SETTINGS_POPUP.takeIf { i ->
                runCatching { context.packageManager.queryBroadcastReceivers(i, 0).any { it.activityInfo.exported } }.getOrDefault(false)
            }?.let(::Intent),
        )

    private fun konkaSettingsIntent(context: Context): Intent? {
        val intent = Intent("com.konka.settings.action.SETTING_MAIN_VIEW").setComponent(KONKA_SETTINGS)
        return intent.takeIf { context.packageManager.resolveService(it, 0)?.serviceInfo?.exported == true }
    }

    private fun settingsActivityIntent(context: Context): Intent =
        firstResolvable(
            context,
            Intent("com.xiaomi.mitv.settings.SETTINGS").setComponent(ComponentName("com.xiaomi.mitv.settings", "com.xiaomi.mitv.settings.entry.MainActivity")),
            Intent(Intent.ACTION_MAIN).setComponent(ComponentName("com.haier.tv.menusetting", "com.haier.tv.menusetting.RootActivity")),
            Intent(Intent.ACTION_MAIN).setComponent(ComponentName("com.konka.tvsettings", "com.konka.settingcenter.settings.view.MainSettingsActivity")),
            Intent(Settings.ACTION_SETTINGS).setComponent(ComponentName("com.android.tv.settings", "com.hisense.systemsettings.MainActivity")),
            // LeTV Max4-55 (MStar, Android 6, Dangbei's slimmed firmware): ACTION_SETTINGS lands in
            // AOSP's empty MainSettings; LeTV's own settings have no launcher entry and answer no
            // settings action (the reporter opened them by component, issue #1528).
            Intent(Intent.ACTION_MAIN).setComponent(ComponentName("com.stv.globalsetting", "com.stv.globalsetting.activity.SettingActivity")),
            // Tencent Aurora Box 7 (issues #1597 #1614): see SKYWORTH_DIGITAL_SETTINGS.
            Intent(Intent.ACTION_MAIN).setComponent(ComponentName(SKYWORTH_DIGITAL_SETTINGS, "$SKYWORTH_DIGITAL_SETTINGS.activity.MainActivity")),
            // Baofeng AML_T962 (issue #1339): see BAOFENG_SETTINGS.
            Intent(Intent.ACTION_MAIN).setComponent(ComponentName(BAOFENG_SETTINGS, "$BAOFENG_SETTINGS.MainActivity")),
            Intent(Settings.ACTION_SETTINGS),
            Intent(Intent.ACTION_MAIN).setComponent(ComponentName("com.konka.systemsetting", "com.konka.systemsetting.MainActivity")),
            Intent(Intent.ACTION_MAIN).setComponent(ComponentName(
                "com.android.settings", "com.asu.settings.TvMainActivity")),
            Intent(Intent.ACTION_MAIN).setComponent(ComponentName("com.tcl.settings", "com.tcl.settings.ui.MainActivity")),
            // TCL 50P8 (sugarcane_mi, MStar m7221, Android 8): neither of those; its settings app's
            // main activity sits in the package root (issue #1405, the reporter's dumpsys).
            Intent(Intent.ACTION_MAIN).setComponent(ComponentName("com.tcl.settings", "com.tcl.settings.MainActivity")),
            Intent(Intent.ACTION_MAIN).setComponent(ComponentName("com.pptv.setting", "com.pptv.setting.root.SettingsActivity")),
            Intent(Intent.ACTION_VIEW, Uri.parse("Settings://com.xgimi.misckey.main/mainPage")),
            // Huawei smart screen V65 (HEGE-560, honghu818, Android 10) neither; its settings app
            // takes tvsetting:// links (issue #1394, the reporter's dumpsys package).
            Intent(Intent.ACTION_VIEW, Uri.parse("tvsetting://com.huawei.homevision.settings/main")),
            WHALEY_SETTINGS,
        )

    private val FENGOS_SETTINGS = ComponentName("cn.formovie.fengos.settings", "cn.formovie.fengos.settings.ui.activity.MainActivity")

    private const val FENGMI_LAUNCHER = "com.tvfengmi.launcher"

    @Volatile
    private var fengOs: Boolean? = null

    fun hidesBrightness(context: Context): Boolean =
        fengOs ?: (fengOsSettingsIntent(context) != null || isInstalled(context, FENGMI_LAUNCHER)).also { fengOs = it }

    private fun fengOsSettingsIntent(context: Context): Intent? =
        resolvable(context, Intent(Intent.ACTION_MAIN).setComponent(FENGOS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

    /**
     * FengOS's own network page is the floating panel its settings open with the Wi-Fi action; the
     * AOSP NetworkActivity, which networkSettingsActivityIntents tries first, is a leftover there
     * (Q3 Pro, issue #1438). Only where FengOS's settings panel is.
     */
    private fun fengOsNetworkIntent(context: Context): Intent? =
        if (fengOsSettingsIntent(context) == null) null else resolvable(context, Intent(Settings.ACTION_WIFI_SETTINGS))

    fun guardSettingsIntent(context: Context): Intent? = resolvable(context, Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))

    fun homeSettingsIntent(context: Context): Intent? =
        if (BuildConfig.FLAVOR == "standard") resolvable(context, Intent(Settings.ACTION_HOME_SETTINGS)) else null

    fun revivalSettingsIntent(context: Context): Intent? =
        resolvable(context, Intent("android.settings.ACTION_NOTIFICATION_LISTENER_SETTINGS"))

    private fun resolvable(context: Context, intent: Intent): Intent? =
        intent.takeIf { startable(context, it) }?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private fun startable(context: Context, intent: Intent): Boolean {
        val info = context.packageManager.resolveActivity(intent, 0)?.activityInfo ?: return false
        return info.exported && info.enabled && info.applicationInfo.enabled &&
            (info.permission == null || ContextCompat.checkSelfPermission(context, info.permission) == PackageManager.PERMISSION_GRANTED)
    }

    fun networkSettingsIntents(context: Context, ethernet: Boolean = false): List<Intent> =
        listOfNotNull(fengOsNetworkIntent(context), ChanghongSettings.intent(context, ChanghongSettings.NETWORK), xgimiSettingsIntent(context, XGIMI_NETWORK_PAGE)) +
            networkSettingsActivityIntents(context, ethernet)

    private fun networkSettingsActivityIntents(context: Context, ethernet: Boolean = false): List<Intent> {
        val wired = listOfNotNull(
            Intent("com.xiaomi.mitv.settings.ETHERNET_SETTINGS").setPackage("com.xiaomi.mitv.settings").takeIf { ethernet },
            // Tencent Aurora Box 7 (issue #1614): its wired page, with DHCP or static, gateway and
            // DNS, is only in Skyworth Digital's settings; AOSP's network page has none of it.
            Intent(Intent.ACTION_MAIN).setComponent(ComponentName(SKYWORTH_DIGITAL_SETTINGS, "$SKYWORTH_DIGITAL_SETTINGS.activity.EthernetSettingActivity")).takeIf { ethernet },
        )
        return allResolvable(
            context,
            // LeTV X3-50 (EUI, Android 5.0.1): its own network page, action
            // com.letv.setting.NETWORK, exported and verified on the TV.
            Intent("com.letv.setting.NETWORK").setComponent(ComponentName("com.stv.globalsetting", "com.stv.globalsetting.activity.NetworkActivity")),
            *listOfNotNull(hisenseNetworkSettingsIntent(context)).toTypedArray(),
            *wired.toTypedArray(),
            Intent("com.xiaomi.mitv.settings.WIFI_SETTINGS").setComponent(ComponentName("com.xiaomi.mitv.settings", "com.xiaomi.mitv.settings.wifi.MitvWifiActivity")),
            Intent(Intent.ACTION_MAIN).setComponent(ComponentName("com.android.tv.settings", "com.android.tv.settings.connectivity.NetworkActivity")),
            Intent(Settings.ACTION_WIFI_SETTINGS),
            Intent("com.pptv.intent.action.WIFI_ACTIVITY").setComponent(ComponentName("com.pptv.setting", "com.pptv.setting.wifi.WifiWorkActivity")),
            Intent(Intent.ACTION_VIEW, Uri.parse("Settings://com.xgimi.settings.net/wifi_misckey")),
            // Huawei V65 (issue #1394): see settingsActivityIntent.
            Intent(Intent.ACTION_VIEW, Uri.parse("tvsetting://com.huawei.homevision.settings/network")),
            // Baofeng AML_T962 (issue #1339): the network page is inside its settings.
            Intent(Intent.ACTION_MAIN).setComponent(ComponentName(BAOFENG_SETTINGS, "$BAOFENG_SETTINGS.MainActivity")),
            Intent(Settings.ACTION_SETTINGS),
            Intent(Intent.ACTION_MAIN).setComponent(ComponentName("com.tcl.settings", "com.tcl.settings.ui.MainActivity")),
            // TCL 50P8 (sugarcane_mi, Android 8) answers only its own Wi-Fi action (issue #1405).
            Intent("android.intent.action.WIFISETTING").setPackage("com.tcl.settings"),
            Intent(Intent.ACTION_MAIN).setComponent(ComponentName("com.tcl.settings", "com.tcl.settings.MainActivity")),
            WHALEY_SETTINGS,
        )
    }

    private fun hisenseNetworkSettingsIntent(context: Context): Intent? {
        val intent = Intent("android.settings.NETWORK_SETTINGS").setComponent(
            ComponentName("com.android.tv.settings", "com.hisense.systemsettings.MainActivity"),
        )
        val info = runCatching { context.packageManager.resolveActivity(intent, 0)?.activityInfo }.getOrNull()
        return intent.takeIf { info != null && info.exported && info.enabled && info.applicationInfo.enabled }
    }

    private val XGIMI_SETTINGS = ComponentName("com.android.newsettings", "com.xgimi.newsettings.SettingService")
    private const val XGIMI_NETWORK_PAGE = "Settings://com.xgimi.settings.net/wifi_misckey"

    private const val XGIMI_DLP_LUMENS_MAX = 10

    fun xgimiBrightnessLevel(): Float? = runCatching {
        val dm = xgimiDisplayManager() ?: return@runCatching null
        val get = xgimiDisplayManagerClass()?.getMethod("getDlpLumensLevel") ?: return@runCatching null
        val raw = (get.invoke(dm) as? Number)?.toInt() ?: return@runCatching null
        ((raw and 0xFF) / XGIMI_DLP_LUMENS_MAX.toFloat()).coerceIn(0f, 1f)
    }.getOrNull()

    private val xgimiDisplayManagerClassOnce by lazy {
        runCatching { Class.forName("com.xgimi.gmpf.api.DisplayManager") }.getOrNull()
    }
    private fun xgimiDisplayManagerClass(): Class<*>? = xgimiDisplayManagerClassOnce

    internal fun xgimiDisplayManager(): Any? {
        val c = xgimiDisplayManagerClass() ?: return null
        return runCatching { c.getMethod("getInstance").invoke(null) }.getOrNull()
            ?: runCatching { c.getDeclaredConstructor().also { it.isAccessible = true }.newInstance() }.getOrNull()
    }

    fun setXgimiBrightnessLevel(level: Int): Boolean {
        val c = xgimiDisplayManagerClass() ?: return false
        val dm = xgimiDisplayManager() ?: return false
        if (XgimiBoost.enabled() == true && !XgimiBoost.disable()) return false
        val target = level.coerceIn(1, XGIMI_DLP_LUMENS_MAX)
        return runCatching { c.getMethod("setDlpLumensLevel", java.lang.Byte.TYPE).invoke(dm, target.toByte()) }.isSuccess
    }

    val xgimiBrightnessMax: Int get() = XGIMI_DLP_LUMENS_MAX

    val hasXgimiBrightness: Boolean get() = xgimiBrightnessLevel() != null

    private fun xgimiSettingsIntent(context: Context, page: String): Intent? {
        val intent = Intent("com.xgimi.settings.SETTINGS").setComponent(XGIMI_SETTINGS)
            .putExtra("data", page)
            .putExtra("packageName", "com.xgimi.misckey")
            .putExtra("unlocked", false)
        return intent.takeIf { context.packageManager.resolveService(it, 0)?.serviceInfo?.exported == true }
    }

    fun startSettingsIntent(context: Context, intent: Intent) {
        if (HisenseSources.open(context, intent)) return
        if (intent.component == ChanghongSettings.component) {
            if (ChanghongSettings.open(context, intent)) return
            val fallback = if (intent.getBooleanExtra(ChanghongSettings.NETWORK, false)) {
                networkSettingsActivityIntents(context).first()
            } else {
                settingsActivityIntent(context)
            }
            context.startActivity(settingsLaunchIntent(context, fallback))
            return
        }
        if (intent.action == XIAOMI_SETTINGS_POPUP.action && intent.`package` == XIAOMI_SETTINGS_POPUP.`package`) {
            context.sendBroadcast(intent)
            return
        }
        if (intent.component == KONKA_SETTINGS) {
            if (runCatching { context.startService(intent) }.getOrNull() != null) return
            context.startActivity(settingsLaunchIntent(context, settingsActivityIntent(context)))
            return
        }
        val activity = if (intent.component == XGIMI_SETTINGS) {
            val shown = runCatching { context.startService(intent) }.getOrNull() != null
            if (shown) return
            if (intent.getStringExtra("data") == XGIMI_NETWORK_PAGE) {
                networkSettingsActivityIntents(context).first()
            } else {
                settingsActivityIntent(context)
            }
        } else intent
        context.startActivity(settingsLaunchIntent(context, activity))
    }

    private val WHALEY_SETTINGS
        get() = Intent(Intent.ACTION_MAIN).setComponent(ComponentName("com.helios.setting", "com.helios.activity.StartActivity"))

    private val SKYWORTH_PICTURE_PANEL = ComponentName(
        "com.skyworth.setting",
        "com.skyworth.setting.service.HalfScreenService",
    )

    private val TIANCI_PICTURE = ComponentName("com.tianci.setting", "com.tianci.setting.activity.PictureSoundSetting")

    fun skyworthPicturePanel(context: Context): Boolean =
        runCatching { context.packageManager.getServiceInfo(SKYWORTH_PICTURE_PANEL, 0) }.isSuccess

    private fun tclSettingsWindow(): Intent =
        Intent("com.tcl.settings.SHOW_WINDOW").setPackage("com.tcl.settings")
            .putExtra("Type", "Settings")
            .putExtra("flag", "1")

    fun openPictureSettings(context: Context): Boolean {
        ChanghongSettings.intent(context, ChanghongSettings.PICTURE)?.let {
            if (ChanghongSettings.open(context, it)) return true
        }
        val backlight = Intent("com.xiaomi.mitv.settings.BACKLIGHT_POPUP")
            .setComponent(ComponentName("com.xiaomi.mitv.settings", "com.xiaomi.mitv.settings.SettingsBroadcastReceiver"))
        if (context.packageManager.queryBroadcastReceivers(backlight, 0).any { it.activityInfo.exported }) {
            val shown = runCatching { context.sendBroadcast(backlight) }.isSuccess
            if (shown) return true
        }
        if (isInstalled(context, "com.tcl.settings")) {
            val activityShown = runCatching {
                val intent = Intent(Settings.ACTION_DISPLAY_SETTINGS)
                    .setComponent(ComponentName("com.tcl.settings", "com.tcl.settings.PictureActivity"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }.isSuccess
            if (activityShown) return true

            val shown = runCatching { context.startService(tclSettingsWindow()) }.getOrNull() != null
            if (shown) return true
        }
        if (skyworthPicturePanel(context)) {
            val shown = runCatching {
                context.startService(
                    Intent().setComponent(SKYWORTH_PICTURE_PANEL)
                        .putExtra("Key", "android.settings.PICTURE_SETTINGS"),
                )
            }.getOrNull() != null
            if (shown) return true
        }
        return runCatching {
            context.startActivity(
                firstResolvable(
                    context,
                    Intent(Intent.ACTION_MAIN).setComponent(TIANCI_PICTURE),
                    Intent("android.settings.IMAGE_SETTINGS"),
                    // LeTV: its own settings have the picture. Zero65 MAX5-65 (EUI, Android 6)
                    // answers the display action with AOSP's display page, which crashes at its
                    // brightness test (issue #1567, the reporter's agent opened both); X3-50
                    // (Android 5.0.1) has no AOSP settings and LeTV's DisplayActivity is not
                    // exported (PR #1540, its author on the TV). Only where it is installed.
                    Intent(Intent.ACTION_MAIN).setComponent(ComponentName("com.stv.globalsetting", "com.stv.globalsetting.activity.SettingActivity")),
                    Intent(Settings.ACTION_DISPLAY_SETTINGS),
                    Intent("com.pptv.intent.action.PICTUREAUDIO_ACTIVITY")
                        .setComponent(ComponentName("com.pptv.setting", "com.pptv.setting.media.PictureAudioSettingActivity")),
                    settingsActivityIntent(context),
                ),
            )
        }.isSuccess
    }

    private const val XGIMI_SOURCE_PAGE = "Settings://com.xgimi.settings.signalSource/hdmi_misckey"

    /**
     * LeTV X3-50: prime DesktopManager's enabled-desktop tags before launching
     * SignalSourceManager, mirroring Dangbei's reflection workaround (decompiled
     * com.dangbei.leradlauncher.rom.util.k / SignalSourceUtil.a()). Without this,
     * SignalSourceManager's SourceBase.enableSignalDesktop() iterates
     * DesktopManager.getEnabledDesktopTags() and crashes with NPE — either the
     * array is null (never set, SourceBase.java:1300) or it contains a null
     * element (the stock deskplatform's DataModel writes an array with a null
     * slot when findPackage returns -1, SourceBase.java:1301). Dangbei calls
     * letv.desktop.DesktopManager.setEnabledDesktopTags(["signal"]) first so
     * getEnabledDesktopTags() returns ["signal"] and enableSignalDesktop()
     * succeeds. This lives inside [openInputPicker] (not in the TV_INPUT key
     * handler) so every caller — the upstream key handler, the source tile, the Control
     * Center inputs button — primes before launching, exactly as Dangbei's
     * only LeTV entry point always primes first. Safe to call even if
     * DesktopManager is absent: catches and logs, then proceeds. `set` is
     * last-writer-wins, so the tags are read back once and a mismatch is
     * logged (deskplatform rewrites them on plugin refresh).
     */
    private fun primeDesktopTags(): Boolean = runCatching {
        val managerClass = Class.forName("letv.desktop.DesktopManager")
        val manager = managerClass.getMethod("get").invoke(null)
        managerClass.getMethod("setEnabledDesktopTags", Array<String>::class.java)
            .invoke(manager, arrayOf("signal") as Any)
        val readBack = runCatching {
            managerClass.getMethod("getEnabledDesktopTags").invoke(manager) as? Array<*>
        }.getOrNull()
        val ok = readBack != null && readBack.size == 1 && readBack[0] == "signal"
        if (ok) DiagLog.i("primed DesktopManager tags=[signal]")
        else DiagLog.i("primed tags read-back mismatch: ${readBack?.joinToString() ?: "null"}")
        ok
    }.onFailure {
        DiagLog.i("prime DesktopManager tags failed: ${it.javaClass.simpleName}: ${it.message}")
    }.getOrDefault(false)

    /**
     * Opens the TV's own input picker, for TVs whose inputs Android does not list. TCL MT5879 keeps
     * its inputs in its own middleware (TvInputManager has none) and its remote's input key opens a
     * side panel: SystemUI's status bar service where the firmware has the custom source panel,
     * TCL settings' otherwise (TclShortCutKeyManager.startTVInputService).
     */
    private val XGIMI_PLAYER = ComponentName("com.xgimi.xhplayer", "com.xgimi.xhplayer.InputActivity")
    private val XGIMI_HDMI_INPUT = Regex("""^com\.xgimi\.tvinput/\.inputservice\.Hdmi(\d)TvInputService/HW\d+$""")

    /**
     * XGIMI GMUI on Android 14 (RS20 Pro Max, DOHKO): the passthrough intent of an HDMI input has
     * no taker, and the source page [openInputPicker] opens asks for the port again, always on
     * HDMI 1, wherever two are plugged in. Its player switches straight to a port named in "sw"
     * (HDMI1, HDMI2), the field of GimiConfigManager's signal list (issue #1483, the reporter's adb;
     * the "launcher_start_source" extra of Android 8, XgimiMstarSources, does nothing there). Only
     * from Android 14, the one version it was tried on. False when it does not apply.
     */
    fun openXgimiInput(context: Context, passthrough: Intent): Boolean {
        if (Build.VERSION.SDK_INT < 34 || passthrough.data?.host != "android.media.tv") return false
        val id = passthrough.data?.lastPathSegment ?: return false
        val port = XGIMI_HDMI_INPUT.matchEntire(id)?.groupValues?.get(1) ?: return false
        val info = runCatching { context.packageManager.getActivityInfo(XGIMI_PLAYER, 0) }.getOrNull() ?: return false
        if (!info.exported || !info.enabled || info.permission != null) return false
        val intent = Intent("com.xgimi.action.hdmiPlayer").setComponent(XGIMI_PLAYER)
            .putExtra("sw", "HDMI$port").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { context.startActivity(intent) }.isSuccess
    }

    fun openInputPicker(context: Context): Boolean {
        val pm = context.packageManager
        changhongInputPanel(context)?.let { intent ->
            if (runCatching { context.startService(intent) }.getOrNull() != null) return true
        }
        xgimiSettingsIntent(context, XGIMI_SOURCE_PAGE)?.let { intent ->
            if (runCatching { context.startService(intent) }.getOrNull() != null) return true
        }
        val hisense = Intent("com.hisense.tv.start_inputs")
            .setComponent(ComponentName("com.hisense.tv.inputs", "com.hisense.tv.InputsUIService"))
        if (pm.resolveService(hisense, 0) != null) {
            val shown = runCatching {
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(hisense) else context.startService(hisense)
            }.getOrNull() != null
            if (shown) return true
        }
        tclInputPanel(context)?.let { intent ->
            if (runCatching { context.startService(intent) }.getOrNull() != null) return true
        }
        val coocaa = Intent("coocaa.intent.action.SOURCE_DIALOG").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { pm.resolveActivity(coocaa, 0) }.getOrNull() != null &&
            runCatching { context.startActivity(coocaa) }.isSuccess
        ) return true
        val aigc = Intent("coocaa.intent.aigc.home.tvsource").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { pm.resolveActivity(aigc, 0) }.getOrNull() != null &&
            runCatching { context.startActivity(aigc) }.isSuccess
        ) return true
        val skyMovie = Intent("com.coocaa.action.SOURCE")
            .setComponent(ComponentName("com.tianci.movieplatform", "com.ccos.cooui.homepage.receiver.LaunchSourceReceiver"))
        if (runCatching { pm.queryBroadcastReceivers(skyMovie, 0) }.getOrNull().orEmpty().any { it.activityInfo.exported }) {
            return runCatching { context.sendBroadcast(skyMovie) }.isSuccess
        }
        // Dangbei DBF6 (Huanglong, Android 12): its own chooser, which switches by itself when only
        // one port has a signal (issue #1477, the reporter opened it with adb).
        val dangbei = Intent(DANGBEI_INPUT_CHOOSER).setPackage(HUANGLONG_TVUI).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { pm.resolveActivity(dangbei, 0) }.getOrNull()?.activityInfo?.exported == true &&
            runCatching { context.startActivity(dangbei) }.isSuccess
        ) return true
        // LeTV eUI (X3-55, MStar, Android 5.0.1) has no tv_input service at all; its signal source
        // app switches to the input last used, and takes no port (issues #818 #998: both reporters
        // opened it with adb, sourceId ignored). The remote's input key picks another from there.
        // Prime the desktop tags first (see primeDesktopTags) so enableSignalDesktop()
        // does not NPE, then launch it the way Dangbei does — no action,
        // NEW_TASK | CLEAR_TASK — so each launch is a fresh task, not a leftover
        // crashed one.
        primeDesktopTags()
        val letv = Intent().setComponent(LETV_SOURCES)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (runCatching { pm.getActivityInfo(LETV_SOURCES, 0) }.getOrNull()?.exported == true &&
            runCatching { context.startActivity(letv) }.isSuccess
        ) return true
        val sharp = Intent(Intent.ACTION_MAIN).setComponent(FOXCONN_SOURCES).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { pm.getActivityInfo(FOXCONN_SOURCES, 0) }.getOrNull()?.exported == true &&
            runCatching { context.startActivity(sharp) }.isSuccess
        ) return true
        // Skyworth 42X5 (8S40, Android 4.4.4, Aerial Lite's issue #1419): the framework turns the
        // remote's input key into a "com.android.sky.SendHotKey" broadcast that ccosservice's exported
        // HotKeyReceiver answers with the input panel. The key goes in an int extra "specialKey"
        // (the reporter read it from the firmware's PhoneWindowManager; sent so with am broadcast,
        // the panel came up; a KeyEvent in EXTRA_KEY_EVENT reached the receiver and was ignored).
        // Its HomeService below is there but ignores the key, so this goes first; sent only to an
        // exported receiver for it.
        val skyHotKey = Intent("com.android.sky.SendHotKey")
        runCatching { pm.queryBroadcastReceivers(skyHotKey, 0) }.getOrNull().orEmpty()
            .firstOrNull { it.activityInfo.exported && it.activityInfo.enabled }?.activityInfo?.let { r ->
                val key = Intent(skyHotKey).setComponent(ComponentName(r.packageName, r.name))
                    .putExtra("specialKey", android.view.KeyEvent.KEYCODE_TV_INPUT)
                if (runCatching { context.sendBroadcast(key) }.isSuccess) return true
            }
        // Baofeng TV (Android 4.4, Aerial Lite's issue #1225): the remote's source key is the
        // broadcast "baofengtv.action.SOURCE_SWITCH", which the system app BFTVSourceSwitch's
        // exported TVEventReceiver answers with its source overlay (read from the APK the reporter
        // sent). Only where that receiver is there and exported; not seen on a full-app Baofeng yet.
        val baofeng = Intent("baofengtv.action.SOURCE_SWITCH")
            .setComponent(ComponentName("com.baofengtv.sourceswitch", "com.baofengtv.sourceswitch.receiver.TVEventReceiver"))
        if (runCatching { pm.queryBroadcastReceivers(baofeng, 0) }.getOrNull().orEmpty().any { it.activityInfo.exported } &&
            runCatching { context.sendBroadcast(baofeng) }.isSuccess
        ) return true
        // Baofeng AML_T962 (Android 9) has no BFTVSourceSwitch; its source bar (HDMI 1-3, ATV,
        // DTMB, AV) is FUI's FRowActivity, which declares no intent filter and opens by component
        // (issue #1339, the reporter's am start). Only an exported one.
        val bftvRow = ComponentName("com.bftv.fui.biu", "com.bftv.fui.biu.views.activity.FRowActivity")
        if (runCatching { pm.getActivityInfo(bftvRow, 0) }.getOrNull()?.exported == true &&
            runCatching { context.startActivity(Intent(Intent.ACTION_MAIN).setComponent(bftvRow).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }.isSuccess
        ) return true
        val coocaaHome = ComponentName("com.tianci.movieplatform", "com.coocaa.homepage.vast.service.HomeService")
        if (runCatching { pm.getServiceInfo(coocaaHome, 0) }.getOrNull()?.exported == true) {
            val key = Intent("coocaa.intent.action.SOURCE_SERVICE").setComponent(coocaaHome)
                .putExtra(Intent.EXTRA_KEY_EVENT, android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_TV_INPUT))
            if (runCatching { context.startService(key) }.getOrNull() != null) return true
        }
        // MStar's tvsetting has the panel the remote's input key shows (Philips 75PUF6393/T3, TV838,
        // MStar maxim, Android 6: no inputs in TvInputManager, and SOURCE_INFO below is only a
        // one-line banner there; the reporter's agent opened this with adb, issue #1580). Before
        // SOURCE_INFO, only an exported one.
        val mstarList = Intent(Intent.ACTION_MAIN).setComponent(MSTAR_SOURCE_LIST).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { pm.getActivityInfo(MSTAR_SOURCE_LIST, 0) }.getOrNull()?.exported == true &&
            runCatching { context.startActivity(mstarList) }.isSuccess
        ) return true
        val mstar = Intent(MSTAR_SOURCE_INFO).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { pm.resolveActivity(mstar, 0)?.activityInfo }.getOrNull()?.exported == true &&
            runCatching { context.startActivity(mstar) }.isSuccess
        ) return true
        // HiSilicon YunOS (喜瑞 HS350, bigfish, Android 9): TvInputManager lists the inputs, but
        // nothing answers their passthrough intent; the system TVSource app's source list does
        // (the reporter's agent opened it with this link, issue #1527). Only an exported one.
        val yunos = Intent(Intent.ACTION_VIEW, Uri.parse("alliance://start_signal")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (runCatching { pm.resolveActivity(yunos, 0)?.activityInfo }.getOrNull()?.exported == true &&
            runCatching { context.startActivity(yunos) }.isSuccess
        ) return true
        return false
    }

    private const val MSTAR_SOURCE_INFO = "com.mstar.tv.tvplayer.ui.intent.action.SOURCE_INFO"
    private val LETV_SOURCES = ComponentName("com.stv.signalsourcemanager", "com.stv.signalsourcemanager.MainActivity")
    private val MSTAR_SOURCE_LIST = ComponentName("com.mstar.tvsetting", "com.mstar.tvsetting.hotkey.InputSourceListViewActivity")
    private val FOXCONN_SOURCES = ComponentName("com.mstar.tvsetting", "com.foxconn.etvg.inputsource.FxcInputSourceActitvity")

    private fun changhongInputPanel(context: Context): Intent? {
        val intent = Intent("CH_MENU_SOURCE")
            .setComponent(ComponentName("com.changhong.SourceMenu", "com.changhong.source.SourceMenuService"))
            .putExtra("MenuSource", "menuSource")
            .putExtra("keyCode", 2001)
        return intent.takeIf {
            runCatching {
                val info = context.packageManager.resolveService(it, 0)?.serviceInfo
                info != null && info.exported && info.enabled && info.applicationInfo.enabled &&
                    (info.permission.isNullOrEmpty() || context.checkCallingOrSelfPermission(info.permission) == PackageManager.PERMISSION_GRANTED)
            }.getOrDefault(false)
        }
    }

    fun tclInputPanel(context: Context): Intent? {
        val pm = context.packageManager
        val intent = if (pm.hasSystemFeature("com.tcl.software.systemui_custom_tv_source")) {
            Intent("com.tcl.tvstatusbar.action.SHOW_WINDOW").setPackage("com.android.systemui")
                .putExtra("TvStatusBar_Type", "Source")
                .putExtra("TvStatusBar_StartFrom", "input")
        } else {
            Intent("com.tcl.insrc.SHOW_WINDOW").setPackage("com.tcl.settings")
        }
        return intent.takeIf { runCatching { pm.resolveService(it, 0) }.getOrNull() != null }
    }

    /**
     * TCL's source manager on older Amlogic boards (TCL 55L2, "AOSP on p34a5", Android 5.1.1): the
     * firmware shows its 信源选择 window on the remote's input key with Aerial in front, and lets
     * the key through as well, so Aerial's Inputs page came up under it (issue #1612, the reporter's
     * dumpsys window: the focused window was com.tcl.sourcemananger, sic). Known by the package.
     */
    fun tclSourceManagerPanel(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo("com.tcl.sourcemananger", 0) }.isSuccess

    /**
     * TCL's Quick Panel on its Google TV sets (TCL G10 "Smart TV Pro", Android 12, STT2.230203.001):
     * on the remote's input key the firmware (TclShortCutKeyManager.startTVInputService) starts
     * com.tcl.suspension/.DismissService with action com.tcl.quickpanel.show.window and openInput=true,
     * whatever is in front, so Aerial's Inputs page came up under it. Only while the Quick Panel is
     * enabled: owners who disabled it get no firmware panel at all and keep Aerial's page.
     */
    fun tclQuickPanel(context: Context): Boolean = runCatching {
        val info = context.packageManager.getServiceInfo(
            ComponentName("com.tcl.suspension", "com.tcl.suspension.DismissService"),
            0,
        )
        info.enabled && info.applicationInfo.enabled
    }.getOrDefault(false)

    fun coocaaInputPanel(context: Context): Boolean = runCatching {
        context.packageManager.getServiceInfo(
            ComponentName(
                "com.coocaa.os.ccosservice",
                "com.coocaa.os.controlcenter.quicksettings.service.FeatureService",
            ),
            0,
        )
    }.isSuccess

    /**
     * Tianci's system service, which answers the remote's input key with the firmware's own source
     * page and lets the key through to Aerial too: both pages came up (Panasonic TH-55HX560C, a
     * Skyworth build, m7221, Android 8, issue #1468; older Coocaa show their SOURCE_DIALOG on the
     * same key, #519 #582).
     */
    fun tianciInputPanel(context: Context): Boolean = runCatching {
        context.packageManager.getPackageInfo("com.tianci.system", 0)
    }.isSuccess

    private fun firstResolvable(context: Context, vararg intents: Intent): Intent {
        return (intents.firstOrNull { startable(context, it) } ?: intents.last())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .let { settingsLaunchIntent(context, it) }
    }

    private fun allResolvable(context: Context, vararg intents: Intent): List<Intent> {
        val pm = context.packageManager
        val seen = HashSet<String>()
        return intents
            .filter { intent ->
                val info = pm.resolveActivity(intent, 0)?.activityInfo ?: return@filter false
                if (!startable(context, intent)) return@filter false
                seen.add("${info.packageName}/${info.name}")
            }
            .ifEmpty { listOf(intents.last()) }
            .map { settingsLaunchIntent(context, Intent(it).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }


    const val XIAOMI_PLAYER = "com.xiaomi.mitv.tvplayer"
    private const val DROIDLOGIC_PLAYER = "com.droidlogic.tvsource"

    private val GENERIC_CEC_NAME = Regex("^(Playback|Recorder|Tuner|Audio ?System|Audio|TV|Reserved)[ _]?\\d*$", RegexOption.IGNORE_CASE)

    fun sources(context: Context): List<TvSource> = frameworkSources(context)
        .let { listed -> (listed + xiaomiSources(context, listed)).sortedWith(compareBy({ it.kind.ordinal }, { it.label })) }
        .ifEmpty { XgimiMstarSources.load(context) }
        .ifEmpty { HisenseSources.load(context) }
        .ifEmpty { VidaaSiloSources.load(context) }
        .ifEmpty { KonkaSources.load(context) }
        .ifEmpty { tclSources(context) }
        .ifEmpty { whaleySources(context) }
        .ifEmpty { dangbeiHuanglongSources(context) }
        .ifEmpty { skyworthSources(context) } + listOfNotNull(usbSource(context))

    private fun usbSource(context: Context): TvSource? {
        val player = USB_PLAYERS.firstOrNull { runCatching { context.packageManager.getActivityInfo(it, 0) }.isSuccess } ?: return null
        return TvSource(
            id = USB_SOURCE_ID,
            label = "USB",
            kind = SourceKind.Usb,
            connected = false,
            deviceName = null,
            intent = Intent(Intent.ACTION_MAIN).setComponent(player)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED),
        )
    }

    const val USB_SOURCE_ID = "usb"

    private val USB_PLAYERS = listOf(
        ComponentName("com.xiaomi.mitv.mediaexplorer", "com.xiaomi.mitv.mediaexplorer.NewScraperMainEntryActivity"),
        ComponentName("com.tcl.ui_mediaCenter", "com.tcl.ui_mediaCenter.main.MainActivity"),
    )

    fun inputsToCheck(context: Context): List<TvSource> =
        frameworkSources(context, withHidden = true).filter { it.hardware != null }

    private fun frameworkSources(context: Context, withHidden: Boolean = false): List<TvSource> {
        val tim = context.getSystemService<TvInputManager>() ?: return emptyList()
        val infos = runCatching { tim.tvInputList }.getOrNull() ?: return emptyList()
        val xiaomi = isInstalled(context, XIAOMI_PLAYER)
        val tclPlayer = tclPlayer(context)
        val profile = DeviceProfile.current(context)

        return infos
            .filter { it.parentId == null && (it.isPassthroughInput || it.type == TvInputInfo.TYPE_TUNER && fromSystem(it)) }
            .filter { Build.VERSION.SDK_INT < 24 || runCatching { !it.isHidden(context) }.getOrDefault(true) }
            .filter { info -> withHidden || hw(info.id).let { it == null || it !in profile.hiddenHardware } }
            .mapNotNull { info ->
                val kind = when (info.type) {
                    TvInputInfo.TYPE_HDMI -> SourceKind.Hdmi
                    TvInputInfo.TYPE_DISPLAY_PORT -> SourceKind.DisplayPort
                    TvInputInfo.TYPE_TUNER -> SourceKind.Tuner
                    TvInputInfo.TYPE_COMPOSITE, TvInputInfo.TYPE_SVIDEO, TvInputInfo.TYPE_COMPONENT -> SourceKind.Av
                    else -> SourceKind.Other
                }
                val label = runCatching { info.loadLabel(context).toString() }.getOrNull()?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
                val state = runCatching { tim.getInputState(info.id) }.getOrDefault(TvInputManager.INPUT_STATE_DISCONNECTED)
                val cec = infos.firstOrNull { it.parentId == info.id }
                val generic = genericRoute(cec ?: info, kind)
                val intent = (if (xiaomi) xiaomiRoute(info, kind, label, profile)?.takeIf { resolves(context, it) } else null)
                    ?: tclPlayer?.takeIf { info.id.startsWith(TCL_INPUTS) }?.let { tclRoute(it, kind, label, hw(info.id)) }
                    ?: generic.takeIf { resolves(context, it) }
                    ?: droidLogicRoute(context, info)
                    ?: jmgoRoute(context, info, label)
                    ?: haierRoute(context, info)
                    ?: tclPlayer?.let { tclRoute(it, kind, label, hw(info.id)) }
                    ?: generic
                val device = cec
                    ?.let { runCatching { it.loadLabel(context).toString() }.getOrNull() }
                    ?.takeIf { it.isNotBlank() && !GENERIC_CEC_NAME.matches(it) }
                TvSource(
                    id = info.id,
                    label = hw(info.id)?.let { profile.labels[it] } ?: label,
                    kind = kind,
                    connected = device != null || state == TvInputManager.INPUT_STATE_CONNECTED && kind != SourceKind.Tuner,
                    deviceName = device,
                    intent = intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    hardware = hw(info.id),
                    firmwareLabel = label,
                    hidden = hw(info.id)?.let { it in profile.hiddenHardware } == true,
                )
            }
            .let { tunersApart(context, it) }
            .sortedWith(compareBy({ it.kind.ordinal }, { it.label }))
    }

    private fun fromSystem(info: TvInputInfo): Boolean = runCatching {
        info.serviceInfo.applicationInfo.flags and
            (ApplicationInfo.FLAG_SYSTEM or ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
    }.getOrDefault(true)

    private fun tunersApart(context: Context, sources: List<TvSource>): List<TvSource> {
        val shared = sources.filter { it.kind == SourceKind.Tuner }.groupBy { it.label }.filterValues { it.size > 1 }.keys
        if (shared.isEmpty()) return sources
        return sources.map { source ->
            if (source.kind != SourceKind.Tuner || source.label !in shared) return@map source
            val service = source.id.substringBefore("/HW").lowercase()
            when {
                "analog" in service || "atv" in service -> source.copy(label = context.getString(R.string.input_antenna))
                "dvb" in service || "dtv" in service || "dtmb" in service || "digital" in service ->
                    source.copy(label = context.getString(R.string.input_digital))
                else -> source
            }
        }
    }

    private fun droidLogicRoute(context: Context, info: TvInputInfo): Intent? {
        if (info.type != TvInputInfo.TYPE_HDMI) return null
        val intent = Intent(Intent.ACTION_MAIN).setComponent(
            ComponentName(DROIDLOGIC_PLAYER, "$DROIDLOGIC_PLAYER.DroidLogicTv"),
        ).putExtra("id", info.id)
        val activity = context.packageManager.resolveActivity(intent, 0)?.activityInfo ?: return null
        return intent.takeIf {
            activity.exported && activity.enabled && activity.applicationInfo.enabled &&
                (activity.permission == null || ContextCompat.checkSelfPermission(context, activity.permission) == PackageManager.PERMISSION_GRANTED)
        }
    }

    private fun jmgoRoute(context: Context, info: TvInputInfo, label: String): Intent? {
        if (info.type != TvInputInfo.TYPE_HDMI) return null
        JMGO_PORT.find(info.id)?.groupValues?.get(1)?.let { return jmgoActivity(context, "AmlogicTvActivity", it) }
        // JMGO H931 (MediaTek mt5889, Android 11): MediaTek's .hdmi.HDMIInputService/HW5-8, and the
        // TV app's MainActivity takes the same extra, then tunes the port's CEC child in its
        // TvActivity, which is not exported (issue #1481, the reporter's agent with adb). The port
        // is the digit in the firmware's label (HDMI 2), else MediaTek's HW5 for HDMI 1.
        if (!info.id.startsWith(MTK_HDMI)) return null
        val port = HDMI_DIGIT.find(label)?.groupValues?.get(1)
            ?: hw(info.id)?.minus(4)?.takeIf { it in 1..4 }?.toString()
            ?: return null
        return jmgoActivity(context, "MainActivity", port)
    }

    private fun jmgoActivity(context: Context, activityName: String, port: String): Intent? {
        val intent = Intent().setComponent(ComponentName(JMGO_TV, "$JMGO_TV.$activityName"))
            .putExtra("inputSrc", "HDMI$port")
        val activity = runCatching { context.packageManager.getActivityInfo(intent.component!!, 0) }.getOrNull() ?: return null
        return intent.takeIf {
            activity.exported && activity.enabled && activity.applicationInfo.enabled &&
                (activity.permission == null || ContextCompat.checkSelfPermission(context, activity.permission) == PackageManager.PERMISSION_GRANTED)
        }
    }

    private fun haierRoute(context: Context, info: TvInputInfo): Intent? {
        if (info.type != TvInputInfo.TYPE_HDMI || !info.id.startsWith(DROIDLOGIC_INPUTS)) return null
        val hw = hw(info.id) ?: return null
        val intent = Intent().setComponent(ComponentName(HAIER_MENU, "$HAIER_MENU.RootActivity"))
            .putExtra("isNewSource", 1)
            .putExtra("sourceid", hw)
        val activity = runCatching { context.packageManager.getActivityInfo(intent.component!!, 0) }.getOrNull() ?: return null
        return intent.takeIf {
            activity.exported && activity.enabled && activity.applicationInfo.enabled &&
                (activity.permission == null || ContextCompat.checkSelfPermission(context, activity.permission) == PackageManager.PERMISSION_GRANTED)
        }
    }

    private const val HAIER_MENU = "com.haier.tv.menusetting"
    private const val DROIDLOGIC_INPUTS = "com.droidlogic.tvinput/"

    private const val JMGO_TV = "com.jmgo.tv"
    private val JMGO_PORT = Regex("""\.Hdmi(\d)InputService/""")

    private const val MTK_HDMI = "com.mediatek.tvinput/.hdmi.HDMIInputService/"
    private val HDMI_DIGIT = Regex("""^HDMI\s*(\d)""", RegexOption.IGNORE_CASE)

    private fun resolves(context: Context, intent: Intent) =
        runCatching { context.packageManager.resolveActivity(intent, 0) != null }.getOrDefault(false)

    private const val TCL_PLAYER = "com.tcl.tv"

    private const val TCL_INPUTS = "com.tcl.tvinput/"

    private fun tclPlayer(context: Context): ComponentName? =
        ComponentName(TCL_PLAYER, "$TCL_PLAYER.TVActivity")
            .takeIf { runCatching { context.packageManager.getActivityInfo(it, 0) }.isSuccess }

    private fun tclIntent(player: ComponentName, ordinal: Int): Intent = Intent().setComponent(player)
        .putExtra("targetSourceId", ordinal)
        .putExtra("referer", "com.tcl.settings.module_source")

    private fun tclRoute(player: ComponentName, kind: SourceKind, label: String, hw: Int?): Intent? {
        if (hw != null && kind != SourceKind.Other) return tclIntent(player, hw)
        val ordinal = when (kind) {
            SourceKind.Hdmi -> Regex("(\\d)").find(label)?.groupValues?.get(1)?.toIntOrNull()?.takeIf { it in 1..4 }?.let { 7 + it }
            SourceKind.Tuner -> 2
            SourceKind.Av -> 3
            else -> null
        } ?: return null
        return tclIntent(player, ordinal)
    }

    private fun tclSources(context: Context): List<TvSource> {
        val player = tclPlayer(context) ?: return emptyList()
        fun source(name: String, ordinal: Int, kind: SourceKind, label: String) = TvSource(
            id = "tcl:$name",
            label = label,
            kind = kind,
            connected = false,
            deviceName = null,
            intent = tclIntent(player, ordinal).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return listOf(
            source("HDMI1", 8, SourceKind.Hdmi, "HDMI 1"),
            source("HDMI2", 9, SourceKind.Hdmi, "HDMI 2"),
            source("HDMI3", 10, SourceKind.Hdmi, "HDMI 3"),
            source("HDMI4", 11, SourceKind.Hdmi, "HDMI 4"),
            source("DTV", 2, SourceKind.Tuner, context.getString(R.string.input_live_tv)),
            source("AV1", 3, SourceKind.Av, context.getString(R.string.input_av)),
        )
    }

    private fun xiaomiSources(context: Context, framework: List<TvSource>): List<TvSource> {
        if (framework.any { it.kind == SourceKind.Hdmi } || !isInstalled(context, XIAOMI_PLAYER)) return emptyList()
        if (isBox()) return emptyList()
        val player = ComponentName(XIAOMI_PLAYER, "$XIAOMI_PLAYER.ExternalSourceActivity")
        if (runCatching { context.packageManager.getActivityInfo(player, 0) }.isFailure) return emptyList()
        val ports = if (Build.MODEL == "MiTV4C") 2 else 3
        val profile = DeviceProfile.current(context)
        return (1..ports).map { port ->
            val known = profile.labels.entries.firstOrNull { it.value == "HDMI $port" }
                ?.let { profile.xiaomiSourceIds[it.key] }
            val input = known ?: (22 + port)
            TvSource(
                id = "xiaomi:$input",
                label = "HDMI $port",
                kind = SourceKind.Hdmi,
                connected = false,
                deviceName = null,
                intent = Intent("com.xiaomi.mitv.tvplayer.EXTSRC_PLAY")
                    .setComponent(player)
                    .putExtra("input", input)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
        }
    }

    fun isBox(): Boolean = prop("ro.build.characteristics").orEmpty().let { "stb" in it || "mbx" in it }

    private fun prop(key: String): String? = runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java).invoke(null, key) as String
    }.getOrNull()

    private const val WHALEY_PLAYER = "com.whaley.tv.tvplayer.ui"

    private fun whaleySources(context: Context): List<TvSource> {
        val player = ComponentName(WHALEY_PLAYER, "com.whaley.tv.tvplayer.ui.RootActivity")
        if (runCatching { context.packageManager.getActivityInfo(player, 0) }.isFailure) return emptyList()
        fun source(ordinal: Int, kind: SourceKind, label: String) = TvSource(
            id = "whaley:$ordinal",
            label = label,
            kind = kind,
            connected = false,
            deviceName = null,
            intent = Intent("com.whaley.tv.tvplayer.ui.START_TV_PLAYER").setComponent(player)
                .putExtra("inputSrc", ordinal)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return listOf(
            source(4, SourceKind.Hdmi, "HDMI 1"),
            source(5, SourceKind.Hdmi, "HDMI 2"),
            source(6, SourceKind.Hdmi, "HDMI 3"),
            source(7, SourceKind.Av, context.getString(R.string.input_av)),
        )
    }

    private const val HUANGLONG_TVUI = "com.huanglong.tvui"
    private const val DANGBEI_INPUT_CHOOSER = "com.dangbei.action.INPUTSOURCE_CHOOSER_ACTIVITY"

    /**
     * Dangbei's Huanglong projectors (DBF6, tv_hi3751v811, Android 12) list no framework inputs.
     * Their TV app switches when its MainActivity is started with an int "SourceName", 9 for HDMI 1
     * and 10 for HDMI 2, which is what its own source chooser does (issue #1477: the reporter read
     * it from the APK and switched both ports with adb). Known by that chooser being there under
     * Dangbei's action, so other Huanglong TVs (Haier, #1311), whose tvui may number differently,
     * are left alone.
     */
    private fun dangbeiHuanglongSources(context: Context): List<TvSource> {
        val pm = context.packageManager
        val chooser = Intent(DANGBEI_INPUT_CHOOSER).setPackage(HUANGLONG_TVUI)
        if (runCatching { pm.resolveActivity(chooser, 0) }.getOrNull() == null) return emptyList()
        val player = ComponentName(HUANGLONG_TVUI, "$HUANGLONG_TVUI.MainActivity")
        val info = runCatching { pm.getActivityInfo(player, 0) }.getOrNull() ?: return emptyList()
        if (!info.exported) return emptyList()
        fun hdmi(port: Int, sourceName: Int) = TvSource(
            id = "huanglong:$sourceName",
            label = "HDMI $port",
            kind = SourceKind.Hdmi,
            connected = false,
            deviceName = null,
            intent = Intent(Intent.ACTION_MAIN).setComponent(player)
                .putExtra("SourceName", sourceName)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
        return listOf(hdmi(1, 9), hdmi(2, 10))
    }

    private const val SKYWORTH_PLAYER = "com.skyworth.tv"
    private val SKYWORTH_SOURCES = Uri.parse("content://com.skyworth.tv.sdk.provider/system/source_list")

    private fun skyworthSources(context: Context): List<TvSource> {
        val player = ComponentName(SKYWORTH_PLAYER, "$SKYWORTH_PLAYER.activity.SkyTVMainActivity")
        val activity = runCatching { context.packageManager.getActivityInfo(player, 0) }.getOrNull()
            ?: return emptyList()
        if (!activity.exported || !activity.enabled || !activity.applicationInfo.enabled) return emptyList()
        return runCatching {
            val sources = mutableListOf<TvSource>()
            context.contentResolver.query(
                SKYWORTH_SOURCES,
                arrayOf("sourceName", "sourceIndex", "displayName", "signalState"),
                null,
                null,
                null,
            )?.use { cursor ->
                val name = cursor.getColumnIndexOrThrow("sourceName")
                val index = cursor.getColumnIndexOrThrow("sourceIndex")
                val display = cursor.getColumnIndexOrThrow("displayName")
                val signal = cursor.getColumnIndexOrThrow("signalState")
                while (cursor.moveToNext()) {
                    val sourceName = cursor.getString(name)?.takeIf { it.isNotBlank() }
                    if (sourceName != null) {
                        val sourceIndex = cursor.getInt(index)
                        val label = cursor.getString(display)?.takeIf { it.isNotBlank() } ?: sourceName
                        sources += TvSource(
                            id = "skyworth:$sourceName:$sourceIndex",
                            label = label,
                            kind = skyworthKind(sourceName),
                            connected = false,
                            deviceName = null,
                            intent = Intent().setComponent(player)
                                .putExtra("source", Bundle().apply {
                                    putString("name", sourceName)
                                    putString("displayname", label)
                                    putInt("index", sourceIndex)
                                    putInt("signal", cursor.getInt(signal))
                                })
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        )
                    }
                }
            }
            sources.distinctBy { it.id }.sortedWith(compareBy({ it.kind.ordinal }, { it.label }))
        }.getOrDefault(emptyList())
    }

    private fun skyworthKind(name: String): SourceKind = when {
        name.startsWith("HDMI") -> SourceKind.Hdmi
        name == "DP" -> SourceKind.DisplayPort
        name == "VOD" || name.startsWith("DTV") || name.startsWith("ATV") || name.startsWith("DVB") ||
            name == "DTMB" -> SourceKind.Tuner
        name.startsWith("AV") || name.startsWith("YUV") || name.startsWith("YPBPR") || name.startsWith("VGA") ||
            name.startsWith("SVIDEO") -> SourceKind.Av
        else -> SourceKind.Other
    }

    fun hw(id: String): Int? = Regex("/HW(\\d+)$").find(id)?.groupValues?.get(1)?.toIntOrNull()

    private fun xiaomiRoute(info: TvInputInfo, kind: SourceKind, label: String, profile: DeviceProfile): Intent? {
        val n = hw(info.id) ?: return null
        return when (kind) {
            SourceKind.Hdmi -> Intent("com.xiaomi.mitv.tvplayer.EXTSRC_PLAY")
                .setComponent(ComponentName(XIAOMI_PLAYER, "$XIAOMI_PLAYER.ExternalSourceActivity"))
                .putExtra(
                    "input",
                    profile.xiaomiSourceIds[n] ?: xiaomiConfiguredSource(label)
                        ?: profile.xiaomiFallbackSourceIds[n] ?: (n + 18),
                )
            SourceKind.DisplayPort -> profile.xiaomiSourceIds[n]?.let {
                Intent("com.xiaomi.mitv.tvplayer.EXTSRC_PLAY")
                    .setComponent(ComponentName(XIAOMI_PLAYER, "$XIAOMI_PLAYER.ExternalSourceActivity"))
                    .putExtra("input", it)
            }
            SourceKind.Av -> Intent("com.xiaomi.mitv.tvplayer.EXTSRC_PLAY")
                .setComponent(ComponentName(XIAOMI_PLAYER, "$XIAOMI_PLAYER.ExternalSourceActivity"))
                .putExtra("input", 2)
            SourceKind.Tuner -> if (isDigitalTuner(label, n)) {
                Intent("com.xiaomi.mitv.tvplayer.DTMB_PLAY").setComponent(ComponentName(XIAOMI_PLAYER, "$XIAOMI_PLAYER.dtmb.DTMBActivity"))
            } else {
                Intent("com.xiaomi.mitv.tvplayer.ATV_PLAY").setComponent(ComponentName(XIAOMI_PLAYER, "$XIAOMI_PLAYER.AtvActivity"))
            }
            SourceKind.Usb, SourceKind.Other -> null
        }
    }

    private fun xiaomiConfiguredSource(label: String): Int? {
        val port = Regex("HDMI\\s*(\\d+)", RegexOption.IGNORE_CASE)
            .find(label)?.groupValues?.get(1)?.toIntOrNull() ?: return null
        return xiaomiHdmiOrder(port)?.trim()?.toIntOrNull()?.takeIf { it > 0 }
    }

    fun xiaomiHdmiOrder(port: Int): String? = runCatching {
        val configuration = Class.forName("mitv.common.ConfigurationManager")
        val manager = configuration.getMethod("getInstance").invoke(null)
        configuration.getMethod("queryTvConfigByKey", String::class.java)
            .invoke(manager, "hdmi${port}_order") as? String
    }.getOrNull()

    private fun isDigitalTuner(label: String, hw: Int): Boolean = when {
        label.contains("ATV", ignoreCase = true) -> false
        label.contains("DTV", ignoreCase = true) || label.contains("DTMB", ignoreCase = true) -> true
        label.contains("DVB", ignoreCase = true) -> true
        else -> hw == 0
    }

    private fun genericRoute(info: TvInputInfo, kind: SourceKind): Intent =
        if (kind == SourceKind.Tuner) {
            Intent(Intent.ACTION_VIEW, TvContract.buildChannelsUriForInput(info.id))
        } else {
            Intent(Intent.ACTION_VIEW, TvContract.buildChannelUriForPassthroughInput(info.id))
        }

    private fun isInstalled(context: Context, pkg: String) =
        runCatching { context.packageManager.getPackageInfo(pkg, 0); true }.getOrDefault(false)
}
