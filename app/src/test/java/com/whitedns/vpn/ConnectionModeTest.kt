package com.whitedns.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionModeTest {
    @Test
    fun proxyModeDoesNotStartTunUnlessAndroidRequiresVpn() {
        assertFalse(ConnectionModePolicy.shouldStartTun(ConnectionMode.Proxy, alwaysOn = false, lockdown = false))
        assertTrue(ConnectionModePolicy.shouldStartTun(ConnectionMode.Vpn, alwaysOn = false, lockdown = false))
        assertTrue(ConnectionModePolicy.shouldStartTun(ConnectionMode.Proxy, alwaysOn = true, lockdown = false))
        assertTrue(ConnectionModePolicy.shouldStartTun(ConnectionMode.Proxy, alwaysOn = false, lockdown = true))
    }
}
