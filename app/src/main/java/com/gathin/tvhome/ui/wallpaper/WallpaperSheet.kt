package com.gathin.tvhome.ui.wallpaper

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.gathin.tvhome.R
import com.gathin.tvhome.SystemSlimmingActivity
import com.gathin.tvhome.settings.AerialSettings
import com.gathin.tvhome.settings.ClockHours
import com.gathin.tvhome.settings.FocusStyle
import com.gathin.tvhome.settings.UiSounds
import com.gathin.tvhome.settings.Effects
import com.gathin.tvhome.system.Sounds
import com.gathin.tvhome.ui.components.ActionChip
import com.gathin.tvhome.ui.components.Chip
import com.gathin.tvhome.ui.components.Choices
import com.gathin.tvhome.ui.components.Group
import com.gathin.tvhome.ui.components.Pressable
import com.gathin.tvhome.ui.components.SettingRow
import com.gathin.tvhome.ui.components.consumeTaps
import com.gathin.tvhome.ui.components.dismissOnTap
import com.gathin.tvhome.ui.components.liquidGlass
import com.gathin.tvhome.ui.components.smoothBackground
import com.gathin.tvhome.ui.components.smoothBorder
import com.gathin.tvhome.ui.components.smoothCut
import com.gathin.tvhome.ui.theme.Glyph
import com.gathin.tvhome.ui.theme.GlyphIcon
import com.gathin.tvhome.ui.theme.Motion
import com.gathin.tvhome.ui.theme.Palette
import com.gathin.tvhome.ui.theme.Type
import com.gathin.tvhome.wallpaper.PhoneServer
import com.gathin.tvhome.wallpaper.AerialFormats
import com.gathin.tvhome.wallpaper.AerialQuality
import com.gathin.tvhome.wallpaper.AerialSet
import com.gathin.tvhome.wallpaper.AerialStream
import com.gathin.tvhome.wallpaper.ScreensaverMode
import com.gathin.tvhome.wallpaper.Wallpaper
import com.gathin.tvhome.wallpaper.WallpaperKind
import com.gathin.tvhome.wallpaper.Wallpapers
import com.gathin.tvhome.tmdb.TmdbTrending
import com.gathin.tvhome.weather.Weather
import com.gathin.tvhome.weather.WeatherKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt
import androidx.compose.ui.geometry.Size

@Composable
fun WallpaperSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    hiddenCount: Int,
    onHiddenApps: () -> Unit,
    onStartScreensaver: () -> Unit,
    onPin: (on: Boolean) -> Unit,
    onSettled: (Boolean) -> Unit = {},
    warm: Boolean = false,
) {
    val context = LocalContext.current
    var phoneUrl by remember { mutableStateOf<String?>(null) }
    var qr by remember { mutableStateOf<ImageBitmap?>(null) }

    BackHandler(enabled = visible, onBack = onDismiss)

    LaunchedEffect(visible) {
        if (!visible) return@LaunchedEffect
        val url = withContext(Dispatchers.IO) { PhoneServer.start(context) }
        phoneUrl = url
        qr = url?.let { withContext(Dispatchers.Default) { PhoneServer.qr(it).asImageBitmap() } }
    }

    val shown = remember { MutableTransitionState(false) }
    shown.targetState = visible
    val settled = shown.isIdle && shown.currentState
    LaunchedEffect(settled) { onSettled(settled) }

    AnimatedVisibility(visible = visible, enter = fadeIn(tween(220, easing = Motion.EaseOut)), exit = fadeOut(tween(180, easing = Motion.EaseOut)), modifier = Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().dismissOnTap(onDismiss).drawBehind {
                val h = if (settled) size.height - SheetCovers.toPx() else size.height
                drawRect(Color.Black.copy(alpha = 0.25f), size = Size(size.width, h))
            },
        )
    }

    val sheet: @Composable (takeFocus: Boolean) -> Unit = { takeFocus ->
        Row(
            Modifier
                .fillMaxWidth()
                .height(SheetHeight)
                .consumeTaps()
                .liquidGlass(SheetCorner, bottomCorners = false)
                .padding(start = 40.dp, end = 32.dp, top = 24.dp),
        ) {
            val scroll = rememberScrollState()
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(scroll).latestBringIntoView(scroll)) {
                BasicText(stringResource(R.string.wp_title), style = Type.LargeTitle)
                Spacer(Modifier.height(16.dp))
                SectionTitle(R.string.wp_wallpaper)
                WallpaperRows(takeFocus)
                if (Wallpapers.items.any { it.kind == WallpaperKind.Source }) Group { note ->
                    Choices(
                        R.string.st_source_sound, null, note,
                        listOf(true to R.string.st_on, false to R.string.st_off),
                        AerialSettings.sourceSound, AerialSettings::chooseSourceSound, first = true,
                    )
                }
                if (Wallpapers.isVideo()) Group { note ->
                    Choices(
                        R.string.st_play, stringResource(R.string.st_play_hint), note,
                        listOf(false to R.string.st_play_still, true to R.string.st_play_always),
                        AerialSettings.wallpaperAlwaysMoves, AerialSettings::chooseWallpaperAlwaysMoves, first = true,
                    )
                }

                Spacer(Modifier.height(6.dp))
                SectionTitle(R.string.st_home_layout)
                Group { note ->
                    Choices(
                        R.string.st_layout, stringResource(R.string.st_home_layout_hint), note,
                        listOf(true to R.string.st_home_one_row, false to R.string.st_home_standard),
                        AerialSettings.oneRowHome, AerialSettings::chooseOneRowHome, first = true,
                    )
                    if (AerialSettings.oneRowHome) Choices(
                        R.string.st_peek, stringResource(R.string.st_peek_hint), note,
                        listOf(true to R.string.st_peek_show, false to R.string.st_peek_hide),
                        AerialSettings.peekNextRow, AerialSettings::choosePeekNextRow,
                    )
                    Choices(
                        R.string.st_columns, stringResource(R.string.st_columns_hint), note,
                        AerialSettings.HOME_COLUMNS.map { it to it.toString() },
                        AerialSettings.homeColumns, AerialSettings::chooseHomeColumns,
                    )
                    Choices(
                        R.string.st_plate, stringResource(R.string.st_plate_hint), note,
                        listOf(true to R.string.st_plate_glass, false to R.string.st_plate_none),
                        AerialSettings.homePlate, AerialSettings::chooseHomePlate,
                    )
                    Choices(
                        R.string.st_names, stringResource(R.string.st_names_hint), note,
                        listOf(false to R.string.st_names_focused, true to R.string.st_names_all),
                        AerialSettings.showAllNames, AerialSettings::chooseShowAllNames,
                    )
                    SettingRow(R.string.st_hidden_apps, stringResource(R.string.st_hidden_apps_hint), note) {
                        ActionChip(
                            if (hiddenCount == 0) stringResource(R.string.st_hidden_none) else stringResource(R.string.st_hidden_count, hiddenCount),
                            Glyph.ChevronRight,
                            onHiddenApps,
                        )
                    }
                }
                Spacer(Modifier.height(10.dp))
                Group { note ->
                    Choices(
                        R.string.st_clock, stringResource(R.string.st_clock_hint), note,
                        listOf(false to R.string.st_clock_left, true to R.string.st_clock_right),
                        AerialSettings.clockRight, AerialSettings::chooseClockRight, first = true,
                    )
                    Choices(
                        R.string.st_clock_size, stringResource(R.string.st_clock_size_hint), note,
                        listOf(false to R.string.st_clock_small, true to R.string.st_clock_large),
                        AerialSettings.largeClock, AerialSettings::chooseLargeClock,
                    )
                    Choices(
                        R.string.st_clock_hours, stringResource(R.string.st_clock_hours_hint), note,
                        listOf(ClockHours.System to R.string.st_clock_hours_system, ClockHours.Twelve to R.string.st_clock_hours_12, ClockHours.TwentyFour to R.string.st_clock_hours_24),
                        AerialSettings.clockHours, AerialSettings::chooseClockHours,
                    )
                    Choices(
                        R.string.wp_weather, null, note,
                        listOf(true to R.string.st_on, false to R.string.st_off),
                        AerialSettings.showWeather, AerialSettings::chooseShowWeather,
                    )
                }
                Spacer(Modifier.height(10.dp))
                Group { note ->
                    Choices(
                        R.string.st_focus_style,
                        stringResource(if (AerialSettings.focusStyle == FocusStyle.HighContrast) R.string.st_focus_high_hint else R.string.st_focus_standard_hint),
                        note,
                        listOf(FocusStyle.HighContrast to R.string.st_focus_high, FocusStyle.Standard to R.string.st_focus_standard),
                        AerialSettings.focusStyle, AerialSettings::chooseFocusStyle, first = true,
                    )
                    Choices(
                        R.string.st_sounds,
                        stringResource(if (AerialSettings.uiSounds == UiSounds.Tv) R.string.st_sounds_tv_hint else R.string.st_sounds_hint),
                        note,
                        listOf(UiSounds.Tv to R.string.st_sounds_tv, UiSounds.Aerial to R.string.st_sounds_aerial, UiSounds.Off to R.string.st_off),
                        AerialSettings.uiSounds, { AerialSettings.chooseUiSounds(it); Sounds.prepare() },
                    )
                    Choices(
                        R.string.st_effects,
                        stringResource(
                            if (AerialSettings.effects == Effects.Auto && AerialSettings.lowEnd) R.string.st_effects_auto_lite_hint
                            else R.string.st_effects_hint,
                        ),
                        note,
                        listOf(Effects.Auto to R.string.st_effects_auto, Effects.Full to R.string.st_effects_full, Effects.Lite to R.string.st_effects_lite),
                        AerialSettings.effects, AerialSettings::chooseEffects,
                    )
                }

                Spacer(Modifier.height(22.dp))
                SectionTitle(R.string.wp_screensaver)
                Group { note ->
                    SettingRow(
                        R.string.ss_kind,
                        when {
                            Wallpapers.screensaver == ScreensaverMode.Videos -> stringResource(R.string.ss_videos_hint)
                            Wallpapers.screensaver == ScreensaverMode.Trending -> stringResource(R.string.ss_trending_hint)
                            Wallpapers.screensaver == ScreensaverMode.System -> stringResource(R.string.ss_system_hint)
                            AerialSettings.backStartsScreensaver -> stringResource(R.string.ss_back_hint)
                            else -> null
                        },
                        note, first = true,
                    ) { ScreensaverChoice(onStartScreensaver) }
                    val qualities = remember { AerialFormats.supported(context) }
                    if (qualities.size > 1) {
                        val keeps = stringResource(
                            R.string.ss_aerial_keeps,
                            remember { android.text.format.Formatter.formatShortFileSize(context, AerialStream.keepLimit(context)) },
                            AerialSet.MAX,
                        )
                        Choices(
                            R.string.ss_quality, keeps, note,
                            listOf(AerialQuality.Auto to R.string.ss_quality_auto, AerialQuality.Hd to R.string.ss_quality_hd) +
                                listOf(AerialQuality.Uhd to R.string.ss_quality_uhd, AerialQuality.DolbyVision to R.string.ss_quality_dv)
                                    .filter { it.first in qualities },
                            AerialSettings.aerialQuality, AerialSettings::chooseAerialQuality,
                        )
                    }
                    if (Wallpapers.screensaver == ScreensaverMode.Aerial || Wallpapers.screensaver == ScreensaverMode.Trending || Wallpapers.screensaver == ScreensaverMode.Videos) Choices(
                        R.string.ss_show_time, stringResource(R.string.ss_show_time_hint), note,
                        listOf(true to R.string.st_on, false to R.string.st_off),
                        AerialSettings.screensaverClock, AerialSettings::chooseScreensaverClock,
                    )
                    Choices(
                        R.string.ss_delay, null, note,
                        AerialSettings.screensaverChoices.map {
                            it to if (it == AerialSettings.NEVER) stringResource(R.string.ss_never) else stringResource(R.string.ss_minutes, it)
                        },
                        AerialSettings.screensaverMinutes, AerialSettings::chooseScreensaverMinutes,
                    )
                    Choices(
                        R.string.ss_back, stringResource(R.string.ss_back_choice_hint), note,
                        listOf(true to R.string.ss_back_on, false to R.string.ss_back_off),
                        AerialSettings.backStartsScreensaver, AerialSettings::chooseBackStartsScreensaver,
                    )
                }

                Spacer(Modifier.height(22.dp))
                SectionTitle(R.string.wp_weather)
                Group(stringResource(if (Weather.chosen) R.string.wp_weather_chosen else R.string.wp_weather_guessed)) { note ->
                    SettingRow(R.string.wp_weather_city, null, note, first = true) { WeatherValue() }
                }

                Spacer(Modifier.height(10.dp))
                Group(stringResource(R.string.st_pin_hint)) { note ->
                    SettingRow(R.string.st_pin, null, note, first = true) {
                        PinChoice(onPin)
                    }
                }
                Spacer(Modifier.height(10.dp))
                Group { note ->
                    SettingRow(R.string.system_slimming, stringResource(R.string.slimming_note), note, first = true) {
                        ActionChip(
                            stringResource(R.string.slimming_open),
                            Glyph.ChevronRight,
                        ) { context.startActivity(Intent(context, SystemSlimmingActivity::class.java)) }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
            Spacer(Modifier.width(28.dp))
            PhoneCard(phoneUrl, qr)
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        if (warm && !visible) Box(Modifier.graphicsLayer { alpha = 0.01f }) { sheet(false) }
        AnimatedVisibility(
            visibleState = shown,
            enter = fadeIn(tween(200, easing = Motion.EaseOut)) + slideInVertically(tween(380, easing = Motion.Drawer)) { it / 3 },
            exit = fadeOut(tween(160, easing = Motion.EaseOut)) + slideOutVertically(tween(200, easing = Motion.EaseOut)) { it / 4 },
        ) {
            sheet(true)
        }
    }
}

@Composable
private fun WallpaperRows(takeFocus: Boolean) {
    val items = Wallpapers.items
    val selected = Wallpapers.selectedId
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (!takeFocus) return@LaunchedEffect
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    val videosTitle = stringResource(R.string.wp_videos_rotate)
    val list = remember(items, selected, videosTitle) {
        buildList {
            addAll(items.filter { it.kind == WallpaperKind.Live })
            addAll(items.filter { it.kind == WallpaperKind.Trending })
            val videos = items.filter { it.kind == WallpaperKind.Video && it.id != Wallpapers.VIDEOS }
            if (videos.size > 1 || selected == Wallpapers.VIDEOS) add(Wallpaper(Wallpapers.VIDEOS, WallpaperKind.Video, videosTitle))
            addAll(videos)
            addAll(items.filter { it.kind == WallpaperKind.Auto })
            addAll(items.filter { it.kind == WallpaperKind.Source })
        }
    }
    var note by remember { mutableStateOf<String?>(null) }
    LazyRow(
        modifier = Modifier.offset(x = (-10).dp).drawWithContent {
            val focusRoom = 16.dp.toPx()
            clipRect(top = -focusRoom, bottom = size.height + focusRoom) {
                this@drawWithContent.drawContent()
            }
        },
        horizontalArrangement = Arrangement.spacedBy(18.dp),
        contentPadding = PaddingValues(start = 14.dp, end = 24.dp, top = 6.dp, bottom = 4.dp),
    ) {
        items(list, key = { it.id }) { wp ->
            val hint = wallpaperNote(wp)
            WallpaperCard(wp, if (wp === list.first()) first else null, onFocused = { note = hint })
        }
    }
    Box(Modifier.height(42.dp).padding(start = 4.dp, top = 8.dp)) {
        note?.let { BasicText(it, style = Type.Caption.copy(color = Palette.TertiaryLabel), maxLines = 2, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
private fun wallpaperNote(wp: Wallpaper): String? = when {
    wp.kind == WallpaperKind.Live -> stringResource(R.string.wp_live)
    wp.id == Wallpapers.VIDEOS -> stringResource(R.string.wp_videos_note)
    wp.kind == WallpaperKind.Video -> stringResource(R.string.wp_video_note)
    wp.kind == WallpaperKind.Source -> stringResource(R.string.wp_source_note)
    wp.kind == WallpaperKind.Auto -> stringResource(R.string.wp_auto_note)
    wp.kind == WallpaperKind.Trending -> stringResource(R.string.wp_trending_note)
    else -> null
}

private val CardCorner = 12.dp

@Composable
private fun WallpaperCard(wp: Wallpaper, focusRequester: FocusRequester?, onFocused: () -> Unit) {
    val selected = Wallpapers.selectedId == wp.id
    val picture = when (wp.kind) {
        WallpaperKind.Live, WallpaperKind.Video -> Wallpapers.pictureVersion
        WallpaperKind.Trending -> TmdbTrending.items.size
        else -> 0
    }
    val density = LocalDensity.current
    val cardW = with(density) { 168.dp.roundToPx() }
    val cardH = with(density) { 94.dp.roundToPx() }
    val corner = with(density) { CardCorner.toPx() }
    val thumb by produceState<ImageBitmap?>(null, wp.id, Wallpapers.items, picture, cardW, cardH) {
        value = withContext(Dispatchers.Default) {
            Wallpapers.thumbnail(wp.id)?.let { smoothCut(it, cardW, cardH, corner).asImageBitmap() }
        }
    }
    Pressable(onClick = { Wallpapers.select(wp.id) }, focusRequester = focusRequester, focusedScale = 1.08f) { state ->
        LaunchedEffect(state.focused) { if (state.focused) onFocused() }
        Column(Modifier.width(168.dp)) {
            Box(
                Modifier
                    .size(168.dp, 94.dp)
                    .smoothBackground(if (thumb == null) Palette.Fill else Color.Transparent, CardCorner)
                    .smoothBorder(
                        if (state.focused || selected) 3.dp else 1.dp,
                        when {
                            state.focused -> Palette.Focus
                            selected -> Palette.Focus.copy(alpha = 0.55f)
                            else -> Palette.Hairline
                        },
                        CardCorner,
                    ),
            ) {
                thumb?.let {
                    Canvas(Modifier.fillMaxSize()) {
                        drawImage(it, dstSize = IntSize(size.width.roundToInt(), size.height.roundToInt()), filterQuality = FilterQuality.Medium)
                    }
                }
                if (selected) {
                    Box(
                        Modifier.align(Alignment.TopEnd).padding(7.dp).size(22.dp).clip(CircleShape).background(Palette.Focus),
                        contentAlignment = Alignment.Center,
                    ) { GlyphIcon(Glyph.Check, Palette.OnFocus, 13.dp) }
                }
                if (wp.kind == WallpaperKind.Live || wp.kind == WallpaperKind.Video || wp.kind == WallpaperKind.Trending) {
                    GlyphIcon(Glyph.Play, Color.White, 22.dp, Modifier.align(Alignment.BottomStart).padding(7.dp))
                }
                if (wp.kind == WallpaperKind.Source) {
                    GlyphIcon(Glyph.Hdmi, Color.White, 34.dp, Modifier.align(Alignment.Center))
                }
            }
            Spacer(Modifier.height(6.dp))
            BasicText(
                wp.title, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = Type.Caption.copy(color = if (state.focused) Palette.Label else Palette.SecondaryLabel),
            )
        }
    }
}

@Composable
private fun SectionTitle(title: Int) {
    BasicText(stringResource(title), style = Type.Headline, modifier = Modifier.padding(start = 4.dp))
    Spacer(Modifier.height(10.dp))
}

@Composable
private fun PinChoice(onPin: (on: Boolean) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(false to R.string.st_pin_off, true to R.string.st_pin_on).forEach { (on, label) ->
            Chip(stringResource(label), on = AerialSettings.pinSet == on) {
                if (AerialSettings.pinSet != on) onPin(on)
            }
        }
    }
}

@Composable
private fun WeatherValue() {
    val w = Weather.now
    val place = Weather.place
    val kind = w?.let {
        stringResource(
            when (it.kind) {
                WeatherKind.Clear -> R.string.wx_clear
                WeatherKind.PartlyCloudy -> R.string.wx_partly_cloudy
                WeatherKind.LessCloudy -> R.string.wx_less_cloudy
                WeatherKind.Cloudy -> R.string.wx_cloudy
                WeatherKind.Fog -> R.string.wx_fog
                WeatherKind.Rain, WeatherKind.LightRain, WeatherKind.ModerateRain, WeatherKind.HeavyRain, WeatherKind.StormRain -> R.string.wx_rain
                WeatherKind.Shower -> R.string.wx_shower
                WeatherKind.Snow, WeatherKind.LightSnow, WeatherKind.ModerateSnow, WeatherKind.HeavySnow, WeatherKind.StormSnow -> R.string.wx_snow
                WeatherKind.Thunder -> R.string.wx_thunder
                WeatherKind.ThunderShower -> R.string.wx_thunder_shower
                WeatherKind.Haze -> R.string.wx_haze
                WeatherKind.Dust -> R.string.wx_dust
                WeatherKind.Sand -> R.string.wx_sand
                WeatherKind.Wind -> R.string.wx_wind
                WeatherKind.LightWind -> R.string.wx_light_wind
                WeatherKind.Typhoon -> R.string.wx_typhoon
                WeatherKind.Tornado -> R.string.wx_tornado
                WeatherKind.Hail -> R.string.wx_hail
                WeatherKind.Sleet -> R.string.wx_sleet
            },
        )
    }
    BasicText(
        if (w != null) listOfNotNull(place, (if (w.forecast.isNotEmpty()) "${w.celsius}°C" else stringResource(R.string.weather_temperature, w.celsius)) + " $kind").joinToString(" · ") else stringResource(R.string.wp_weather_none),
        style = Type.Body.copy(color = Palette.SecondaryLabel),
        modifier = Modifier.padding(end = 8.dp),
    )
}

@Composable
private fun ScreensaverChoice(onStart: () -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        listOf(
            ScreensaverMode.Aerial to R.string.ss_aerial,
            ScreensaverMode.Videos to R.string.ss_videos,
            ScreensaverMode.Trending to R.string.ss_trending,
            ScreensaverMode.System to R.string.ss_system,
        ).forEach { (mode, label) ->
            Chip(stringResource(label), on = Wallpapers.screensaver == mode) { Wallpapers.chooseScreensaver(mode) }
        }
        Spacer(Modifier.width(6.dp))
        Pressable(onClick = onStart, focusedScale = 1.06f) { state ->
            val bg by animateColorAsState(if (state.focused) Palette.Focus else Palette.FillStrong, tween(140, easing = Motion.EaseOut), label = "ss-start")
            val fg = if (state.focused) Palette.OnFocus else Palette.Label
            Row(
                Modifier.clip(RoundedCornerShape(50)).drawBehind { drawRect(bg) }.height(40.dp).padding(horizontal = 20.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                GlyphIcon(Glyph.Play, fg, 14.dp)
                BasicText(stringResource(R.string.ss_start), style = Type.Body.copy(color = fg))
            }
        }
    }
}

@Composable
private fun PhoneCard(url: String?, qr: ImageBitmap?) {
    Column(Modifier.width(220.dp).padding(top = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(176.dp).smoothBackground(Color.White, 18.dp).padding(14.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (qr != null) {
                Image(qr, contentDescription = null, contentScale = ContentScale.Fit, filterQuality = FilterQuality.None, modifier = Modifier.fillMaxSize())
            } else {
                GlyphIcon(Glyph.Offline, Color.Black.copy(alpha = 0.35f), 40.dp)
            }
        }
        Spacer(Modifier.height(14.dp))
        BasicText(stringResource(R.string.wp_phone_title), style = Type.Headline)
        Spacer(Modifier.height(4.dp))
        BasicText(
            stringResource(if (url != null) R.string.wp_phone_body else R.string.wp_phone_offline),
            style = Type.Caption.copy(textAlign = androidx.compose.ui.text.style.TextAlign.Center),
        )
        if (url != null) {
            Spacer(Modifier.height(6.dp))
            BasicText(url.removePrefix("http://"), style = Type.Caption.copy(color = Palette.SecondaryLabel))
        }
    }
}

private val SheetHeight = 360.dp
private val SheetCorner = 30.dp

val SheetCovers = SheetHeight - SheetCorner
