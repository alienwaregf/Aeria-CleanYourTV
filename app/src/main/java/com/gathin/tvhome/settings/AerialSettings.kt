package com.gathin.tvhome.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import android.os.SystemClock
import android.provider.Settings
import com.gathin.tvhome.guard.GuardSetup
import com.gathin.tvhome.system.DeviceClass
import com.gathin.tvhome.wallpaper.AerialQuality
import java.security.MessageDigest

enum class FocusStyle(val key: String) {
    Standard("standard"), HighContrast("high_contrast");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: HighContrast
    }
}

enum class UiSounds(val key: String) {
    Tv("tv"), Aerial("aerial"), Off("off");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key }
    }
}

enum class Effects(val key: String) {
    Auto("auto"), Full("full"), Lite("lite");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: Auto
    }
}

enum class ClockHours(val key: String) {
    System("system"), Twelve("12"), TwentyFour("24");

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: System
    }
}

object AerialSettings {
    private const val PREFS = "settings"
    @Volatile
    private var started = false
    private lateinit var app: Context

    private var chosenFocusStyle by mutableStateOf(FocusStyle.HighContrast)

    val focusStyle: FocusStyle get() = chosenFocusStyle


    var pinnedSources by mutableStateOf<Set<String>>(emptySet()); private set

    var bootSource by mutableStateOf<String?>(null); private set

    var cecSwitch by mutableStateOf(false); private set

    var sourceSound by mutableStateOf(false); private set

    var bootApp by mutableStateOf<String?>(null); private set

    var oneRowHome by mutableStateOf(true); private set

    private var chosenColumns by mutableIntStateOf(DEFAULT_HOME_COLUMNS)

    val homeColumns: Int get() = chosenColumns

    var previewApps by mutableIntStateOf(0)

    var peekNextRow by mutableStateOf(true); private set

    var uiSounds by mutableStateOf(UiSounds.Tv); private set

    var homePlate by mutableStateOf(true); private set

    var clockRight by mutableStateOf(false); private set

    var largeClock by mutableStateOf(false); private set

    var clockHours by mutableStateOf(ClockHours.System); private set

    var wallpaperAlwaysMoves by mutableStateOf(false); private set

    var showWeather by mutableStateOf(true); private set

    var screensaverClock by mutableStateOf(true); private set

    private var chosenAllNames by mutableStateOf(false)

    val showAllNames: Boolean get() = chosenAllNames

    var backStartsScreensaver by mutableStateOf(true); private set

    var pinSet by mutableStateOf(false); private set

    var pinArrows by mutableStateOf(false); private set
    private var pinHash: String? = null
    private var unlockedUntil = 0L
    private const val UNLOCK_MS = 10 * 60 * 1000L

    var screensaverMinutes by mutableStateOf(DEFAULT_SCREENSAVER_MINUTES); private set

    const val NEVER = 0

    val screensaverChoices = listOf(2, 5, 10, 20, NEVER)

    var aerialQuality by mutableStateOf(AerialQuality.Auto); private set

    var effects by mutableStateOf(Effects.Auto); private set

    var lowEnd = false; private set

    val lite: Boolean get() = effects == Effects.Lite || (effects == Effects.Auto && lowEnd)
    private const val DEFAULT_SCREENSAVER_MINUTES = 5

    val HOME_COLUMNS = 4..6
    private const val DEFAULT_HOME_COLUMNS = 5

    @Synchronized
    fun init(context: Context) {
        if (started) return
        app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val str: (String) -> String? = { key -> runCatching { prefs.getString(key, null) }.getOrNull() }
        val bool: (String, Boolean) -> Boolean = { key, def -> runCatching { prefs.getBoolean(key, def) }.getOrDefault(def) }
        val int: (String, Int) -> Int = { key, def -> runCatching { prefs.getInt(key, def) }.getOrDefault(def) }
        val strSet: (String) -> Set<String>? = { key -> runCatching { prefs.getStringSet(key, null)?.toSet() }.getOrNull() }
        chosenFocusStyle = FocusStyle.of(str("focus_style"))
        pinnedSources = strSet("pinned_sources") ?: emptySet()
        bootSource = str("boot_source")
        cecSwitch = bool("cec_switch", false)
        sourceSound = bool("source_sound", false)
        bootApp = str("boot_app")
        pinHash = str("pin_hash")
        pinSet = pinHash != null
        pinArrows = bool("pin_arrows", false)
        oneRowHome = bool("one_row_home", true)
        peekNextRow = bool("peek_next_row", true)
        chosenColumns = int("home_columns", DEFAULT_HOME_COLUMNS).takeIf { it in HOME_COLUMNS } ?: DEFAULT_HOME_COLUMNS
        clockRight = bool("clock_right", false)
        largeClock = bool("large_clock", false)
        clockHours = ClockHours.of(str("clock_hours"))
        homePlate = bool("home_plate", true)
        chosenAllNames = bool("show_all_names", false)
        wallpaperAlwaysMoves = bool("wallpaper_always_moves", false)
        showWeather = bool("show_weather", true)
        screensaverClock = bool("screensaver_clock", true)
        uiSounds = UiSounds.of(str("ui_sounds_kind")) ?: when {
            !bool("ui_sounds", true) -> UiSounds.Off
            Settings.System.getInt(app.contentResolver, Settings.System.SOUND_EFFECTS_ENABLED, 1) == 1 -> UiSounds.Tv
            else -> UiSounds.Aerial
        }
        backStartsScreensaver = bool("back_starts_screensaver", true)
        screensaverMinutes = int("screensaver_minutes", DEFAULT_SCREENSAVER_MINUTES).takeIf { it in screensaverChoices } ?: DEFAULT_SCREENSAVER_MINUTES
        aerialQuality = AerialQuality.of(str("aerial_quality"))
        effects = Effects.of(str("effects"))
        lowEnd = DeviceClass.lowEnd(app)
        started = true
    }

    private fun save(block: SharedPreferences.Editor.() -> Unit) {
        if (!::app.isInitialized) return
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply(block).apply()
    }

    fun chooseBootSource(id: String?) {
        bootSource = id
        bootApp = null
        save { putString("boot_source", id).remove("boot_app") }
    }

    fun chooseCecSwitch(on: Boolean) {
        cecSwitch = on
        save { putBoolean("cec_switch", on) }
    }

    fun chooseSourceSound(on: Boolean) {
        sourceSound = on
        save { putBoolean("source_sound", on) }
    }

    fun chooseBootApp(pkg: String?) {
        bootApp = pkg
        bootSource = null
        save { putString("boot_app", pkg).remove("boot_source") }
    }

    val hasBootTarget: Boolean get() = bootSource != null || bootApp != null

    val locked: Boolean get() = pinSet && SystemClock.elapsedRealtime() > unlockedUntil

    fun unlock(pin: String): Boolean {
        if (hash(pin) != pinHash) return false
        unlockedUntil = SystemClock.elapsedRealtime() + UNLOCK_MS
        return true
    }

    fun relock() { unlockedUntil = 0L }

    fun choosePin(pin: String?, arrows: Boolean = false) {
        pinHash = pin?.let(::hash)
        pinSet = pinHash != null
        pinArrows = pin != null && arrows
        unlockedUntil = 0L
        save { putString("pin_hash", pinHash); putBoolean("pin_arrows", pinArrows) }
    }

    private fun hash(pin: String): String =
        MessageDigest.getInstance("SHA-256").digest("aerial:$pin".toByteArray()).joinToString("") { "%02x".format(it) }

    fun chooseOneRowHome(on: Boolean) {
        oneRowHome = on
        save { putBoolean("one_row_home", on) }
    }

    fun chooseHomeColumns(columns: Int) {
        if (columns !in HOME_COLUMNS) return
        chosenColumns = columns
        save { putInt("home_columns", columns) }
    }

    fun choosePeekNextRow(on: Boolean) {
        peekNextRow = on
        save { putBoolean("peek_next_row", on) }
    }

    fun chooseUiSounds(kind: UiSounds) {
        uiSounds = kind
        save { putString("ui_sounds_kind", kind.key) }
    }

    fun chooseHomePlate(on: Boolean) {
        homePlate = on
        save { putBoolean("home_plate", on) }
    }

    fun chooseClockRight(on: Boolean) {
        clockRight = on
        save { putBoolean("clock_right", on) }
    }

    fun chooseLargeClock(on: Boolean) {
        largeClock = on
        save { putBoolean("large_clock", on) }
    }

    fun chooseClockHours(hours: ClockHours) {
        clockHours = hours
        save { putString("clock_hours", hours.key) }
    }

    fun chooseShowAllNames(on: Boolean) {
        chosenAllNames = on
        save { putBoolean("show_all_names", on) }
    }

    fun chooseWallpaperAlwaysMoves(on: Boolean) {
        wallpaperAlwaysMoves = on
        save { putBoolean("wallpaper_always_moves", on) }
    }

    fun chooseShowWeather(on: Boolean) {
        showWeather = on
        save { putBoolean("show_weather", on) }
    }

    fun chooseScreensaverClock(on: Boolean) {
        screensaverClock = on
        save { putBoolean("screensaver_clock", on) }
    }

    fun chooseBackStartsScreensaver(on: Boolean) {
        backStartsScreensaver = on
        save { putBoolean("back_starts_screensaver", on) }
    }

    fun chooseAerialQuality(quality: AerialQuality) {
        aerialQuality = quality
        save { putString("aerial_quality", quality.key) }
    }

    fun chooseScreensaverMinutes(minutes: Int) {
        if (minutes !in screensaverChoices) return
        screensaverMinutes = minutes
        save { putInt("screensaver_minutes", minutes) }
        if (::app.isInitialized) GuardSetup.setSystemScreensaverOff(app, minutes == NEVER)
    }

    fun setPinned(inputId: String, pinned: Boolean) {
        pinnedSources = if (pinned) pinnedSources + inputId else pinnedSources - inputId
        save { putStringSet("pinned_sources", pinnedSources) }
    }


    fun chooseEffects(choice: Effects) {
        effects = choice
        save { putString("effects", choice.key) }
    }

    fun chooseFocusStyle(style: FocusStyle) {
        chosenFocusStyle = style
        save { putString("focus_style", style.key) }
    }

}
