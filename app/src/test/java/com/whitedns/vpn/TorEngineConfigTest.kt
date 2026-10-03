package com.whitedns.vpn

import org.junit.Assert.*
import org.junit.Test

class TorEngineConfigTest {
    private fun profile(settings: Map<String, String>) = EngineProfile(name = "Tor", kind = EngineKind.TOR, config = EngineConfig.Tor(settings))
    @Test fun managedTransportPathIsAnUnquotedArgument() {
        val config = TorEngineConfig.build(profile(mapOf("bridgeMode" to "custom", "transport" to "obfs4", "bridges" to "obfs4 192.0.2.1:443")), "/native", "/data", 1080, "")
        assertTrue(config.contains("ClientTransportPlugin obfs4 exec /native/libobfs4proxy.so\n"))
        assertThrows(IllegalArgumentException::class.java) {
            TorEngineConfig.build(profile(mapOf("bridgeMode" to "custom", "bridges" to "obfs4 192.0.2.1:443")), "/invalid path", "/data", 1080, "")
        }
    }
    @Test fun snowflakeReceivesBrokerAndStunDefaults() {
        val config = TorEngineConfig.build(profile(mapOf("transport" to "snowflake")), "/native", "/data", 1080, "snowflake 192.0.2.3:80 fingerprint=example")
        assertTrue(config.contains("exec /native/libsnowflake.so -url https://1098762253.rsc.cdn77.org/"))
        assertTrue(config.contains("-fronts www.cdn77.com,www.phpmyadmin.net"))
        assertTrue(config.contains("-ice stun:stun.antisip.com:3478"))
    }
    @Test fun missingSnowflakeDefaultsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            TorEngineConfig.build(profile(mapOf("transport" to "snowflake")), "/native", "/data", 1080, "")
        }
    }
    @Test fun unsupportedConjureVariantsAreRejectedBeforeStartup() {
        for (option in listOf("registrar=dns", "registrar=ampcache", "transport=prefix")) {
            assertThrows(IllegalArgumentException::class.java) {
                EngineProfileValidation.validate(profile(mapOf("bridgeMode" to "custom", "transport" to "conjure", "bridges" to "conjure 192.0.2.1:80 $option")))
            }
        }
        EngineProfileValidation.validate(profile(mapOf("bridgeMode" to "custom", "transport" to "conjure", "bridges" to "conjure 192.0.2.1:80 registrar=http transport=min")))
    }
    @Test fun directModeDoesNotLaunchTransport() {
        val config = TorEngineConfig.build(profile(mapOf("bridgeMode" to "none")), "/native", "/data", 1080, "")
        assertTrue(config.contains("SocksPort 127.0.0.1:1080")); assertFalse(config.contains("ClientTransportPlugin"))
    }
    @Test fun customBridgesCannotInjectTorDirectives() {
        assertThrows(IllegalArgumentException::class.java) { EngineProfileValidation.validate(profile(mapOf("bridgeMode" to "custom", "bridges" to "ClientTransportPlugin evil exec /path"))) }
    }
    @Test fun wrongTransportBridgeIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            TorEngineConfig.build(profile(mapOf("bridgeMode" to "custom", "transport" to "obfs4", "bridges" to "snowflake 192.0.2.3:1")), "/native", "/data", 1080, "")
        }
    }
    @Test fun routingOverridesPreserveTcpOnlyPolicy() {
        for (mode in listOf(RoutingMode.IranBypass, RoutingMode.GlobalProxy)) {
            val yaml = MihomoRuntimeConfigBuilder.flClashRuntimeYaml(EngineRuntimeYaml.build(SocksEndpoint(12345)), "secret", routingMode = mode, rejectProxiedUdp = true)
            assertTrue(yaml.indexOf("NETWORK,UDP,REJECT") < yaml.indexOf("MATCH,"))
            if (mode == RoutingMode.IranBypass) assertTrue(yaml.indexOf("RULE-SET,whitedns-iran,DIRECT") < yaml.indexOf("NETWORK,UDP,REJECT"))
        }
    }
    @Test fun localSocksRouteExplicitlyDisablesUdpAndDirectFallback() {
        val config = EngineRuntimeYaml.build(SocksEndpoint(12345))
        assertTrue(config.contains("NETWORK,UDP,REJECT")); assertTrue(config.contains("udp: false")); assertFalse(config.contains("DIRECT")); assertTrue(config.contains("12345"))
    }
}
