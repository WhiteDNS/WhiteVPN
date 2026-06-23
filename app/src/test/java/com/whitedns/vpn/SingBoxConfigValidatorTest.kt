package com.whitedns.vpn

import org.junit.Test

class SingBoxConfigValidatorTest {
    @Test(expected = IllegalArgumentException::class)
    fun rejectsBlankConfig() {
        SingBoxConfigValidator.requireValid("")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonJsonConfig() {
        SingBoxConfigValidator.requireValid("vmess://not-json")
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsConfigMissingTunInbound() {
        SingBoxConfigValidator.requireValid(
            """
            {
              "inbounds": [{"type": "mixed", "tag": "mixed-in"}],
              "outbounds": [{"type": "selector", "tag": "selector"}]
            }
            """.trimIndent(),
        )
    }

    @Test
    fun acceptsTunConfigWithProxyOutbound() {
        SingBoxConfigValidator.requireValid(
            """
            {
              "inbounds": [{"type": "tun", "tag": "tun-in"}],
              "outbounds": [{"type": "selector", "tag": "selector"}]
            }
            """.trimIndent(),
        )
    }
}
