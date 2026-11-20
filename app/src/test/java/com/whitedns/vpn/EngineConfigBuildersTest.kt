package com.whitedns.vpn

import com.whitedns.vpn.engines.models.DnsTunnelEngineConfig
import com.whitedns.vpn.engines.models.DnsTunnelProfile
import com.whitedns.vpn.engines.models.PsiphonConfigBuilder
import com.whitedns.vpn.engines.models.PsiphonProfile
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class EngineConfigBuildersTest {
    @Test fun directPsiphonDoesNotChooseFrontedProtocols() {
        val config = JSONObject(PsiphonConfigBuilder.build(PsiphonProfile(mode = "direct"), "/data", 1080, 1081))
        val protocols = config.getJSONArray("LimitTunnelProtocols")
        assertTrue(protocols.length() > 0)
        repeat(protocols.length()) { assertFalse(protocols.getString(it).startsWith("FRONTED-")) }
        assertTrue(config.getBoolean("DisableTactics"))
    }
    @Test fun dnsTunnelsUseTheirActualWireFormat() {
        for (engine in listOf("dnstt", "vaydns")) {
            val profile = DnsTunnelProfile(engine = engine, domain = "t.example.com", publicKey = "a".repeat(64))
            val config = JSONObject(DnsTunnelEngineConfig.build(profile, "127.0.0.1:1080", "8.8.8.8:53"))
            assertEquals(engine, config.getString("wire"))
            assertFalse(config.has("via"))
        }
    }
    @Test fun ipv6ResolversHaveUnambiguousTransportPorts() {
        val profile = DnsTunnelProfile("dnstt", "t.example.com", "a".repeat(64), resolvers = "2001:4860:4860::8888")
        assertEquals("[2001:4860:4860::8888]:53", profile.dnsAddress())
        assertEquals("tls://[2001:4860:4860::8888]:853", profile.copy(dnsTransport = "dot").dnsAddress())
        assertEquals("tcp://[2001:4860:4860::8888]:53", profile.copy(dnsTransport = "tcp").dnsAddress())
    }
}
