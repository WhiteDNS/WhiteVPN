package com.whitedns.vpn

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

class EncryptedIpListCodecTest {
    @Test
    fun decryptsRawAesGcmPayloadText() {
        val payload = encryptedPayload(
            plaintext = "dmxlc3M6Ly9leGFtcGxl",
            passphrase = WhiteDnsConfig.SUBSCRIPTION_ENCRYPTION_KEY,
        )

        val plaintext = EncryptedPayloadCodec.decryptText(
            payload,
            WhiteDnsConfig.SUBSCRIPTION_ENCRYPTION_KEY,
            label = "encrypted subscription",
        )

        assertEquals("dmxlc3M6Ly9leGFtcGxl", plaintext)
    }

    @Test
    fun decryptsAesGcmPayloadWithSha256PassphraseKey() {
        val payload = encryptedPayload(
            plaintext = """
                104.16.7.92
                invalid
                104.16.3.200 999.1.1.1
                104.16.7.92
            """.trimIndent(),
        )

        val ips = EncryptedIpListCodec.decryptIpList(payload)

        assertEquals(listOf("104.16.7.92", "104.16.3.200"), ips)
    }

    @Test
    fun rejectsUnsupportedMetadata() {
        val payload = JSONObject(encryptedPayload("104.16.7.92"))
            .put("algorithm", "AES-CBC")
            .toString()

        assertThrows(IllegalArgumentException::class.java) {
            EncryptedIpListCodec.decryptIpList(payload)
        }
    }

    @Test
    fun rejectsInvalidAuthTag() {
        val payload = JSONObject(encryptedPayload("104.16.7.92"))
            .put("ciphertext", base64Url(ByteArray(24) { it.toByte() }))
            .toString()

        assertThrows(IOException::class.java) {
            EncryptedIpListCodec.decryptIpList(payload)
        }
    }

    private fun encryptedPayload(
        plaintext: String,
        passphrase: String = WhiteDnsConfig.ENCRYPTED_IP_LIST_KEY,
    ): String {
        val iv = ByteArray(12) { index -> (index + 1).toByte() }
        val key = MessageDigest.getInstance("SHA-256")
            .digest(passphrase.toByteArray(Charsets.UTF_8))
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(128, iv))
        val ciphertext = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return JSONObject()
            .put("version", 1)
            .put("algorithm", "AES-GCM")
            .put("operator", "all")
            .put("count", 2)
            .put("encoding", "base64url")
            .put("iv", base64Url(iv))
            .put("ciphertext", base64Url(ciphertext))
            .toString()
    }

    private fun base64Url(value: ByteArray): String {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value)
    }
}
