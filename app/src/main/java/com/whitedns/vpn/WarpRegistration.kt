package com.whitedns.vpn

import org.bouncycastle.crypto.params.X25519PrivateKeyParameters
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.security.SecureRandom
import java.time.Instant
import java.util.Base64

/** Registers a free Cloudflare WARP device and turns it into a Mihomo WireGuard subscription. */
internal object WarpRegistration {
    // Same device API and client headers as the Android WARP client (see bepass-org/warp-plus).
    private const val REGISTER_URL = "https://api.cloudflareclient.com/v0a4005/reg"
    private const val DEFAULT_SERVER = "162.159.192.1"
    private const val ENDPOINT_PORT = 2408
    private const val TIMEOUT_MS = 15_000
    const val PROXY_NAME = "WARP"

    data class Keys(val privateKey: String, val publicKey: String)

    fun generateKeys(random: SecureRandom = SecureRandom()): Keys {
        val private = X25519PrivateKeyParameters(random)
        val encoder = Base64.getEncoder()
        return Keys(encoder.encodeToString(private.encoded), encoder.encodeToString(private.generatePublicKey().encoded))
    }

    fun requestBody(publicKey: String, now: Instant = Instant.now()): String = JSONObject()
        .put("install_id", "")
        .put("fcm_token", "")
        .put("tos", now.toString())
        .put("key", publicKey)
        .put("type", "Android")
        .put("model", "PC")
        .put("locale", "en_US")
        .put("warp_enabled", true)
        .toString()

    /** Mihomo YAML with one WARP WireGuard proxy, built from the `/reg` response. */
    fun subscriptionYaml(response: String, privateKey: String): String {
        val config = JSONObject(response).getJSONObject("config")
        val peer = config.getJSONArray("peers").getJSONObject(0)
        val publicKey = peer.getString("public_key").takeIf(String::isNotBlank)
            ?: throw IOException("WARP response has no peer key")
        val server = peer.optJSONObject("endpoint")?.optString("v4").orEmpty()
            .substringBeforeLast(':').takeIf(String::isNotBlank) ?: DEFAULT_SERVER
        val addresses = config.getJSONObject("interface").getJSONObject("addresses")
        val ipv4 = addresses.optString("v4").takeIf(String::isNotBlank)
            ?: throw IOException("WARP response has no IPv4 address")
        val reserved = runCatching { Base64.getDecoder().decode(config.optString("client_id")) }
            .getOrNull()?.takeIf { it.size == 3 }
            ?.map { it.toInt() and 0xff }
        return WarpConfig(
            server = server,
            port = ENDPOINT_PORT,
            privateKey = privateKey,
            publicKey = publicKey,
            ipv4 = ipv4,
            ipv6 = addresses.optString("v6").takeIf(String::isNotBlank),
            reserved = reserved,
            noise = true,
        ).toYaml()
    }

    /**
     * Creates a new WARP device. When [viaLocalProxy] is set the request goes through the running
     * Mihomo mixed port, because the app itself is outside the tunnel and the API may be blocked.
     */
    fun register(viaLocalProxy: Boolean): String {
        val keys = generateKeys()
        val proxy = if (viaLocalProxy) {
            Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", MihomoRuntimeDefaults.MIXED_PORT))
        } else {
            Proxy.NO_PROXY
        }
        val connection = URL(REGISTER_URL).openConnection(proxy) as HttpURLConnection
        try {
            connection.requestMethod = "POST"
            connection.connectTimeout = TIMEOUT_MS
            connection.readTimeout = TIMEOUT_MS
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
            connection.setRequestProperty("User-Agent", "okhttp/3.12.1")
            connection.setRequestProperty("CF-Client-Version", "a-6.30-3596")
            connection.outputStream.use { it.write(requestBody(keys.publicKey).toByteArray()) }
            val code = connection.responseCode
            if (code !in 200..299) throw IOException("WARP registration failed with HTTP $code")
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            return subscriptionYaml(body, keys.privateKey)
        } finally {
            connection.disconnect()
        }
    }
}

/** The editable parts of a WARP subscription; keys and addresses stay as Cloudflare issued them. */
internal data class WarpConfig(
    val server: String,
    val port: Int,
    val privateKey: String,
    val publicKey: String,
    val ipv4: String,
    val ipv6: String?,
    val reserved: List<Int>?,
    val noise: Boolean,
) {
    fun toYaml(): String {
        val defaults = MihomoConnectionOptionsPolicy.DEFAULT_NOISE
        val proxy = JSONObject()
            .put("name", WarpRegistration.PROXY_NAME)
            .put("type", "wireguard")
            .put("server", server)
            .put("port", port)
            .put("ip", ipv4)
            .put("ip-version", if (ipv6 == null) "ipv4" else "ipv4-prefer")
            .put("private-key", privateKey)
            .put("public-key", publicKey)
            .put("allowed-ips", JSONArray(listOf("0.0.0.0/0", "::/0")))
            .put("udp", true)
            .put("mtu", 1280)
            .apply {
                ipv6?.let { put("ipv6", it) }
                reserved?.let { put("reserved", JSONArray(it)) }
                // Plain WARP handshakes are blocked in Iran; fake packets before the handshake help it through.
                if (noise) {
                    put(
                        "wireguard-dpi-option",
                        JSONObject()
                            .put("fake-count", defaults.count)
                            .put("fake-min-size", defaults.minSize)
                            .put("fake-max-size", defaults.maxSize)
                            .put("fake-ttl", 3),
                    )
                }
            }
        return MihomoLinkConfigBuilder.build(listOf(proxy))
    }

    companion object {
        const val CLOUDFLARE_PUBLIC_KEY = "bmXOC+F1FxEMF9dyiK2H5/1SUtzH0JuVo51h2wPfgyo="
        private val HOST = Regex("""[A-Za-z0-9.:\[\]-]{1,253}""")

        fun isValidServer(value: String): Boolean = HOST.matches(value)

        /** Reads a subscription made by [WarpRegistration]; anything else returns null. */
        fun parse(yaml: String): WarpConfig? {
            val block = runCatching { MihomoProxyBlockParser.parse(yaml) }.getOrNull()?.singleOrNull() ?: return null
            if (!block.type.equals("wireguard", ignoreCase = true)) return null
            val lines = block.lines
            val publicKey = proxyFieldValue(lines, "public-key") ?: return null
            if (publicKey != CLOUDFLARE_PUBLIC_KEY && block.name != WarpRegistration.PROXY_NAME) return null
            return WarpConfig(
                server = block.server.takeIf(String::isNotBlank) ?: return null,
                port = proxyFieldValue(lines, "port")?.toIntOrNull() ?: return null,
                privateKey = proxyFieldValue(lines, "private-key") ?: return null,
                publicKey = publicKey,
                ipv4 = proxyFieldValue(lines, "ip") ?: return null,
                ipv6 = proxyFieldValue(lines, "ipv6"),
                reserved = proxyFieldValue(lines, "reserved")
                    ?.removeSurrounding("[", "]")?.split(',')
                    ?.mapNotNull { it.trim().toIntOrNull() }?.takeIf { it.size == 3 },
                noise = proxyFieldValue(lines, "wireguard-dpi-option") != null,
            )
        }
    }
}
