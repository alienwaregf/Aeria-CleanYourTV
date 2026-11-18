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

@Composable
fun SettingRow(label: Int, hint: String?, note: (String?) -> Unit, first: Boolean = false, control: @Composable () -> Unit) {
    if (!first) Box(Modifier.padding(start = 18.dp).fillMaxWidth().height(1.dp).background(Palette.Hairline))
    Row(
        Modifier
            .fillMaxWidth()
            .height(58.dp)
            .padding(start = 18.dp, end = 10.dp)
            .onFocusChanged { if (it.hasFocus) note(hint) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(stringResource(label), style = Type.Body, modifier = Modifier.weight(1f), maxLines = 1)
        control()
    }
}

@Composable
fun <T> Choices(
    label: Int,
    hint: String?,
    note: (String?) -> Unit,
    options: List<Pair<T, Any>>,
    current: T,
    choose: (T) -> Unit,
    first: Boolean = false,
) {
    SettingRow(label, hint, note, first) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            options.forEach { (value, text) ->
                Chip(if (text is Int) stringResource(text) else text.toString(), on = current == value) { choose(value) }
            }
        }
    }
}

@Composable
fun Chip(label: String, on: Boolean, onFocused: (() -> Unit)? = null, onClick: () -> Unit) {
    Pressable(onClick = onClick, focusedScale = 1.06f) { state ->
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

@Composable
fun ActionChip(label: String, glyph: Glyph, onClick: () -> Unit) {
    Pressable(onClick = onClick, focusedScale = 1.06f) { state ->
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
