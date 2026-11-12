package com.gathin.tvhome.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent

/**
 * Parts of Home left undrawn, to find what a slow TV's GPU spends its frames on: frame times with
 * one part gone, against all of it (tools/perf.py). Set over ADB until Aerial restarts, never saved:
 *   adb shell am broadcast -a com.gathin.slimtv.CONFIG -n com.gathin.slimtv/.guard.ConfigReceiver --es skip_layers picture,plate
 *   ... --es skip_layers ""        (everything back)
 * The parts: picture (the wallpaper, and the grid's glass), shade (over a film or an input's
 * picture, and the screensaver's under its labels), plate, clock, pill, tiles, shadow (a lifted tile's), names.
 */
object DiagLayers {
    val ALL = setOf("picture", "shade", "plate", "clock", "pill", "tiles", "shadow", "names")

    /** Compose state: the next frame leaves them out, no restart. */
    var skipped by mutableStateOf(emptySet<String>())
        private set

    /** [list] is comma-separated; unknown names are ignored. Returns what is skipped now. */
    fun skip(list: String): Set<String> {
        skipped = list.split(',').map { it.trim() }.filter { it in ALL }.toSet()
        return skipped
    }

    fun shows(name: String): Boolean = name !in skipped
}

/** Draws its content unless [DiagLayers] leaves [name] out. */
fun Modifier.diagLayer(name: String): Modifier = drawWithContent { if (DiagLayers.shows(name)) drawContent() }
