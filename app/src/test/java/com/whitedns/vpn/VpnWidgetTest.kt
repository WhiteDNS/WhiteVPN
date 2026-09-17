package com.whitedns.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class VpnWidgetTest {
    @Test
    fun widgetRoutesTransitionsRestrictionsAndReadiness() {
        fun action(
            state: VpnState,
            privacy: Boolean = true,
            configured: Boolean = true,
            permission: Boolean = true,
            alwaysOn: Boolean = false,
        ) = vpnWidgetAction(state, privacy, configured, permission, alwaysOn)

        assertEquals(VpnWidgetAction.Connect, action(VpnState.Stopped))
        assertEquals(VpnWidgetAction.Connect, action(VpnState.DailyLimitReached))
        assertEquals(VpnWidgetAction.Disconnect, action(VpnState.Started))
        for (state in listOf(VpnState.Starting, VpnState.Stopping)) {
            assertEquals(VpnWidgetAction.None, action(state))
            assertEquals(VpnWidgetAction.None, action(state, alwaysOn = true))
        }
        assertEquals(VpnWidgetAction.OpenApp, action(VpnState.Error("failure")))
        assertEquals(VpnWidgetAction.OpenApp, action(VpnState.Stopped, privacy = false))
        assertEquals(VpnWidgetAction.OpenApp, action(VpnState.Stopped, configured = false))
        assertEquals(VpnWidgetAction.OpenApp, action(VpnState.Stopped, permission = false))
        assertEquals(VpnWidgetAction.OpenApp, action(VpnState.Started, alwaysOn = true))
        assertEquals(VpnWidgetAction.OpenApp, action(VpnState.Stopped, alwaysOn = true))
        // Revoked setup must not prevent stopping an existing user-started connection.
        assertEquals(VpnWidgetAction.Disconnect, action(VpnState.Started, privacy = false, configured = false))
    }
}
