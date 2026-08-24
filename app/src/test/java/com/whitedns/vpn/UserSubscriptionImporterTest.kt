package com.whitedns.vpn

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.Base64

class UserSubscriptionImporterTest {
    @Test
    fun boundedSubscriptionReadWorksBeforeAndroid33() {
        val bytes = ByteArray(16) { it.toByte() }

        assertArrayEquals(bytes.copyOf(7), ByteArrayInputStream(bytes).readAtMost(7))
        assertArrayEquals(bytes, ByteArrayInputStream(bytes).readAtMost(32))
    }

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
                "name", "type", "server", "port", "uuid", "udp", "tls", "skip-cert-verify",
                "client-fingerprint", "servername", "reality-opts", "xudp", "network", "grpc-opts",
            ),
            vless.keys().asSequence().toSet(),
        )
        assertEquals("vless", vless.getString("type"))
        assertTrue(vless.getBoolean("tls"))
        assertFalse(vless.getBoolean("skip-cert-verify"))
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
                "name", "type", "server", "port", "password", "udp", "sni", "skip-cert-verify",
                "network", "ws-opts", "client-fingerprint",
            ),
            trojan.keys().asSequence().toSet(),
        )
        assertEquals("trojan", trojan.getString("type"))
        assertTrue(trojan.getBoolean("udp"))
        assertFalse(trojan.getBoolean("skip-cert-verify"))
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
    fun importerConvertsSocksLinksAndSkipsMalformedSiblings() {
        val standardCredentials = Base64.getEncoder().encodeToString("🌀:standard".toByteArray())
        val urlSafeCredentials = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("🌀:url-safe".toByteArray())
        val links = """
            socks://anonymous.example.com:1080
            socks5://us%2Ber:pa%3Ass@[2001:db8::1]:1081?udp=false#IPv6%20Auth
            socks5://$standardCredentials@standard.example.com:1082#Standard
            socks5://$urlSafeCredentials@192.0.2.10:1083#URL-safe
            socks5://solo@192.0.2.11:1084#User-only
            socks://:1080
            socks://missing-port.example.com
            socks5://:password@missing-user.example.com:1085
            socks5://bad-port.example.com:70000
        """.trimIndent()
        val encoded = Base64.getEncoder().encodeToString(links.toByteArray()).trimEnd('=')

        val proxies = SubConvConverter.convert(encoded)
        val imported = UserSubscriptionImporter.import(encoded, nowMs = 123L)
        val profiles = MihomoConfigParser.parse(imported.yaml, 123L).catalog.profiles
        val byName = proxies.associateBy { it.getString("name") }

        assertEquals(5, proxies.size)
        assertEquals(5, imported.connectionCount)
        assertTrue(profiles.all { it.type == "socks5" })
        assertTrue(byName.getValue("anonymous.example.com:1080").getBoolean("udp"))
        assertFalse(byName.getValue("anonymous.example.com:1080").has("username"))
        assertEquals("2001:db8::1", byName.getValue("IPv6 Auth").getString("server"))
        assertEquals("us+er", byName.getValue("IPv6 Auth").getString("username"))
        assertEquals("pa:ss", byName.getValue("IPv6 Auth").getString("password"))
        assertFalse(byName.getValue("IPv6 Auth").getBoolean("udp"))
        assertEquals("🌀", byName.getValue("Standard").getString("username"))
        assertEquals("standard", byName.getValue("Standard").getString("password"))
        assertEquals("🌀", byName.getValue("URL-safe").getString("username"))
        assertEquals("url-safe", byName.getValue("URL-safe").getString("password"))
        assertEquals("solo", byName.getValue("User-only").getString("username"))
        assertFalse(byName.getValue("User-only").has("password"))
        assertTrue(imported.yaml.contains("name: 'WhiteDNS Proxy'"))
    }

    @Test
    fun importerConvertsHysteria2ShareLinks() {
        val imported = UserSubscriptionImporter.import(
            "hysteria2://password%21@hy2.example.com:10810?security=tls&obfs=salamander&obfs-password=obfs-password&insecure=0&sni=hy2.example.com#Hysteria2",
            nowMs = 123L,
        )
        val snapshot = MihomoConfigParser.parse(imported.yaml, 123L)

        assertEquals(UserSubscriptionFormat.Links, imported.format)
        assertEquals("hysteria2", snapshot.catalog.profiles.single().type)
        assertTrue(imported.yaml.contains("password: 'password!'"))
        assertTrue(imported.yaml.contains("obfs: 'salamander'"))
        assertTrue(imported.yaml.contains("obfs-password: 'obfs-password'"))
        assertTrue(imported.yaml.contains("sni: 'hy2.example.com'"))
        assertTrue(imported.yaml.contains("skip-cert-verify: false"))
    }

    @Test
    fun importerConvertsBase64WireGuardLinksAndTheirMihomoOptions() {
        val link = "wireguard://private%2Bkey%3D@engage.example.com:2408" +
            "?address=172.16.0.2%2F32%2C2606%3A4700%3A110%3A8765%3A%3A2%2F128" +
            "&publickey=public%2Bkey%3D&reserved=1%2C2%2C3&keepalive=25&mtu=1280" +
            "&wnoise=quic&wnoisecount=5&wnoisedelay=2-5&wpayloadsize=40-90" +
            "&version=3&jc=4&jmin=40&jmax=70&header-protection-key=aGVsbG8%3D" +
            "&content-padding-addition=16-32&rekey-after-time=120s&rekey-timeout=5s" +
            "&reject-after-time=180s&keepalive-timeout=30s&max-handshake-attempts=10" +
            "&random-trailers=true&disable-cookies=false&ip-stack=mips&congestion-controller=bbr3#WARP"
        val encoded = Base64.getEncoder().encodeToString(link.toByteArray()).trimEnd('=')

        val imported = UserSubscriptionImporter.import(encoded, nowMs = 123L)
        val snapshot = MihomoConfigParser.parse(imported.yaml, 123L)

        assertEquals(UserSubscriptionFormat.Links, imported.format)
        assertEquals(1, imported.connectionCount)
        assertEquals("wireguard", snapshot.catalog.profiles.single().type)
        assertTrue(imported.yaml.contains("private-key: 'private+key='"))
        assertTrue(imported.yaml.contains("public-key: 'public+key='"))
        assertTrue(imported.yaml.contains("ipv6: '2606:4700:110:8765::2'"))
        assertTrue(imported.yaml.contains("reserved: [1, 2, 3]"))
        assertTrue(imported.yaml.contains("persistent-keepalive: 25"))
        assertTrue(imported.yaml.contains("amnezia-wg-option:"))
        assertTrue(imported.yaml.contains("'version': 3"))
        assertTrue(imported.yaml.contains("'header-protection-key': 'aGVsbG8='"))
        assertTrue(imported.yaml.contains("'random-trailers': true"))
        assertTrue(imported.yaml.contains("'disable-cookies': false"))
        assertTrue(imported.yaml.contains("wireguard-dpi-option:"))
        assertTrue(imported.yaml.contains("'fake-count': 5"))
        assertTrue(imported.yaml.contains("'fake-min-size': 40"))
        assertTrue(imported.yaml.contains("'fake-max-size': 90"))
        assertTrue(imported.yaml.contains("'fake-ttl': 3"))
        assertTrue(imported.yaml.contains("ip-stack:"))
        assertTrue(imported.yaml.contains("'mode': 'mips'"))
        assertTrue(imported.yaml.contains("'congestion-controller': 'bbr3'"))
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
    fun importerNormalizesClashJsonSocksAliases() {
        val imported = UserSubscriptionImporter.import(
            """
            {
              "proxies": [
                {
                  "name": "Clash SOCKS",
                  "type": "socks",
                  "server": "socks.example.com",
                  "port": 1080,
                  "username": "user",
                  "password": "pass"
                },
                {
                  "name": "Clash SOCKS5",
                  "type": "socks5",
                  "server": "192.0.2.20",
                  "port": 1081,
                  "udp": false
                },
                {
                  "name": "Invalid SOCKS",
                  "type": "socks5",
                  "server": "",
                  "port": 1082
                }
              ]
            }
            """.trimIndent(),
            nowMs = 123L,
        )
        val profiles = MihomoConfigParser.parse(imported.yaml, 123L).catalog.profiles

        assertEquals(UserSubscriptionFormat.Mihomo, imported.format)
        assertEquals(2, imported.connectionCount)
        assertTrue(profiles.all { it.type == "socks5" })
        assertTrue(imported.yaml.contains("username: 'user'"))
        assertTrue(imported.yaml.contains("password: 'pass'"))
        assertTrue(imported.yaml.contains("udp: true"))
        assertTrue(imported.yaml.contains("udp: false"))
        assertFalse(imported.yaml.contains("Invalid SOCKS"))
    }

    @Test
    fun importerNormalizesClashJsonWireGuardSubscriptions() {
        val imported = UserSubscriptionImporter.import(
            """
            {
              "proxies": [{
                "name": "WARP Pro",
                "type": "wireguard",
                "server": "162.159.192.1",
                "port": 2408,
                "ip": "172.16.0.2",
                "private-key": "private-key",
                "public-key": "public-key",
                "reserved": [1, 2, 3],
                "allowed-ips": ["0.0.0.0/0", "::/0"],
                "udp": true,
                "amnezia-wg-option": {"jc": 4, "jmin": 40, "jmax": 70}
              }]
            }
            """.trimIndent(),
            nowMs = 123L,
        )
        val snapshot = MihomoConfigParser.parse(imported.yaml, 123L)

        assertEquals(UserSubscriptionFormat.Mihomo, imported.format)
        assertEquals(1, imported.connectionCount)
        assertEquals("wireguard", snapshot.catalog.profiles.single().type)
        assertEquals(AmneziaNoiseSettings(4, 40, 70), snapshot.catalog.profiles.single().amneziaNoise)
        assertTrue(imported.yaml.contains("private-key: 'private-key'"))
        assertTrue(imported.yaml.contains("allowed-ips: ['0.0.0.0/0', '::/0']"))
        assertTrue(imported.yaml.contains("amnezia-wg-option: {'jc': 4, 'jmin': 40, 'jmax': 70}"))
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
    fun importerNormalizesXraySocksOutbounds() {
        val imported = UserSubscriptionImporter.import(
            """
            [
              {
                "remarks": "Xray SOCKS Auth",
                "outbounds": [{
                  "tag": "proxy",
                  "protocol": "socks",
                  "settings": {
                    "address": "auth.example.com",
                    "port": 1080,
                    "user": "user",
                    "pass": "pass",
                    "level": 1,
                    "email": "ignored@example.com"
                  },
                  "streamSettings": {"network": "ws"}
                }]
              },
              {
                "remarks": "Xray SOCKS Anonymous",
                "outbounds": [{
                  "protocol": "socks",
                  "settings": {"address": "192.0.2.30", "port": 1081}
                }]
              },
              {
                "remarks": "Missing password",
                "outbounds": [{
                  "protocol": "socks",
                  "settings": {"address": "invalid.example.com", "port": 1082, "user": "user"}
                }]
              },
              {
                "remarks": "Invalid port",
                "outbounds": [{
                  "protocol": "socks",
                  "settings": {"address": "invalid.example.net", "port": 70000}
                }]
              }
            ]
            """.trimIndent(),
            nowMs = 123L,
        )
        val profiles = MihomoConfigParser.parse(imported.yaml, 123L).catalog.profiles

        assertEquals(UserSubscriptionFormat.Links, imported.format)
        assertEquals(2, imported.connectionCount)
        assertTrue(profiles.all { it.type == "socks5" })
        assertTrue(imported.yaml.contains("username: 'user'"))
        assertTrue(imported.yaml.contains("password: 'pass'"))
        assertEquals(1, Regex("username:").findAll(imported.yaml).count())
        assertTrue(imported.yaml.contains("udp: true"))
        assertFalse(imported.yaml.contains("level:"))
        assertFalse(imported.yaml.contains("email:"))
        assertFalse(imported.yaml.contains("network:"))
        assertFalse(imported.yaml.contains("Missing password"))
        assertFalse(imported.yaml.contains("Invalid port"))
    }

    @Test
    fun importerPreservesXrayTcpHttpHeaderCamouflage() {
        val imported = UserSubscriptionImporter.import(
            """
            [{
              "remarks": "Xray TCP HTTP",
              "outbounds": [{
                "protocol": "vless",
                "settings": {"vnext": [{
                  "address": "vless.example.com",
                  "port": 443,
                  "users": [{"id": "00000000-0000-0000-0000-000000000001", "encryption": "none"}]
                }]},
                "streamSettings": {
                  "network": "tcp",
                  "tcpSettings": {"header": {
                    "type": "http",
                    "request": {
                      "method": "POST",
                      "path": ["/edge", "/backup"],
                      "headers": {
                        "Host": ["cdn.example.com"],
                        "Connection": "keep-alive",
                        "User-Agent": []
                      }
                    }
                  }}
                }
              }]
            }]
            """.trimIndent(),
            nowMs = 123L,
        )

        assertEquals(1, imported.connectionCount)
        assertTrue(imported.yaml.contains("network: 'http'"))
        assertTrue(imported.yaml.contains("'method': 'POST'"))
        assertTrue(imported.yaml.contains("'path': ['/edge', '/backup']"))
        assertTrue(imported.yaml.contains("'Host': ['cdn.example.com']"))
        assertTrue(imported.yaml.contains("'Connection': ['keep-alive']"))
        assertFalse(imported.yaml.contains("'User-Agent'"))
    }

    @Test
    fun importerNormalizesXrayWireGuardAndChains() {
        val imported = UserSubscriptionImporter.import(
            """
            [
              {
                "remarks": "WARP",
                "outbounds": [{
                  "tag": "proxy",
                  "protocol": "wireguard",
                  "settings": {
                    "secretKey": "private-one",
                    "address": ["172.16.0.2/32", "2606:4700:110:8765::2/128"],
                    "mtu": 1280,
                    "reserved": [1, 2, 3],
                    "amnezia-wg-option": {"version": 3, "jc": 4, "jmin": 40, "jmax": 70},
                    "wireguard-dpi-option": {"fake-count": 4, "fake-min-size": 40, "fake-max-size": 70, "fake-ttl": 3},
                    "ip-stack": {"mode": "mips", "congestion-controller": "bbr3"},
                    "peers": [{
                      "endpoint": "engage.example.com:2408",
                      "publicKey": "public-one",
                      "keepAlive": 25
                    }]
                  }
                }]
              },
              {
                "remarks": "WoW",
                "outbounds": [
                  {
                    "tag": "chain",
                    "protocol": "wireguard",
                    "settings": {
                      "secretKey": "private-chain",
                      "address": ["172.16.0.3/32"],
                      "peers": [{"endpoint": "162.159.192.1:2408", "publicKey": "public-chain"}]
                    },
                    "streamSettings": {"sockopt": {"dialerProxy": "proxy"}}
                  },
                  {
                    "tag": "proxy",
                    "protocol": "wireguard",
                    "settings": {
                      "secretKey": "private-base",
                      "address": ["172.16.0.4/32"],
                      "peers": [{"endpoint": "engage.example.net:2408", "publicKey": "public-base"}]
                    }
                  }
                ]
              }
            ]
            """.trimIndent(),
            nowMs = 123L,
        )
        val snapshot = MihomoConfigParser.parse(imported.yaml, 123L)

        assertEquals(UserSubscriptionFormat.Links, imported.format)
        assertEquals(3, imported.connectionCount)
        assertTrue(snapshot.catalog.profiles.all { it.type == "wireguard" })
        assertTrue(imported.yaml.contains("private-key: 'private-one'"))
        assertTrue(imported.yaml.contains("ip: '172.16.0.2'"))
        assertTrue(imported.yaml.contains("ipv6: '2606:4700:110:8765::2'"))
        assertTrue(imported.yaml.contains("ip-version: 'ipv4-prefer'"))
        assertTrue(imported.yaml.contains("ip-version: 'ipv4'"))
        assertTrue(imported.yaml.contains("allowed-ips: ['0.0.0.0/0', '::/0']"))
        assertTrue(imported.yaml.contains("reserved: [1, 2, 3]"))
        assertTrue(imported.yaml.contains("persistent-keepalive: 25"))
        assertTrue(imported.yaml.contains("amnezia-wg-option:"))
        assertTrue(imported.yaml.contains("'version': 3"))
        assertTrue(imported.yaml.contains("wireguard-dpi-option:"))
        assertTrue(imported.yaml.contains("'fake-count': 4"))
        assertTrue(imported.yaml.contains("'fake-ttl': 3"))
        assertTrue(imported.yaml.contains("ip-stack:"))
        assertTrue(imported.yaml.contains("'congestion-controller': 'bbr3'"))
        assertTrue(imported.yaml.contains("dialer-proxy: 'WoW (proxy)'"))
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
    fun clashJsonProxyCarryingALineBreakInAKeyIsDropped() {
        val hostile = """
            {
              "proxies": [
                {
                  "name": "Hostile",
                  "type": "trojan",
                  "server": "trojan.example.com",
                  "port": 443,
                  "password": "secret",
                  "sni\n    skip-cert-verify: true\n    x": "edge.example.com"
                },
                {
                  "name": "Benign",
                  "type": "trojan",
                  "server": "trojan.example.net",
                  "port": 443,
                  "password": "secret"
                }
              ]
            }
        """.trimIndent()

        val imported = UserSubscriptionImporter.import(hostile, nowMs = 123L)

        assertEquals(1, imported.connectionCount)
        assertFalse(imported.yaml.contains("skip-cert-verify: true"))
        assertFalse(imported.yaml.contains("Hostile"))
        assertTrue(imported.yaml.contains("name: 'Benign'"))
    }

    @Test
    fun importedProxiesNeverDisableCertificateVerification() {
        val trojanLink = "trojan://secret@trojan.example.com:443?sni=trojan.example.com&allowInsecure=1#Node"
        val encoded = Base64.getEncoder().encodeToString(trojanLink.toByteArray()).trimEnd('=')

        val imported = UserSubscriptionImporter.import(encoded, nowMs = 123L)

        assertTrue(imported.yaml.contains("skip-cert-verify: false"))
        assertFalse(imported.yaml.contains("skip-cert-verify: true"))
    }

    @Test
    fun xrayJsonAllowInsecureDoesNotDisableCertificateVerification() {
        val imported = UserSubscriptionImporter.import(
            """
        [
          {
            "remarks": "Xray",
            "outbounds": [
              {
                "tag": "proxy",
                "protocol": "trojan",
                "settings": { "servers": [ { "address": "trojan.example.com", "port": 443, "password": "secret" } ] },
                "streamSettings": {
                  "security": "tls",
                  "tlsSettings": { "serverName": "edge.example.com", "allowInsecure": true }
                }
              }
            ]
          }
        ]
            """.trimIndent(),
            nowMs = 123L,
        )

        assertEquals(1, imported.connectionCount)
        assertTrue(imported.yaml.contains("skip-cert-verify: false"))
        assertFalse(imported.yaml.contains("skip-cert-verify: true"))
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
