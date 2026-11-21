package com.gathin.tvhome.apps

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.util.Log
import com.gathin.tvhome.R
import com.gathin.tvhome.guard.HomeGuardService
import com.gathin.tvhome.system.CoocaaHome
import com.gathin.tvhome.system.TvSource
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext

interface LayoutOps {
    fun move(key: String, target: String)
    fun moveInFolder(key: String, target: String)
    fun hide(key: String)
    fun unhide(key: String)
    fun toFolder(key: String, id: String?)
    fun outOfFolder(key: String)
    fun ungroup(id: String)
}

data class AppEntry(
    val packageName: String,
    val label: String,
    val component: ComponentName,
    val leanback: Boolean,
    val removable: Boolean,
    val art: TileArt,
    val source: TvSource? = null,
    val folder: Folder? = null,
    val shortcut: Intent? = null,
) {
    val key: String get() = packageName

    fun launchIntent(): Intent {
        val carried = source?.let { Intent(it.intent) } ?: shortcut?.let { Intent(it) }
        if (carried != null) return carried
        return if (rewritesLauncher) Intent().setComponent(component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        else Intent(Intent.ACTION_MAIN)
            .addCategory(if (leanback) Intent.CATEGORY_LEANBACK_LAUNCHER else Intent.CATEGORY_LAUNCHER)
            .setComponent(component)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
    }
}

internal val rewritesLauncher: Boolean by lazy {
    runCatching {
        Class.forName("android.os.SystemProperties").getMethod("get", String::class.java)
            .invoke(null, "persist.sys.launcher.component") as? String
    }.getOrNull().orEmpty().isNotEmpty()
}

fun sourceEntry(context: Context, source: TvSource): AppEntry {
    val key = SOURCE_KEY_PREFIX + source.id
    val look = SourceLooks.of(source.id)
    val name = look?.name ?: look?.device?.let { context.getString(it.label) }
    val made = SourceArt(look, source.label, source.deviceName, CustomIcons.stamp(key), TileArtFactory.W)
    val art = synchronized(sourceArt) { sourceArt[key]?.takeIf { it.first == made }?.second } ?: run {
        val custom = CustomIcons.load(key)
        (if (custom != null) TileArtFactory.fromBanner(android.graphics.drawable.BitmapDrawable(android.content.res.Resources.getSystem(), custom))
        else if (look?.device != null && look.name == null) TileArtFactory.forSource(source.kind, null, null, look.device)
        else if (name != null) TileArtFactory.forSource(source.kind, name, source.label, look?.device)
        else TileArtFactory.forSource(source.kind, source.label, source.deviceName))
            .also { synchronized(sourceArt) { sourceArt[key] = made to it } }
    }
    return AppEntry(
        packageName = key,
        label = name ?: source.label,
        component = ComponentName("input", source.id),
        leanback = false,
        removable = false,
        art = art,
        source = source,
    )
}

private data class SourceArt(val look: SourceLook?, val label: String, val device: String?, val stamp: Long, val width: Int)
private val sourceArt = HashMap<String, Pair<SourceArt, TileArt>>()

const val SOURCE_KEY_PREFIX = "input:"

fun folderEntry(folder: Folder, art: TileArt): AppEntry = AppEntry(
    packageName = folder.key,
    label = folder.name,
    component = ComponentName("folder", folder.id),
    leanback = false,
    removable = false,
    art = art,
    folder = folder,
)

object AppCatalog {
    private const val TAG = "AerialTiles"
    private const val CATEGORY_APPSTORE_LAUNCHER = "android.intent.category.APPSTORE_LAUNCHER"

    private val UNLISTED_ENTRIES = listOf(
        ComponentName("com.xiaomi.mitv.mediaexplorer", "com.xiaomi.mitv.mediaexplorer.NewScraperMainEntryActivity"),
        ComponentName("com.xiaomi.smarthome.tv", "com.xiaomi.smarthome.tv.ui.MainActivity"),
        ComponentName("com.sony.dtv.searchapp", "com.ktcp.aiagent.xwability.activity.VoipHomeActivity"),
        ComponentName("com.gitv.dvb.live", "com.gitv.dvb.live.activity.PlayActivity"),
        ComponentName("com.xiaomi.mitv.appstore", "com.xiaomi.mitv.appstore.MainActivity"),
        ComponentName("com.baofengtv.tvplayer", "com.baofengtv.tvplayer.MainActivity"),
        ComponentName("com.bftv.fui.biu", "com.bftv.fui.biu.views.activity.FRowActivity"),
    )

    private val UNLISTED_NAMES = mapOf(
        ComponentName("com.sony.dtv.searchapp", "com.ktcp.aiagent.xwability.activity.VoipHomeActivity") to R.string.tile_xiaowei_call,
    )

    private val SYSTEM_TILES = mapOf(
        "com.xiaomi.mitv.settings" to SystemTile.Settings,
        "com.mitv.gallery" to SystemTile.Photos,
        "com.xiaomi.mitv.calendar" to SystemTile.Calendar,
        "com.xiaomi.tweather" to SystemTile.Weather,
        "com.xiaomi.mitv.smartshare" to SystemTile.Cast,
        "com.xiaomi.mitv.tvmanager" to SystemTile.Security,
        "com.xiaomi.mitv.appstore" to SystemTile.Store,
        "com.xiaomi.mitv.mediaexplorer" to SystemTile.Media,
        "com.tcl.settings" to SystemTile.Settings,
        "com.tcl.tvsmartalbum" to SystemTile.Photos,
        "com.tcl.common.weather" to SystemTile.Weather,
        "com.tcl.MultiScreenInteraction_TV" to SystemTile.Cast,
        "com.tcl.guard" to SystemTile.Security,
        "com.tcl.appmarket2" to SystemTile.Store,
        "com.tcl.ui_mediaCenter" to SystemTile.Media,
        "com.tcl.browser" to SystemTile.Browser,
    )

    private val SYSTEM_CATEGORIES = listOf(
        Intent.CATEGORY_APP_GALLERY to SystemTile.Photos,
        Intent.CATEGORY_APP_CALENDAR to SystemTile.Calendar,
        Intent.CATEGORY_APP_BROWSER to SystemTile.Browser,
        Intent.CATEGORY_APP_MARKET to SystemTile.Store,
        CATEGORY_APPSTORE_LAUNCHER to SystemTile.Store,
    )

    private class Cached(val stamp: Long, val width: Int, val label: String, val art: TileArt)
    private val cache = HashMap<String, Cached>()

    private val drawnByAerial = java.util.concurrent.ConcurrentHashMap<String, SystemTile>()

    fun usesLibrary(pkg: String): Boolean =
        IconLibrary.uses(pkg) && (!drawnByAerial.containsKey(pkg) || IconLibrary.pick(pkg) == IconLibrary.Pick.Library)

    @Volatile var loaded: List<AppEntry> = emptyList(); private set

    @Volatile var pinnedSources: List<AppEntry> = emptyList()

    @Volatile var folders: List<AppEntry> = emptyList()

    @Volatile var renameFolder: ((id: String, name: String) -> Unit)? = null

    @Volatile var home: List<AppEntry> = emptyList()
    @Volatile var folderApps: Map<String, List<AppEntry>> = emptyMap()
    @Volatile var hiddenApps: List<AppEntry> = emptyList()

    @Volatile var layout: LayoutOps? = null

    @Volatile var sources: List<TvSource> = emptyList()

    @Volatile var pinSource: ((id: String, pinned: Boolean) -> Unit)? = null

    @Volatile var lastTiming: LongArray = LongArray(3); private set

    suspend fun load(context: Context, first: ((installed: Set<String>) -> Set<String>)? = null): List<AppEntry> = withContext(Dispatchers.Default) {
        val started = android.os.SystemClock.uptimeMillis()
        TileDiskCache.init(context)
        val pm = context.packageManager
        val homeLabel = context.getString(R.string.tile_stock_home)
        val found = LinkedHashMap<String, Pair<ActivityInfo, Boolean>>()
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER), 0)
            .forEach { found.putIfAbsent(it.activityInfo.packageName, it.activityInfo to true) }
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .forEach { found.putIfAbsent(it.activityInfo.packageName, it.activityInfo to false) }
        pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(CATEGORY_APPSTORE_LAUNCHER), 0)
            .forEach { found.putIfAbsent(it.activityInfo.packageName, it.activityInfo to false) }
        for (component in UNLISTED_ENTRIES) {
            val info = runCatching { pm.getActivityInfo(component, 0) }.getOrNull() ?: continue
            if (info.exported && info.isEnabled && info.applicationInfo.enabled) found.putIfAbsent(info.packageName, info to false)
        }
        val stockHomes = HashSet<String>()
        val skyworthHomes = listOf("com.skyworth.officehomepage", "com.tianci.movieplatform")
            .any { runCatching { pm.getPackageInfo(it, 0) }.isSuccess }
        val preferredHomes = listOfNotNull(CoocaaHome.launcher(context)?.packageName) + SKYWORTH_PREFERRED_STOCK_HOMES
        val stockInfos = stockLaunchers(pm)
            .filter { !skyworthHomes || it.packageName != "com.example.android.home" }
            .sortedBy {
                preferredHomes.indexOf(it.packageName).let { i -> if (i == -1) Int.MAX_VALUE else i }
            }
        for (info in stockInfos) {
            if (skyworthHomes && stockHomes.isNotEmpty()) continue
            if (found.putIfAbsent(info.packageName, info to false) == null) stockHomes += info.packageName
        }
        if (skyworthHomes) found.remove("com.example.android.home")
        found.remove(context.packageName)

        val byCategory = HashMap<String, SystemTile>()
        for ((category, tile) in SYSTEM_CATEGORIES) {
            pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(category), 0)
                .forEach { byCategory.putIfAbsent(it.activityInfo.packageName, tile) }
        }
        pm.resolveActivity(Intent(android.provider.Settings.ACTION_SETTINGS), 0)
            ?.let { byCategory.putIfAbsent(it.activityInfo.packageName, SystemTile.Settings) }

        val queried = android.os.SystemClock.uptimeMillis()
        val whole = first?.invoke(found.keys)?.takeIf { it.isNotEmpty() }
        coroutineScope {
            found.values.map { (info, leanback) ->
                val home = info.packageName in stockHomes
                val later = whole != null && info.packageName !in whole
                async {
                    runCatching { entry(pm, info, leanback, byCategory, home, later) }
                        .onFailure { Log.w(TAG, "left ${info.packageName} out of Home", it) }
                        .getOrNull()?.let { if (home) it.copy(label = homeLabel) else it }
                        ?.let { e ->
                            val own = info.labelRes != 0 || info.nonLocalizedLabel != null
                            UNLISTED_NAMES[ComponentName(info.packageName, info.name)]
                                ?.takeIf { !own }?.let { e.copy(label = context.getString(it)) } ?: e
                        }
                }
            }.awaitAll().filterNotNull()
        }.plus(shortcuts(context, pm)).also {
            val now = android.os.SystemClock.uptimeMillis()
            lastTiming = longArrayOf(started, queried, now)
            loaded = it
            TileDiskCache.keepOnly(it.mapTo(HashSet()) { app -> app.packageName })
        }
    }

    private fun shortcuts(context: Context, pm: PackageManager): List<AppEntry> {
        val kids = Intent("android.intent.action.ENTER_KIDS_MODE").setPackage("com.mitv.tvhome")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val info = runCatching { pm.resolveActivity(kids, 0)?.activityInfo }.getOrNull()?.takeIf { it.exported } ?: return emptyList()
        return listOf(
            AppEntry(
                packageName = KIDS_KEY,
                label = context.getString(R.string.tile_kids),
                component = ComponentName(info.packageName, info.name),
                leanback = false,
                removable = false,
                art = CustomIcons.load(KIDS_KEY)?.let {
                    runCatching { TileArtFactory.fromBanner(android.graphics.drawable.BitmapDrawable(android.content.res.Resources.getSystem(), it)) }.getOrNull()
                } ?: TileArtFactory.forSystem(SystemTile.Kids),
                shortcut = kids,
            ),
        )
    }

    private const val KIDS_KEY = "shortcut:kids"

    private fun placeholder(tint: androidx.compose.ui.graphics.Color): TileArt {
        val plain = android.graphics.Bitmap.createBitmap(16, 9, android.graphics.Bitmap.Config.ARGB_8888)
            .apply { eraseColor(tint.copy(alpha = 1f).toArgb()) }.asImageBitmap()
        return TileArt(plain, null, androidx.compose.ui.geometry.Rect.Zero, tint, plain, placeholder = true)
    }

    private val SKYWORTH_PREFERRED_STOCK_HOMES = listOf("com.skyworth.officehomepage", "com.tianci.movieplatform")

    private fun stockLaunchers(pm: PackageManager): List<ActivityInfo> {
        val guarded = HomeGuardService.stockHomes()
        if (guarded != null) {
            return guarded.mapNotNull { (pkg, classes) ->
                classes.firstNotNullOfOrNull { cls ->
                    runCatching { pm.getActivityInfo(ComponentName(pkg, cls), 0) }.getOrNull()
                        ?.takeIf { it.exported && it.isEnabled && it.applicationInfo.enabled }
                }
            }
        }
        return pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)
            .filter { it.priority >= 0 && it.activityInfo.exported }
            .map { it.activityInfo }
    }

    private fun densest(pm: PackageManager, info: ActivityInfo, res: Int): android.graphics.drawable.Drawable? =
        if (res == 0) null else runCatching {
            pm.getResourcesForApplication(info.applicationInfo)
                .getDrawableForDensity(res, android.util.DisplayMetrics.DENSITY_XXXHIGH, null)
        }.getOrNull()

    fun archiveArt(pm: PackageManager, path: String): Pair<String, TileArt>? = runCatching {
        val pi = pm.getPackageArchiveInfo(path, PackageManager.GET_ACTIVITIES) ?: return null
        val ai = pi.applicationInfo ?: return null
        ai.sourceDir = path
        ai.publicSourceDir = path
        val res = pm.getResourcesForApplication(ai)
        fun drawable(id: Int) = if (id == 0) null else runCatching {
            res.getDrawableForDensity(id, android.util.DisplayMetrics.DENSITY_XXXHIGH, null)
        }.getOrNull()
        val label = (ai.loadLabel(pm)?.toString() ?: "").trim().ifEmpty { pi.packageName }
        val bannerId = ai.banner.takeIf { it != 0 } ?: pi.activities?.firstOrNull { it.bannerResource != 0 }?.bannerResource ?: 0
        val banner = drawable(bannerId)?.takeIf { it.intrinsicWidth >= it.intrinsicHeight * 4 / 3 }
        val recipe = TileRecipes.of(pi.packageName)
        val drawn = recipe?.glyph?.let { TileArtFactory.glyphTile(recipe.top ?: 0, recipe.bottom ?: 0, it) }
        val art = drawn ?: if (banner != null && recipe?.useIcon != true) TileArtFactory.fromBanner(banner)
            else TileArtFactory.fromIcon(drawable(ai.icon) ?: return null, recipe)
        label to art
    }.getOrNull()

    private fun banner(pm: PackageManager, info: ActivityInfo, leanback: Boolean): android.graphics.drawable.Drawable? =
        (if (leanback) densest(pm, info, info.bannerResource) ?: runCatching { info.loadBanner(pm) }.getOrNull() else null)
            ?.takeIf { it.intrinsicWidth >= it.intrinsicHeight * 4 / 3 }

    private fun fromApp(pm: PackageManager, info: ActivityInfo, banner: android.graphics.drawable.Drawable?, recipe: TileRecipe?): TileArt = when {
        recipe?.glyph != null -> TileArtFactory.glyphTile(recipe.top ?: 0, recipe.bottom ?: 0, recipe.glyph)
        banner != null && recipe?.useIcon != true -> TileArtFactory.fromBanner(banner)
        else -> TileArtFactory.fromIcon(densest(pm, info, info.iconResource) ?: info.loadIcon(pm), recipe)
    }

    fun ownArt(context: Context, entry: AppEntry): TileArt? = runCatching {
        drawnByAerial[entry.packageName]?.let { return@runCatching TileArtFactory.forSystem(it) }
        val pm = context.packageManager
        val info = pm.getActivityInfo(entry.component, 0)
        fromApp(pm, info, banner(pm, info, entry.leanback), TileRecipes.of(entry.packageName))
    }.getOrNull()

    private fun entry(
        pm: PackageManager, info: ActivityInfo, leanback: Boolean, byCategory: Map<String, SystemTile>,
        stockHome: Boolean = false, later: Boolean = false,
    ): AppEntry {
        val pkg = info.packageName
        val stamp = runCatching { pm.getPackageInfo(pkg, 0).lastUpdateTime }.getOrDefault(0L) + CustomIcons.stamp(pkg) +
            IconLibrary.stamp(pkg) + if (stockHome) 1 else 0
        val system = (info.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
        val ownTile = if (stockHome) SystemTile.Home else if (system) SYSTEM_TILES[pkg] ?: byCategory[pkg] else null
        if (ownTile != null) drawnByAerial[pkg] = ownTile else drawnByAerial.remove(pkg)
        val inMemory = synchronized(cache) { cache[pkg] }?.takeIf { it.stamp == stamp && it.width == TileArtFactory.W }
        if (inMemory == null && later) {
            TileDiskCache.readHeader(pkg, TileDiskCache.key(stamp))?.let { (label, tint) ->
                return AppEntry(pkg, label, ComponentName(pkg, info.name), leanback, !system, placeholder(tint))
            }
        }
        val cached = inMemory
            ?: runCatching { TileDiskCache.read(pkg, TileDiskCache.key(stamp)) }.getOrNull()?.let { (label, art) ->
                Cached(stamp, TileArtFactory.W, label, art).also { synchronized(cache) { cache[pkg] = it } }
            }
        val label: String
        val art: TileArt
        if (cached != null) {
            label = cached.label
            art = cached.art
        } else {
            label = runCatching { info.loadLabel(pm)?.toString() }.getOrNull().orEmpty().trim().ifEmpty { pkg }
            val banner = banner(pm, info, leanback)
            val own = if (stockHome) SystemTile.Home
                else if (system) SYSTEM_TILES[pkg] ?: byCategory[pkg]?.takeIf { banner == null } else null
            val custom = CustomIcons.load(pkg)
            val library = if (custom == null && (own == null || IconLibrary.pick(pkg) == IconLibrary.Pick.Library)) IconLibrary.load(pkg) else null
            val recipe = TileRecipes.of(pkg)
            art = runCatching {
                when {
                    custom != null -> TileArtFactory.fromBanner(android.graphics.drawable.BitmapDrawable(android.content.res.Resources.getSystem(), custom))
                    library != null -> TileArtFactory.fromBanner(android.graphics.drawable.BitmapDrawable(android.content.res.Resources.getSystem(), library))
                    own != null -> TileArtFactory.forSystem(own)
                    else -> fromApp(pm, info, banner, recipe)
                }
            }.recoverCatching {
                Log.w(TAG, "could not draw the tile of $pkg; using its icon", it)
                TileArtFactory.fromIcon(info.loadIcon(pm), null)
            }.recoverCatching {
                Log.w(TAG, "could not draw the icon of $pkg either; using the default one", it)
                TileArtFactory.fromIcon(pm.defaultActivityIcon, null)
            }.getOrThrow()
            synchronized(cache) { cache[pkg] = Cached(stamp, TileArtFactory.W, label, art) }
            runCatching { TileDiskCache.write(pkg, TileDiskCache.key(stamp), label, art) }
        }
        val removable = !system
        return AppEntry(pkg, label, ComponentName(pkg, info.name), leanback, removable, art)
    }
}
