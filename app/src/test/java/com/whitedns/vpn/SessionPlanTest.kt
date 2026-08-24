package com.whitedns.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionPlanTest {
    @Test
    fun resolvesOneCoherentRuntimePlan() {
        val snapshot = MihomoConfigParser.parse(
            """
            proxies:
              - name: Node A
                type: vless
                server: origin.example.com
                port: 443
                tls: true
            proxy-groups:
              - name: Proxy Select
                type: select
                proxies:
                  - Auto Select
              - name: WhiteDNS Proxy
                type: select
                proxies:
                  - Manual
              - name: Auto Select
                type: url-test
                proxies:
                  - Node A
            """.trimIndent(),
            fetchedAt = 123L,
        )
        val splitTunnelPlan = SplitTunnelRuntimePlan(
            mode = SplitTunnelMode.BypassSelected,
            selectedPackages = listOf("app.example"),
            allowedPackages = emptyList(),
            disallowedPackages = listOf("app.example"),
            skippedPackages = emptyList(),
        )
        val lanSharing = LanSharingSettings(
            enabled = true,
            passwordRequired = true,
            password = "secret-password",
        )
        val dns = DnsRuntimeSettings(
            mode = DnsPrivacyMode.DoH,
            dohUrl = "https://resolver.example/dns-query",
            dotEndpoint = "tls://resolver.example:853",
        )
        val endpoint = CleanIpResult(
            ip = "203.0.113.10",
            port = 8443,
            latencyMs = 20,
            lossRate = 0.0,
            checkedAt = 456L,
        )

        val plan = SessionPlanner.resolve(
            request = SessionPlanRequest(
                snapshot = snapshot,
                splitTunnelPlan = splitTunnelPlan,
                selectedCountryCode = null,
                topEndpoint = endpoint,
                validateConnectivity = true,
                dpiBypassEnabled = true,
                dpiBypassPort = 12345,
                quickSpeedRequested = true,
            ),
            preferences = SessionPlanPreferences(
                frontingIps = listOf("203.0.113.10:8443"),
                connectionOptions = MihomoConnectionOptions(),
                selectedSubscriptionId = "subscription-1",
                explicitProfile = null,
                selectedAutomaticTypes = emptySet(),
                lanSharing = lanSharing,
                routingMode = RoutingMode.IranBypass,
                dns = dns,
                connectionMode = ConnectionMode.Proxy,
                dpiBypassEnabled = true,
                alwaysOn = true,
                lockdown = false,
                tlsIntegrityEnabled = true,
            ),
        )

        assertEquals(snapshot, plan.snapshot)
        assertEquals(splitTunnelPlan, plan.splitTunnelPlan)
        assertEquals(lanSharing, plan.lanSharing)
        assertEquals(RoutingMode.IranBypass, plan.routingMode)
        assertEquals(dns, plan.dns)
        assertEquals(MihomoConnectionOptions(), plan.connectionOptions)
        assertEquals(EffectiveDeviceAccess.TunnelAccess, plan.effectiveDeviceAccess)
        assertEquals("subscription-1", plan.selectedSubscriptionId)
        assertEquals(endpoint, plan.topEndpoint)
        assertEquals("203.0.113.10", plan.serverOverrideIp)
        assertEquals(8443, plan.serverOverridePort)
        assertEquals(12345, plan.dpiBypassPort)
        assertTrue(plan.validateConnectivity)
        assertTrue(plan.tlsIntegrityEnabled)
        assertFalse(plan.automaticSelectionEligible)
        assertFalse(plan.quickSpeedEligible)
        assertFalse(plan.bridgeResult.applied)
        assertEquals("mode-ineligible", plan.bridgeResult.reason)
        assertEquals("Auto Select", plan.selectedMap["Proxy Select"])
        assertTrue(plan.runtimeYaml.contains("server: 203.0.113.10"))
        assertTrue(plan.runtimeYaml.contains("port: 8443"))
        assertTrue(plan.runtimeYaml.contains("port: 12345"))
        assertTrue(plan.runtimeYaml.contains("dialer-proxy:"))

        val runtimeDocument = plan.toMihomoRuntimeDocument()
        assertEquals(plan.runtimeYaml, runtimeDocument.rawYaml)
        assertEquals(splitTunnelPlan, runtimeDocument.splitTunnelPlan)
        assertEquals(lanSharing, runtimeDocument.lanSharing)
        assertEquals(RoutingMode.IranBypass, runtimeDocument.routingMode)
        assertEquals(dns, runtimeDocument.dns)
        assertEquals(plan.selectedMap, runtimeDocument.selectedMap)
    }

    @Test
    fun retryVariantChangesOnlyTheAutomaticBridgeDecision() {
        val snapshot = MihomoConfigParser.parse(
            """
            proxies:
              - name: Node A
                type: vless
                server: origin.example.com
                port: 443
            proxy-groups:
              - name: Proxy Select
                type: select
                proxies:
                  - Auto Select
              - name: WhiteDNS Proxy
                type: select
                proxies:
                  - Manual
              - name: Manual
                type: select
                proxies:
                  - Node A
              - name: Auto Select
                type: url-test
                proxies:
                  - Node A
            """.trimIndent(),
        )
        val dns = DnsRuntimeSettings(
            mode = DnsPrivacyMode.Automatic,
            dohUrl = DnsPrivacyPolicy.DEFAULT_DOH_URL,
            dotEndpoint = DnsPrivacyPolicy.DEFAULT_DOT_ENDPOINT,
        )
        val preferences = SessionPlanPreferences(
            frontingIps = emptyList(),
            connectionOptions = MihomoConnectionOptions(),
            selectedSubscriptionId = "subscription-1",
            explicitProfile = null,
            selectedAutomaticTypes = emptySet(),
            lanSharing = LanSharingSettings(),
            routingMode = RoutingMode.GlobalProxy,
            dns = dns,
            connectionMode = ConnectionMode.Proxy,
            dpiBypassEnabled = false,
            alwaysOn = false,
            lockdown = false,
            tlsIntegrityEnabled = false,
        )
        val request = SessionPlanRequest(
            snapshot = snapshot,
            splitTunnelPlan = SplitTunnelRuntimePlan.off(),
            selectedCountryCode = null,
            topEndpoint = null,
            validateConnectivity = true,
            dpiBypassEnabled = true,
            dpiBypassPort = 23456,
            quickSpeedRequested = true,
        )

        val bridged = SessionPlanner.resolve(request, preferences)
        val original = SessionPlanner.resolve(
            request.copy(allowAutomaticBridge = false),
            preferences,
        )

        assertTrue(bridged.automaticSelectionEligible)
        assertTrue(bridged.quickSpeedEligible)
        assertTrue(bridged.bridgeResult.applied)
        assertEquals("Auto Select", bridged.selectedMap["WhiteDNS Proxy"])
        assertFalse(original.bridgeEligible)
        assertEquals("retry-original-runtime", original.bridgeResult.reason)
        assertEquals(EffectiveDeviceAccess.ProxyOnlyAccess, original.effectiveDeviceAccess)
        assertEquals(bridged.snapshot, original.snapshot)
        assertEquals(bridged.splitTunnelPlan, original.splitTunnelPlan)
        assertEquals(bridged.lanSharing, original.lanSharing)
        assertEquals(bridged.routingMode, original.routingMode)
        assertEquals(bridged.dns, original.dns)
        assertEquals(23456, bridged.dpiBypassPort)
        assertEquals(bridged.dpiBypassPort, original.dpiBypassPort)
        assertTrue(bridged.runtimeYaml.contains("port: 23456"))
        assertTrue(original.runtimeYaml.contains("port: 23456"))

        listOf(
            preferences.copy(explicitProfile = snapshot.catalog.profiles.single()),
            preferences.copy(selectedAutomaticTypes = setOf("vless")),
        ).forEach { constrainedPreferences ->
            val constrained = SessionPlanner.resolve(request, constrainedPreferences)
            assertFalse(constrained.automaticSelectionEligible)
            assertFalse(constrained.quickSpeedEligible)
            assertFalse(constrained.bridgeEligible)
            assertEquals("mode-ineligible", constrained.bridgeResult.reason)
        }
    }
}
