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
        assertEquals(0xFFF8FAFB.toInt(), light.background)
        assertEquals(0xFF000000.toInt(), dark.background)
        assertEquals(0xFFF1F5F9.toInt(), dark.textPrimary)
        assertEquals(0xFF34D399.toInt(), dark.teal)
    }

    @Test
    fun paletteKeepsDashboardSurfacesAndStateColorsDistinct() {
        val light = WhiteDnsDesignTokens.palette(isNight = false)
        val dark = WhiteDnsDesignTokens.palette(isNight = true)

        assertEquals(0xFFFFFFFF.toInt(), light.surface)
        assertEquals(0xFFF0F4F8.toInt(), light.surfaceVariant)
        assertTrue(light.background != light.surface)
        assertTrue(light.teal != light.amber)
        assertTrue(light.teal != light.red)
        assertTrue(dark.surface != dark.surfaceVariant)
        assertTrue(dark.teal != dark.amber)
        assertTrue(dark.teal != dark.red)
    }
}
