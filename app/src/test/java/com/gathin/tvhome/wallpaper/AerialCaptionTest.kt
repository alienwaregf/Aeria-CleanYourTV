package com.gathin.tvhome.wallpaper

import org.junit.Assert.assertEquals
import org.junit.Test

/** The place name shown as an aerial plays: tvOS changes it as the camera moves on. */
class AerialCaptionTest {
    private fun aerial(vararg captions: Pair<Int, String>) =
        Aerial("id", "Hawaii", "landscape", "url", "uhd", "dv", captions.toList())

    @Test fun theLabelUntilTheFirstCaption() {
        val film = aerial(5 to "Kauai", 40 to "Waimea Canyon")
        assertEquals("Hawaii", film.captionAt(0))
        assertEquals("Hawaii", film.captionAt(4))
    }

    @Test fun eachCaptionFromItsSecondUntilTheNext() {
        val film = aerial(0 to "Kauai", 40 to "Waimea Canyon", 90 to "Na Pali Coast")
        assertEquals("Kauai", film.captionAt(0))
        assertEquals("Kauai", film.captionAt(39))
        assertEquals("Waimea Canyon", film.captionAt(40))
        assertEquals("Na Pali Coast", film.captionAt(90))
        assertEquals("Na Pali Coast", film.captionAt(10_000))
    }

    @Test fun theLabelWithoutCaptions() {
        assertEquals("Hawaii", aerial().captionAt(30))
    }
}
