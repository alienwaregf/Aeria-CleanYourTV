package com.gathin.tvhome

import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import com.gathin.tvhome.ui.wallpaper.latestBringIntoView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.relocation.BringIntoViewModifierNode
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.gathin.tvhome.ui.components.Pressable
import com.gathin.tvhome.ui.components.UhdCanvas
import com.gathin.tvhome.ui.components.consumeTaps
import com.gathin.tvhome.ui.components.dismissOnTap
import com.gathin.tvhome.ui.components.smoothBackground
import com.gathin.tvhome.ui.theme.LocalReducedMotion
import com.gathin.tvhome.ui.theme.Motion
import com.gathin.tvhome.ui.theme.Palette
import com.gathin.tvhome.ui.theme.Type
import com.gathin.tvhome.ui.theme.canvasDensity
import kotlinx.coroutines.launch

interface SlimmingCallbacks {
    fun onDailyToggle()
    fun onPick()
    fun onRetry()
    fun onRow(pkg: String)
    fun onDisable(includeOptional: Boolean)
    fun onRestore(includeOptional: Boolean)
    fun onPickerPick(folder: String)
    fun onPickerDismiss()
}

enum class SlimmingConfirm { DISABLE, RESTORE }

class SlimmingUiState {
    var device by mutableStateOf("")
    var listText by mutableStateOf("")
    var sourceText by mutableStateOf("")
    var statusText by mutableStateOf("")
    var dailyText by mutableStateOf("")
    var busy by mutableStateOf(false)
    var showPick by mutableStateOf(false)
    var showRetry by mutableStateOf(false)
    var showPicker by mutableStateOf(false)
    var confirm by mutableStateOf<SlimmingConfirm?>(null)
    var pickerFolders by mutableStateOf<List<String>>(emptyList())
    val recommended: SnapshotStateList<SystemSlimmingActivity.Row> = mutableStateListOf()
    val optional: SnapshotStateList<SystemSlimmingActivity.Row> = mutableStateListOf()
}

private fun Modifier.suppressBringIntoView(): Modifier = this then SuppressBringIntoViewElement()

private data class SuppressBringIntoViewElement(val dummy: Unit = Unit) : ModifierNodeElement<SuppressBringIntoViewNode>() {
    override fun create() = SuppressBringIntoViewNode()
    override fun update(node: SuppressBringIntoViewNode) {}
    override fun InspectorInfo.inspectableProperties() { name = "suppressBringIntoView" }
}

private class SuppressBringIntoViewNode : Modifier.Node(), BringIntoViewModifierNode {
    override suspend fun bringIntoView(childCoordinates: LayoutCoordinates, boundsProvider: () -> Rect?) {
    }
}

fun showSlimmingScreen(activity: ComponentActivity, ui: SlimmingUiState, callbacks: SlimmingCallbacks) {
    val uhd = UhdCanvas.scale(activity)
    val reducedMotion =
        Settings.Global.getFloat(activity.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
    val content: @Composable () -> Unit = {
        val canvas = Density(
            density = canvasDensity(activity.resources.displayMetrics),
            fontScale = LocalDensity.current.fontScale,
        )
        CompositionLocalProvider(LocalReducedMotion provides reducedMotion, LocalDensity provides canvas) {
            SlimmingScreen(ui, callbacks)
        }
    }
    if (Build.VERSION.SDK_INT >= 29 && uhd > 1f) {
        activity.setContentView(UhdCanvas.host(activity, ComposeView(activity).apply { setContent(content) }, uhd))
    } else {
        activity.setContent(content = content)
    }
}

@Composable
fun SlimmingScreen(ui: SlimmingUiState, cb: SlimmingCallbacks) {
    val firstRequester = remember { FocusRequester() }
    val pickRequester = remember { FocusRequester() }
    val disableRequester = remember { FocusRequester() }
    val restoreRequester = remember { FocusRequester() }
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val scrollTop: (KeyEvent) -> Boolean = { e ->
        if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionUp && scroll.value > 0) {
            scope.launch { scroll.animateScrollTo(0) }
            true
        } else false
    }
    var pickerWasOpen by remember { mutableStateOf(false) }
    val firstRow = ui.recommended.firstOrNull() ?: ui.optional.firstOrNull()
    val pairCount = maxOf(ui.recommended.size, ui.optional.size)

    LaunchedEffect(ui.showPicker) {
        if (ui.showPicker) pickerWasOpen = true
        else if (pickerWasOpen) {
            pickerWasOpen = false
            withFrameNanos { }
            runCatching { pickRequester.requestFocus() }
        }
    }
    var confirmWasOpen by remember { mutableStateOf(false) }
    var lastConfirm by remember { mutableStateOf<SlimmingConfirm?>(null) }
    LaunchedEffect(ui.confirm) {
        val c = ui.confirm
        if (c != null) {
            confirmWasOpen = true
            lastConfirm = c
        } else if (confirmWasOpen) {
            confirmWasOpen = false
            withFrameNanos { }
            runCatching {
                when (lastConfirm) {
                    SlimmingConfirm.DISABLE -> disableRequester.requestFocus()
                    SlimmingConfirm.RESTORE -> restoreRequester.requestFocus()
                    null -> {}
                }
            }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(
            Modifier.fillMaxSize().padding(start = 72.dp, end = 72.dp, top = 38.dp, bottom = 38.dp).verticalScroll(scroll).latestBringIntoView(scroll),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column {
                    BasicText(stringResource(R.string.system_slimming), style = Type.LargeTitle)
                    if (ui.device.isNotEmpty()) {
                        BasicText(
                            ui.device,
                            style = Type.Body.copy(color = Palette.SecondaryLabel),
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                    if (ui.listText.isNotEmpty()) {
                        BasicText(ui.listText, style = Type.Title, modifier = Modifier.padding(top = 18.dp))
                    }
                    if (ui.sourceText.isNotEmpty()) {
                        BasicText(
                            ui.sourceText,
                            style = Type.Body.copy(color = Palette.SecondaryLabel),
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                    if (ui.statusText.isNotEmpty()) {
                        BasicText(ui.statusText, style = Type.Body, modifier = Modifier.padding(top = 10.dp))
                    }
                    BasicText(
                        stringResource(R.string.slimming_note),
                        style = Type.Body.copy(color = Palette.SecondaryLabel),
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.padding(top = 8.dp).suppressBringIntoView().onFocusChanged {
                    if (it.hasFocus && scroll.value > 0) scope.launch { scroll.animateScrollTo(0) }
                },
            ) {
                    if (ui.recommended.isNotEmpty() || ui.optional.isNotEmpty()) {
                        SlimmingChoice(
                            stringResource(R.string.slimming_disable_recommended),
                            focusRequester = disableRequester,
                            onClick = { ui.confirm = SlimmingConfirm.DISABLE },
                            modifier = Modifier.weight(1f),
                            onKey = scrollTop,
                            enabled = !ui.busy,
                        )
                    }
                    if (ui.showPick) {
                        SlimmingChoice(
                            stringResource(R.string.slimming_pick),
                            focusRequester = pickRequester,
                            onClick = { cb.onPick() },
                            modifier = Modifier.weight(1f),
                            onKey = scrollTop,
                            enabled = !ui.busy,
                        )
                    }
                    if (ui.recommended.isNotEmpty() || ui.optional.isNotEmpty()) {
                        SlimmingChoice(
                            stringResource(R.string.slimming_restore),
                            focusRequester = restoreRequester,
                            onClick = { ui.confirm = SlimmingConfirm.RESTORE },
                            modifier = Modifier.weight(1f),
                            onKey = scrollTop,
                            enabled = !ui.busy,
                        )
                    }
                    SlimmingChoice(ui.dailyText, onClick = { cb.onDailyToggle() }, modifier = Modifier.weight(1f), onKey = scrollTop, enabled = !ui.busy)
                }
            if (ui.showRetry) {
                SlimmingChoice(stringResource(R.string.slimming_retry), onClick = { cb.onRetry() }, modifier = Modifier.suppressBringIntoView())
            }
            if (ui.recommended.isNotEmpty() || ui.optional.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Box(Modifier.weight(1f)) {
                        if (ui.recommended.isNotEmpty()) {
                            BasicText(
                                stringResource(R.string.slimming_recommended),
                                style = Type.Title,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                    Box(Modifier.weight(1f)) {
                        if (ui.optional.isNotEmpty()) {
                            BasicText(
                                stringResource(R.string.slimming_optional),
                                style = Type.Title,
                                modifier = Modifier.padding(top = 8.dp),
                            )
                        }
                    }
                }
                for (i in 0 until pairCount) {
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Box(Modifier.weight(1f)) {
                            ui.recommended.getOrNull(i)?.let { row ->
                                SlimmingRow(row, if (row === firstRow) firstRequester else null, onClick = { cb.onRow(row.pkg) }, enabled = !ui.busy)
                            }
                        }
                        Box(Modifier.weight(1f)) {
                            ui.optional.getOrNull(i)?.let { row ->
                                SlimmingRow(row, if (row === firstRow) firstRequester else null, onClick = { cb.onRow(row.pkg) }, enabled = !ui.busy)
                            }
                        }
                    }
                }
            }
        }
        if (ui.showPicker) {
            FolderPicker(ui.pickerFolders, onPick = { cb.onPickerPick(it) }, onDismiss = { cb.onPickerDismiss() })
        }
        val confirm = ui.confirm
        if (confirm != null) {
            SlimmingConfirmDialog(
                confirm,
                hasOptional = ui.optional.isNotEmpty(),
                onPick = { includeOptional ->
                    if (confirm == SlimmingConfirm.DISABLE) cb.onDisable(includeOptional)
                    else cb.onRestore(includeOptional)
                    ui.confirm = null
                },
                onDismiss = { ui.confirm = null },
            )
        }
    }
}

@Composable
private fun SlimmingChoice(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onKey: ((KeyEvent) -> Boolean)? = null,
    enabled: Boolean = true,
) {
    Pressable(onClick = { if (enabled) onClick() }, modifier = modifier.fillMaxWidth().graphicsLayer { alpha = if (enabled) 1f else 0.5f }, focusRequester = focusRequester, focusedScale = 1.03f, onKey = onKey) { state ->
        val bg by animateColorAsState(
            if (state.focused) Palette.Focus else Palette.Fill,
            tween(140, easing = Motion.EaseOut),
            label = "choice-bg",
        )
        val fg = if (state.focused) Palette.OnFocus else Palette.Label
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).drawBehind { drawRect(bg) }.height(52.dp)
                .padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BasicText(
                text,
                style = Type.Body.copy(color = fg),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun SlimmingRow(
    row: SystemSlimmingActivity.Row,
    focusRequester: FocusRequester?,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Pressable(
        onClick = { if (enabled) onClick() },
        modifier = Modifier.fillMaxWidth(),
        focusRequester = focusRequester,
        focusedScale = 1.03f,
    ) { state ->
        val bg by animateColorAsState(
            if (state.focused) Palette.Focus else Palette.Fill,
            tween(140, easing = Motion.EaseOut),
            label = "row-bg",
        )
        val fg = if (state.focused) Palette.OnFocus else Palette.Label
        val stateColor = when {
            row.state == SystemSlimmingActivity.STATE_DISABLED -> Palette.Green
            state.focused -> Palette.OnFocus.copy(alpha = 0.8f)
            row.state == SystemSlimmingActivity.STATE_INSTALLED -> Palette.Label.copy(alpha = 0.8f)
            else -> Palette.Label.copy(alpha = 0.5f)
        }
        Row(
            Modifier.fillMaxWidth()
                .graphicsLayer { alpha = if (!enabled) 0.5f else if (row.state == SystemSlimmingActivity.STATE_NOT_INSTALLED && !state.focused) 0.5f else 1f }
                .clip(RoundedCornerShape(12.dp)).drawBehind { drawRect(bg) }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val icon = row.icon
            if (icon != null) {
                Image(
                    bitmap = icon.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.size(40.dp).padding(end = 12.dp),
                )
            }
            Column(Modifier.weight(1f)) {
                BasicText(
                    row.label,
                    style = Type.Headline.copy(color = fg),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                BasicText(
                    row.pkg,
                    style = Type.Callout.copy(
                        color = if (state.focused) Palette.OnFocus.copy(alpha = 0.7f) else Palette.SecondaryLabel,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
            BasicText(
                stringResource(
                    when (row.state) {
                        SystemSlimmingActivity.STATE_DISABLED -> R.string.slimming_state_disabled
                        SystemSlimmingActivity.STATE_INSTALLED -> R.string.slimming_state_installed
                        else -> R.string.slimming_state_not_installed
                    },
                ),
                style = Type.Body.copy(color = stateColor),
                modifier = Modifier.padding(start = 16.dp),
            )
        }
    }
}

@Composable
private fun FolderPicker(folders: List<String>, onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val firstRequester = remember { FocusRequester() }
    val listState = rememberLazyListState()
    val pickerScope = rememberCoroutineScope()
    val scrollTop: (KeyEvent) -> Boolean = { e ->
        if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionUp &&
            (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0)
        ) {
            pickerScope.launch { listState.animateScrollToItem(0) }
            true
        } else false
    }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { firstRequester.requestFocus() }
    }
    BackHandler { onDismiss() }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).dismissOnTap { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.width(480.dp).heightIn(max = 420.dp).smoothBackground(Palette.Glass, 16.dp)
                .consumeTaps().padding(20.dp),
        ) {
            BasicText(
                stringResource(R.string.slimming_pick),
                style = Type.Title,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            LazyColumn(state = listState, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(folders, key = { it }) { folder ->
                    Pressable(
                        onClick = { onPick(folder) },
                        modifier = Modifier.fillMaxWidth(),
                        focusRequester = if (folder == folders.firstOrNull()) firstRequester else null,
                        focusedScale = 1.03f,
                        onKey = if (folder == folders.firstOrNull()) scrollTop else null,
                    ) { state ->
                        val bg by animateColorAsState(
                            if (state.focused) Palette.Focus else Palette.Fill,
                            tween(140, easing = Motion.EaseOut),
                            label = "folder-bg",
                        )
                        val fg = if (state.focused) Palette.OnFocus else Palette.Label
                        Box(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).drawBehind { drawRect(bg) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                        ) {
                            BasicText(
                                folder,
                                style = Type.Body.copy(color = fg),
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SlimmingConfirmDialog(
    confirm: SlimmingConfirm,
    hasOptional: Boolean,
    onPick: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val firstRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        withFrameNanos { }
        runCatching { firstRequester.requestFocus() }
    }
    BackHandler { onDismiss() }
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).dismissOnTap { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.width(480.dp).smoothBackground(Palette.Glass, 16.dp)
                .consumeTaps().padding(20.dp),
        ) {
            BasicText(
                stringResource(
                    if (confirm == SlimmingConfirm.DISABLE) R.string.slimming_disable_recommended
                    else R.string.slimming_restore,
                ),
                style = Type.Title,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                SlimmingConfirmOption(
                    stringResource(R.string.slimming_confirm_recommended),
                    includeOptional = false,
                    focusRequester = firstRequester,
                    onPick = onPick,
                )
                if (hasOptional) {
                    SlimmingConfirmOption(
                        stringResource(R.string.slimming_confirm_both),
                        includeOptional = true,
                        focusRequester = null,
                        onPick = onPick,
                    )
                }
            }
        }
    }
}

@Composable
private fun SlimmingConfirmOption(
    label: String,
    includeOptional: Boolean,
    focusRequester: FocusRequester?,
    onPick: (Boolean) -> Unit,
) {
    Pressable(
        onClick = { onPick(includeOptional) },
        modifier = Modifier.fillMaxWidth(),
        focusRequester = focusRequester,
        focusedScale = 1.03f,
    ) { state ->
        val bg by animateColorAsState(
            if (state.focused) Palette.Focus else Palette.Fill,
            tween(140, easing = Motion.EaseOut),
            label = "confirm-bg",
        )
        val fg = if (state.focused) Palette.OnFocus else Palette.Label
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).drawBehind { drawRect(bg) }
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            BasicText(
                label,
                style = Type.Body.copy(color = fg),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
