package com.whitedns.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WhiteDnsDesignTokensTest {
    @Test
    fun paletteFollowsNightFlag() {
        val light = WhiteDnsDesignTokens.palette(isNight = false)
        val dark = WhiteDnsDesignTokens.palette(isNight = true)

        assertFalse(light.isDark)
        assertTrue(dark.isDark)
        assertEquals(0xFFFFFFFF.toInt(), light.background)
        assertEquals(0xFF0B1117.toInt(), dark.background)
        assertEquals(0xFFF4F8FA.toInt(), dark.textPrimary)
        assertEquals(0xFF00B867.toInt(), dark.teal)
    }
}
