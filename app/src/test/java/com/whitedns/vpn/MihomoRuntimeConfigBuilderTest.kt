package com.whitedns.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.net.SocketTimeoutException
import java.nio.file.Files
import java.security.cert.CertPathValidatorException
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLPeerUnverifiedException

class MihomoRuntimeConfigBuilderTest {
    @Test
    fun bundledGeoDataIsInstalledOnceBeforeCoreSetup() {
        val baseDir = Files.createTempDirectory("mihomo-geodata").toFile()
        val existing = File(baseDir, "GeoSite.dat").apply { writeText("newer-data") }

        val installed = MihomoGeoDataInstaller.install(baseDir) { path ->
            ByteArrayInputStream("bundled-$path".toByteArray())
        }

        assertEquals(listOf("GEOIP.metadb", "GEOIP.dat", "ASN.mmdb"), installed)
        assertEquals("newer-data", existing.readText())
        MihomoGeoDataInstaller.fileNames.forEach { fileName ->
            assertTrue(
                baseDir.listFiles()?.any { candidate ->
                    candidate.name.equals(fileName, ignoreCase = true) && candidate.length() > 0L
                } == true,
            )
        }
        assertTrue(MihomoGeoDataInstaller.install(baseDir) { error("already installed") }.isEmpty())
    }

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
        assertEquals(TlsIntegrityPolicy.TEST_URLS, MihomoRuntimeDefaults.HEALTH_URLS)
        assertEquals("https://valid-isrgrootx1.letsencrypt.org/", MihomoRuntimeDefaults.HEALTH_URL)
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
        assertFalse(patch.getBoolean("allow-lan"))
        assertFalse(patch.has("bind-address"))
        assertFalse(patch.has("authentication"))
        assertFalse(patch.has("skip-auth-prefixes"))
        assertFalse(patch.has("lan-allowed-ips"))
        assertFalse(tun.getBoolean("enable"))
        assertFalse(tun.has("auto-route"))
        assertFalse(tun.has("strict-route"))
        assertFalse(tun.has("include-package"))
        assertFalse(tun.has("exclude-package"))
    }

    @Test
    fun corePatchExposesAuthenticatedProxyOnlyWhenLanSharingIsEnabled() {
        val patch = MihomoRuntimeConfigBuilder.corePatchJson(
            appName = "WhiteVPN",
            secret = "secret-123",
            splitTunnelPlan = SplitTunnelRuntimePlan.off(),
            lanSharing = LanSharingSettings(enabled = true, password = "safe-password"),
        )

        assertTrue(patch.getBoolean("allow-lan"))
        assertEquals("0.0.0.0", patch.getString("bind-address"))
        assertEquals("whitedns:safe-password", patch.getJSONArray("authentication").getString(0))
        assertEquals("127.0.0.0/8", patch.getJSONArray("skip-auth-prefixes").getString(0))
        assertEquals(
            listOf("127.0.0.0/8", "10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "100.64.0.0/10"),
            (0 until patch.getJSONArray("lan-allowed-ips").length()).map {
                patch.getJSONArray("lan-allowed-ips").getString(it)
            },
        )
    }

    @Test
    fun lanSharingCanRunWithoutAuthentication() {
        val settings = LanSharingSettings(enabled = true, passwordRequired = false)
        val patch = MihomoRuntimeConfigBuilder.corePatchJson(
            appName = "WhiteVPN",
            secret = "secret-123",
            splitTunnelPlan = SplitTunnelRuntimePlan.off(),
            lanSharing = settings,
        )
        val yaml = MihomoRuntimeConfigBuilder.flClashRuntimeYaml(
            rawYaml = "proxies: []",
            secret = "secret-123",
            lanSharing = settings,
        )

        assertTrue(patch.getBoolean("allow-lan"))
        assertEquals("0.0.0.0", patch.getString("bind-address"))
        assertFalse(patch.has("authentication"))
        assertFalse(patch.has("skip-auth-prefixes"))
        assertTrue(patch.has("lan-allowed-ips"))
        assertTrue(yaml.contains("allow-lan: true"))
        assertTrue(yaml.contains("bind-address: 0.0.0.0"))
        assertFalse(yaml.contains("authentication:"))
        assertFalse(yaml.contains("skip-auth-prefixes:"))
        assertTrue(yaml.contains("lan-allowed-ips:"))
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
            allow-lan: true
            bind-address: "*"
            authentication:
              - attacker:password
            skip-auth-prefixes:
              - 0.0.0.0/0
            lan-allowed-ips:
              - 0.0.0.0/0
            lan-disallowed-ips:
              - 192.168.0.2/32
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
        assertFalse(runtimeYaml.contains("attacker:password"))
        assertFalse(runtimeYaml.contains("0.0.0.0/0"))
        assertFalse(runtimeYaml.contains("192.168.0.2/32"))
        assertTrue(runtimeYaml.contains("allow-lan: false"))
        assertFalse(runtimeYaml.contains("bind-address:"))
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
    fun flClashRuntimeYamlBindsTheDnsListenerToLoopbackOnly() {
        val yaml = """
            proxies:
              - name: Node
                type: http
                server: example.com
                port: 443
        """.trimIndent()

        val runtimeYaml = MihomoRuntimeConfigBuilder.flClashRuntimeYaml(
            rawYaml = yaml,
            secret = "secret-123",
            controlPort = 39125,
        )

        // `allow-lan: false` does not gate the DNS listener, so binding 0.0.0.0 would expose an
        // open resolver to every other device on the network.
        assertTrue(runtimeYaml.contains("listen: 127.0.0.1:1053"))
        assertFalse(runtimeYaml.contains("0.0.0.0"))
    }

    @Test
    fun flClashRuntimeYamlWritesAuthenticatedLanProxySettings() {
        val runtimeYaml = MihomoRuntimeConfigBuilder.flClashRuntimeYaml(
            rawYaml = "proxies: []",
            secret = "secret-123",
            lanSharing = LanSharingSettings(enabled = true, password = "safe-password"),
        )

        assertTrue(runtimeYaml.contains("allow-lan: true"))
        assertTrue(runtimeYaml.contains("bind-address: 0.0.0.0"))
        assertTrue(runtimeYaml.contains("authentication:\n  - 'whitedns:safe-password'"))
        assertTrue(runtimeYaml.contains("skip-auth-prefixes:\n  - 127.0.0.0/8"))
        assertTrue(runtimeYaml.contains("lan-allowed-ips:"))
        assertTrue(runtimeYaml.contains("  - 100.64.0.0/10"))
        assertTrue(runtimeYaml.contains("tun:\n  enable: false"))
    }

    @Test
    fun lanSharingPasswordAndPrivateAddressPolicyAreStable() {
        val password = LanSharingPassword.generate()

        assertEquals(24, password.length)
        assertTrue(password.matches(Regex("[A-Za-z0-9_-]{24}")))
        assertTrue(LanSharingAddresses.isPrivateIpv4("192.168.43.1"))
        assertTrue(LanSharingAddresses.isPrivateIpv4("100.64.0.1"))
        assertFalse(LanSharingAddresses.isPrivateIpv4("8.8.8.8"))
        assertFalse(LanSharingAddresses.isPrivateIpv4("172.32.0.1"))
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
    fun subscriptionRoutingPreservesSubscriptionRoutingBlocks() {
        val routing = """
            rule-providers:
              existing:
                type: file
                path: ./existing.yaml
            sub-rules:
              private:
                - DOMAIN-SUFFIX,internal.example,DIRECT
            rules:
              - RULE-SET,existing,DIRECT
              - MATCH,Proxy
        """.trimIndent()

        val runtimeYaml = MihomoRuntimeConfigBuilder.flClashRuntimeYaml(
            rawYaml = "proxies: []\n$routing",
            secret = "secret-123",
        )

        assertTrue(runtimeYaml.contains(routing))
        assertFalse(runtimeYaml.contains("whitedns-iran"))
    }

    @Test
    fun iranRoutingUsesDailyTextProviderAndFirstProxyFallback() {
        val runtimeYaml = MihomoRuntimeConfigBuilder.flClashRuntimeYaml(
            rawYaml = """
                proxies:
                  - name: "Node's"
                    type: http
                    server: example.com
                    port: 443
                rule-providers:
                  old-provider:
                    type: file
                    path: ./old.yaml
                sub-rules:
                  old-sub-rule:
                    - MATCH,DIRECT
                rules:
                  - MATCH,DIRECT
            """.trimIndent(),
            secret = "secret-123",
            routingMode = RoutingMode.IranBypass,
        )

        assertTrue(runtimeYaml.contains("url: https://github.com/ygbkm/clash-rules-iran/releases/latest/download/rules.txt"))
        assertTrue(runtimeYaml.contains("behavior: classical"))
        assertTrue(runtimeYaml.contains("format: text"))
        assertTrue(runtimeYaml.contains("path: ./ruleset/whitedns-iran.txt"))
        assertTrue(runtimeYaml.contains("interval: 86400"))
        assertTrue(runtimeYaml.contains("size-limit: 10485760"))
        assertTrue(runtimeYaml.contains("proxy: 'Node''s'"))
        assertTrue(runtimeYaml.contains("- 'RULE-SET,whitedns-iran,DIRECT'"))
        assertTrue(runtimeYaml.contains("- 'MATCH,Node''s'"))
        assertFalse(runtimeYaml.contains("old-provider"))
        assertFalse(runtimeYaml.contains("old-sub-rule"))
        assertEquals(1, Regex("(?m)^rule-providers:").findAll(runtimeYaml).count())
        assertEquals(1, Regex("(?m)^rules:").findAll(runtimeYaml).count())
    }

    @Test
    fun globalRoutingUsesWhiteDnsGroupAndRemovesPreviousRules() {
        val runtimeYaml = MihomoRuntimeConfigBuilder.flClashRuntimeYaml(
            rawYaml = """
                proxies:
                  - name: Node
                    type: http
                    server: example.com
                    port: 443
                proxy-groups:
                  - name: Main Proxy Select
                    type: select
                    proxies:
                      - Node
                  - name: WhiteDNS Proxy
                    type: select
                    proxies:
                      - Node
                rule-providers:
                  old-provider:
                    type: file
                    path: ./old.yaml
                rules:
                  - MATCH,DIRECT
            """.trimIndent(),
            secret = "secret-123",
            routingMode = RoutingMode.GlobalProxy,
        )

        assertFalse(runtimeYaml.contains("rule-providers:"))
        assertFalse(runtimeYaml.contains("MATCH,DIRECT"))
        assertEquals(1, Regex("(?m)^rules:").findAll(runtimeYaml).count())
        assertTrue(runtimeYaml.contains("rules:\n  - 'MATCH,WhiteDNS Proxy'"))
        assertTrue(runtimeYaml.contains("mode: rule"))
    }

    @Test
    fun routingFallsBackToMainSelectorBeforeTheFirstProxy() {
        val runtimeYaml = MihomoRuntimeConfigBuilder.flClashRuntimeYaml(
            rawYaml = """
                proxies:
                  - name: Node
                    type: http
                    server: example.com
                    port: 443
                proxy-groups:
                  - name: Main Proxy Select
                    type: select
                    proxies:
                      - Node
            """.trimIndent(),
            secret = "secret-123",
            routingMode = RoutingMode.GlobalProxy,
        )

        assertTrue(runtimeYaml.contains("rules:\n  - 'MATCH,Main Proxy Select'"))
    }

    @Test
    fun routingOverrideFailsWithoutAProxyTarget() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            MihomoRuntimeConfigBuilder.flClashRuntimeYaml(
                rawYaml = "proxies: []",
                secret = "secret-123",
                routingMode = RoutingMode.GlobalProxy,
            )
        }

        assertTrue(error.message.orEmpty().contains("requires a usable proxy or proxy group"))
    }

    @Test
    fun routingModeWireNamesAreStableAndInvalidValuesUseSubscription() {
        assertEquals(RoutingMode.Subscription, RoutingMode.fromWireName(null))
        assertEquals(RoutingMode.Subscription, RoutingMode.fromWireName("invalid"))
        RoutingMode.values().forEach { mode ->
            assertEquals(mode, RoutingMode.fromWireName(mode.wireName))
        }
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
    fun flClashSetupParamsCarryNativeSelections() {
        val setup = MihomoRuntimeConfigBuilder.setupParamsJson(
            mapOf(
                "WhiteDNS Proxy Select" to "WhiteDNS Auto Select",
                "WhiteDNS Proxy" to "WhiteDNS Countries",
            ),
        )

        assertEquals(MihomoRuntimeDefaults.HEALTH_URL, setup.getString("test-url"))
        assertEquals(
            "WhiteDNS Auto Select",
            setup.getJSONObject("selected-map").getString("WhiteDNS Proxy Select"),
        )
        assertEquals(
            "WhiteDNS Countries",
            setup.getJSONObject("selected-map").getString("WhiteDNS Proxy"),
        )
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
    fun connectionOptionsPatchWireGuardNoiseWithoutChangingEch() {
        val yaml = """
            proxies:
              - name: TLS
                type: vless
                server: tls.example.com
                port: 443
                tls: true
                ech-opts: {'enable': false, 'query-server-name': 'ech.example.com'}
              - name: Reality
                type: vless
                server: reality.example.com
                port: 443
                tls: true
                reality-opts:
                  public-key: key
              - name: WARP
                type: wireguard
                server: 162.159.192.1
                port: 2408
                amnezia-wg-option:
                  jc: 1
                  jmin: 10
                  jmax: 20
                  s1: 15
              - { name: Inline WARP, type: wireguard, server: 162.159.192.2, port: 2408, amnezia-wg-option: {'jc': 2, 'jmin': 20, 'jmax': 30} }
        """.trimIndent()
        val options = MihomoConnectionOptions(
            amneziaNoiseEnabled = true,
            amneziaNoise = AmneziaNoiseSettings(5, 50, 100),
        )

        val patched = MihomoConnectionOptionsPatcher.patch(yaml, options)

        assertEquals(1, Regex("ech-opts:").findAll(patched).count())
        assertTrue(patched.contains("ech-opts: {'enable': false, 'query-server-name': 'ech.example.com'}"))
        assertTrue(patched.contains("jc: 5"))
        assertTrue(patched.contains("jmin: 50"))
        assertTrue(patched.contains("jmax: 100"))
        assertTrue(patched.contains("s1: 15"))
        assertEquals(2, Regex("amnezia-wg-option:").findAll(patched).count())
        assertEquals(yaml, MihomoConnectionOptionsPatcher.patch(yaml, MihomoConnectionOptions()))
        assertThrows(IllegalArgumentException::class.java) {
            MihomoConnectionOptionsPolicy.validateNoise(AmneziaNoiseSettings(5, 101, 100))
        }
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
    fun controllerProxiesBuildLeafFirstSelectorPathForExplicitConnection() {
        val response = JSONObject(
            """
                {
                  "proxies": {
                    "WhiteDNS Proxy": {
                      "type": "Selector",
                      "all": ["Automatic", "Manual"]
                    },
                    "Automatic": {
                      "type": "URLTest",
                      "all": ["Node A", "Node B"]
                    },
                    "Manual": {
                      "type": "Selector",
                      "all": ["Node A", "Node B"]
                    },
                    "Node A": { "type": "Vless" },
                    "Node B": { "type": "Trojan" }
                  }
                }
            """.trimIndent(),
        )

        assertEquals(
            listOf(
                MihomoGroupSelection("Manual", "Node B"),
                MihomoGroupSelection("WhiteDNS Proxy", "Manual"),
            ),
            MihomoControllerProxies.selectorPath(
                response = response,
                targetName = "Node B",
                preferredRoots = listOf("WhiteDNS Proxy"),
            ),
        )
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
