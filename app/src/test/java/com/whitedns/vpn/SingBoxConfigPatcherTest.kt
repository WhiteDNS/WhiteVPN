package com.whitedns.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class SingBoxConfigPatcherTest {
    @Test
    fun runtimeDiagnosticsReadsRouteAndDnsDetours() {
        val diagnostics = SingBoxConfigPatcher.runtimeDiagnostics(sampleRuntimeConfig())

        assertEquals("true", diagnostics.routeAutoDetectInterface)
        assertEquals("dns-direct", diagnostics.defaultDomainResolver)
        assertEquals(listOf("dns-remote->selector", "dns-direct->direct"), diagnostics.dnsDetours)
    }

    private fun sampleRuntimeConfig(): String {
        return """
            {
              "route": {
                "auto_detect_interface": true,
                "default_domain_resolver": {"server": "dns-direct"},
                "final": "selector"
              },
              "dns": {
                "servers": [
                  {"tag": "dns-remote", "type": "tcp", "server": "8.8.8.8", "detour": "selector"},
                  {"tag": "dns-direct", "type": "udp", "server": "8.8.8.8"}
                ]
              }
            }
        """.trimIndent()
    }
}
