package com.whitedns.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.security.SecureRandom

class RuntimeSecurityPolicyTest {
    @Test
    fun commandServerSecretIsRandomHex256BitValue() {
        val secret = LibboxCommandServerSecret.generate(
            object : SecureRandom() {
                override fun nextBytes(bytes: ByteArray) {
                    bytes.indices.forEach { bytes[it] = it.toByte() }
                }
            },
        )

        assertEquals("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f", secret)
    }

    @Test
    fun autoRouteRequiresDnsServer() {
        assertEquals("1.1.1.1", TunDnsPolicy.requireAutoRouteDnsServer(" 1.1.1.1 "))
        assertThrows(IllegalStateException::class.java) {
            TunDnsPolicy.requireAutoRouteDnsServer(" ")
        }
    }
}
