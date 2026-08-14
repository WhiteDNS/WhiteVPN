package com.whitedns.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class VpnNotificationActionPolicyTest {
    @Test
    fun startingNotificationCanDisconnect() {
        assertEquals(
            listOf(Actions.DISCONNECT),
            VpnNotificationActionPolicy.actionsFor(VpnState.Starting),
        )
    }

    @Test
    fun startedNotificationCanDisconnectOrReconnect() {
        assertEquals(
            listOf(Actions.DISCONNECT, Actions.RECONNECT),
            VpnNotificationActionPolicy.actionsFor(VpnState.Started),
        )
    }

    @Test
    fun alwaysOnNotificationCannotDisconnect() {
        assertEquals(
            listOf(Actions.RECONNECT),
            VpnNotificationActionPolicy.actionsFor(VpnState.Started, disconnectAllowed = false),
        )
        assertEquals(
            emptyList<String>(),
            VpnNotificationActionPolicy.actionsFor(VpnState.Starting, disconnectAllowed = false),
        )
    }

    @Test
    fun inactiveStatesHaveNoForegroundActions() {
        assertEquals(emptyList<String>(), VpnNotificationActionPolicy.actionsFor(VpnState.Stopped))
        assertEquals(emptyList<String>(), VpnNotificationActionPolicy.actionsFor(VpnState.Stopping))
        assertEquals(emptyList<String>(), VpnNotificationActionPolicy.actionsFor(VpnState.DailyLimitReached))
        assertEquals(emptyList<String>(), VpnNotificationActionPolicy.actionsFor(VpnState.Error("failed")))
    }
}
