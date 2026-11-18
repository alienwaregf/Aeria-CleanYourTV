package com.gathin.tvhome

import android.os.Build
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
    fun onPickerPick(folder: String)
    fun onPickerDismiss()
}

class SlimmingUiState {
    var device by mutableStateOf("")
    var listText by mutableStateOf("")
    var sourceText by mutableStateOf("")
    var statusText by mutableStateOf("")
    var dailyText by mutableStateOf("")
    var notes by mutableStateOf<String?>(null)
    var showPick by mutableStateOf(false)
    var showRetry by mutableStateOf(false)
    var showPicker by mutableStateOf(false)
    var pickerFolders by mutableStateOf<List<String>>(emptyList())
    var focusFirst by mutableIntStateOf(0)
    val recommended: SnapshotStateList<SystemSlimmingActivity.Row> = mutableStateListOf()
    val optional: SnapshotStateList<SystemSlimmingActivity.Row> = mutableStateListOf()
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
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val scrollTop: (KeyEvent) -> Boolean = { e ->
        if (e.type == KeyEventType.KeyDown && e.key == Key.DirectionUp &&
            (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0)
        ) {
            scope.launch { listState.animateScrollToItem(0) }
            true
        } else false
    }
    var pickerWasOpen by remember { mutableStateOf(false) }
    val firstRow = ui.recommended.firstOrNull() ?: ui.optional.firstOrNull()
    val pairCount = maxOf(ui.recommended.size, ui.optional.size)

    LaunchedEffect(ui.focusFirst) {
        if (ui.focusFirst > 0) {
            withFrameNanos { }
            runCatching { firstRequester.requestFocus() }
        }
    }
    LaunchedEffect(ui.showPicker) {
        if (ui.showPicker) pickerWasOpen = true
        else if (pickerWasOpen) {
            pickerWasOpen = false
            withFrameNanos { }
            runCatching { pickRequester.requestFocus() }
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 72.dp, end = 72.dp, top = 38.dp, bottom = 38.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
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
                    ui.notes?.let {
                        BasicText(
                            it,
                            style = Type.Callout,
                            maxLines = 4,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                    BasicText(
                        stringResource(R.string.slimming_note),
                        style = Type.Body.copy(color = Palette.SecondaryLabel),
                        modifier = Modifier.padding(top = 10.dp),
                    )
                }
            }
            item {
                SlimmingChoice(ui.dailyText, onClick = { cb.onDailyToggle() }, modifier = Modifier.padding(top = 8.dp), onKey = scrollTop)
            }
            if (ui.showPick) {
                item {
                    SlimmingChoice(
                        stringResource(R.string.slimming_pick),
                        focusRequester = pickRequester,
                        onClick = { cb.onPick() },
                    )
                }
            }
            if (ui.showRetry) {
                item {
                    SlimmingChoice(stringResource(R.string.slimming_retry), onClick = { cb.onRetry() })
                }
            }
            if (ui.recommended.isNotEmpty() || ui.optional.isNotEmpty()) {
                item {
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
                }
                items(pairCount, key = { "pair:$it" }) { i ->
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Box(Modifier.weight(1f)) {
                            ui.recommended.getOrNull(i)?.let { row ->
                                SlimmingRow(row, if (row === firstRow) firstRequester else null) { cb.onRow(row.pkg) }
                            }
                        }
                        Box(Modifier.weight(1f)) {
                            ui.optional.getOrNull(i)?.let { row ->
                                SlimmingRow(row, if (row === firstRow) firstRequester else null) { cb.onRow(row.pkg) }
                            }
                        }
                    }
                }
            }
        }
        if (ui.showPicker) {
            FolderPicker(ui.pickerFolders, onPick = { cb.onPickerPick(it) }, onDismiss = { cb.onPickerDismiss() })
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
) {
    Pressable(onClick = onClick, modifier = modifier.fillMaxWidth(), focusRequester = focusRequester, focusedScale = 1.03f, onKey = onKey) { state ->
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
) {
    Pressable(
        onClick = onClick,
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
                .graphicsLayer { alpha = if (row.state == SystemSlimmingActivity.STATE_NOT_INSTALLED && !state.focused) 0.5f else 1f }
                .clip(RoundedCornerShape(12.dp)).drawBehind { drawRect(bg) }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
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
