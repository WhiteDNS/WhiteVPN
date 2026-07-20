package com.whitedns.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectButtonModelTest {
    @Test
    fun stoppedStateConnects() {
        val model = ConnectButtonModel(VpnState.Stopped)

        assertEquals("اتصال", model.label())
        assertEquals(Actions.CONNECT, model.nextAction())
        assertTrue(model.isEnabled())
    }

    @Test
    fun startedStateDisconnects() {
        val model = ConnectButtonModel(VpnState.Started)

        assertEquals("قطع اتصال", model.label())
        assertEquals(Actions.DISCONNECT, model.nextAction())
        assertTrue(model.isEnabled())
    }

    @Test
    fun startingStateCanCancelConnect() {
        val model = ConnectButtonModel(VpnState.Starting)

        assertEquals("در حال اتصال…", model.label())
        assertEquals(Actions.DISCONNECT, model.nextAction())
        assertTrue(model.isEnabled())
    }

    @Test
    fun stoppingStateIsDisabled() {
        val model = ConnectButtonModel(VpnState.Stopping)

        assertEquals("در حال قطع اتصال…", model.label())
        assertEquals(null, model.nextAction())
        assertFalse(model.isEnabled())
    }

    @Test
    fun errorStateRestoresConnectButton() {
        val model = ConnectButtonModel(VpnState.Starting)

        model.onStateChanged(VpnState.Error("failed"))

        assertEquals("تلاش دوباره", model.label())
        assertEquals(Actions.CONNECT, model.nextAction())
        assertTrue(model.isEnabled())
    }

    @Test
    fun legacyDailyLimitStateRestoresConnectButton() {
        val model = ConnectButtonModel(VpnState.DailyLimitReached)

        assertEquals("تلاش دوباره", model.label())
        assertEquals(Actions.CONNECT, model.nextAction())
        assertTrue(model.isEnabled())
    }
}
