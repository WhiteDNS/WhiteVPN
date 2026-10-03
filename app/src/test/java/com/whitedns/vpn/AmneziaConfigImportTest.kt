package com.whitedns.vpn

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Base64
import java.util.zip.DeflaterOutputStream

class AmneziaConfigImportTest {
    private val key = "AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE="
    private val config = """
        [Interface]
        PrivateKey = $key
        Address = 10.55.0.2/32
        DNS = 10.55.0.1
        MTU = 1405
        Jc = 4
        Jmin = 75
        Jmax = 135
        S1 = 115
        S2 = 57
        S3 = 49
        S4 = 15
        H1 = 457719643
        H2 = 708296422
        H3 = 1524605461
        H4 = 2120902613
        I1 = <r 181>
        HeaderProtectionKey = $key
        ContentPaddingAddition = 19-44
        RekeyAfterTime = 105-130
        RekeyTimeout = 4-5
        RejectAfterTime = 174-211
        KeepaliveTimeout = 10-17
        MaxHandshakeAttempts = 15-34
        RandomTrailers = on
        DisableCookies = on
        # آزمون
        [Peer]
        PublicKey = $key
        AllowedIPs = 0.0.0.0/0, ::/0
        Endpoint = offline.example:10970
    """.trimIndent()
    private fun link(bytes: ByteArray, padded: Boolean = false): String = "vpn://" +
        (if (padded) Base64.getUrlEncoder() else Base64.getUrlEncoder().withoutPadding()).encodeToString(bytes)
    private fun packed(text: String): ByteArray {
        val bytes = text.toByteArray()
        val out = ByteArrayOutputStream()
        out.write(ByteBuffer.allocate(4).putInt(bytes.size).array())
        DeflaterOutputStream(out).use { it.write(bytes) }
        return out.toByteArray()
    }
    @Test fun unpaddedAndPaddedLinksPreserveAllV3ParametersOffline() {
        assertEquals(config, AmneziaConfigImport.decode(link(config.toByteArray())))
        assertEquals(config, AmneziaConfigImport.decode("  " + link(config.toByteArray(), true) + "\n"))
    }
    @Test fun compressedLinksMatchQtQCompressWithoutChangingSettings() {
        assertEquals(config, AmneziaConfigImport.decode(link(packed(config))))
    }
    @Test fun filesSupportUtf8BomCrLfAndLinkTextWithoutAnExtensionDependency() {
        val windows = config.replace("\n", "\r\n")
        assertEquals(windows, AmneziaConfigImport.read(ByteArrayInputStream(("\uFEFF" + windows).toByteArray())))
        assertEquals(config, AmneziaConfigImport.read(ByteArrayInputStream(link(config.toByteArray()).toByteArray())))
    }
    @Test fun exportedClientContainerIsExtractedAndOtherProtocolsAreNeverSelected() {
        val last = JSONObject().put("config", config).toString()
        val root = JSONObject().put("containers", JSONArray().put(JSONObject().put("awg", JSONObject().put("last_config", last))))
        assertEquals(config, AmneziaConfigImport.decode(link(packed(root.toString()))))
        assertThrows(IllegalArgumentException::class.java) { AmneziaConfigImport.decode(root.toString().replace("awg", "openvpn")) }
    }
    @Test fun ambiguousContainersAndServerOnlyLinksAreRejected() {
        val rows = JSONArray()
        listOf(config, config.replace("10970", "10971")).forEach { rows.put(JSONObject().put("awg", JSONObject().put("last_config", JSONObject().put("config", it)))) }
        assertThrows(IllegalArgumentException::class.java) { AmneziaConfigImport.decode(JSONObject().put("containers", rows).toString()) }
        assertThrows(IllegalArgumentException::class.java) { AmneziaConfigImport.decode(link(JSONObject().put("api_key", "secret").toString().toByteArray())) }
    }
    @Test fun badInputsNeverExposeCredentialsInAnExceptionOrCause() {
        listOf("vpn://?token=$key", "https://example.invalid/config", "vpn://", link(byteArrayOf(0xc3.toByte(), 0x28)),
            link(config.replace("PrivateKey = $key", "PrivateKey = private-secret").toByteArray()),
            link(config.replace("DNS = 10.55.0.1", "").toByteArray()), config + "\u0000").forEach {
            val error = assertThrows(IllegalArgumentException::class.java) { AmneziaConfigImport.decode(it) }
            assertEquals("Invalid AmneziaWG import", error.message); assertNull(error.cause)
        }
    }
    @Test fun oversizedInputsDecompressionBombsTruncationAndTrailingDataAreRejected() {
        assertThrows(IllegalArgumentException::class.java) { AmneziaConfigImport.read(ByteArrayInputStream(ByteArray(AmneziaConfigImport.MAX_INPUT_BYTES + 1))) }
        assertThrows(IllegalArgumentException::class.java) { AmneziaConfigImport.decode(config + "#".repeat(AmneziaConfigImport.MAX_CONFIG_BYTES)) }
        val good = packed(config)
        listOf(packed("A".repeat(AmneziaConfigImport.MAX_CONFIG_BYTES + 1)), good.copyOf(good.size - 2), good + byteArrayOf(1),
            good.clone().apply { this[3] = 1 }).forEach { bytes ->
            assertThrows(IllegalArgumentException::class.java) { AmneziaConfigImport.decode(link(bytes)) }
        }
    }
    @Test fun deeplyNestedJsonDoesNotReachTheJsonParser() {
        val nested = "{" + "[".repeat(200) + "]".repeat(200) + "}"
        assertThrows(IllegalArgumentException::class.java) { AmneziaConfigImport.decode(nested) }
        val wrapped = JSONObject().put("containers", JSONArray().put(JSONObject().put("awg", JSONObject().put("last_config", nested))))
        assertThrows(IllegalArgumentException::class.java) { AmneziaConfigImport.decode(wrapped.toString()) }
    }
}
