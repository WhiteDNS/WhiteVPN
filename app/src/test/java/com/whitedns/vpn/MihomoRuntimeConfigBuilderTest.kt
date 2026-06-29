package com.whitedns.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONObject

class MihomoRuntimeConfigBuilderTest {
    @Test
    fun corePatchUsesMihomoPortsTunDefaultsAndVpnOnlyPackages() {
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
        assertTrue(tun.getBoolean("enable"))
        assertEquals("gvisor", tun.getString("stack"))
        assertEquals("WhiteDNS VPN", tun.getString("device"))
        assertEquals("172.19.0.1/30", tun.getJSONArray("inet4-address").getString(0))
        assertEquals("com.example.mail", tun.getJSONArray("include-package").getString(0))
        assertFalse(tun.has("exclude-package"))
    }

    @Test
    fun corePatchMapsBypassPackagesToMihomoExcludePackage() {
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

        assertEquals("com.example.chat", patch.getJSONObject("tun").getJSONArray("exclude-package").getString(0))
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
        assertTrue(runtimeYaml.contains("enhanced-mode: fake-ip"))
        assertTrue(runtimeYaml.contains("tun:\n  enable: false"))
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
}
