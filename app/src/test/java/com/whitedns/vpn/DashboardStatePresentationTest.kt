package com.whitedns.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardStatePresentationTest {
    @Test
    fun stoppedStateUsesNeutralConnectPresentation() {
        val presentation = DashboardStatePresenter.forState(VpnState.Stopped)

        assertEquals("No signal", presentation.title)
        assertEquals(DashboardTone.Neutral, presentation.tone)
        assertFalse(presentation.showProgress)
    }

    @Test
    fun transitionalStatesUseProgressPresentation() {
        val starting = DashboardStatePresenter.forState(VpnState.Starting)
        val stopping = DashboardStatePresenter.forState(VpnState.Stopping)

        assertEquals("Searching...", starting.title)
        assertEquals(DashboardTone.Progress, starting.tone)
        assertTrue(starting.showProgress)
        assertEquals("Disconnecting", stopping.title)
        assertEquals(DashboardTone.Progress, stopping.tone)
        assertTrue(stopping.showProgress)
    }

    @Test
    fun startedStateUsesConnectedPresentation() {
        val presentation = DashboardStatePresenter.forState(VpnState.Started)

        assertEquals("Connected", presentation.title)
        assertEquals(DashboardTone.Connected, presentation.tone)
        assertFalse(presentation.showProgress)
    }

    @Test
    fun errorStateUsesMinimalErrorPresentation() {
        val presentation = DashboardStatePresenter.forState(VpnState.Error("failed"))

        assertEquals("Connection error", presentation.title)
        assertEquals(DashboardTone.Error, presentation.tone)
        assertFalse(presentation.showProgress)
    }

    @Test
    fun legacyDailyLimitStateUsesStoppedPresentation() {
        val presentation = DashboardStatePresenter.forState(VpnState.DailyLimitReached)

        assertEquals("No signal", presentation.title)
        assertEquals(DashboardTone.Neutral, presentation.tone)
        assertFalse(presentation.showProgress)
    }
}
