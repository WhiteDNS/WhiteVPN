package com.whitedns.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import java.net.SocketTimeoutException
import java.security.cert.CertPathValidatorException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

class MihomoRuntimeConfigBuilderTest {
    @Test
    fun tlsIntegrityPolicyUsesPublicFallbacksAndExpiresQuarantine() {
        val endpoint = CleanIpResult("104.16.0.1", 443, 1, 0.0, 1)
        val nowMs = 1_000L
        val untilMs = TlsIntegrityPolicy.quarantineUntil(nowMs)

        assertEquals(
            listOf(
                "https://valid-isrgrootx1.letsencrypt.org/",
                "https://connectivitycheck.gstatic.com/generate_204",
                "https://cloudflare.com/cdn-cgi/trace",
            ),
            TlsIntegrityPolicy.TEST_URLS,
        )
        assertEquals(2_000, TlsIntegrityPolicy.PROBE_TIMEOUT_MS)
        assertEquals(7_000L, TlsIntegrityPolicy.TOTAL_TIMEOUT_MS)
        assertEquals("104.16.0.1:443", TlsIntegrityPolicy.endpointKey(endpoint))
        assertTrue(TlsIntegrityPolicy.isQuarantined(untilMs, nowMs))
        assertFalse(TlsIntegrityPolicy.isQuarantined(untilMs, untilMs))
    }

    @Test
    fun tlsIntegrityOnlyRejectsCertificateFailures() {
        val chainFailure = SSLHandshakeException("certificate rejected").apply {
            initCause(CertPathValidatorException("invalid chain"))
        }

        assertTrue(TlsIntegrityPolicy.isCertificateFailure(chainFailure))
        assertTrue(TlsIntegrityPolicy.isCertificateFailure(SSLPeerUnverifiedException("wrong host")))
        assertFalse(TlsIntegrityPolicy.isCertificateFailure(SocketTimeoutException("blocked")))
        assertFalse(TlsIntegrityPolicy.isCertificateFailure(SSLHandshakeException("connection reset")))
    }

    @Test
    fun dpiBypassProxyArgsUseIranByedpiDefaults() {
        val args = DpiBypassDefaults.proxyArgs(31_234).toList()

        assertTrue(args.containsAll(listOf("-Kt,h", "-d1", "-f-1")))
        assertTrue(args.contains("-p31234"))
        assertFalse(args.contains("-o1"))
        assertFalse(args.contains("-r-5+se"))
    }

    @Test
    fun corePatchUsesMihomoPortsAndDisablesConfigTun() {
        val patch = MihomoRuntimeConfigBuilder.corePatchJson(
            appName = "WhiteDNS VPN",
            secret = "secret-123",
            controlPort = 39123,
            splitTunnelPlan = SplitTunnelRuntimePlan(
                mode = SplitTunnelMode.VpnOnlySelected,
                selectedPackages = listOf("com.example.mail"),
                allowedPackages = listOf("com.example.mail"),
                disallowedPackages = emptyList(),
                skippedPackages = emptyList(),
            ),
        )
        val tun = patch.getJSONObject("tun")

        assertEquals(2080, patch.getInt("mixed-port"))
        assertEquals("127.0.0.1:39123", patch.getString("external-controller"))
        assertEquals("secret-123", patch.getString("secret"))
        assertFalse(tun.getBoolean("enable"))
        assertFalse(tun.has("auto-route"))
        assertFalse(tun.has("strict-route"))
        assertFalse(tun.has("include-package"))
        assertFalse(tun.has("exclude-package"))
    }

    @Test
    fun corePatchDoesNotApplyBypassPackagesToDisabledConfigTun() {
        val patch = MihomoRuntimeConfigBuilder.corePatchJson(
            appName = "WhiteDNS VPN",
            secret = "secret-123",
            splitTunnelPlan = SplitTunnelRuntimePlan(
                mode = SplitTunnelMode.BypassSelected,
                selectedPackages = listOf("com.example.chat"),
                allowedPackages = emptyList(),
                disallowedPackages = listOf("com.example.chat"),
                skippedPackages = emptyList(),
            ),
        )

        assertFalse(patch.getJSONObject("tun").has("exclude-package"))
    }

    @Test
    fun serviceJsonUsesRuntimePaths() {
        val service = MihomoRuntimeConfigBuilder.serviceJson(
            appName = "WhiteDNS VPN",
            versionName = "1.2.3",
            baseDir = "/files/mihomo",
            cacheDir = "/cache/mihomo",
            profileYaml = "/files/mihomo/service_core_runtime_profile.yaml",
            patchFinal = "/files/mihomo/service_core_patch_final.json",
            logFile = "/files/mihomo/service_core.log",
            errorFile = "/files/service_error.log",
            secret = "abcdef1234567890-secret",
            controlPort = 39124,
        )

        assertEquals(39124, service.getInt("control_port"))
        assertEquals("/files/mihomo", service.getString("base_dir"))
        assertEquals("/cache/mihomo", service.getString("cache_dir"))
        assertEquals("/files/mihomo/service_core_runtime_profile.yaml", service.getString("core_path"))
        assertEquals("/files/mihomo/service_core_patch_final.json", service.getString("core_path_patch_final"))
        assertEquals("/files/mihomo/service_core.log", service.getString("log_path"))
        assertEquals("/files/service_error.log", service.getString("err_path"))
        assertEquals("abcdef1234567890", service.getString("id"))
        assertEquals("abcdef1234567890-secret", service.getString("secret"))
        assertTrue(service.getBoolean("prepare"))
    }

    @Test
    fun flClashRuntimeYamlReplacesOnlyWhiteDnsOwnedTopLevelSettings() {
        val yaml = """
            mixed-port: 1111
            external-controller: 0.0.0.0:1234
            dns:
              enable: false
            tun:
              enable: true
              stack: system
            proxies:
              - name: Example
                type: http
                server: example.com
                port: 443
            proxy-groups:
              - name: Auto
                type: url-test
                proxies:
                  - Example
        """.trimIndent()

        val runtimeYaml = MihomoRuntimeConfigBuilder.flClashRuntimeYaml(
            rawYaml = yaml,
            secret = "secret-123",
            controlPort = 39125,
        )

        assertTrue(runtimeYaml.contains("proxies:"))
        assertTrue(runtimeYaml.contains("proxy-groups:"))
        assertFalse(runtimeYaml.contains("mixed-port: 1111"))
        assertFalse(runtimeYaml.contains("external-controller: 0.0.0.0:1234"))
        assertFalse(runtimeYaml.contains("dns:\n  enable: false"))
        assertTrue(runtimeYaml.contains("mixed-port: 2080"))
        assertTrue(runtimeYaml.contains("external-controller: 127.0.0.1:39125"))
        assertTrue(runtimeYaml.contains("secret: \"secret-123\""))
        assertTrue(runtimeYaml.contains("dns:\n  enable: true"))
        assertTrue(runtimeYaml.contains("respect-rules: false"))
        assertTrue(runtimeYaml.contains("enhanced-mode: fake-ip"))
        assertTrue(runtimeYaml.contains("tun:\n  enable: false"))
    }

    @Test
    fun flClashRuntimeYamlRoutesDnsThroughWhiteDnsProxyGroup() {
        val yaml = """
            proxies:
              - name: Node
                type: http
                server: example.com
                port: 443
            proxy-groups:
              - name: 🚀 WhiteDNS Proxy
                type: select
                proxies:
                  - Node
        """.trimIndent()

        val runtimeYaml = MihomoRuntimeConfigBuilder.flClashRuntimeYaml(
            rawYaml = yaml,
            secret = "secret-123",
            controlPort = 39125,
        )

        assertTrue(runtimeYaml.contains("respect-rules: true"))
        assertTrue(runtimeYaml.contains("- 'https://1.1.1.1/dns-query#🚀 WhiteDNS Proxy'"))
        assertTrue(runtimeYaml.contains("- 'https://8.8.8.8/dns-query#🚀 WhiteDNS Proxy'"))
        assertTrue(runtimeYaml.contains("- 'tls://1.1.1.1:853#🚀 WhiteDNS Proxy'"))
        assertTrue(runtimeYaml.contains("- 'tls://8.8.8.8:853#🚀 WhiteDNS Proxy'"))
        assertFalse(runtimeYaml.contains("tcp://"))
        assertTrue(runtimeYaml.contains("proxy-server-nameserver:\n    - 1.1.1.1\n    - 8.8.8.8"))
    }

    @Test
    fun flClashRuntimeYamlHonorsExplicitEncryptedDnsModes() {
        val yaml = """
            proxies:
              - name: Node
                type: http
                server: example.com
                port: 443
        """.trimIndent()

        val dohYaml = MihomoRuntimeConfigBuilder.flClashRuntimeYaml(
            rawYaml = yaml,
            secret = "secret-123",
            dnsPrivacyMode = DnsPrivacyMode.DoH,
        )
        val dotYaml = MihomoRuntimeConfigBuilder.flClashRuntimeYaml(
            rawYaml = yaml,
            secret = "secret-123",
            dnsPrivacyMode = DnsPrivacyMode.DoT,
        )

        assertTrue(dohYaml.contains("- 'https://1.1.1.1/dns-query'"))
        assertFalse(dohYaml.contains("tls://"))
        assertTrue(dotYaml.contains("- 'tls://1.1.1.1:853'"))
        assertFalse(dotYaml.contains("https://1.1.1.1/dns-query"))
        assertEquals(DnsPrivacyMode.Automatic, DnsPrivacyMode.fromWireName("unsupported"))
    }

    @Test
    fun dnsPrivacyPolicyValidatesAndNormalizesCustomEndpoints() {
        assertEquals(
            "https://dns.example/dns-query",
            DnsPrivacyPolicy.normalizeDohUrl(" https://dns.example/dns-query "),
        )
        assertEquals("tls://dns.example:853", DnsPrivacyPolicy.normalizeDotEndpoint("dns.example"))
        assertEquals("tls://dns.example:8853", DnsPrivacyPolicy.normalizeDotEndpoint("dns.example:8853"))
        assertEquals(
            "tls://[2606:4700:4700::1111]:853",
            DnsPrivacyPolicy.normalizeDotEndpoint("[2606:4700:4700::1111]"),
        )

        assertThrows(IllegalArgumentException::class.java) {
            DnsPrivacyPolicy.normalizeDohUrl("http://dns.example/dns-query")
        }
        assertThrows(IllegalArgumentException::class.java) {
            DnsPrivacyPolicy.normalizeDohUrl("https://user@dns.example/dns-query")
        }
        assertThrows(IllegalArgumentException::class.java) {
            DnsPrivacyPolicy.normalizeDotEndpoint("udp://dns.example:53")
        }
        assertThrows(IllegalArgumentException::class.java) {
            DnsPrivacyPolicy.normalizeDotEndpoint("dns.example/path")
        }
    }

    @Test
    fun explicitDnsModesPutCustomResolverBeforeEncryptedFallbacks() {
        val yaml = """
            proxies:
              - name: Node
                type: http
                server: example.com
                port: 443
        """.trimIndent()

        val dohYaml = MihomoRuntimeConfigBuilder.flClashRuntimeYaml(
            rawYaml = yaml,
            secret = "secret-123",
            dnsPrivacyMode = DnsPrivacyMode.DoH,
            dohUrl = "https://dns.example/dns-query",
        )
        val dotYaml = MihomoRuntimeConfigBuilder.flClashRuntimeYaml(
            rawYaml = yaml,
            secret = "secret-123",
            dnsPrivacyMode = DnsPrivacyMode.DoT,
            dotEndpoint = "dns.example:8853",
        )

        assertTrue(dohYaml.indexOf("https://dns.example/dns-query") < dohYaml.indexOf("https://1.1.1.1/dns-query"))
        assertTrue(dohYaml.contains("https://8.8.8.8/dns-query"))
        assertFalse(dohYaml.contains("tls://"))
        assertTrue(dotYaml.indexOf("tls://dns.example:8853") < dotYaml.indexOf("tls://1.1.1.1:853"))
        assertTrue(dotYaml.contains("tls://8.8.8.8:853"))
        assertFalse(dotYaml.contains("https://"))
    }

    @Test
    fun flClashSetupParamsUseHealthUrlAndEmptySelectionMap() {
        val setup = MihomoRuntimeConfigBuilder.setupParamsJson()

        assertEquals("https://www.gstatic.com/generate_204", setup.getString("test-url"))
        assertEquals(0, setup.getJSONObject("selected-map").length())
    }

    @Test
    fun frontingPatcherReplacesOnlyProxyServerFields() {
        val yaml = """
            rule-providers:
              ads:
                url: https://config.example/server:keep
            proxies:
              - name: One
                type: vless
                server: original.example.com
                port: 443
                servername: sni.example.com
                ws-opts:
                  headers:
                    Host: host.example.com
              - { name: Two, type: trojan, server: inline.example.com, port: 8443, sni: inline-sni.example.com }
            proxy-groups:
              - name: Auto
                type: url-test
                proxies:
                  - One
        """.trimIndent()

        val patched = MihomoFrontingPatcher.patchProxyServers(
            rawYaml = yaml,
            serverOverrideIp = "162.159.192.1",
        )

        assertTrue(patched.contains("url: https://config.example/server:keep"))
        assertTrue(patched.contains("server: 162.159.192.1"))
        assertTrue(patched.contains("server: 162.159.192.1, port: 8443"))
        assertTrue(patched.contains("servername: sni.example.com"))
        assertTrue(patched.contains("Host: host.example.com"))
        assertTrue(patched.contains("sni: inline-sni.example.com"))
        assertFalse(patched.contains("server: original.example.com"))
        assertFalse(patched.contains("server: inline.example.com"))
    }

    @Test
    fun frontingPatcherOverridesPortsOnlyWhenExplicitlyRequested() {
        val yaml = """
            proxies:
              - { name: One, type: vless, server: one.example.com, port: 443 }
        """.trimIndent()

        val patched = MihomoFrontingPatcher.patchProxyServers(yaml, "162.159.192.1", 2053)

        assertTrue(patched.contains("server: 162.159.192.1, port: 2053"))
    }

    @Test
    fun dpiBypassPatcherAddsLocalProxyAndDialerProxyOnlyWhenEnabled() {
        val yaml = """
            proxies:
              - name: One
                type: vless
                server: one.example.com
                port: 443
                ws-opts:
                  headers:
                    Host: host.example.com
              - { name: Two, type: trojan, server: two.example.com, port: 8443 }
            proxy-groups:
              - name: Auto
                type: url-test
                proxies:
                  - One
        """.trimIndent()

        val disabled = MihomoDpiBypassPatcher.patch(yaml, enabled = false)
        val patched = MihomoDpiBypassPatcher.patch(yaml, enabled = true, proxyPort = 31_234)
        val repatched = MihomoDpiBypassPatcher.patch(patched, enabled = true, proxyPort = 31_235)

        assertEquals(yaml, disabled)
        assertTrue(patched.contains("name: 'WhiteDNS ByeByeDPI'"))
        assertTrue(patched.contains("type: socks5"))
        assertTrue(patched.contains("server: 127.0.0.1"))
        assertTrue(patched.contains("udp: true"))
        assertTrue(patched.contains("port: 31234"))
        assertTrue(patched.contains("dialer-proxy: 'WhiteDNS ByeByeDPI'"))
        assertTrue(patched.contains("dialer-proxy: 'WhiteDNS ByeByeDPI' }"))
        assertTrue(patched.contains("Host: host.example.com"))
        assertTrue(repatched.contains("port: 31235"))
        assertEquals(1, Regex("name: 'WhiteDNS ByeByeDPI'").findAll(repatched).count())
    }

    @Test
    fun controllerProxiesResolveAutoGroupToLeafProxy() {
        val response = JSONObject(
            """
                {
                  "proxies": {
                    "🚀 Proxy Select": { "type": "Selector", "now": "♻️ Auto Select" },
                    "♻️ Auto Select": { "type": "URLTest", "now": "🇩🇪 DE | 01" },
                    "🇩🇪 DE | 01": { "type": "Vless" }
                  }
                }
            """.trimIndent(),
        )

        assertEquals("🇩🇪 DE | 01", MihomoControllerProxies.activeProxyName(response, "🚀 Proxy Select"))
    }

    @Test
    fun runtimeHealthParsesCloudflareTraceCountry() {
        val trace = """
            ip=203.0.113.1
            loc=nl
            warp=off
        """.trimIndent()

        assertEquals("NL", MihomoRuntimeHealth.countryCodeFromTrace(trace))
    }

    @Test
    fun mihomoDelayPolicyAcceptsOnlyPositiveDelay() {
        assertEquals(42L, MihomoDelayPolicy.acceptedDelayMs(42))
        assertEquals(null, MihomoDelayPolicy.acceptedDelayMs(0))
        assertEquals(null, MihomoDelayPolicy.acceptedDelayMs(-1))
        assertEquals(null, MihomoDelayPolicy.acceptedDelayMs(null))
    }
}
