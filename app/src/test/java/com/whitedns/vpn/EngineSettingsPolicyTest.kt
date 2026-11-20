package com.whitedns.vpn

import org.junit.Assert.*
import org.junit.Test

class EngineSettingsPolicyTest {
    @Test fun everySocksEngineRetainsMihomoRoutingButRejectsIgnoredConnectionOverrides() {
        EngineKind.selectable.filter { it.socks }.forEach { kind ->
            val p = EngineSettingsPolicy.forRoute(kind)
            assertTrue(p.routing && p.dns && p.apps && p.sharing && p.proxy)
            assertFalse(p.chains || p.location || p.fronting || p.tlsIntegrity || p.mihomoNoise)
        }
    }
    @Test fun amneziaUsesItsOwnDnsAndRoutingWhileKeepingAppSelection() {
        val p = EngineSettingsPolicy.forRoute(EngineKind.AMNEZIAWG)
        assertTrue(p.apps)
        assertFalse(p.routing || p.dns || p.sharing || p.proxy || p.chains || p.fronting || p.mihomoNoise || p.tlsIntegrity || p.location)
    }
    @Test fun ikev2AndRetiredEngineDoNotExposeUnsupportedControls() {
        for (kind in listOf(EngineKind.IKEV2, EngineKind.OPENCONNECT)) {
            val p = EngineSettingsPolicy.forRoute(kind)
            assertFalse(p.apps || p.dns || p.routing || p.proxy || p.sharing || p.chains || p.location || p.fronting || p.tlsIntegrity || p.mihomoNoise)
        }
    }
    @Test fun mihomoNoiseOnlyAppliesToEligibleWireguardCandidates() {
        val p = EngineSettingsPolicy.forRoute(null, false)
        assertFalse(p.mihomoNoise)
        assertTrue(p.apps && p.routing && p.dns && p.fronting && p.chains && p.sharing && p.proxy && p.location && p.tlsIntegrity)
        assertTrue(EngineSettingsPolicy.forRoute(null, true).mihomoNoise)
    }
}
