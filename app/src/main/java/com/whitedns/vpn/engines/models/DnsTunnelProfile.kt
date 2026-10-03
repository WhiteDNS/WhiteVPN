// Adapted from ZedSecure, revision 2c01da26bed98b883503850df1b7f793d28f7f54.
package com.whitedns.vpn.engines.models


data class DnsTunnelProfile(

    val engine: String,

    val domain: String,

    val publicKey: String,

    val dnsttCompat: Boolean = false,

    val dnsTransport: String = TRANSPORT_UDP,

    val resolvers: String = "",

    val dohUrl: String = "",

    val resolverMode: String = MODE_FANOUT,

    val rrSpreadCount: Int = 3,

    val authoritative: Boolean = false,

    val autoTune: Boolean = false,

    val dnsPayloadSize: Int = 100,

    val recordType: String = "txt",

    val maxQnameLen: Int = 101,

    val rps: Double = 0.0,
    val idleTimeout: Int = 0,
    val keepalive: Int = 0,
    val udpTimeout: Int = 0,
    val maxNumLabels: Int = 0,
    val clientIdSize: Int = 0,

    val socksUser: String = "",
    val socksPass: String = "",

    val sshEnabled: Boolean = false,

    val sshHost: String = "",
    val sshPort: Int = 22,
    val sshUsername: String = "",
    val sshPassword: String = "",

    val sshAuthType: String = AUTH_PASSWORD,
    val sshPrivateKey: String = "",
    val sshKeyPassphrase: String = "",

    val forwardDnsThroughSsh: Boolean = false,
) {
    val isVaydns: Boolean get() = engine == ENGINE_VAYDNS

    fun dnsAddress(): String {
        val list = resolvers.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        return when (dnsTransport) {
            TRANSPORT_DOH -> dohUrl.trim().ifBlank { "https://dns.google/dns-query" }
            TRANSPORT_TCP -> list.ifEmpty { listOf("8.8.8.8") }
                .joinToString(",") { "tcp://" + withPort(it, 53) }
            TRANSPORT_DOT -> list.ifEmpty { listOf("8.8.8.8") }
                .joinToString(",") { "tls://" + withDotPort(it) }
            else -> list.ifEmpty { listOf("8.8.8.8") }.joinToString(",") { withPort(it, 53) }
        }
    }

    private fun withPort(hostPort: String, default: Int): String {
        if (hostPort.count { it == ':' } >= 2 && !hostPort.startsWith("[")) return "[$hostPort]:$default"
        val hasPort = if (hostPort.startsWith("[")) hostPort.contains("]:") else hostPort.contains(":")
        return if (hasPort) hostPort else "$hostPort:$default"
    }

    private fun withDotPort(hostPort: String): String {
        if (hostPort.count { it == ':' } >= 2 && !hostPort.startsWith("[")) return "[$hostPort]:853"
        if (hostPort.startsWith("[") && !hostPort.contains("]:")) return "$hostPort:853"
        val idx = if (hostPort.startsWith("[")) hostPort.indexOf("]:").let { if (it < 0) -1 else it + 1 }
        else hostPort.lastIndexOf(':')
        if (idx < 0) return "$hostPort:853"
        val port = hostPort.substring(idx + 1).toIntOrNull()
        return if (port == null || port == 53) hostPort.substring(0, idx) + ":853" else hostPort
    }

    companion object {
        const val ENGINE_DNSTT = "dnstt"
        const val ENGINE_VAYDNS = "vaydns"

        const val AUTH_PASSWORD = "password"
        const val AUTH_KEY = "key"

        const val TRANSPORT_UDP = "udp"
        const val TRANSPORT_TCP = "tcp"
        const val TRANSPORT_DOT = "dot"
        const val TRANSPORT_DOH = "doh"

        const val MODE_FANOUT = "fanout"
        const val MODE_ROUND_ROBIN = "roundrobin"

        val TRANSPORTS = listOf(
            TRANSPORT_UDP to "UDP", TRANSPORT_TCP to "TCP", TRANSPORT_DOT to "DoT", TRANSPORT_DOH to "DoH",
        )
        val RESOLVER_MODES = listOf(MODE_FANOUT to "Reliable", MODE_ROUND_ROBIN to "Fast")
        val RECORD_TYPES = listOf("txt", "cname", "a", "aaaa", "mx", "ns", "srv", "null", "caa")

        val PAYLOAD_PRESETS = listOf(0, 100, 80, 60, 50)
    }
}
