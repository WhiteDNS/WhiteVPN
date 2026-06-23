package com.whitedns.vpn

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class SubscriptionSingBoxConfigBuilderTest {
    @Test
    fun probeConfigContainsOnlySampledProfilesAndUrlTestGroup() {
        val profiles = sampleProfiles().take(2)

        val probe = SubscriptionSingBoxConfigBuilder.profileProbeConfig(profiles)
        val root = JSONObject(probe.config)
        val outbounds = root.getJSONArray("outbounds")
        val urlTest = (0 until outbounds.length())
            .map { outbounds.getJSONObject(it) }
            .single { it.optString("tag") == probe.groupTag }

        assertEquals(0, root.getJSONArray("inbounds").length())
        assertEquals(4, outbounds.length())
        assertEquals(2, urlTest.getJSONArray("outbounds").length())
        assertFalse(probe.config.contains("profile-99"))
    }

    @Test
    fun probeConfigOverridesAllProfileServersButKeepsOriginalPorts() {
        val profiles = listOf(
            sampleProfiles().first().copy(
                tag = "profile-443",
                port = 443,
                outboundJson = """{"type":"trojan","tag":"profile-443","server":"a.example.com","server_port":443}""",
            ),
            sampleProfiles().first().copy(
                tag = "profile-8443",
                port = 8443,
                outboundJson = """{"type":"trojan","tag":"profile-8443","server":"b.example.com","server_port":8443}""",
            ),
        )

        val root = JSONObject(
            SubscriptionSingBoxConfigBuilder.profileProbeConfig(
                profiles = profiles,
                serverOverrideIp = "104.16.0.10",
            ).config,
        )
        val profileOutbounds = root.getJSONArray("outbounds").objects()
            .filter { it.optString("type") == "trojan" }

        assertEquals(listOf("104.16.0.10", "104.16.0.10"), profileOutbounds.map { it.getString("server") })
        assertEquals(listOf(443, 8443), profileOutbounds.map { it.getInt("server_port") })
    }

    @Test
    fun runtimeConfigContainsOnlySelectedProfileAndRequiredPlumbing() {
        val selected = sampleProfiles().first()

        val root = JSONObject(SubscriptionSingBoxConfigBuilder.runtimeConfig(selected))
        val outbounds = root.getJSONArray("outbounds")

        SingBoxConfigValidator.requireValid(root.toString())
        assertEquals(2, root.getJSONArray("inbounds").length())
        assertEquals(3, outbounds.length())
        assertEquals(selected.tag, outbounds.getJSONObject(0).getString("tag"))
        assertEquals("selector", outbounds.getJSONObject(1).getString("tag"))
        assertEquals("direct", outbounds.getJSONObject(2).getString("tag"))
        assertEquals(1280, root.getJSONArray("inbounds").getJSONObject(0).getInt("mtu"))
        assertRuntimeInboundsDoNotUseLegacySniff(root)
    }

    @Test
    fun runtimeConfigOverridesSelectedServerButKeepsOriginalPort() {
        val selected = sampleProfiles().first().copy(
            tag = "selected",
            port = 8443,
            outboundJson = """{"type":"trojan","tag":"selected","server":"selected.example.com","server_port":8443}""",
        )

        val root = JSONObject(
            SubscriptionSingBoxConfigBuilder.runtimeConfig(
                profile = selected,
                serverOverrideIp = "104.16.0.10",
            ),
        )
        val outbound = root.getJSONArray("outbounds").getJSONObject(0)

        SingBoxConfigValidator.requireValid(root.toString())
        assertEquals("104.16.0.10", outbound.getString("server"))
        assertEquals(8443, outbound.getInt("server_port"))
    }

    @Test
    fun runtimeConfigRoutesUserDnsThroughSelectedProxyAndKeepsDirectBootstrap() {
        val selected = sampleProfiles().first()

        val root = JSONObject(SubscriptionSingBoxConfigBuilder.runtimeConfig(selected))
        val route = root.getJSONObject("route")
        val dns = root.getJSONObject("dns")
        val servers = dns.getJSONArray("servers")
        val remoteDns = (0 until servers.length())
            .map { servers.getJSONObject(it) }
            .single { it.getString("tag") == "dns-remote" }
        val directDns = (0 until servers.length())
            .map { servers.getJSONObject(it) }
            .single { it.getString("tag") == "dns-direct" }

        assertEquals("dns-remote", dns.getString("final"))
        assertEquals("tcp", remoteDns.getString("type"))
        assertEquals("selector", remoteDns.getString("detour"))
        assertEquals("dns-direct", route.getJSONObject("default_domain_resolver").getString("server"))
        assertEquals("udp", directDns.getString("type"))
        assertFalse(directDns.has("detour"))
        val rules = route.getJSONArray("rules")
        val quicRejectRule = rules.getJSONObject(0)
        assertEquals("udp", quicRejectRule.getJSONArray("network").getString(0))
        assertEquals(443, quicRejectRule.getJSONArray("port").getInt(0))
        assertEquals("reject", quicRejectRule.getString("action"))
        assertEquals("default", quicRejectRule.getString("method"))
        assertTrue(quicRejectRule.getBoolean("no_drop"))
        val portDnsHijackRule = rules.getJSONObject(1)
        val protocolDnsHijackRule = rules.getJSONObject(2)
        assertEquals(53, portDnsHijackRule.getJSONArray("port").getInt(0))
        assertEquals("hijack-dns", portDnsHijackRule.getString("action"))
        assertEquals("dns", protocolDnsHijackRule.getJSONArray("protocol").getString(0))
        assertEquals("hijack-dns", protocolDnsHijackRule.getString("action"))
    }

    @Test
    fun udpAppsRuntimeModeOmitsBrowserQuicReject() {
        val selected = sampleProfiles().first()

        val root = JSONObject(
            SubscriptionSingBoxConfigBuilder.runtimeConfig(
                profile = selected,
                runtimeMode = RuntimeCompatibilityMode.UdpApps,
            ),
        )
        val rules = root.getJSONObject("route").getJSONArray("rules")

        assertEquals(2, rules.length())
        assertEquals("hijack-dns", rules.getJSONObject(0).getString("action"))
        assertEquals("hijack-dns", rules.getJSONObject(1).getString("action"))
    }

    @Test
    fun startupProbeConfigDoesNotRouteDnsThroughMissingSelector() {
        val profiles = sampleProfiles().take(2)

        val root = JSONObject(SubscriptionSingBoxConfigBuilder.profileProbeConfig(profiles).config)
        val dns = root.getJSONObject("dns")
        val servers = dns.getJSONArray("servers")

        assertFalse(dns.has("final"))
        assertFalse(root.getJSONObject("route").has("rules"))
        assertEquals(1, servers.length())
        assertEquals("dns-direct", servers.getJSONObject(0).getString("tag"))
    }

    private fun assertRuntimeInboundsDoNotUseLegacySniff(root: JSONObject) {
        val inbounds = root.getJSONArray("inbounds")
        for (index in 0 until inbounds.length()) {
            val inbound = inbounds.getJSONObject(index)
            assertFalse(inbound.has("sniff"))
            assertFalse(inbound.has("sniff_override_destination"))
            assertFalse(inbound.has("sniff_timeout"))
        }
    }

    private fun sampleProfiles(): List<ConnectionProfile> {
        val links = (1..4).joinToString("\n") { index ->
            "trojan://secret-$index@trojan-$index.example.com:443?sni=trojan-$index.example.com#profile-$index"
        }
        val encoded = Base64.getEncoder().encodeToString(links.toByteArray())
        return ProxySubscriptionParser.parseBase64(encoded).catalog.profiles
    }

    private fun org.json.JSONArray.objects(): List<JSONObject> {
        return (0 until length()).map { getJSONObject(it) }
    }
}
