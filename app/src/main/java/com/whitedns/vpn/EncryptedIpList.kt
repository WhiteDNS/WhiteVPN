package com.whitedns.vpn

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

object EncryptedIpListCodec {
    fun decryptIpList(
        payloadJson: String,
        passphrase: String = WhiteDnsConfig.ENCRYPTED_IP_LIST_KEY,
    ): List<String> {
        return parsePlaintextIps(
            EncryptedPayloadCodec.decryptText(payloadJson, passphrase, label = "encrypted IP list"),
        )
    }

    fun parsePlaintextIps(value: String): List<String> {
        val seen = linkedSetOf<String>()
        value
            .split(Regex("\\s+"))
            .map(String::trim)
            .filter(String::isNotBlank)
            .filter(::isValidIpv4)
            .forEach { seen += it }
        return seen.toList()
    }

    private fun isValidIpv4(value: String): Boolean {
        val octets = value.split(".")
        return octets.size == 4 && octets.all { octet ->
            octet.toIntOrNull()?.let { it in 0..255 } == true
        }
    }
}

object EncryptedPayloadCodec {
    private const val AES_GCM_TAG_BITS = 128

    fun decryptText(
        payloadJson: String,
        passphrase: String,
        label: String = "encrypted payload",
    ): String {
        // The passphrase is injected at build time and is empty when a build was configured
        // without it. Fail loudly rather than deriving a key from the empty string.
        if (passphrase.isBlank()) {
            throw IOException("No decryption key is configured for $label")
        }
        val root = JSONObject(payloadJson)
        require(root.optInt("version") == 1) { "Unsupported $label version" }
        require(root.optString("algorithm") == "AES-GCM") { "Unsupported $label algorithm" }
        require(root.optString("encoding") == "base64url") { "Unsupported $label encoding" }

        val iv = decodeUrlSafeBase64(root.getString("iv"))
        val ciphertext = decodeUrlSafeBase64(root.getString("ciphertext"))
        val key = MessageDigest.getInstance("SHA-256")
            .digest(passphrase.toByteArray(Charsets.UTF_8))
        return runCatching {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), GCMParameterSpec(AES_GCM_TAG_BITS, iv))
            cipher.doFinal(ciphertext).toString(Charsets.UTF_8)
        }.getOrElse { error ->
            throw IOException("Unable to decrypt $label", error)
        }
    }
}

class EncryptedIpListRepository(private val context: Context) {
    suspend fun fetchIps(): List<String> = withContext(Dispatchers.IO) {
        val connection = URL(WhiteDnsConfig.ENCRYPTED_IP_LIST_URL)
            .openConnection() as HttpURLConnection
        connection.connectTimeout = CONNECT_TIMEOUT_MS
        connection.readTimeout = READ_TIMEOUT_MS
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/json,*/*;q=0.1")

        try {
            DiagnosticLogger.info(context, "encryptedIpList.fetch.start", "url=${WhiteDnsConfig.ENCRYPTED_IP_LIST_URL}")
            val responseCode = connection.responseCode
            DiagnosticLogger.info(context, "encryptedIpList.http.response", "code=$responseCode")
            if (responseCode !in 200..299) {
                throw IOException("Encrypted IP list returned HTTP $responseCode")
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            EncryptedIpListCodec.decryptIpList(body).also { ips ->
                DiagnosticLogger.info(context, "encryptedIpList.decrypt.success", "ips=${ips.size}")
                if (ips.isEmpty()) throw IOException("Encrypted IP list did not contain usable IPv4 addresses")
            }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 12_000
        const val READ_TIMEOUT_MS = 20_000
    }
}

@OptIn(ExperimentalEncodingApi::class)
private fun decodeUrlSafeBase64(value: String): ByteArray {
    val normalized = value.filterNot(Char::isWhitespace)
    require(normalized.isNotBlank()) { "Base64 value is blank" }
    val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
    return Base64.UrlSafe.decode(padded)
}
