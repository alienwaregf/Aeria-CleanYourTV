package com.gathin.tvhome.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent

object DiagLayers {
    val ALL = setOf("picture", "shade", "plate", "clock", "pill", "tiles", "shadow", "names")

    var skipped by mutableStateOf(emptySet<String>())
        private set

    fun skip(list: String): Set<String> {
        skipped = list.split(',').map { it.trim() }.filter { it in ALL }.toSet()
        return skipped
    }

    fun shows(name: String): Boolean = name !in skipped
}

fun Modifier.diagLayer(name: String): Modifier = drawWithContent { if (DiagLayers.shows(name)) drawContent() }
