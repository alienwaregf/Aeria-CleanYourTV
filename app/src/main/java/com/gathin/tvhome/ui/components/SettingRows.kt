package com.gathin.tvhome.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.relocation.BringIntoViewResponder
import androidx.compose.foundation.relocation.bringIntoViewResponder
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gathin.tvhome.ui.theme.Glyph
import com.gathin.tvhome.ui.theme.GlyphIcon
import com.gathin.tvhome.ui.theme.Motion
import com.gathin.tvhome.ui.theme.Palette
import com.gathin.tvhome.ui.theme.Type


@Composable
fun Group(note: String? = null, rows: @Composable ColumnScope.(note: (String?) -> Unit) -> Unit) {
    var focusedNote by remember { mutableStateOf<String?>(null) }
    var inside by remember { mutableStateOf(false) }
    var height by remember { mutableStateOf(0f) }
    val withNote = remember {
        @OptIn(ExperimentalFoundationApi::class)
        object : BringIntoViewResponder {
            override fun calculateRectForParent(localRect: Rect) =
                Rect(localRect.left, localRect.top, localRect.right, maxOf(localRect.bottom, height))
            override suspend fun bringChildIntoView(localRect: () -> Rect?) {}
        }
    }
    @OptIn(ExperimentalFoundationApi::class)
    Column(Modifier.fillMaxWidth().onSizeChanged { height = it.height.toFloat() }.bringIntoViewResponder(withNote)) {
        Column(
            Modifier
                .fillMaxWidth()
                .smoothBackground(Color.White.copy(alpha = 0.05f), 16.dp)
                .onFocusChanged { inside = it.hasFocus },
        ) { rows { focusedNote = it } }
        Box(Modifier.height(38.dp).padding(start = 16.dp, top = 7.dp)) {
            (if (inside) focusedNote ?: note else note)?.let {
                BasicText(it, style = Type.Caption.copy(color = Palette.TertiaryLabel), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * One row: the setting's name on the left, its control on the right, a hairline above all but the first.
 * The note follows [hint] while the row has focus: a hint that describes one option changes as
 * focus moves along the chips or a chip is chosen (issue #1782: it stayed the one from when the
 * row took focus).
 */
@Composable
fun SettingRow(label: Int, hint: String?, note: (String?) -> Unit, first: Boolean = false, control: @Composable () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    LaunchedEffect(focused, hint) { if (focused) note(hint) }
    if (!first) Box(Modifier.padding(start = 18.dp).fillMaxWidth().height(1.dp).background(Palette.Hairline))
    Row(
        Modifier
            .fillMaxWidth()
            .height(58.dp)
            .padding(start = 18.dp, end = 10.dp)
            .onFocusChanged {
                // At once as well, so the row above's note does not show for a frame under this row.
                if (it.hasFocus && !focused) note(hint)
                focused = it.hasFocus
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(stringResource(label), style = Type.Body, modifier = Modifier.weight(1f), maxLines = 1)
        control()
    }
}

/**
 * A row of chips for one of a few values. With [hintOf], the note is the focused chip's (the
 * chosen one's before a chip has focus), not one for the row.
 */
@Composable
fun <T> Choices(
    label: Int,
    hint: String?,
    note: (String?) -> Unit,
    options: List<Pair<T, Any>>,
    current: T,
    choose: (T) -> Unit,
    first: Boolean = false,
    hintOf: (@Composable (T) -> String?)? = null,
) {
    var focusedValue by remember { mutableStateOf<T?>(null) }
    val rowHint = if (hintOf != null) hintOf(focusedValue ?: current) else hint
    SettingRow(label, rowHint, note, first) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            options.forEachIndexed { index, (value, text) ->
                Chip(
                    if (text is Int) stringResource(text) else text.toString(),
                    on = current == value,
                    onFocused = if (hintOf != null) ({ focusedValue = value }) else null,
                    rowStart = index == 0,
                    rowEnd = index == options.lastIndex,
                ) { choose(value) }
            }
        }
    }
}

/** A pill to pick one of a few options: a check on the chosen one, white when focused. */
/** A pill to pick one of a few options: a check on the chosen one, white when focused. */
@Composable
fun Chip(
    label: String,
    on: Boolean,
    onFocused: (() -> Unit)? = null,
    rowStart: Boolean = false,
    rowEnd: Boolean = false,
    onClick: () -> Unit,
) {
    Pressable(
        onClick = onClick, focusedScale = 1.06f,
        modifier = Modifier.focusProperties {
            if (rowStart) left = FocusRequester.Cancel
            if (rowEnd) right = FocusRequester.Cancel
        },
    ) { state ->
        if (onFocused != null) LaunchedEffect(state.focused) { if (state.focused) onFocused() }
        val bg by animateColorAsState(
            when {
                state.focused -> Palette.Focus
                on -> Palette.FillStrong
                else -> Palette.Fill
            },
            tween(140, easing = Motion.EaseOut), label = "chip-bg",
        )
        val fg = if (state.focused) Palette.OnFocus else Palette.Label
        Row(
            Modifier.clip(RoundedCornerShape(50)).drawBehind { drawRect(bg) }.height(40.dp).padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (on) GlyphIcon(Glyph.Check, fg, 14.dp)
            BasicText(label, style = Type.Body.copy(color = fg))
        }
    }
}

/** A pill that does something rather than choosing a value: white when focused, a glyph after its label. */
@Composable
fun ActionChip(label: String, glyph: Glyph, onClick: () -> Unit, focusRequester: FocusRequester? = null) {
    Pressable(
        onClick = onClick, focusRequester = focusRequester, focusedScale = 1.06f,
        modifier = Modifier.focusProperties {
            left = FocusRequester.Cancel
            right = FocusRequester.Cancel
        },
    ) { state ->
        val bg by animateColorAsState(if (state.focused) Palette.Focus else Palette.FillStrong, tween(140, easing = Motion.EaseOut), label = "action-bg")
        val fg = if (state.focused) Palette.OnFocus else Palette.Label
        Row(
            Modifier.clip(RoundedCornerShape(50)).drawBehind { drawRect(bg) }.height(40.dp).padding(start = 20.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            BasicText(label, style = Type.Body.copy(color = fg))
            GlyphIcon(glyph, fg, 18.dp)
        }
    }
}
