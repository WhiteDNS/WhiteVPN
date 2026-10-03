package com.whitedns.vpn

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Base64
import java.util.zip.Inflater

/** Offline decoding only: never fetches a URL, resolves an endpoint, or logs input. */
internal object AmneziaConfigImport {
    const val MAX_CONFIG_BYTES = 65_536
    const val MAX_INPUT_BYTES = 4 * ((MAX_CONFIG_BYTES + 2) / 3) + 16
    private const val INVALID = "Invalid AmneziaWG import"

    fun read(stream: InputStream): String = try {
        decode(utf8(boundedRead(stream, MAX_INPUT_BYTES)))
    } catch (_: Exception) { throw IllegalArgumentException(INVALID) }

    fun decode(input: String): String {
        try {
            require(input.length <= MAX_INPUT_BYTES)
            var text = input.trim().removePrefix("\uFEFF").trim()
            if (text.startsWith("vpn://", ignoreCase = true)) {
                val encoded = text.substring(6)
                require(encoded.isNotEmpty() && encoded.matches(Regex("[A-Za-z0-9_+/-]+={0,2}")))
                val payload = Base64.getUrlDecoder().decode(encoded.replace('+', '-').replace('/', '_'))
                require(payload.size <= MAX_CONFIG_BYTES)
                text = utf8(unpack(payload)).trim().removePrefix("\uFEFF").trim()
            }
            require(text.toByteArray(Charsets.UTF_8).size <= MAX_CONFIG_BYTES && '\u0000' !in text)
            if (text.startsWith('{')) text = embeddedConfig(text)
            AmneziaProfile.parse(text)
            return text
        } catch (_: Exception) {
            // Parser errors can contain private keys. Never retain the cause or raw input.
            throw IllegalArgumentException(INVALID)
        }
    }

    /** Qt qCompress: four-byte big-endian decoded length, followed by a zlib stream. */
    private fun unpack(bytes: ByteArray): ByteArray {
        if (bytes.size < 6 || bytes[0] != 0.toByte()) return bytes
        val size = ByteBuffer.wrap(bytes, 0, 4).int
        require(size in 1..MAX_CONFIG_BYTES)
        val inflater = Inflater()
        try {
            inflater.setInput(bytes, 4, bytes.size - 4)
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4096)
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                require(count > 0 && output.size() + count <= size)
                output.write(buffer, 0, count)
            }
            require(output.size() == size && inflater.remaining == 0)
            return output.toByteArray()
        } finally { inflater.end() }
    }

    /** Import an exported AWG client configuration, never server administration/API credentials. */
    private fun jsonObject(text: String): JSONObject {
        // Limit nesting before JSONObject parsing; an untrusted file must not exhaust the stack.
        var depth = 0; var quoted = false; var escaped = false
        text.forEach { c ->
            if (quoted) {
                if (escaped) escaped = false else if (c == '\\') escaped = true else if (c == '"') quoted = false
            } else when (c) {
                '"' -> quoted = true
                '{', '[' -> { depth++; require(depth <= 16) }
                '}', ']' -> depth--
            }
        }
        return JSONObject(text)
    }

    private fun embeddedConfig(text: String): String {
        val json = jsonObject(text)
        val containers = json.getJSONArray("containers")
        val candidates = (0 until containers.length()).mapNotNull { index ->
            val awg = containers.optJSONObject(index)?.optJSONObject("awg") ?: return@mapNotNull null
            val last = awg.opt("last_config")
            val config = when (last) {
                is JSONObject -> last
                is String -> jsonObject(last)
                else -> return@mapNotNull null
            }
            (config.opt("config") as? String)?.takeIf { it.isNotBlank() }
        }.distinct()
        require(candidates.size == 1)
        return candidates.single()
    }

    private fun utf8(bytes: ByteArray): String = Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        .decode(ByteBuffer.wrap(bytes)).toString()

    private fun boundedRead(stream: InputStream, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val count = stream.read(buffer)
            if (count < 0) return output.toByteArray()
            require(output.size() + count <= limit) { INVALID }
            require(count > 0) { INVALID }
            output.write(buffer, 0, count)
        }
    }
}
