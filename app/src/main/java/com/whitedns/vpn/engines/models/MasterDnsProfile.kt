// Adapted from ZedSecure, revision 2c01da26bed98b883503850df1b7f793d28f7f54.
package com.whitedns.vpn.engines.models


data class MasterDnsProfile(

    val domains: String = "",

    val encryptionKey: String = "",

    val encryptionMethod: Int = ENC_XOR,

    val resolvers: String = "",

    val listenPort: Int = 18000,

    val balancingStrategy: Int = 3,

    val packetDuplication: Int = 3,

    val compression: Int = 0,

    val autoDisableDeadResolvers: Boolean = true,

    val logLevel: String = "INFO",
) {
    private fun domainsArray(): String =
        domains.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
            .joinToString(", ", "[", "]") { tomlString(it) }

    fun resolversText(): String =
        resolvers.split(',', '\n').map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .joinToString("\n")

    fun toToml(listenIp: String = "127.0.0.1"): String = buildString {
        appendLine("DOMAINS = ${domainsArray()}")
        appendLine("DATA_ENCRYPTION_METHOD = $encryptionMethod")
        appendLine("ENCRYPTION_KEY = ${tomlString(encryptionKey)}")
        appendLine("PROTOCOL_TYPE = \"SOCKS5\"")
        appendLine("LISTEN_IP = ${tomlString(listenIp)}")
        appendLine("LISTEN_PORT = $listenPort")
        appendLine("SOCKS5_AUTH = false")
        appendLine("RESOLVER_BALANCING_STRATEGY = $balancingStrategy")
        appendLine("PACKET_DUPLICATION_COUNT = ${packetDuplication.coerceIn(1, 10)}")
        appendLine("UPLOAD_COMPRESSION_TYPE = $compression")
        appendLine("DOWNLOAD_COMPRESSION_TYPE = $compression")
        appendLine("AUTO_DISABLE_TIMEOUT_SERVERS = $autoDisableDeadResolvers")

        appendLine("LOG_LEVEL = ${tomlString(if (logLevel.trim().equals("DEBUG", ignoreCase = true)) "DEBUG" else "INFO")}")
    }

    private fun tomlString(raw: String): String = buildString {
        append('"')
        for (c in raw) when (c) {
            '\\' -> append("\\\\")
            '"' -> append("\\\"")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (c.code < 0x20) append("\\u%04X".format(c.code)) else append(c)
        }
        append('"')
    }

    val isValid: Boolean
        get() = domains.isNotBlank() && encryptionKey.isNotBlank() && resolversText().isNotBlank()

    companion object {
        fun splitResolvers(raw: String): List<String> =
            raw.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }

        fun isNumericResolver(entry: String): Boolean {
            val text = entry.trim()
            if (text.isEmpty()) return false
            if (looksNumericHost(text)) return true
            val host: String
            if (text.startsWith("[")) {
                val close = text.indexOf(']')
                if (close < 0) return false
                host = text.substring(1, close)
                val rest = text.substring(close + 1)
                if (rest.isNotEmpty() && !isPort(rest.removePrefix(":"))) return false
            } else {
                val sep = text.lastIndexOf(':')
                if (sep < 0) return false
                host = text.substring(0, sep)
                if (!isPort(text.substring(sep + 1))) return false
            }
            return looksNumericHost(host)
        }

        fun invalidResolvers(raw: String): List<String> =
            splitResolvers(raw).filterNot { isNumericResolver(it) }

        private fun isPort(s: String): Boolean = s.toIntOrNull()?.let { it in 1..65535 } == true

        private fun looksNumericHost(h: String): Boolean {
            val bare = h.substringBefore('/')
            val suffix = h.substringAfter('/', "")
            if (h.contains('/') && suffix.toIntOrNull()?.let { it in 0..128 } != true) return false
            if (bare.isEmpty()) return false

            if (bare.count { it == ':' } >= 2) {
                return bare.all { it.isDigit() || it == ':' || it == '.' || it in "abcdefABCDEF" }
            }
            if (bare.contains(':')) return false
            val parts = bare.split('.')
            return parts.size == 4 && parts.all { p -> p.isNotEmpty() && p.all { it.isDigit() } && p.toIntOrNull()?.let { it in 0..255 } == true }
        }

        const val ENC_NONE = 0
        const val ENC_XOR = 1
        const val ENC_CHACHA20 = 2
        const val ENC_AES128 = 3
        const val ENC_AES192 = 4
        const val ENC_AES256 = 5
    }
}
