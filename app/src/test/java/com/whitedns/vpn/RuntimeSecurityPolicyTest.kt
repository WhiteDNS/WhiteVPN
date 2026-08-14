package com.whitedns.vpn

import org.junit.Assert.assertEquals
import org.junit.Test
import java.security.SecureRandom

class RuntimeSecurityPolicyTest {
    @Test
    fun controllerSecretIsRandomHex256BitValue() {
        val secret = MihomoControllerSecret.generate(
            object : SecureRandom() {
                override fun nextBytes(bytes: ByteArray) {
                    bytes.indices.forEach { bytes[it] = it.toByte() }
                }
            },
        )

        assertEquals("000102030405060708090a0b0c0d0e0f101112131415161718191a1b1c1d1e1f", secret)
    }
}
