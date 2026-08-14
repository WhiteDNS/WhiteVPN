package com.whitedns.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectButtonModelTest {
    @Test
    fun stoppedStateConnects() {
        val model = ConnectButtonModel(VpnState.Stopped)

        assertEquals(R.string.connect_action_connect, model.labelRes())
        assertEquals(Actions.CONNECT, model.nextAction())
        assertTrue(model.isEnabled())
    }

    @Test
    fun startedStateDisconnects() {
        val model = ConnectButtonModel(VpnState.Started)

        assertEquals(R.string.connect_action_disconnect, model.labelRes())
        assertEquals(Actions.DISCONNECT, model.nextAction())
        assertTrue(model.isEnabled())
    }

    @Test
    fun alwaysOnStateCannotDisconnect() {
        val model = ConnectButtonModel(VpnState.Started)

        model.onStateChanged(VpnState.Started, alwaysOn = true)

        assertEquals(R.string.connect_action_always_on, model.labelRes())
        assertEquals(null, model.nextAction())
        assertFalse(model.isEnabled())
    }

    @Test
    fun startingStateCanCancelConnect() {
        val model = ConnectButtonModel(VpnState.Starting)

        assertEquals(R.string.connect_action_connecting, model.labelRes())
        assertEquals(Actions.DISCONNECT, model.nextAction())
        assertTrue(model.isEnabled())
    }

    @Test
    fun stoppingStateIsDisabled() {
        val model = ConnectButtonModel(VpnState.Stopping)

        assertEquals(R.string.connect_action_disconnecting, model.labelRes())
        assertEquals(null, model.nextAction())
        assertFalse(model.isEnabled())
    }

    @Test
    fun errorStateRestoresConnectButton() {
        val model = ConnectButtonModel(VpnState.Starting)

        model.onStateChanged(VpnState.Error("failed"))

        assertEquals(R.string.connect_action_retry, model.labelRes())
        assertEquals(Actions.CONNECT, model.nextAction())
        assertTrue(model.isEnabled())
    }

    @Test
    fun legacyDailyLimitStateRestoresConnectButton() {
        val model = ConnectButtonModel(VpnState.DailyLimitReached)

        assertEquals(R.string.connect_action_retry, model.labelRes())
        assertEquals(Actions.CONNECT, model.nextAction())
        assertTrue(model.isEnabled())
    }
}
