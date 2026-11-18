package com.gathin.tvhome.guard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.gathin.tvhome.apps.TileSheet
import com.gathin.tvhome.settings.AerialSettings
import com.gathin.tvhome.settings.Effects
import com.gathin.tvhome.system.DeviceProfile
import com.gathin.tvhome.system.DiagLog
import com.gathin.tvhome.system.HisenseSources
import com.gathin.tvhome.system.SystemProbe
import com.gathin.tvhome.ui.components.DiagLayers
import com.gathin.tvhome.ui.components.UhdCanvas
import com.gathin.tvhome.wallpaper.AerialQuality
import com.gathin.tvhome.wallpaper.ScreensaverMode
import com.gathin.tvhome.wallpaper.Wallpapers
import kotlinx.coroutines.launch

class ConfigReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        intent.getStringExtra("export_tiles")?.takeIf { Regex("^[A-Za-z0-9_-]+$").matches(it) }?.let { name ->
            val app = context.applicationContext
            @OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
            kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Default) {
                runCatching { TileSheet.export(app, name) }.onFailure { android.util.Log.e("AerialTiles", "Export failed", it) }
            }
            resultData = "exporting $name"
            return
        }
        intent.getStringExtra("device_profile")?.let { json ->
            val ok = DeviceProfile.setOwn(context, json.takeUnless { it.trim() == "default" })
            val profile = DeviceProfile.current(context).toJson()
            resultData = if (ok) "device_profile=$profile (${DeviceProfile.origin(context)})" else "device_profile not valid JSON; kept $profile"
            return
        }
        if (intent.getBooleanExtra("list_sources", false)) {
            val shown = SystemProbe.sources(context).map { it.id }.toSet()
            val all = (SystemProbe.sources(context) + SystemProbe.inputsToCheck(context).filter { it.hidden }).distinctBy { it.id }
            resultData = org.json.JSONArray(all.map { s ->
                org.json.JSONObject().put("id", s.id).put("label", s.label).put("kind", s.kind.name)
                    .put("shown", s.id in shown).apply {
                        s.hardware?.let { put("hw", it) }
                        s.firmwareLabel?.let { put("firmwareLabel", it) }
                        s.xiaomiInput?.let { put("xiaomiInput", it) }
                    }
            }).toString()
            return
        }
        intent.getStringExtra("open_source")?.let { id ->
            val source = (SystemProbe.inputsToCheck(context) + SystemProbe.sources(context)).firstOrNull { it.id == id }
            resultData = if (source == null) "open_source: no input $id" else runCatching {
                if (!HisenseSources.open(context, source.intent)) context.startActivity(source.intent)
                "open_source: ${source.label} -> ${source.intent.component?.flattenToShortString() ?: source.intent.data}" +
                    (source.xiaomiInput?.let { " input=$it" } ?: "")
            }.getOrElse { "open_source: ${it.javaClass.simpleName} ${it.message}" }
            return
        }
        if (intent.getBooleanExtra("clear_pin", false)) {
            AerialSettings.init(context)
            AerialSettings.choosePin(null)
        }
        val layout = mapOf<String, (Boolean) -> Unit>(
            "large_clock" to AerialSettings::chooseLargeClock,
            "clock_right" to AerialSettings::chooseClockRight,
            "one_row_home" to AerialSettings::chooseOneRowHome,
            "peek_next_row" to AerialSettings::choosePeekNextRow,
            "home_plate" to AerialSettings::chooseHomePlate,
            "show_all_names" to AerialSettings::chooseShowAllNames,
        ).filterKeys { intent.hasExtra(it) }
        if (layout.isNotEmpty()) {
            AerialSettings.init(context)
            layout.forEach { (key, choose) -> choose(intent.getBooleanExtra(key, false)) }
        }
        if (intent.hasExtra("preview_apps")) AerialSettings.previewApps = intent.getIntExtra("preview_apps", 0).coerceAtLeast(0)
        if (intent.hasExtra("home_columns")) {
            AerialSettings.init(context)
            AerialSettings.chooseHomeColumns(intent.getIntExtra("home_columns", 5))
        }
        if (intent.hasExtra("diag")) DiagLog.setEnabled(context, intent.getBooleanExtra("diag", false))
        for (key in listOf("mode_switch", "soft_layer", "saver_fade_offscreen")) {
            if (intent.hasExtra(key)) DiagLog.setOption(context, key, intent.getBooleanExtra(key, false))
        }
        if (intent.hasExtra("uhd_canvas")) {
            UhdCanvas.choose(context, intent.extras?.get("uhd_canvas") as? Boolean)
        }
        if (intent.hasExtra("keep_adb")) GuardSetup.setKeepAdb(context, intent.getBooleanExtra("keep_adb", false))
        if (intent.hasExtra("adb_probe_port")) GuardSetup.adbProbePort = intent.getIntExtra("adb_probe_port", 5555)
        if (intent.hasExtra("guard_off_in_standby")) GuardSetup.setOffInStandby(context, intent.getBooleanExtra("guard_off_in_standby", false))
        if (intent.getBooleanExtra("self_compile", false)) com.gathin.tvhome.update.SelfCompile.runNow(context)
        if (intent.getBooleanExtra("system_compile", false)) com.gathin.tvhome.update.SelfCompile.askSystemNow(context)
        if (intent.hasExtra("own_screensaver")) GuardSetup.setOwnScreensaver(context, intent.getBooleanExtra("own_screensaver", false))
        intent.getStringExtra("screensaver")?.let { key ->
            Wallpapers.init(context)
            ScreensaverMode.entries.firstOrNull { it.key == key }?.let { Wallpapers.chooseScreensaver(it) }
        }
        intent.getStringExtra("wallpaper")?.let { id ->
            Wallpapers.init(context)
            AerialSettings.init(context)
            val before = Wallpapers.selectedId
            if (id.isNotEmpty()) Wallpapers.select(id)
            resultData = "wallpaper_before=$before aerial_quality=${AerialSettings.aerialQuality.key}"
            return
        }
        intent.getStringExtra("skip_layers")?.let { list ->
            resultData = "skip_layers=" + DiagLayers.skip(list).joinToString(",")
            return
        }
        intent.getStringExtra("effects")?.let { key ->
            AerialSettings.init(context)
            Effects.entries.firstOrNull { it.key == key }?.let(AerialSettings::chooseEffects)
        }
        intent.getStringExtra("aerial_quality")?.let { key ->
            AerialSettings.init(context)
            AerialQuality.entries.firstOrNull { it.key == key }?.let(AerialSettings::chooseAerialQuality)
        }
        val cecKeys = listOf("cec_home_input", "cec_home_player", "cec_home_input_setting").filter { intent.hasExtra(it) }
        if (cecKeys.isNotEmpty()) {
            val edit = context.getSharedPreferences("system", Context.MODE_PRIVATE).edit()
            for (key in cecKeys) edit.putString(key, intent.getStringExtra(key)?.takeIf { it.isNotBlank() })
            edit.commit()
            HomeGuardService.refreshCecHome()
        }
        GuardSetup.ensureEnabled(context)
        resultData = "keep_adb=${GuardSetup.keepAdb(context)} guard_off_in_standby=${GuardSetup.offInStandby(context)}"
    }
}
