package com.gathin.tvhome.settings

import android.content.Context
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

object AerialSettings {
    private const val PREFS = "settings"
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

    fun init(context: Context) {
        if (started) return
        started = true
        app = context.applicationContext
        val prefs = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        chosenFocusStyle = FocusStyle.of(prefs.getString("focus_style", null))
        pinnedSources = prefs.getStringSet("pinned_sources", null)?.toSet() ?: emptySet()
        bootSource = prefs.getString("boot_source", null)
        cecSwitch = prefs.getBoolean("cec_switch", false)
        sourceSound = prefs.getBoolean("source_sound", false)
        bootApp = prefs.getString("boot_app", null)
        pinHash = prefs.getString("pin_hash", null)
        pinSet = pinHash != null
        pinArrows = prefs.getBoolean("pin_arrows", false)
        oneRowHome = prefs.getBoolean("one_row_home", true)
        peekNextRow = prefs.getBoolean("peek_next_row", true)
        chosenColumns = prefs.getInt("home_columns", DEFAULT_HOME_COLUMNS).takeIf { it in HOME_COLUMNS } ?: DEFAULT_HOME_COLUMNS
        clockRight = prefs.getBoolean("clock_right", false)
        largeClock = prefs.getBoolean("large_clock", false)
        homePlate = prefs.getBoolean("home_plate", true)
        chosenAllNames = prefs.getBoolean("show_all_names", false)
        wallpaperAlwaysMoves = prefs.getBoolean("wallpaper_always_moves", false)
        showWeather = prefs.getBoolean("show_weather", true)
        screensaverClock = prefs.getBoolean("screensaver_clock", true)
        uiSounds = UiSounds.of(prefs.getString("ui_sounds_kind", null)) ?: when {
            !prefs.getBoolean("ui_sounds", true) -> UiSounds.Off
            Settings.System.getInt(app.contentResolver, Settings.System.SOUND_EFFECTS_ENABLED, 1) == 1 -> UiSounds.Tv
            else -> UiSounds.Aerial
        }
        backStartsScreensaver = prefs.getBoolean("back_starts_screensaver", true)
        screensaverMinutes = prefs.getInt("screensaver_minutes", DEFAULT_SCREENSAVER_MINUTES)
        aerialQuality = AerialQuality.of(prefs.getString("aerial_quality", null))
        effects = Effects.of(prefs.getString("effects", null))
        lowEnd = DeviceClass.lowEnd(app)
    }

    fun chooseBootSource(id: String?) {
        bootSource = id
        bootApp = null
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString("boot_source", id).remove("boot_app") }
    }

    fun chooseCecSwitch(on: Boolean) {
        cecSwitch = on
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("cec_switch", on) }
    }

    fun chooseSourceSound(on: Boolean) {
        sourceSound = on
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("source_sound", on) }
    }

    fun chooseBootApp(pkg: String?) {
        bootApp = pkg
        bootSource = null
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString("boot_app", pkg).remove("boot_source") }
    }

    val hasBootTarget: Boolean get() = bootSource != null || bootApp != null

    val locked: Boolean get() = pinSet && SystemClock.elapsedRealtime() > unlockedUntil

    fun unlock(pin: String): Boolean {
        if (hash(pin) != pinHash) return false
        unlockedUntil = SystemClock.elapsedRealtime() + UNLOCK_MS
        return true
    }

    fun relock() { unlockedUntil = 0L }

    fun pinMatches(pin: String): Boolean = pinHash != null && hash(pin) == pinHash

    fun choosePin(pin: String?, arrows: Boolean = false) {
        pinHash = pin?.let(::hash)
        pinSet = pinHash != null
        pinArrows = pin != null && arrows
        unlockedUntil = 0L
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString("pin_hash", pinHash); putBoolean("pin_arrows", pinArrows) }
    }

    private fun hash(pin: String): String =
        MessageDigest.getInstance("SHA-256").digest("aerial:$pin".toByteArray()).joinToString("") { "%02x".format(it) }

    fun chooseOneRowHome(on: Boolean) {
        oneRowHome = on
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("one_row_home", on) }
    }

    fun chooseHomeColumns(columns: Int) {
        if (columns !in HOME_COLUMNS) return
        chosenColumns = columns
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putInt("home_columns", columns) }
    }

    fun choosePeekNextRow(on: Boolean) {
        peekNextRow = on
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("peek_next_row", on) }
    }

    fun chooseUiSounds(kind: UiSounds) {
        uiSounds = kind
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString("ui_sounds_kind", kind.key) }
    }

    fun chooseHomePlate(on: Boolean) {
        homePlate = on
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("home_plate", on) }
    }

    fun chooseClockRight(on: Boolean) {
        clockRight = on
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("clock_right", on) }
    }

    fun chooseLargeClock(on: Boolean) {
        largeClock = on
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("large_clock", on) }
    }

    fun chooseShowAllNames(on: Boolean) {
        chosenAllNames = on
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("show_all_names", on) }
    }

    fun chooseWallpaperAlwaysMoves(on: Boolean) {
        wallpaperAlwaysMoves = on
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("wallpaper_always_moves", on) }
    }

    fun chooseShowWeather(on: Boolean) {
        showWeather = on
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("show_weather", on) }
    }

    fun chooseScreensaverClock(on: Boolean) {
        screensaverClock = on
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("screensaver_clock", on) }
    }

    fun chooseBackStartsScreensaver(on: Boolean) {
        backStartsScreensaver = on
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean("back_starts_screensaver", on) }
    }

    fun chooseAerialQuality(quality: AerialQuality) {
        aerialQuality = quality
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString("aerial_quality", quality.key) }
    }

    fun chooseScreensaverMinutes(minutes: Int) {
        screensaverMinutes = minutes
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putInt("screensaver_minutes", minutes) }
        GuardSetup.setSystemScreensaverOff(app, minutes == NEVER)
    }

    fun setPinned(inputId: String, pinned: Boolean) {
        pinnedSources = if (pinned) pinnedSources + inputId else pinnedSources - inputId
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putStringSet("pinned_sources", pinnedSources) }
    }


    fun chooseEffects(choice: Effects) {
        effects = choice
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString("effects", choice.key) }
    }

    fun chooseFocusStyle(style: FocusStyle) {
        chosenFocusStyle = style
        app.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString("focus_style", style.key) }
    }

}
