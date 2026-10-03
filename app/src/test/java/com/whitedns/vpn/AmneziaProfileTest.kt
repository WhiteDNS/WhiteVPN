package com.whitedns.vpn

import org.junit.Assert.*
import org.junit.Test

class AmneziaProfileTest {
    private val key = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="
    private fun config(extra: String = "") = """
        [Interface]
        PrivateKey = $key
        Address = 10.55.0.2/32
        DNS = 10.55.0.1
        Jc = 4
        Jmin = 40
        Jmax = 70
        S1 = 20
        S2 = 30
        H1 = 12345
        H2 = 23456
        H3 = 34567
        H4 = 45678
        $extra
        [Peer]
        PublicKey = $key
        PresharedKey = $key
        AllowedIPs = 0.0.0.0/0, ::/0
        Endpoint = server.example:51820
        PersistentKeepalive = 25
    """.trimIndent()

    @Test fun fullConfigurationSurvivesEncryptedCatalogCodecAndUsesPhysicalDns() {
        val p = EngineProfile(name = "AWG", kind = EngineKind.AMNEZIAWG, config = EngineConfig.AmneziaWg(mapOf("config" to config())))
        val restored = EngineProfileCodec.decode(EngineProfileCodec.encode(p))
        assertEquals(p, restored)
        val names = mutableListOf<String>()
        val userspace = AmneziaProfile.resolvedUserspace(AmneziaProfile.parse(restored.value("config"))) {
            names += it; "192.0.2.10"
        }
        assertEquals(listOf("server.example"), names)
        assertTrue(userspace.contains("endpoint=192.0.2.10:51820"))
        assertTrue(userspace.contains("jc=4") && userspace.contains("h1=12345"))
        assertTrue(userspace.contains("persistent_keepalive_interval=25"))
        assertFalse(AmneziaProfile.publicSummary(AmneziaProfile.parse(config())).contains(key))
    }
    @Test fun embeddedAppListsAreRejectedInsteadOfOverridingSavedAppSelection() {
        assertThrows(IllegalArgumentException::class.java) { AmneziaProfile.parse(config("IncludedApplications = com.example.app")) }
    }
    @Test fun unknownOptionsAndMissingDnsAreRejectedWithoutSecretParserErrors() {
        val error = assertThrows(IllegalArgumentException::class.java) { AmneziaProfile.parse(config("UnknownSecret = $key")) }
        assertFalse(error.message.orEmpty().contains(key))
        assertNull(error.cause)
        assertThrows(IllegalArgumentException::class.java) { AmneziaProfile.parse(config().replace("DNS = 10.55.0.1", "")) }
    }
    @Test fun malformedEndpointsCannotResolveOrConnect() {
        assertThrows(IllegalArgumentException::class.java) { AmneziaProfile.parse(config().replace("server.example:51820", "server.example:0")) }
    }
    @Test fun nativeRequiresVpnAndRemainsAvailableUnderLockdownWithoutUidBypass() {
        val p = EngineProfile(name = "AWG", kind = EngineKind.AMNEZIAWG, config = EngineConfig.AmneziaWg(mapOf("config" to config())))
        assertThrows(IllegalArgumentException::class.java) { EnginePreflight.validate(p, EngineAvailability.Available, false, false) }
        EnginePreflight.validate(p, EngineAvailability.Available, true, true)
    }
    @Test fun retiredProfileStillDecodesButCannotBeSelectedForStartup() {
        val old = EngineProfile(name = "old", kind = EngineKind.OPENCONNECT, config = EngineConfig.OpenConnect(mapOf("server" to "example.com")))
        assertEquals(old, EngineProfileCodec.decode(EngineProfileCodec.encode(old)))
        assertFalse(EngineKind.selectable.contains(EngineKind.OPENCONNECT))
        assertThrows(IllegalArgumentException::class.java) { EnginePreflight.validate(old, EngineAvailability.Available, true, false) }
        assertThrows(IllegalStateException::class.java) { BackendSessionPlan.forEngine(old) }
    }
    @Test fun trafficTotalsIgnoreKeysAndHandleCounterOverflow() {
        val counters = AmneziaTrafficCounters.parse("private_key=secret\ntx_bytes=15\nrx_bytes=20\ntx_bytes=5\nrx_bytes=-1\npublic_key=secret")
        assertEquals(20L, counters.upload); assertEquals(20L, counters.download)
        assertEquals(Long.MAX_VALUE, AmneziaTrafficCounters.parse("tx_bytes=9223372036854775807\ntx_bytes=2").upload)
    }
}
