package com.gathin.tvhome.ui.components

import android.view.KeyEvent as NativeKeyEvent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import com.gathin.tvhome.system.Sounds
import com.gathin.tvhome.ui.theme.LocalReducedMotion
import com.gathin.tvhome.ui.theme.Motion

fun KeyEvent.isSelect(): Boolean = when (nativeKeyEvent.keyCode) {
    NativeKeyEvent.KEYCODE_DPAD_CENTER, NativeKeyEvent.KEYCODE_ENTER, NativeKeyEvent.KEYCODE_NUMPAD_ENTER,
    NativeKeyEvent.KEYCODE_BUTTON_A, NativeKeyEvent.KEYCODE_BUTTON_SELECT -> true
    else -> false
}

class PressState {
    var focused by mutableStateOf(false)
    var pressed by mutableStateOf(false)

    internal var byKey = false
}

fun Modifier.consumeTaps(): Modifier = pointerInput(Unit) { detectTapGestures { } }

fun Modifier.dismissOnTap(onDismiss: () -> Unit): Modifier = pointerInput(Unit) { detectTapGestures { onDismiss() } }

@Composable
fun Pressable(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    focusRequester: FocusRequester? = null,
    focusedScale: Float = 1.06f,
    onKey: ((KeyEvent) -> Boolean)? = null,
    content: @Composable BoxScope.(PressState) -> Unit,
) {
    val state = remember { PressState() }
    val ownRequester = remember { FocusRequester() }
    val requester = focusRequester ?: ownRequester
    val click by rememberUpdatedState(onClick)
    val reduced = LocalReducedMotion.current
    val scale by animateFloatAsState(
        targetValue = when {
            reduced -> 1f
            state.pressed -> focusedScale - 0.05f
            state.focused -> focusedScale
            else -> 1f
        },
        animationSpec = if (state.pressed) tween(100, easing = Motion.EaseOut) else spring(dampingRatio = 1f, stiffness = 520f),
        label = "pressable-scale",
    )
    Box(
        modifier
            .focusRequester(requester)
            .onFocusChanged {
                if (it.isFocused && !state.focused) Sounds.focusMoved()
                state.focused = it.isFocused
                if (!it.isFocused) {
                    state.pressed = false
                    state.byKey = false
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onPress = {
                        state.pressed = true
                        tryAwaitRelease()
                        if (!state.byKey) state.pressed = false
                    },
                    onTap = {
                        runCatching { requester.requestFocus() }
                        Sounds.select()
                        click()
                    },
                )
            }
            .onKeyEvent { e ->
                if (onKey?.invoke(e) == true) return@onKeyEvent true
                if (!e.isSelect()) return@onKeyEvent false
                when (e.type) {
                    KeyEventType.KeyDown -> {
                        if (e.nativeKeyEvent.repeatCount == 0) {
                            state.pressed = true
                            state.byKey = true
                        }
                        true
                    }
                    KeyEventType.KeyUp -> {
                        val was = state.pressed && state.byKey
                        state.pressed = false
                        state.byKey = false
                        if (was) {
                            Sounds.select()
                            onClick()
                        }
                        true
                    }
                    else -> false
                }
            }
            .focusable(enabled)
            .graphicsLayer { scaleX = scale; scaleY = scale },
    ) { content(state) }
}
