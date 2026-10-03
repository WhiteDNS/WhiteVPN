package com.whitedns.vpn

import org.junit.Assert.*
import org.junit.Test
import org.json.JSONObject

class EngineProfilesTest {
    private fun ssh() = EngineProfile(name = "SSH", kind = EngineKind.SSH,
        config = EngineConfig.Ssh(mapOf("host" to "example.com", "username" to "user", "password" to "secret")))
    @Test fun legacySelectionKeepsSubscriptionAndFingerprintExactly() {
        assertEquals(RouteProfileRef.Mihomo("subscription-1", "sha256:unchanged"),
            RouteSelectionMigration.read(null, "subscription-1", "sha256:unchanged"))
    }
    @Test fun engineSelectionDoesNotRewriteLegacyIdentity() {
        assertEquals(RouteProfileRef.Engine("missing-profile"), RouteSelectionMigration.read("missing-profile", "sub", "fp"))
    }
    @Test fun profileCodecPreservesStableIdentityAndSecrets() {
        val original = ssh()
        assertEquals(original, EngineProfileCodec.decode(JSONObject(EngineProfileCodec.encode(original).toString())))
    }
    @Test fun allSocksEnginesHaveTcpOnlyStandaloneCapabilities() {
        EngineKind.entries.filter { it.socks }.forEach {
            val caps = EngineCapabilities.forKind(it)
            assertTrue(caps.customDns && caps.appSplitTunnel && caps.lanSharing && caps.destinationRules)
            assertFalse(caps.udp || caps.chains)
        }
    }
    @Test fun nativeCapabilitiesCannotPretendToSupportMihomoSettings() {
        val openConnect = EngineCapabilities.forKind(EngineKind.AMNEZIAWG)
        assertTrue(openConnect.appSplitTunnel)
        assertFalse(openConnect.proxyAccess || openConnect.destinationRules || openConnect.customDns || openConnect.lanSharing || openConnect.chains)
        assertFalse(EngineCapabilities.forKind(EngineKind.IKEV2).appSplitTunnel)
    }
    @Test fun backendDispatchIsExplicit() {
        assertTrue(BackendSessionPlan.forEngine(ssh()) is BackendSessionPlan.SocksEngine)
        for (kind in listOf(EngineKind.AMNEZIAWG, EngineKind.IKEV2)) {
            val profile = EngineProfile(name = kind.title, kind = kind, config = EngineConfig.of(kind, emptyMap()))
            val plan = BackendSessionPlan.forEngine(profile)
            if (kind == EngineKind.IKEV2) assertTrue(plan is BackendSessionPlan.PlatformIkev2)
            else assertTrue(plan is BackendSessionPlan.AmneziaWg)
        }
    }
    @Test fun unavailableEngineFailsBeforeStartup() {
        assertThrows(IllegalArgumentException::class.java) { EnginePreflight.validate(ssh(), EngineAvailability.Unavailable(EngineAvailability.Reason.NATIVE_LIBRARY), true, false) }
    }
    @Test fun lockdownRejectsSocksUidBypass() {
        assertThrows(IllegalArgumentException::class.java) { EnginePreflight.validate(ssh(), EngineAvailability.Available, true, true) }
    }
    @Test fun nativeProfileRequiresTunnelAccess() {
        val p = EngineProfile(name = "gateway", kind = EngineKind.OPENCONNECT, config = EngineConfig.OpenConnect(mapOf("server" to "https://example.com")))
        assertThrows(IllegalArgumentException::class.java) { EnginePreflight.validate(p, EngineAvailability.Available, false, false) }
    }
    @Test fun futureConfigVersionCannotBeSilentlyRead() {
        val document = EngineProfileCodec.encode(ssh()).put("version", 2)
        assertThrows(IllegalArgumentException::class.java) { EngineProfileCodec.decode(document) }
    }
    @Test fun changedConfigTypeIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { EngineProfileValidation.validate(ssh().copy(config = EngineConfig.Tor(ssh().config.settings))) }
    }
    @Test fun masterDnsDoesNotInheritDohTransport() {
        val p = EngineProfile(name = "DNS", kind = EngineKind.DNS, config = EngineConfig.DnsTunnel(mapOf(
            "engine" to "masterdns", "domains" to "t.example.com", "resolvers" to "8.8.8.8", "encryptionKey" to "secret", "dnsTransport" to "doh")))
        assertThrows(IllegalArgumentException::class.java) { EngineProfileValidation.validate(p) }
    }
}
