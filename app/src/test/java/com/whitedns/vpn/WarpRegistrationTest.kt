package com.whitedns.vpn

import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.util.Base64

class WarpRegistrationTest {
    @Test
    fun generatedPublicKeyMatchesPrivateKey() {
        val keys = WarpRegistration.generateKeys()
        val private = Base64.getDecoder().decode(keys.privateKey)
        assertEquals(32, private.size)
        val derived = X25519PrivateKeyParameters(private, 0).generatePublicKey().encoded
        assertEquals(keys.publicKey, Base64.getEncoder().encodeToString(derived))
    }

    @Test
    fun requestBodyCarriesOnlyTheKeyAndGenericDeviceFields() {
        val body = JSONObject(WarpRegistration.requestBody("pub", Instant.parse("2026-11-20T00:00:00Z")))
        assertEquals("pub", body.getString("key"))
        assertEquals("2026-11-20T00:00:00Z", body.getString("tos"))
        assertEquals("", body.getString("install_id"))
        assertEquals("PC", body.getString("model"))
        assertTrue(body.getBoolean("warp_enabled"))
    }

    @Test
    fun registrationResponseBecomesOneWireGuardConnection() {
        val response = """
            {"id":"d","token":"t","config":{
              "client_id":"AQID",
              "peers":[{"public_key":"bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo=",
                "endpoint":{"v4":"162.159.192.5:0","v6":"[2606:4700:d0::a29f:c005]:0","host":"engage.cloudflareclient.com:2408"}}],
              "interface":{"addresses":{"v4":"172.16.0.2","v6":"2606:4700:110:8a36::1"}}}}
        """.trimIndent()

        val yaml = WarpRegistration.subscriptionYaml(response, "cHJpdmF0ZQ==")
        val snapshot = MihomoConfigParser.parse(yaml)
        val profile = snapshot.catalog.profiles.single()

        assertEquals("WARP", profile.tag)
        assertEquals("wireguard", profile.type)
        assertEquals("162.159.192.5", profile.server)
        assertEquals(2408, profile.port)
        assertTrue(yaml.contains("172.16.0.2"))
        assertTrue(yaml.contains("2606:4700:110:8a36::1"))
        assertTrue(Regex("""reserved:\s*\[\s*1,\s*2,\s*3\s*]""").containsMatchIn(yaml))
        assertTrue(yaml.contains("wireguard-dpi-option"))
        assertTrue(yaml.contains("bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="))
    }

    @Test
    fun warpConfigSurvivesFieldEdits() {
        val original = WarpConfig(
            server = "162.159.192.5",
            port = 2408,
            privateKey = "cHJpdmF0ZQ==",
            publicKey = WarpConfig.CLOUDFLARE_PUBLIC_KEY,
            ipv4 = "172.16.0.2",
            ipv6 = "2606:4700:110:8a36::1",
            reserved = listOf(1, 2, 3),
            noise = true,
        )
        assertEquals(original, WarpConfig.parse(original.toYaml()))

        val edited = original.copy(server = "188.114.97.3", port = 894, noise = false)
        assertEquals(edited, WarpConfig.parse(edited.toYaml()))
        assertTrue(!edited.toYaml().contains("wireguard-dpi-option"))

        val vless = """
            proxies:
              - name: 'x'
                type: vless
                server: a.example
                port: 443
        """.trimIndent()
        assertEquals(null, WarpConfig.parse(vless))
        assertTrue(WarpConfig.isValidServer("162.159.192.1"))
        assertTrue(!WarpConfig.isValidServer("1.1.1.1 type: vless"))
    }
}
