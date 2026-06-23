package com.whitedns.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class QuickSettingsTileStateMapperTest {
    @Test
    fun startedMapsToActiveTile() {
        val presentation = QuickSettingsTileStateMapper.presentationFor(VpnState.Started)

        assertEquals(QuickSettingsTileVisualState.Active, presentation.visualState)
        assertEquals("Connected", presentation.subtitle)
    }

    @Test
    fun transitionalStatesMapToUnavailableTile() {
        assertEquals(
            QuickSettingsTileVisualState.Unavailable,
            QuickSettingsTileStateMapper.presentationFor(VpnState.Starting).visualState,
        )
        assertEquals(
            QuickSettingsTileVisualState.Unavailable,
            QuickSettingsTileStateMapper.presentationFor(VpnState.Stopping).visualState,
        )
    }

    @Test
    fun stoppedAndErrorMapToInactiveTile() {
        assertEquals(
            QuickSettingsTileVisualState.Inactive,
            QuickSettingsTileStateMapper.presentationFor(VpnState.Stopped).visualState,
        )
        assertEquals(
            QuickSettingsTileVisualState.Inactive,
            QuickSettingsTileStateMapper.presentationFor(VpnState.Error("failed")).visualState,
        )
    }

    @Test
    fun legacyDailyLimitMapsToInactiveTile() {
        val presentation = QuickSettingsTileStateMapper.presentationFor(VpnState.DailyLimitReached)

        assertEquals(QuickSettingsTileVisualState.Inactive, presentation.visualState)
        assertEquals("Disconnected", presentation.subtitle)
    }
}
