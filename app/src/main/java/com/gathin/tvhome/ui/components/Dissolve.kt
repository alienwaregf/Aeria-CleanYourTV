package com.gathin.tvhome.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun <T> Dissolve(
    targetState: T,
    durationMs: Int,
    easing: Easing,
    modifier: Modifier = Modifier,
    label: String = "dissolve",
    content: @Composable (T) -> Unit,
) {
    AnimatedContent(
        targetState = targetState,
        modifier = modifier,
        transitionSpec = {
            (fadeIn(tween(durationMs, easing = easing)) togetherWith ExitTransition.KeepUntilTransitionsFinished)
                .using(null)
        },
        label = label,
    ) { content(it) }
}
