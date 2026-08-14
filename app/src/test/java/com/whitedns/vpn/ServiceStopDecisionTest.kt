package com.whitedns.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class ServiceStopDecisionTest {
    @Test
    fun serviceStopDuringStartupIsAStartFailure() {
        assertEquals(ServiceStopAction.StartupFailure, ServiceStopDecision.forState(VpnState.Starting))
    }

    @Test
    fun serviceStopAfterStartedStopsVpn() {
        assertEquals(ServiceStopAction.StopVpn, ServiceStopDecision.forState(VpnState.Started))
    }

    @Test
    fun serviceStopWhileAlreadyStoppedIsIgnored() {
        assertEquals(ServiceStopAction.Ignore, ServiceStopDecision.forState(VpnState.Stopped))
    }

    @Test
    fun serviceStopAfterDailyLimitIsIgnored() {
        assertEquals(ServiceStopAction.Ignore, ServiceStopDecision.forState(VpnState.DailyLimitReached))
    }
}
