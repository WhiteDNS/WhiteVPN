package com.whitedns.vpn

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class UserSubscriptionImporterTest {
    @Test
    fun subConvPortProducesMihomoFieldsForEverySupportedLinkType() {
        val vmess = Base64.getEncoder().encodeToString(
            """
            {
              "v": "2",
              "ps": "VMess",
              "add": "vmess.example.com",
              "port": "443",
              "id": "00000000-0000-0000-0000-000000000003",
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
        val ssCredentials = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("aes-128-gcm:password".toByteArray())
        val links = """
            vless://00000000-0000-0000-0000-000000000001@vless.example.com:443?security=reality&type=grpc&serviceName=vpn&sni=edge.example.com&pbk=public-key&sid=short-id#Node
            trojan://secret@trojan.example.com:443?sni=trojan.example.com&type=ws&path=%2Ftrojan#Node
            vmess://$vmess
            ss://$ssCredentials@ss.example.net:8388?plugin=v2ray-plugin%3Bmode%3Dwebsocket%3Bhost%3Dedge.example.com%3Bpath%3D%2Fss%3Btls#SS
        """.trimIndent()
        val encoded = Base64.getEncoder().encodeToString(links.toByteArray()).trimEnd('=')

        val proxies = SubConvConverter.convert(encoded)

        assertEquals(listOf("Node", "Node-01", "VMess", "SS"), proxies.map { it.getString("name") })

        val vless = proxies[0]
        assertEquals(
            setOf(
                "name", "type", "server", "port", "uuid", "udp", "tls",
                "client-fingerprint", "servername", "reality-opts", "xudp", "network", "grpc-opts",
            ),
            vless.keys().asSequence().toSet(),
        )
        assertEquals("vless", vless.getString("type"))
        assertTrue(vless.getBoolean("tls"))
        assertEquals("chrome", vless.getString("client-fingerprint"))
        assertEquals("edge.example.com", vless.getString("servername"))
        assertEquals("public-key", vless.getJSONObject("reality-opts").getString("public-key"))
        assertEquals("short-id", vless.getJSONObject("reality-opts").getString("short-id"))
        assertEquals("grpc", vless.getString("network"))
        assertEquals("vpn", vless.getJSONObject("grpc-opts").getString("grpc-service-name"))
        assertTrue(vless.getBoolean("xudp"))

        val trojan = proxies[1]
        assertEquals(
            setOf(
                "name", "type", "server", "port", "password", "udp", "sni",
                "network", "ws-opts", "client-fingerprint",
            ),
            trojan.keys().asSequence().toSet(),
        )
        assertEquals("trojan", trojan.getString("type"))
        assertTrue(trojan.getBoolean("udp"))
        assertEquals("/trojan", trojan.getJSONObject("ws-opts").getString("path"))
        assertTrue(
            trojan.getJSONObject("ws-opts").getJSONObject("headers")
                .getString("User-Agent").isNotBlank(),
        )

        val vmessProxy = proxies[2]
        assertEquals(
            setOf(
                "name", "type", "server", "port", "uuid", "alterId", "cipher", "udp", "xudp",
                "tls", "skip-cert-verify", "servername", "network", "ws-opts",
            ),
            vmessProxy.keys().asSequence().toSet(),
        )
        assertEquals(0, vmessProxy.getInt("alterId"))
        assertEquals("auto", vmessProxy.getString("cipher"))
        assertTrue(vmessProxy.getBoolean("tls"))
        assertEquals("edge.example.com", vmessProxy.getJSONObject("ws-opts")
            .getJSONObject("headers").getString("Host"))

        val shadowsocks = proxies[3]
        assertEquals(
            setOf("name", "type", "server", "port", "password", "cipher", "udp", "plugin", "plugin-opts"),
            shadowsocks.keys().asSequence().toSet(),
        )
        assertEquals("ss", shadowsocks.getString("type"))
        assertEquals("aes-128-gcm", shadowsocks.getString("cipher"))
        assertEquals("v2ray-plugin", shadowsocks.getString("plugin"))
        assertTrue(shadowsocks.getJSONObject("plugin-opts").getBoolean("tls"))
    }

    @Test
    fun subConvPortMirrorsVlessWebsocketAndHttp2Options() {
        val proxies = SubConvConverter.convert(
            """
            vless://00000000-0000-0000-0000-000000000001@ws.example.com:443?security=tls&type=ws&host=edge.example.com&path=%2Fws&ed=2048&eh=X-Early#WS
            vless://00000000-0000-0000-0000-000000000002@h2.example.com:443?security=tls&type=http&host=edge.example.com&path=%2Fh2#H2
            """.trimIndent(),
        )

        val websocket = proxies[0].getJSONObject("ws-opts")
        assertEquals("/ws", websocket.getString("path"))
        assertEquals("edge.example.com", websocket.getJSONObject("headers").getString("Host"))
        assertTrue(websocket.getJSONObject("headers").getString("User-Agent").isNotBlank())
        assertEquals(2048, websocket.getInt("max-early-data"))
        assertEquals("X-Early", websocket.getString("early-data-header-name"))

        val http2 = proxies[1]
        assertEquals("h2", http2.getString("network"))
        assertEquals("/h2", http2.getJSONObject("h2-opts").getJSONArray("path").getString(0))
        assertEquals("edge.example.com", http2.getJSONObject("h2-opts").getJSONArray("host").getString(0))
        assertEquals(0, http2.getJSONObject("h2-opts").getJSONObject("headers").length())
    }

    @Test
    fun nestedProxyOptionsUseYamlSafeQuoting() {
        val proxy = JSONObject()
            .put("name", "Escaped path")
            .put("type", "vless")
            .put("server", "example.com")
            .put("port", 443)
            .put("ws-opts", JSONObject().put("path", "</ws"))

        val yaml = MihomoLinkConfigBuilder.build(listOf(proxy))

        assertTrue(yaml.contains("ws-opts: {'path': '</ws'}"))
        assertFalse(yaml.contains("\\/"))
    }

    @Test
    fun importerConvertsLinkListsIntoMihomoYamlWithGroups() {
        val imported = UserSubscriptionImporter.import(
            "vless://00000000-0000-0000-0000-000000000001@vless.example.com:443?security=tls&type=ws&host=edge.example.com&path=%2Fvpn&sni=edge.example.com#VLESS",
            nowMs = 123L,
        )
        val snapshot = MihomoConfigParser.parse(imported.yaml, 123L)

        assertEquals(UserSubscriptionFormat.Links, imported.format)
        assertEquals(1, imported.connectionCount)
        assertEquals(1, snapshot.catalog.profiles.size)
        assertEquals(2, snapshot.summary.groups.size)
        assertTrue(imported.yaml.contains("ws-opts:"))
        assertTrue(imported.yaml.contains("servername: 'edge.example.com'"))
        assertTrue(imported.yaml.contains("name: 'WhiteDNS Proxy'"))
        assertTrue(imported.yaml.contains("- 'MATCH,WhiteDNS Proxy'"))
    }

    @Test
    fun importerAddsGroupsToMihomoYamlThatOnlyContainsProxies() {
        val imported = UserSubscriptionImporter.import(
            """
            proxies:
              - name: 'Custom node'
                type: vless
                server: example.com
                port: 443
                uuid: 00000000-0000-0000-0000-000000000001
            """.trimIndent(),
        )

        assertEquals(UserSubscriptionFormat.Mihomo, imported.format)
        assertEquals(1, imported.connectionCount)
        assertEquals(2, MihomoConfigParser.parse(imported.yaml).summary.groups.size)
    }

    @Test
    fun importerNormalizesClashJsonSubscriptions() {
        val imported = UserSubscriptionImporter.import(
            """
            {
              "proxies": [{
                "name": "Clash VLESS",
                "type": "vless",
                "server": "vless.example.com",
                "port": 443,
                "uuid": "00000000-0000-0000-0000-000000000001",
                "tls": true,
                "servername": "edge.example.com",
                "network": "ws",
                "ws-opts": {"path": "/vpn", "headers": {"Host": "edge.example.com"}}
              }]
            }
            """.trimIndent(),
            nowMs = 123L,
        )
        val snapshot = MihomoConfigParser.parse(imported.yaml, 123L)

        assertEquals(UserSubscriptionFormat.Mihomo, imported.format)
        assertEquals(1, imported.connectionCount)
        assertEquals("vless.example.com", snapshot.catalog.profiles.single().server)
        assertTrue(imported.yaml.contains("network: 'ws'"))
        assertTrue(imported.yaml.contains("ws-opts:"))
        assertEquals(2, snapshot.summary.groups.size)
    }

    @Test
    fun importerNormalizesXrayJsonConfigArrays() {
        val imported = UserSubscriptionImporter.import(
            """
            [{
              "remarks": "Xray VLESS",
              "outbounds": [{
                "protocol": "vless",
                "settings": {"vnext": [{
                  "address": "vless.example.com",
                  "port": 443,
                  "users": [{"id": "00000000-0000-0000-0000-000000000001", "encryption": "none"}]
                }]},
                "streamSettings": {
                  "network": "ws",
                  "security": "tls",
                  "tlsSettings": {"serverName": "edge.example.com", "fingerprint": "chrome"},
                  "wsSettings": {"path": "/vpn", "host": "edge.example.com"}
                }
              }]
            }]
            """.trimIndent(),
            nowMs = 123L,
        )
        val snapshot = MihomoConfigParser.parse(imported.yaml, 123L)

        assertEquals(UserSubscriptionFormat.Links, imported.format)
        assertEquals(1, imported.connectionCount)
        assertEquals("vless.example.com", snapshot.catalog.profiles.single().server)
        assertTrue(imported.yaml.contains("servername: 'edge.example.com'"))
        assertTrue(imported.yaml.contains("'path': '/vpn'"))
        assertEquals(2, snapshot.summary.groups.size)
    }

    @Test
    fun importerSupportsXrayTrojanAndSkipsAggregateConfigs() {
        val imported = UserSubscriptionImporter.import(
            """
            [
              {
                "remarks": "Trojan profile",
                "outbounds": [{
                  "tag": "proxy",
                  "protocol": "trojan",
                  "settings": {"servers": [{
                    "address": "trojan.example.com",
                    "port": 443,
                    "password": "secret"
                  }]},
                  "streamSettings": {"network": "ws", "security": "tls"}
                }]
              },
              {
                "remarks": "Best Ping",
                "outbounds": [
                  {"tag": "proxy-1", "protocol": "trojan", "settings": {"servers": [{"address": "one.example.com", "port": 443, "password": "one"}]}},
                  {"tag": "proxy-2", "protocol": "trojan", "settings": {"servers": [{"address": "two.example.com", "port": 443, "password": "two"}]}}
                ]
              }
            ]
            """.trimIndent(),
            nowMs = 123L,
        )

        assertEquals(1, imported.connectionCount)
        assertTrue(imported.yaml.contains("type: 'trojan'"))
        assertTrue(imported.yaml.contains("password: 'secret'"))
        assertFalse(imported.yaml.contains("Best Ping"))
    }

    @Test
    fun oldGeneratedYamlIsMigratedToTheRuntimeTrafficGroup() {
        val oldYaml = """
            proxy-groups:
              - name: 'WhiteDNS Select'
                type: select
                proxies:
                  - 'WhiteDNS Auto'
        """.trimIndent()

        val migrated = MihomoLinkConfigBuilder.migrateGeneratedYaml(oldYaml)

        assertFalse(migrated.contains("WhiteDNS Select"))
        assertTrue(migrated.contains("name: 'WhiteDNS Proxy'"))
        assertTrue(migrated.contains("- 'MATCH,WhiteDNS Proxy'"))
    }
}
