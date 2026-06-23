package com.whitedns.vpn

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class ProxySubscriptionParserTest {
    @Test
    fun parserDecodesUnpaddedBase64AndParsesCommonSchemes() {
        val vmessPayload = Base64.getEncoder().encodeToString(
            """
            {
              "v": "2",
              "ps": "VMess US",
              "add": "vmess.example.com",
              "port": "443",
              "id": "00000000-0000-0000-0000-000000000000",
              "aid": "0",
              "scy": "auto",
              "net": "ws",
              "host": "edge.example.com",
              "path": "/vmess",
              "tls": "tls",
              "sni": "edge.example.com"
            }
            """.trimIndent().toByteArray(),
        )
        val shadowsocksMain = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("aes-128-gcm:pass@example.net:8388".toByteArray())
        val links = listOf(
            "vless://00000000-0000-0000-0000-000000000001@vless.example.com:443?security=tls&type=ws&host=edge.example.com&path=%2Fvless&sni=edge.example.com#VLESS%20US",
            "trojan://secret@trojan.example.com:443?sni=trojan.example.com&type=ws&path=%2Ftrojan#Trojan%20DE",
            "vmess://$vmessPayload",
            "ss://$shadowsocksMain#SS%20NL",
            "hysteria://hysteria.example.com:443?auth=hy-pass&upmbps=20&downmbps=100&sni=hysteria.example.com#HY%20JP",
            "hysteria2://hy2-pass@hy2.example.com:443?sni=hy2.example.com&obfs=salamander&obfs-password=obfs-pass#HY2%20SG",
            "ssr://unsupported",
            "vless://missing-port",
        ).joinToString("\n")
        val encoded = Base64.getEncoder().encodeToString(links.toByteArray()).trimEnd('=')

        val result = ProxySubscriptionParser.parseBase64(encoded)

        assertEquals(8, result.stats.totalLinks)
        assertEquals(6, result.stats.supportedLinks)
        assertEquals(1, result.stats.malformedLinks)
        assertEquals(1, result.stats.unsupportedLinks)
        assertEquals(
            listOf("vless", "trojan", "vmess", "shadowsocks", "hysteria", "hysteria2"),
            result.catalog.profiles.map { it.type },
        )
        assertTrue(result.catalog.profiles.all { it.outboundJson?.isNotBlank() == true })
    }

    @Test
    fun parserDeduplicatesByGeneratedOutboundFingerprint() {
        val links = """
            trojan://secret@trojan.example.com:443?sni=trojan.example.com#A
            trojan://secret@trojan.example.com:443?sni=trojan.example.com#B
        """.trimIndent()
        val encoded = Base64.getEncoder().encodeToString(links.toByteArray())

        val result = ProxySubscriptionParser.parseBase64(encoded)

        assertEquals(1, result.catalog.profiles.size)
        assertEquals(1, result.stats.duplicateLinks)
    }

    @Test
    fun parserBuildsExpectedOutboundFields() {
        val link = "vless://00000000-0000-0000-0000-000000000001@vless.example.com:443?security=tls&type=ws&host=edge.example.com&path=%2Fvless&sni=edge.example.com#VLESS%20US"
        val encoded = Base64.getEncoder().encodeToString(link.toByteArray())

        val profile = ProxySubscriptionParser.parseBase64(encoded).catalog.profiles.single()
        val outbound = JSONObject(profile.outboundJson!!)

        assertEquals("vless", outbound.getString("type"))
        assertEquals("vless.example.com", outbound.getString("server"))
        assertEquals("edge.example.com", outbound.getJSONObject("tls").getString("server_name"))
        assertEquals("ws", outbound.getJSONObject("transport").getString("type"))
        assertEquals("edge.example.com", profile.validationHost)
    }

    @Test
    fun vlessEncryptionNoneDoesNotBecomePacketEncoding() {
        val link = "vless://00000000-0000-0000-0000-000000000001@vless.example.com:443?encryption=none&security=tls&sni=edge.example.com#VLESS"
        val encoded = Base64.getEncoder().encodeToString(link.toByteArray())

        val outbound = JSONObject(ProxySubscriptionParser.parseBase64(encoded).catalog.profiles.single().outboundJson!!)

        assertTrue(!outbound.has("packet_encoding"))
    }

    @Test
    fun vlessOnlyAcceptsSingBoxPacketEncodingValues() {
        val links = """
            vless://00000000-0000-0000-0000-000000000001@vless.example.com:443?security=tls&packetEncoding=xudp#valid
            vless://00000000-0000-0000-0000-000000000002@vless.example.com:443?security=tls&packetEncoding=none#invalid
        """.trimIndent()
        val encoded = Base64.getEncoder().encodeToString(links.toByteArray())

        val result = ProxySubscriptionParser.parseBase64(encoded)

        assertEquals(1, result.catalog.profiles.size)
        assertEquals(1, result.stats.malformedLinks)
        val outbound = JSONObject(result.catalog.profiles.single().outboundJson!!)
        assertEquals("xudp", outbound.getString("packet_encoding"))
    }

    @Test
    fun runtimeCompatibilityOnlyAllowsExplicitUdpCapableProfiles() {
        val links = """
            vless://00000000-0000-0000-0000-000000000001@vless.example.com:443?security=tls&packetEncoding=xudp#vless-udp
            vless://00000000-0000-0000-0000-000000000002@vless.example.com:443?security=tls#vless-tcp
            hysteria2://hy2-pass@hy2.example.com:443?sni=hy2.example.com#hy2
            trojan://secret@trojan.example.com:443?sni=trojan.example.com#trojan
        """.trimIndent()
        val encoded = Base64.getEncoder().encodeToString(links.toByteArray())

        val profiles = ProxySubscriptionParser.parseBase64(encoded).catalog.profiles.associateBy { it.tag.substringBefore(" ") }

        assertTrue(profiles.getValue("vless-udp").supportsUdpApps)
        assertFalse(profiles.getValue("vless-tcp").supportsUdpApps)
        assertTrue(profiles.getValue("hy2").supportsUdpApps)
        assertFalse(profiles.getValue("trojan").supportsUdpApps)
        assertEquals(RuntimeCompatibilityMode.UdpApps, RuntimeCompatibilityPolicy.effectiveMode(profiles.getValue("vless-udp")))
        assertEquals(RuntimeCompatibilityMode.Compatible, RuntimeCompatibilityPolicy.effectiveMode(profiles.getValue("trojan")))
    }

    @Test
    fun realityTlsOmitsEmptyShortId() {
        val link = "vless://00000000-0000-0000-0000-000000000001@vless.example.com:443?security=reality&pbk=abc&sni=edge.example.com&sid=#VLESS"
        val encoded = Base64.getEncoder().encodeToString(link.toByteArray())

        val outbound = JSONObject(ProxySubscriptionParser.parseBase64(encoded).catalog.profiles.single().outboundJson!!)
        val reality = outbound.getJSONObject("tls").getJSONObject("reality")

        assertTrue(!reality.has("short_id"))
    }
}
