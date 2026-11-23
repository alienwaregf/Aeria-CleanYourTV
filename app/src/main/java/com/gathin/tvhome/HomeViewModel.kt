package com.gathin.tvhome

import android.app.Application
import android.os.Build
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.geometry.Rect
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gathin.tvhome.apps.AppCatalog
import com.gathin.tvhome.apps.AppEntry
import com.gathin.tvhome.apps.AppNames
import com.gathin.tvhome.apps.LayoutOps
import com.gathin.tvhome.apps.CustomIcons
import com.gathin.tvhome.apps.IconLibrary
import com.gathin.tvhome.apps.SourceLooks
import com.gathin.tvhome.apps.FOLDER_KEY_PREFIX
import com.gathin.tvhome.apps.SOURCE_KEY_PREFIX
import com.gathin.tvhome.apps.LayoutStore
import com.gathin.tvhome.apps.TileArt
import com.gathin.tvhome.apps.TopShelfContent
import com.gathin.tvhome.apps.TileArtFactory
import com.gathin.tvhome.apps.folderEntry
import com.gathin.tvhome.apps.sourceEntry
import com.gathin.tvhome.settings.AerialSettings
import com.gathin.tvhome.system.XgimiBoost
import com.gathin.tvhome.system.Brightness
import com.gathin.tvhome.system.DiagLog
import com.gathin.tvhome.system.HomeStatus
import com.gathin.tvhome.system.NetworkStatus
import com.gathin.tvhome.system.SourceKind
import com.gathin.tvhome.system.SystemProbe
import com.gathin.tvhome.system.TvSource
import com.gathin.tvhome.system.VolumeStatus
import com.gathin.tvhome.ui.home.PinMode
import com.gathin.tvhome.ui.home.ScreensaverSkips
import com.gathin.tvhome.ui.home.TileFaces
import com.gathin.tvhome.ui.theme.Glyph
import com.gathin.tvhome.ui.theme.canvasDensity
import com.gathin.tvhome.wallpaper.Wallpapers
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val POLL_QUIET_MS = 400L

sealed interface Overlay {
    data object None : Overlay
    data class ControlCenter(val page: CcPage = CcPage.Main) : Overlay
    data class AppMenu(val key: String, val anchor: Rect, val inFolder: String? = null) : Overlay
    data class Folder(val id: String) : Overlay
    data object HiddenApps : Overlay
    data object Wallpaper : Overlay
    data class Pin(val mode: PinMode, val onDone: () -> Unit) : Overlay
}

enum class CcPage { Main, Inputs, InputSettings, Setup, Update }

private const val LEADING_TILES = 13

private val BOOT_CATCH_UP_MS = listOf(60_000L, 180_000L)

class HomeViewModel(app: Application) : AndroidViewModel(app) {
    private val context get() = getApplication<Application>()
    private val store = LayoutStore(app)
    private var all: List<AppEntry> = emptyList()

    var apps by mutableStateOf<List<AppEntry>>(emptyList()); private set
    var hiddenApps by mutableStateOf<List<AppEntry>>(emptyList()); private set

    var folderApps by mutableStateOf<Map<String, List<AppEntry>>>(emptyMap()); private set

    private val folderArt = HashMap<String, Pair<List<TileArt>, TileArt>>()
    var loaded by mutableStateOf(false); private set

    var overlay by mutableStateOf<Overlay>(Overlay.None)
    var movingKey by mutableStateOf<String?>(null); private set

    var network by mutableStateOf(NetworkStatus()); private set
    var volume by mutableStateOf(VolumeStatus()); private set
    var sources by mutableStateOf<List<TvSource>>(emptyList()); private set
    var homeStatus by mutableStateOf(HomeStatus.NotDefault); private set

    var brightness by mutableStateOf<Float?>(null); private set
    var brightnessBoost by mutableStateOf<Boolean?>(null); private set

    private var brightnessActedAt = 0L

    private val brightnessSteps = Channel<Int>(Channel.CONFLATED)

    private val xgimiBrightness by lazy { SystemProbe.hasXgimiBrightness }

    var homeResetPending by mutableStateOf(false)

    var homePressedTick by mutableStateOf(0)

    var lastInputAt by mutableLongStateOf(0L)

    var resumed by mutableStateOf(false)

    var homeBounce by mutableStateOf(false)

    val filmFront: Boolean get() = resumed || homeBounce

    var screensaverVisible by mutableStateOf(false)
    val screensaverSkips = ScreensaverSkips()

    private val layoutOps: LayoutOps = object : LayoutOps {
        override fun move(key: String, target: String) { store.move(key, target); arrange() }
        override fun moveInFolder(key: String, target: String) { store.moveInFolder(key, target); arrange() }
        override fun hide(key: String) = this@HomeViewModel.hide(key)
        override fun unhide(key: String) = this@HomeViewModel.unhide(key)
        override fun toFolder(key: String, id: String?) { putInFolder(key, id) }
        override fun outOfFolder(key: String) = takeOutOfFolder(key)
        override fun ungroup(id: String) = this@HomeViewModel.ungroup(id)
    }

    private var loadJob: Job? = null

    private val customIconsChanged: () -> Unit = {
        DiagLog.i("custom icons changed; reloading Home")
        reload()
    }

    init {
        AppCatalog.renameFolder = ::renameFolder
        AppCatalog.pinSource = ::pinSourceById
        AppCatalog.layout = layoutOps
        reload()
        viewModelScope.launch(Dispatchers.Default) {
            for (level in brightnessSteps) SystemProbe.setXgimiBrightnessLevel(level)
        }
        CustomIcons.onChange(customIconsChanged)
        viewModelScope.launch { snapshotFlow { IconLibrary.version }.drop(1).collect { reload() } }
        offerSetupOnce()
        viewModelScope.launch { snapshotFlow { SourceLooks.all }.drop(1).collect { if (loaded) arrange() } }
        viewModelScope.launch { snapshotFlow { AppNames.all }.drop(1).collect { if (loaded) arrange() } }
        viewModelScope.launch { snapshotFlow { AerialSettings.previewApps }.drop(1).collect { if (loaded) arrange() } }
        viewModelScope.launch {
            snapshotFlow { AerialSettings.homeColumns }.drop(1).collect {
                if (TileArtFactory.fitColumns()) reload() else if (loaded) prepareFaces()
            }
        }
    }

    private fun offerSetupOnce() {
        val prefs = context.getSharedPreferences("settings", android.content.Context.MODE_PRIVATE)
        if (prefs.getBoolean("setup_offered", false)) return
        viewModelScope.launch {
            delay(4_000)
            val granted = context.checkCallingOrSelfPermission(android.Manifest.permission.WRITE_SECURE_SETTINGS) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
            val status = SystemProbe.homeStatus(context)
            if (status == HomeStatus.NotDefault && !granted && (overlay != Overlay.None || screensaverVisible)) return@launch
            prefs.edit().putBoolean("setup_offered", true).apply()
            if (status == HomeStatus.NotDefault && !granted) overlay = Overlay.ControlCenter(CcPage.Setup)
        }
    }

    private var packagesSeen = 0

    fun reloadIfPackagesChanged() {
        if (!loaded) return
        if (Build.VERSION.SDK_INT >= 26 && context.packageManager.getChangedPackages(packagesSeen) == null) return
        reload()
    }

    fun reload() {
        TopShelfContent.clearAppCache()
        if (Build.VERSION.SDK_INT >= 26) {
            packagesSeen = context.packageManager.getChangedPackages(0)?.sequenceNumber ?: packagesSeen
        }
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            val ctx = context
            val started = SystemClock.uptimeMillis()
            val probing = async(Dispatchers.Default) { SystemProbe.sources(ctx) }
            val first: ((Set<String>) -> Set<String>)? = if (loaded) null else { installed -> store.leading(LEADING_TILES, installed) }
            all = AppCatalog.load(ctx, first)
            val (from, queried, done) = AppCatalog.lastTiming
            val now = SystemClock.uptimeMillis()
            DiagLog.i("${all.size} apps in ${now - started} ms (wait ${from - started}, queries ${queried - from}, " +
                "tiles ${done - queried}, main ${now - done})")
            sources = probing.await()
            arrange()
            prepareFaces()
            val wasLoaded = loaded
            if (!wasLoaded) {
                DiagLog.i("Home loaded: ${apps.size} tiles, ${sources.size} inputs, ${SystemClock.uptimeMillis() - started} ms")
                catchUpAfterBoot()
            }
            loaded = true
            val waiting = all.count { it.art.placeholder }
            if (waiting > 0) {
                val rest = SystemClock.uptimeMillis()
                all = AppCatalog.load(ctx)
                arrange()
                prepareFaces()
                DiagLog.i("$waiting more tiles in ${SystemClock.uptimeMillis() - rest} ms")
            }
            refreshIconLibrary()
        }
    }

    private fun refreshIconLibrary() {
        val installed = all.mapTo(HashSet()) { it.packageName }
        viewModelScope.launch {
            if (IconLibrary.refresh(installed)) IconLibrary.changed()
        }
    }

    private suspend fun prepareFaces(entries: List<AppEntry>, focused: Boolean = false) {
        val dm = context.resources.displayMetrics
        withContext(Dispatchers.Default) {
            TileFaces.prepare(entries.map { it.art }.filterNot { it.placeholder }, dm.widthPixels, canvasDensity(dm), focused)
        }
    }

    private var facesJob: Job? = null

    private fun prepareFaces() {
        val shown = apps + folderApps.values.flatten()
        val row = apps.take(AerialSettings.homeColumns)
        facesJob?.cancel()
        facesJob = viewModelScope.launch {
            val started = SystemClock.uptimeMillis()
            prepareFaces(shown)
            DiagLog.i("tile faces in ${SystemClock.uptimeMillis() - started} ms")
            delay(1_000)
            prepareFaces(row, focused = true)
        }
    }

    private fun catchUpAfterBoot() {
        if (SystemClock.elapsedRealtime() >= BOOT_CATCH_UP_MS.last()) return
        viewModelScope.launch {
            for (at in BOOT_CATCH_UP_MS) {
                val wait = at - SystemClock.elapsedRealtime()
                if (wait <= 0) continue
                delay(wait)
                val again = AppCatalog.load(context)
                if (again.map { it.key }.toSet() == all.map { it.key }.toSet()) continue
                DiagLog.i("${again.size - all.size} more apps a while into the boot")
                all = again
                arrange()
            }
        }
    }

    private fun arrange() {
        val pinned = sources.filter { it.id in AerialSettings.pinnedSources }.map { sourceEntry(context, it) }
        AppCatalog.pinnedSources = pinned
        AppCatalog.sources = sources
        Wallpapers.setSources(
            sources.filter { (it.kind == SourceKind.Hdmi || it.kind == SourceKind.DisplayPort) && '/' in it.id }
                .map { it.id to (it.deviceName?.let { d -> "${it.label} · $d" } ?: it.label) },
        )
        val named = all.map { e -> AppNames.of(e.key)?.let { e.copy(label = it) } ?: e }
        val arrangement = store.arrange(named + pinned) { folder, inside ->
            val shown = inside.take(9).map { it.art }
            val art = folderArt[folder.id]?.takeIf { (made, _) -> made.size == shown.size && made.indices.all { made[it] === shown[it] } }?.second
                ?: TileArtFactory.forFolder(shown).also { folderArt[folder.id] = shown to it }
            folderEntry(folder, art)
        }
        apps = AerialSettings.previewApps.takeIf { it > 0 }?.let { arrangement.home.take(it) } ?: arrangement.home
        AppCatalog.home = arrangement.home
        AppCatalog.folderApps = arrangement.folders
        AppCatalog.hiddenApps = arrangement.hidden
        AppCatalog.folders = arrangement.home.filter { it.folder != null }
        folderApps = arrangement.folders
        hiddenApps = arrangement.hidden
    }

    fun entry(key: String): AppEntry? = apps.firstOrNull { it.key == key } ?: folderApps.values.flatten().firstOrNull { it.key == key } ?: hiddenApps.firstOrNull { it.key == key }

    fun folders() = store.folders()

    fun putInFolder(key: String, id: String?): String {
        val names = context.resources.getStringArray(R.array.folder_names)
        val app = entry(key)
        val base = if (app != null && !app.removable && app.source == null) names[0] else context.getString(R.string.folder_default)
        val taken = store.folders().map { it.name }.toSet()
        val name = generateSequence(1) { it + 1 }.map { if (it == 1) base else "$base $it" }.first { it !in taken }
        val folder = store.putInFolder(key, id, name)
        arrange()
        return FOLDER_KEY_PREFIX + folder
    }

    fun takeOutOfFolder(key: String) {
        store.takeOutOfFolder(key)
        arrange()
    }

    fun renameFolder(id: String, name: String) {
        store.renameFolder(id, name)
        arrange()
    }

    fun ungroup(id: String) {
        store.ungroup(id)
        folderArt.remove(id)
        arrange()
    }

    fun resetIcon(key: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { CustomIcons.clear(key) }
            CustomIcons.changed()
        }
    }

    var notice by mutableStateOf<String?>(null); private set
    var noticeGlyph by mutableStateOf(Glyph.Power); private set
    private var noticeJob: Job? = null

    fun notify(text: String, glyph: Glyph = Glyph.Power) {
        notice = text
        noticeGlyph = glyph
        noticeJob?.cancel()
        noticeJob = viewModelScope.launch {
            delay(4000)
            notice = null
        }
    }

    fun bootTargetLabel(): String? {
        AerialSettings.bootApp?.let { pkg -> return entry(pkg)?.label ?: pkg }
        val id = AerialSettings.bootSource ?: return null
        return entry(SOURCE_KEY_PREFIX + id)?.label ?: sources.firstOrNull { it.id == id }?.label
    }

    fun chooseBootTarget(pkg: String? = null, sourceId: String? = null) {
        val before = bootTargetLabel()
        if (pkg != null) AerialSettings.chooseBootApp(pkg) else AerialSettings.chooseBootSource(sourceId)
        val after = bootTargetLabel()
        when {
            after == null -> before?.let { notify(context.getString(R.string.notice_boot_off, it)) }
            before != null && before != after -> notify(context.getString(R.string.notice_boot_switch, after, before))
            else -> notify(context.getString(R.string.notice_boot_on, after))
        }
    }

    private fun pinSourceById(id: String, pinned: Boolean) {
        sources.firstOrNull { it.id == id }?.let { setSourcePinned(it, pinned) }
    }

    fun setSourcePinned(source: TvSource, pinned: Boolean) {
        AerialSettings.setPinned(source.id, pinned)
        arrange()
        if (pinned) {
            val key = apps.firstOrNull { it.source?.id == source.id }?.key
            val first = apps.firstOrNull()?.key
            if (key != null && first != null && key != first) {
                store.move(key, first)
                arrange()
            }
        }
    }

    fun refreshSystem() {
        viewModelScope.launch {
            val ctx = context
            val (n, v, s) = withContext(Dispatchers.Default) {
                Triple(SystemProbe.network(ctx), SystemProbe.volume(ctx), SystemProbe.sources(ctx))
            }
            homeStatus = withContext(Dispatchers.Default) { SystemProbe.homeStatus(ctx) }
            val level = withContext(Dispatchers.Default) { Brightness.level(ctx) }
            network = n; volume = v
            fun shown(list: List<TvSource>) = list.map { listOf(it.id, it.label, it.deviceName, it.intent.toUri(0)) }
            if (shown(s) != shown(sources)) { sources = s; arrange() } else sources = s
            brightness = level
        }
    }

    fun refreshSources() {
        viewModelScope.launch {
            val ctx = context
            val s = withContext(Dispatchers.Default) { SystemProbe.sources(ctx) }
            if (s.isNotEmpty() && sources.isEmpty()) { sources = s; arrange() }
        }
    }

    fun refreshPanel() {
        viewModelScope.launch {
            val ctx = context
            homeStatus = withContext(Dispatchers.Default) { SystemProbe.homeStatus(ctx) }
            brightness = withContext(Dispatchers.Default) { Brightness.level(ctx) }
        }
    }

    suspend fun discoverBrightnessBoost() {
        XgimiBoost.discover(context)
        refreshBrightness()
    }

    fun refreshBrightness() {
        viewModelScope.launch {
            val (level, boost) = withContext(Dispatchers.Default) {
                Brightness.level(context) to XgimiBoost.enabled()
            }
            if (SystemClock.elapsedRealtime() - brightnessActedAt < POLL_QUIET_MS) return@launch
            brightness = level
            brightnessBoost = boost
        }
    }

    fun refreshNetwork() { network = SystemProbe.network(context) }

    fun refreshVolume() { volume = SystemProbe.volume(context) }

    fun adjustVolume(direction: Int) {
        SystemProbe.adjustVolume(context, direction)
        volume = SystemProbe.volume(context)
    }

    fun adjustBrightness(direction: Int) {
        val current = brightness
        if (xgimiBrightness && current != null) {
            if (brightnessBoost == true && direction > 0) return
            val next = if (brightnessBoost == true) 1f else Brightness.xgimiStep(current, direction) ?: return
            if (brightnessBoost != null) brightnessBoost = false
            brightness = next
            brightnessActedAt = SystemClock.elapsedRealtime()
            brightnessSteps.trySend(Math.round(next * SystemProbe.xgimiBrightnessMax))
            return
        }
        brightness = Brightness.adjust(context, direction)
    }

    fun toggleMute() {
        SystemProbe.toggleMute(context)
        volume = SystemProbe.volume(context)
    }

    fun whenUnlocked(action: () -> Unit) {
        if (!AerialSettings.locked) action()
        else overlay = Overlay.Pin(PinMode.Unlock) { overlay = Overlay.None; action() }
    }

    fun hide(key: String) {
        store.hide(key)
        arrange()
    }

    fun unhide(key: String) {
        store.unhide(key)
        arrange()
    }

    fun startMove(key: String) {
        overlay = Overlay.None
        movingKey = key
    }

    fun startMoveInFolder(key: String) {
        movingKey = key
    }

    fun moveInFolder(key: String, target: String) {
        store.moveInFolder(key, target)
        arrange()
    }

    fun moveBy(delta: Int) {
        val key = movingKey ?: return
        val from = apps.indexOfFirst { it.key == key }
        if (from < 0) return
        val to = (from + delta).coerceIn(0, apps.lastIndex)
        if (to == from) return
        store.move(key, apps[to].key)
        arrange()
    }

    fun endMove() { movingKey = null }

    fun onHomePressed(resetFocus: Boolean = true) {
        screensaverVisible = false
        AerialSettings.relock()
        overlay = Overlay.None
        movingKey = null
        homePressedTick++
        if (resetFocus) homeResetPending = true
    }

    override fun onCleared() {
        CustomIcons.removeOnChange(customIconsChanged)
        if (AppCatalog.renameFolder == ::renameFolder) AppCatalog.renameFolder = null
        if (AppCatalog.pinSource == ::pinSourceById) AppCatalog.pinSource = null
        if (AppCatalog.layout === layoutOps) AppCatalog.layout = null
    }
}

