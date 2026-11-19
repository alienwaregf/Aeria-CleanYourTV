package com.gathin.tvhome.ui.control

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.draw.drawBehind
import android.view.KeyEvent as NativeKeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.gathin.tvhome.ui.components.smoothBackground
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.gathin.tvhome.CcPage
import com.gathin.tvhome.HomeViewModel
import com.gathin.tvhome.Overlay
import com.gathin.tvhome.R
import com.gathin.tvhome.apps.SourceLooks
import com.gathin.tvhome.settings.AerialSettings
import com.gathin.tvhome.system.HisenseLaserPanel
import com.gathin.tvhome.system.HomeStatus
import com.gathin.tvhome.system.LaunchFallback
import com.gathin.tvhome.system.NetKind
import com.gathin.tvhome.system.OutputDevice
import com.gathin.tvhome.system.SourceKind
import com.gathin.tvhome.system.Sounds
import com.gathin.tvhome.system.SystemProbe
import com.gathin.tvhome.ui.components.Pressable
import com.gathin.tvhome.ui.components.consumeTaps
import com.gathin.tvhome.ui.components.dismissOnTap
import com.gathin.tvhome.ui.components.liquidGlass
import com.gathin.tvhome.ui.home.MenuRow
import com.gathin.tvhome.ui.home.VpnBadge
import com.gathin.tvhome.ui.home.dateText
import com.gathin.tvhome.ui.home.timeText
import com.gathin.tvhome.ui.theme.Glyph
import com.gathin.tvhome.ui.theme.GlyphIcon
import com.gathin.tvhome.ui.theme.Motion
import com.gathin.tvhome.ui.theme.Palette
import com.gathin.tvhome.ui.theme.Type
import com.gathin.tvhome.update.SelfCompile
import java.time.LocalDateTime

@Composable
fun ControlCenter(
    visible: Boolean,
    page: CcPage,
    vm: HomeViewModel,
    now: LocalDateTime,
    onOpen: (android.content.Intent) -> Unit,
    onClose: () -> Unit,
    warm: Boolean = false,
    onShelf: Boolean = false,
) {
    BackHandler(enabled = visible) {
        when (page) {
            CcPage.Main -> onClose()
            CcPage.Inputs -> vm.overlay = Overlay.ControlCenter(CcPage.Main)
            CcPage.InputSettings -> vm.overlay = Overlay.ControlCenter(CcPage.Inputs)
            CcPage.Setup -> vm.overlay = Overlay.ControlCenter(CcPage.Main)
        }
    }

    LaunchedEffect(visible) { if (visible) vm.refreshPanel() }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(220, easing = Motion.EaseOut)),
        exit = fadeOut(tween(180, easing = Motion.EaseOut)),
        modifier = Modifier.fillMaxSize(),
    ) {
        Box(Modifier.fillMaxSize().dismissOnTap(onClose).background(Color.Black.copy(alpha = 0.28f)))
    }

    Box(Modifier.fillMaxSize().padding(top = 24.dp, end = 32.dp), contentAlignment = Alignment.TopEnd) {
        if (warm && !visible) {
            Column(Modifier.graphicsLayer { alpha = 0.01f }.width(300.dp).liquidGlass(24.dp).padding(16.dp)) {
                MainPage(vm, now, takeFocus = false, poll = false, onOpen = onOpen)
            }
        }
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(200, easing = Motion.EaseOut)) + scaleIn(tween(280, easing = Motion.Drawer), initialScale = 0.9f, transformOrigin = TransformOrigin(1f, 0f)),
            exit = fadeOut(tween(160, easing = Motion.EaseOut)) + scaleOut(tween(160, easing = Motion.EaseOut), targetScale = 0.95f, transformOrigin = TransformOrigin(1f, 0f)),
        ) {
            Column(
                Modifier
                    .width(300.dp)
                    .consumeTaps()
                    .liquidGlass(24.dp, seeThroughFilm = onShelf),
            ) {
                AnimatedContent(
                    targetState = page,
                    transitionSpec = {
                        (fadeIn(tween(130, easing = Motion.EaseOut)) togetherWith fadeOut(tween(110, easing = Motion.EaseOut)))
                            .using(SizeTransform { _, _ -> tween(0) })
                    },
                    label = "cc-page",
                ) { p ->
                    Box(Modifier.padding(16.dp)) {
                        when (p) {
                            CcPage.Main -> MainPage(vm, now, takeFocus = visible, poll = visible, onOpen = onOpen)
                            CcPage.Inputs -> InputsPage(vm, visible, onOpen)
                            CcPage.InputSettings -> InputSettingsPage(vm, visible)
                            CcPage.Setup -> SetupPage(vm, visible, onOpen)
                        }
                    }
                }
            }
        }
    }
}


@Composable
private fun MainPage(
    vm: HomeViewModel,
    now: LocalDateTime,
    takeFocus: Boolean,
    poll: Boolean,
    onOpen: (android.content.Intent) -> Unit,
) {
    val context = LocalContext.current
    val laser = remember { HisenseLaserPanel.usable(context) }
    val showsVolume = remember { OutputDevice.showsVolume(context) }
    val showsBrightness = remember { OutputDevice.showsBrightness() }
    PollBrightness(vm, laser, poll)
    val first = remember { FocusRequester() }
    val networkFocus = remember { FocusRequester() }
    val settingsFocus = remember { FocusRequester() }
    var cameFrom by remember { mutableStateOf(first) }
    LaunchedEffect(poll) {
        if (!poll) return@LaunchedEffect
        while (vm.sources.isEmpty()) {
            vm.refreshSources()
            delay(3000)
        }
    }
    LaunchedEffect(Unit) {
        if (!takeFocus) return@LaunchedEffect
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }

    Column {
        Row(verticalAlignment = Alignment.Bottom) {
            Column(Modifier.weight(1f)) {
                BasicText(timeText(now), style = Type.SmallClock.copy(fontSize = Type.LargeTitle.fontSize * 1.05f))
                BasicText(dateText(now), style = Type.Callout)
            }
            val net = vm.network
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                GlyphIcon(
                    when (net.kind) { NetKind.Wifi -> Glyph.Wifi; NetKind.Ethernet -> Glyph.Ethernet; NetKind.Offline -> Glyph.Offline; NetKind.Other -> Glyph.Globe },
                    Palette.SecondaryLabel, 14.dp, level = net.level,
                )
                BasicText(
                    when (net.kind) {
                        NetKind.Wifi -> net.name ?: stringResource(R.string.net_wifi)
                        NetKind.Ethernet -> stringResource(R.string.net_ethernet)
                        NetKind.Offline -> stringResource(R.string.net_offline)
                        NetKind.Other -> stringResource(R.string.net_other)
                    },
                    style = Type.Caption, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.widthIn(max = 110.dp),
                )
                if (net.vpn) VpnBadge(Palette.SecondaryLabel, fontSize = 9.sp)
            }
        }

        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            RoundButton(
                Glyph.Input, stringResource(R.string.cc_inputs),
                focusRequester = first,
                modifier = Modifier.onFocusChanged { if (it.isFocused) cameFrom = first },
            ) {
                vm.overlay = if (vm.sources.isEmpty() && SystemProbe.openInputPicker(context)) Overlay.None
                else Overlay.ControlCenter(CcPage.Inputs)
            }
            RoundButton(
                when (vm.network.kind) { NetKind.Ethernet -> Glyph.Ethernet; NetKind.Offline -> Glyph.Offline; NetKind.Other -> Glyph.Globe; else -> Glyph.Wifi },
                stringResource(R.string.cc_network),
                focusRequester = networkFocus,
                modifier = Modifier.onFocusChanged { if (it.isFocused) cameFrom = networkFocus },
            ) {
                vm.whenUnlocked {
                    val pages = SystemProbe.networkSettingsIntents(context, ethernet = vm.network.kind == NetKind.Ethernet)
                    LaunchFallback.arm(pages.drop(1))
                    onOpen(pages.first())
                }
            }
            RoundButton(
                Glyph.Gear, stringResource(R.string.cc_settings),
                focusRequester = settingsFocus,
                modifier = Modifier.onFocusChanged { if (it.isFocused) cameFrom = settingsFocus },
            ) {
                vm.whenUnlocked {
                    LaunchFallback.arm(SystemProbe.settingsFallbacks(context))
                    onOpen(SystemProbe.settingsIntent(context))
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        if (showsVolume) {
            VolumeRow(vm, cameFrom)
            Spacer(Modifier.height(6.dp))
        }
        if (showsBrightness) BrightnessRow(vm, laser)

        Spacer(Modifier.height(6.dp))
        MenuRow(
            Glyph.Photo,
            stringResource(R.string.cc_personalize),
            onClick = { vm.overlay = Overlay.Wallpaper },
        )
        vm.bootTargetLabel()?.let { name ->
            MenuRow(
                Glyph.Power,
                stringResource(R.string.cc_boot),
                onClick = { vm.overlay = Overlay.ControlCenter(CcPage.InputSettings) },
                trailing = "$name ›",
            )
        }
        if (vm.homeStatus == HomeStatus.NotDefault) {
            MenuRow(Glyph.Shield, stringResource(R.string.cc_setup_title), onClick = { vm.overlay = Overlay.ControlCenter(CcPage.Setup) }, trailing = "›")
        }

        LaunchedEffect(Unit) { SelfCompile.recheck(context) }
        if (SelfCompile.offered) {
            MenuRow(Glyph.Download, stringResource(R.string.cc_self_compile), onClick = { SelfCompile.run(context, auto = false) })
        }

        Spacer(Modifier.height(8.dp))
        val (dot, text) = when (vm.homeStatus) {
            HomeStatus.Default -> Palette.Green to stringResource(R.string.cc_home_default)
            HomeStatus.Guarded -> Palette.Green to stringResource(R.string.cc_home_guarded)
            HomeStatus.NotDefault -> Palette.Orange to stringResource(R.string.cc_home_not_default)
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 14.dp)) {
            Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
            Spacer(Modifier.width(8.dp))
            BasicText(text, style = Type.Caption, modifier = Modifier.weight(1f))
            BasicText("CleanYourTV懒人版", style = Type.Caption.copy(color = Palette.TertiaryLabel), modifier = Modifier.padding(end = 14.dp))
        }
    }
}

@Composable
private fun SetupPage(vm: HomeViewModel, takeFocus: Boolean, onOpen: (android.content.Intent) -> Unit) {
    val context = LocalContext.current
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (!takeFocus) return@LaunchedEffect
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    val guard = remember { SystemProbe.guardSettingsIntent(context) }
    val home = remember { SystemProbe.homeSettingsIntent(context) }
    val revival = remember { SystemProbe.revivalSettingsIntent(context) }
    Column {
        MenuRow(Glyph.ChevronLeft, stringResource(R.string.cc_setup_title), focusRequester = first, onClick = { vm.overlay = Overlay.ControlCenter(CcPage.Main) })
        BasicText(stringResource(R.string.cc_setup_body), style = Type.Caption, modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp))
        guard?.let { MenuRow(Glyph.Shield, stringResource(R.string.cc_setup_guard), onClick = { vm.whenUnlocked { onOpen(it) } }, trailing = stringResource(R.string.cc_setup_guard_where) + " ›") }
        home?.let { MenuRow(Glyph.Check, stringResource(R.string.cc_setup_home), onClick = { vm.whenUnlocked { onOpen(it) } }, trailing = "›") }
        revival?.let { MenuRow(Glyph.Moon, stringResource(R.string.cc_setup_revival), onClick = { vm.whenUnlocked { onOpen(it) } }, trailing = stringResource(R.string.cc_setup_revival_where) + " ›") }
    }
}

@Composable
private fun RoundButton(
    glyph: Glyph,
    label: String,
    focusRequester: FocusRequester? = null,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Pressable(onClick = onClick, modifier = modifier, focusRequester = focusRequester, focusedScale = 1.1f) { state ->
        val bg by animateColorAsState(if (state.focused) Palette.Focus else Palette.Fill, tween(140, easing = Motion.EaseOut), label = "round-bg")
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.width(64.dp)) {
            Box(Modifier.size(48.dp).clip(CircleShape).drawBehind { drawRect(bg) }, contentAlignment = Alignment.Center) {
                GlyphIcon(glyph, if (state.focused) Palette.OnFocus else Palette.Label, 20.dp)
            }
            Spacer(Modifier.height(6.dp))
            BasicText(
                label, maxLines = 1, overflow = TextOverflow.Ellipsis,
                style = Type.Caption.copy(color = if (state.focused) Palette.Label else Palette.SecondaryLabel, textAlign = TextAlign.Center),
            )
        }
    }
}

@Composable
private fun SliderRow(
    glyph: Glyph,
    level: Float,
    value: String,
    steps: Int,
    onStep: (Int) -> Unit,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val atLevel by rememberUpdatedState(level)
    val doStep by rememberUpdatedState(onStep)
    val step = { by: Int ->
        if ((by > 0 && atLevel >= 0.999f) || (by < 0 && atLevel <= 0.001f)) Sounds.limit() else Sounds.step()
        doStep(by)
    }
    val stepCount by rememberUpdatedState(steps.coerceAtLeast(1))
    Pressable(
        onClick = onClick,
        focusedScale = 1.03f,
        modifier = modifier.fillMaxWidth(),
        onKey = { e ->
            val code = e.nativeKeyEvent.keyCode
            if (code != NativeKeyEvent.KEYCODE_DPAD_LEFT && code != NativeKeyEvent.KEYCODE_DPAD_RIGHT) return@Pressable false
            if (e.type == KeyEventType.KeyDown) step(if (code == NativeKeyEvent.KEYCODE_DPAD_RIGHT) 1 else -1)
            true
        },
    ) { state ->
        val bg by animateColorAsState(if (state.focused) Palette.Focus else Palette.Fill, tween(140, easing = Motion.EaseOut), label = "slider-bg")
        val fg = if (state.focused) Palette.OnFocus else Palette.Label
        val fraction by animateFloatAsState(level, spring(dampingRatio = 1f, stiffness = 700f), label = "slider-level")
        Row(
            Modifier
                .fillMaxWidth()
                .pointerInput(Unit) {
                    var travelled = 0f
                    detectHorizontalDragGestures(onDragStart = { travelled = 0f }) { change, dx ->
                        change.consume()
                        travelled += dx
                        val pitch = size.width.toFloat() / stepCount
                        while (travelled >= pitch) { step(1); travelled -= pitch }
                        while (travelled <= -pitch) { step(-1); travelled += pitch }
                    }
                }
                .smoothBackground({ bg }, 14.dp).height(40.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            GlyphIcon(glyph, fg, 18.dp, level = level)
            Canvas(Modifier.weight(1f).height(6.dp)) {
                val r = CornerRadius(size.height / 2)
                drawRoundRect(fg.copy(alpha = 0.2f), cornerRadius = r)
                drawRoundRect(fg, size = Size(size.width * fraction, size.height), cornerRadius = r)
            }
            BasicText(
                value,
                style = Type.Headline.copy(color = fg, textAlign = TextAlign.End),
                modifier = Modifier.widthIn(min = 28.dp),
            )
        }
    }
}

@Composable
private fun VolumeRow(vm: HomeViewModel, returnTo: FocusRequester) {
    val volume = vm.volume
    SliderRow(
        glyph = if (volume.muted) Glyph.SpeakerMuted else Glyph.Speaker,
        level = if (volume.muted) 0f else volume.fraction,
        value = if (volume.muted) stringResource(R.string.cc_muted) else volume.level.toString(),
        steps = volume.max,
        onStep = vm::adjustVolume,
        modifier = Modifier.focusProperties { up = returnTo },
        onClick = vm::toggleMute,
    )
}

@Composable
private fun PollBrightness(vm: HomeViewModel, laser: Boolean, enabled: Boolean) {
    val context = LocalContext.current
    val xgimi = remember { SystemProbe.xgimiBrightnessLevel() != null }
    LaunchedEffect(xgimi, laser, enabled) {
        if (!enabled || (!xgimi && !laser)) return@LaunchedEffect
        if (xgimi) vm.discoverBrightnessBoost()
        while (isActive) {
            delay(700)
            vm.refreshBrightness()
        }
    }
}

@Composable
private fun BrightnessRow(vm: HomeViewModel, laser: Boolean) {
    val context = LocalContext.current
    val xgimi = remember { SystemProbe.xgimiBrightnessLevel() != null }
    val level = vm.brightness
    if (level == null) {
        MenuRow(Glyph.Sun, stringResource(R.string.cc_brightness), onClick = {
            vm.overlay = Overlay.None
            SystemProbe.openPictureSettings(context)
        }, trailing = stringResource(R.string.cc_brightness_in_settings) + " ›")
    } else {
        SliderRow(
            glyph = Glyph.Sun,
            level = level,
            value = when {
                vm.brightnessBoost == true -> "10+"
                laser -> Math.round(level * HisenseLaserPanel.LEVEL_MAX).toString()
                else -> Math.round(level * 100).toString()
            },
            steps = when {
                laser -> HisenseLaserPanel.LEVEL_MAX
                xgimi -> SystemProbe.xgimiBrightnessMax
                else -> 20
            },
            onStep = vm::adjustBrightness,
            onClick = {},
        )
    }
}

@Composable
private fun InputsPage(vm: HomeViewModel, takeFocus: Boolean, onOpen: (android.content.Intent) -> Unit) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (!takeFocus) return@LaunchedEffect
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    Column(Modifier.heightIn(max = 470.dp).verticalScroll(rememberScrollState())) {
        MenuRow(Glyph.ChevronLeft, stringResource(R.string.cc_inputs), onClick = { vm.overlay = Overlay.ControlCenter(CcPage.Main) })
        Spacer(Modifier.height(8.dp))
        if (vm.sources.isEmpty()) {
            BasicText(stringResource(R.string.cc_no_inputs), style = Type.Callout, modifier = Modifier.padding(14.dp))
        }
        val connected = stringResource(R.string.input_connected)
        vm.sources.forEachIndexed { i, source ->
            MenuRow(
                glyph = when (source.kind) {
                    SourceKind.Hdmi, SourceKind.DisplayPort -> Glyph.Hdmi
                    SourceKind.Tuner -> Glyph.Antenna
                    SourceKind.Av -> Glyph.Av
                    SourceKind.Usb -> Glyph.Usb
                    SourceKind.Other -> Glyph.Input
                },
                text = source.label,
                trailing = SourceLooks.of(source.id)?.let { it.name ?: it.device?.let { d -> stringResource(d.label) } }
                    ?: source.deviceName ?: if (source.connected) connected else null,
                focusRequester = if (i == 0) first else null,
                onClick = { onOpen(source.intent) },
            )
        }
        if (vm.sources.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            MenuRow(Glyph.Gear, stringResource(R.string.cc_inputs_settings), onClick = { vm.overlay = Overlay.ControlCenter(CcPage.InputSettings) })
        }
    }
}

@Composable
private fun InputSettingsPage(vm: HomeViewModel, takeFocus: Boolean) {
    val first = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (!takeFocus) return@LaunchedEffect
        withFrameNanos { }
        runCatching { first.requestFocus() }
    }
    fun name(source: com.gathin.tvhome.system.TvSource) = source.deviceName?.let { "${source.label} · $it" } ?: source.label
    Column(Modifier.heightIn(max = 470.dp).verticalScroll(rememberScrollState())) {
        MenuRow(Glyph.ChevronLeft, stringResource(R.string.cc_inputs_settings), onClick = { vm.overlay = Overlay.ControlCenter(CcPage.Inputs) })

        Spacer(Modifier.height(10.dp))
        BasicText(stringResource(R.string.cc_inputs_pin), style = Type.Callout, modifier = Modifier.padding(start = 14.dp, bottom = 6.dp))
        vm.sources.forEachIndexed { i, source ->
            val pinned = source.id in AerialSettings.pinnedSources
            ChoiceRow(
                text = name(source),
                selected = pinned,
                focusRequester = if (i == 0) first else null,
                onClick = { vm.setSourcePinned(source, !pinned) },
            )
        }
        BasicText(
            stringResource(R.string.cc_inputs_pin_hint),
            style = Type.Caption,
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 8.dp),
        )

        Spacer(Modifier.height(18.dp))
        BasicText(stringResource(R.string.cc_inputs_boot), style = Type.Callout, modifier = Modifier.padding(start = 14.dp, bottom = 6.dp))
        ChoiceRow(
            text = stringResource(R.string.cc_inputs_boot_none),
            selected = !AerialSettings.hasBootTarget,
            focusRequester = null,
            onClick = { vm.chooseBootTarget() },
        )
        AerialSettings.bootApp?.let { pkg ->
            ChoiceRow(
                text = vm.entry(pkg)?.label ?: pkg,
                selected = true,
                focusRequester = null,
                onClick = {},
            )
        }
        vm.sources.forEach { source ->
            ChoiceRow(
                text = name(source),
                selected = source.id == AerialSettings.bootSource,
                focusRequester = null,
                onClick = { vm.chooseBootTarget(sourceId = source.id) },
            )
        }
        BasicText(
            stringResource(R.string.cc_inputs_boot_hint),
            style = Type.Caption,
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 8.dp),
        )

        Spacer(Modifier.height(18.dp))
        BasicText(stringResource(R.string.cc_inputs_cec), style = Type.Callout, modifier = Modifier.padding(start = 14.dp, bottom = 6.dp))
        ChoiceRow(stringResource(R.string.st_on), AerialSettings.cecSwitch, null) { AerialSettings.chooseCecSwitch(true) }
        ChoiceRow(stringResource(R.string.st_off), !AerialSettings.cecSwitch, null) { AerialSettings.chooseCecSwitch(false) }
        BasicText(
            stringResource(R.string.cc_inputs_cec_hint),
            style = Type.Caption,
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 8.dp),
        )
    }
}

@Composable
private fun ChoiceRow(text: String, selected: Boolean, focusRequester: FocusRequester?, onClick: () -> Unit) {
    Pressable(onClick = onClick, focusRequester = focusRequester, focusedScale = 1.03f, modifier = Modifier.fillMaxWidth()) { state ->
        val bg by animateColorAsState(if (state.focused) Palette.Focus else Color.Transparent, tween(140, easing = Motion.EaseOut), label = "choice-bg")
        val fg = if (state.focused) Palette.OnFocus else Palette.Label
        Row(
            Modifier.fillMaxWidth().smoothBackground({ bg }, 12.dp).height(44.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(text, style = Type.Body.copy(color = fg), modifier = Modifier.weight(1f))
            if (selected) GlyphIcon(Glyph.Check, fg, 16.dp)
        }
    }
}
