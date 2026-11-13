package com.gathin.tvhome.system

import android.content.ComponentName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class XgimiMstarSourcesTest {
    @Test
    fun configOffersOnlyPhysicalHdmiEntriesAndPreservesNativeSwitchNames() {
        val sources = XgimiMstarSources.parseConfig(
            """{"signal":[
                {"name":"HDMI","source":"E_INPUT_SOURCE_HDMI","sw":"HDMI"},
                {"name":"HDMI 2","source":"E_INPUT_SOURCE_HDMI2","sw":"hdmi2"},
                {"name":"USB2.0","source":"","sw":""},
                {"name":"AV","source":"E_INPUT_SOURCE_AV","sw":"AV"},
                {"name":"phantom","source":"E_INPUT_SOURCE_HDMI","sw":"HDMI0"},
                {"name":"duplicate","source":"E_INPUT_SOURCE_HDMI","sw":"HDMI1"}
            ]}""",
            mapOf("E_INPUT_SOURCE_HDMI" to true),
        )

        assertEquals(listOf("xgimi:HDMI1", "xgimi:HDMI2"), sources.map { it.id })
        assertEquals(listOf("HDMI 1", "HDMI 2"), sources.map { it.label })
        assertTrue(sources[0].connected)
        assertFalse(sources[1].connected)
        assertEquals(
            ComponentName("com.xgimi.xhplayer", "com.xgimi.xhplayer.InputActivity"),
            sources[0].intent.component,
        )
        assertEquals("HDMI1", sources[0].intent.getStringExtra("launcher_start_source"))
    }

    @Test
    fun missingSignalListReturnsNoSources() {
        assertTrue(XgimiMstarSources.parseConfig("{}", emptyMap()).isEmpty())
    }
}
