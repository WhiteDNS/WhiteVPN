package com.whitedns.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VpnAnalyticsEventPolicyTest {
    @Test
    fun startedTransitionLogsConnected() {
        assertEquals(
            AnalyticsEvents.VPN_CONNECTED,
            VpnAnalyticsEventPolicy.forStatePublished(VpnState.Starting, VpnState.Started),
        )
    }

    @Test
    fun repeatedStartedStateDoesNotLogConnectedAgain() {
        assertNull(VpnAnalyticsEventPolicy.forStatePublished(VpnState.Started, VpnState.Started))
    }

    @Test
    fun startupErrorLogsConnectionTryFailed() {
        assertEquals(
            AnalyticsEvents.CONNECTION_TRY_FAILED,
            VpnAnalyticsEventPolicy.forStatePublished(VpnState.Starting, VpnState.Error("failed")),
        )
    }

    @Test
    fun completedDisconnectFromConnectedStateLogsDisconnected() {
        assertEquals(
            AnalyticsEvents.VPN_DISCONNECTED,
            VpnAnalyticsEventPolicy.forDisconnectFinished(wasConnected = true),
        )
    }

    @Test
    fun canceledStartupDoesNotLogDisconnected() {
        assertNull(VpnAnalyticsEventPolicy.forDisconnectFinished(wasConnected = false))
    }
}
