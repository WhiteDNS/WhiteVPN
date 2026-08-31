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
        assertEquals(0xFFF1F9F5.toInt(), light.background)
        assertEquals(0xFF050E09.toInt(), dark.background)
        assertEquals(0xFFE4EEE9.toInt(), dark.textPrimary)
        assertEquals(0xFF3FBE90.toInt(), dark.teal)
    }

    @Test
    fun paletteKeepsDashboardSurfacesAndStateColorsDistinct() {
        val light = WhiteDnsDesignTokens.palette(isNight = false)
        val dark = WhiteDnsDesignTokens.palette(isNight = true)

        assertEquals(0xFFF8FDFB.toInt(), light.surface)
        assertEquals(0xFFD3E2DB.toInt(), light.surfaceVariant)
        assertTrue(light.background != light.surface)
        assertEquals(4, setOf(light.neutral, light.teal, light.amber, light.red).size)
        assertTrue(dark.surface != dark.surfaceVariant)
        assertEquals(4, setOf(dark.neutral, dark.teal, dark.amber, dark.red).size)
        assertTrue(light.tealGradientStart != light.tealGradientEnd)
        assertTrue(light.amberGradientStart != light.amberGradientEnd)
        assertTrue(light.redGradientStart != light.redGradientEnd)
        assertTrue(dark.tealGradientStart != dark.tealGradientEnd)
        assertTrue(dark.amberGradientStart != dark.amberGradientEnd)
        assertTrue(dark.redGradientStart != dark.redGradientEnd)
    }
}
