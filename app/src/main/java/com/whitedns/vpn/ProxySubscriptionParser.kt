package com.whitedns.vpn

import org.json.JSONObject
import java.net.URLDecoder
import java.util.Locale
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

data class SubscriptionCatalog(
    val profiles: List<ConnectionProfile>,
    val fetchedAt: Long,
)

data class SubscriptionParseStats(
    val totalLinks: Int,
    val supportedLinks: Int,
    val duplicateLinks: Int,
    val malformedLinks: Int,
    val unsupportedByScheme: Map<String, Int>,
) {
    val unsupportedLinks: Int
        get() = unsupportedByScheme.values.sum()
}

data class SubscriptionParseResult(
    val catalog: SubscriptionCatalog,
    val stats: SubscriptionParseStats,
)

object ProxySubscriptionParser {
    private val supportedSchemes = setOf("vless", "trojan", "vmess", "ss", "hysteria", "hysteria2", "hy2")

    fun parseBase64(
        encoded: String,
        nowMs: Long = System.currentTimeMillis(),
        decoder: (String) -> ByteArray = ::decodeBase64,
    ): SubscriptionParseResult {
        val decodedText = decodeSubscriptionText(encoded, decoder)
        val links = decodedText
            .lineSequence()
            .map(String::trim)
            .filter { it.isNotBlank() && "://" in it }
            .toList()

        val profiles = mutableListOf<ConnectionProfile>()
        val seenFingerprints = mutableSetOf<String>()
        val usedTags = mutableSetOf<String>()
        val unsupported = linkedMapOf<String, Int>()
        var malformed = 0
        var duplicate = 0

        for ((index, link) in links.withIndex()) {
            val scheme = link.substringBefore("://").lowercase(Locale.US)
            if (scheme !in supportedSchemes) {
                unsupported[scheme] = unsupported.getOrDefault(scheme, 0) + 1
                continue
            }

            val parsed = runCatching { parseLink(link, index) }.getOrNull()
            if (parsed == null) {
                malformed += 1
                continue
            }

            val fingerprint = ProfileFingerprint.from(
                type = parsed.type,
                server = parsed.server,
                port = parsed.port,
                validationHost = parsed.validationHost,
                outboundJson = parsed.outboundWithoutTag.toString(),
            )
            if (!seenFingerprints.add(fingerprint)) {
                duplicate += 1
                continue
            }

            val tag = uniqueTag(parsed.displayName, parsed.type, fingerprint, usedTags)
            val outbound = JSONObject(parsed.outboundWithoutTag.toString()).put("tag", tag)
            profiles += ConnectionProfile(
                tag = tag,
                type = parsed.type,
                server = parsed.server,
                port = parsed.port,
                transport = parsed.transport,
                validationHost = parsed.validationHost,
                fingerprint = fingerprint,
                outboundJson = outbound.toString(),
            )
        }

        return SubscriptionParseResult(
            catalog = SubscriptionCatalog(profiles = profiles, fetchedAt = nowMs),
            stats = SubscriptionParseStats(
                totalLinks = links.size,
                supportedLinks = profiles.size,
                duplicateLinks = duplicate,
                malformedLinks = malformed,
                unsupportedByScheme = unsupported,
            ),
        )
    }

    private fun decodeSubscriptionText(encoded: String, decoder: (String) -> ByteArray): String {
        val compact = encoded.filterNot(Char::isWhitespace)
        val padded = compact + "=".repeat((4 - compact.length % 4) % 4)
        return decoder(padded).toString(Charsets.UTF_8)
    }

    private fun parseLink(link: String, index: Int): ParsedProfile {
        return when (link.substringBefore("://").lowercase(Locale.US)) {
            "vless" -> parseVless(link, index)
            "trojan" -> parseTrojan(link, index)
            "vmess" -> parseVmess(link, index)
            "ss" -> parseShadowsocks(link, index)
            "hysteria" -> parseHysteria(link, index)
            "hysteria2", "hy2" -> parseHysteria2(link, index)
            else -> error("unsupported scheme")
        }
    }

    private fun parseVless(link: String, index: Int): ParsedProfile {
        val standard = parseStandardLink(link, defaultPort = -1)
        require(standard.userInfo.isNotBlank() && standard.host.isNotBlank() && standard.port > 0)
        val security = standard.query["security"].orEmpty().lowercase(Locale.US)
        val transportType = standard.query["type"].orEmpty().ifBlank { standard.query["network"].orEmpty() }
        val outbound = baseOutbound("vless", standard.host, standard.port)
            .put("uuid", percentDecode(standard.userInfo))
        standard.query["flow"]?.takeIf(String::isNotBlank)?.let { outbound.put("flow", it) }
        val encryption = standard.query["encryption"].orEmpty().lowercase(Locale.US)
        require(encryption.isBlank() || encryption == "none")
        putVlessPacketEncoding(outbound, standard.query)
        if (security == "reality") require(!standard.query["pbk"].isNullOrBlank())
        putTls(outbound, security, standard.query, standard.host, defaultEnabled = security == "tls" || security == "reality")
        require(putV2RayTransport(outbound, transportType, standard.query))
        return parsedProfile("vless", standard, outbound, transportType, index)
    }

    private fun parseTrojan(link: String, index: Int): ParsedProfile {
        val standard = parseStandardLink(link, defaultPort = 443)
        require(standard.userInfo.isNotBlank() && standard.host.isNotBlank() && standard.port > 0)
        val transportType = standard.query["type"].orEmpty().ifBlank { standard.query["network"].orEmpty() }
        val security = standard.query["security"].orEmpty().lowercase(Locale.US)
        val outbound = baseOutbound("trojan", standard.host, standard.port)
            .put("password", percentDecode(standard.userInfo))
        putTls(outbound, security, standard.query, standard.host, defaultEnabled = security != "none")
        require(putV2RayTransport(outbound, transportType, standard.query))
        return parsedProfile("trojan", standard, outbound, transportType, index)
    }

    private fun parseVmess(link: String, index: Int): ParsedProfile {
        val payload = link.substringAfter("vmess://").substringBefore("#")
        val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
        val json = JSONObject(decodeBase64(padded).toString(Charsets.UTF_8))
        val host = json.optString("add")
        val port = json.optString("port").toIntOrNull() ?: json.optInt("port", -1)
        val uuid = json.optString("id")
        require(host.isNotBlank() && port > 0 && uuid.isNotBlank())

        val transportType = json.optString("net")
        val query = mutableMapOf<String, String>()
        json.optString("host").takeIf(String::isNotBlank)?.let { query["host"] = it }
        json.optString("path").takeIf(String::isNotBlank)?.let { query["path"] = it }
        json.optString("sni").takeIf(String::isNotBlank)?.let { query["sni"] = it }
        json.optString("tls").takeIf(String::isNotBlank)?.let { query["security"] = it }
        json.optString("alpn").takeIf(String::isNotBlank)?.let { query["alpn"] = it }
        val displayName = json.optString("ps").ifBlank { "vmess-$index" }
        val standard = StandardLink(
            displayName = displayName,
            userInfo = uuid,
            host = host,
            port = port,
            query = query,
        )

        val outbound = baseOutbound("vmess", host, port)
            .put("uuid", uuid)
            .put("security", json.optString("scy").ifBlank { "auto" })
            .put("alter_id", json.optInt("aid", 0))
        putTls(outbound, query["security"].orEmpty(), query, host, defaultEnabled = query["security"].orEmpty().isNotBlank())
        require(putV2RayTransport(outbound, transportType, query))
        return parsedProfile("vmess", standard, outbound, transportType, index)
    }

    private fun parseShadowsocks(link: String, index: Int): ParsedProfile {
        val standard = parseShadowsocksLink(link, index)
        val parts = percentDecode(standard.userInfo).split(":", limit = 2)
        require(parts.size == 2 && parts[0].isNotBlank() && parts[1].isNotBlank())
        val outbound = baseOutbound("shadowsocks", standard.host, standard.port)
            .put("method", parts[0])
            .put("password", parts[1])
        standard.query["plugin"]?.takeIf(String::isNotBlank)?.let { pluginValue ->
            val pluginParts = pluginValue.split(";", limit = 2)
            outbound.put("plugin", pluginParts[0])
            if (pluginParts.size == 2 && pluginParts[1].isNotBlank()) {
                outbound.put("plugin_opts", pluginParts[1])
            }
        }
        return parsedProfile("shadowsocks", standard, outbound, "", index)
    }

    private fun parseHysteria(link: String, index: Int): ParsedProfile {
        val standard = parseStandardLink(link, defaultPort = -1)
        require(standard.host.isNotBlank() && standard.port > 0)
        val outbound = baseOutbound("hysteria", standard.host, standard.port)
        val auth = standard.query["auth_str"] ?: standard.query["auth"] ?: standard.userInfo
        require(auth.isNotBlank())
        outbound.put("auth_str", percentDecode(auth))
        standard.query["upmbps"]?.toIntOrNull()?.let { outbound.put("up_mbps", it) }
        standard.query["downmbps"]?.toIntOrNull()?.let { outbound.put("down_mbps", it) }
        standard.query["obfs"]?.takeIf(String::isNotBlank)?.let { outbound.put("obfs", it) }
        putTls(outbound, "tls", standard.query, standard.host, defaultEnabled = true)
        return parsedProfile("hysteria", standard, outbound, "udp", index)
    }

    private fun parseHysteria2(link: String, index: Int): ParsedProfile {
        val standard = parseStandardLink(link, defaultPort = -1)
        require(standard.host.isNotBlank() && standard.port > 0)
        val outbound = baseOutbound("hysteria2", standard.host, standard.port)
        val password = standard.query["password"] ?: standard.query["auth"] ?: standard.userInfo
        require(password.isNotBlank())
        outbound.put("password", percentDecode(password))
        standard.query["upmbps"]?.toIntOrNull()?.let { outbound.put("up_mbps", it) }
        standard.query["downmbps"]?.toIntOrNull()?.let { outbound.put("down_mbps", it) }
        val obfs = standard.query["obfs"]
        val obfsPassword = standard.query["obfs-password"] ?: standard.query["obfs_password"]
        if (!obfs.isNullOrBlank() && !obfsPassword.isNullOrBlank()) {
            outbound.put("obfs", JSONObject().put("type", obfs).put("password", obfsPassword))
        }
        putTls(outbound, "tls", standard.query, standard.host, defaultEnabled = true)
        return parsedProfile("hysteria2", standard, outbound, "udp", index)
    }

    private fun parseStandardLink(link: String, defaultPort: Int): StandardLink {
        val withoutScheme = link.substringAfter("://")
        val bodyAndFragment = withoutScheme.split("#", limit = 2)
        val displayName = bodyAndFragment.getOrNull(1)?.let(::percentDecode).orEmpty()
        val bodyAndQuery = bodyAndFragment[0].split("?", limit = 2)
        val query = parseQuery(bodyAndQuery.getOrNull(1).orEmpty())
        val authority = bodyAndQuery[0].substringBefore("/")
        val userSplit = authority.lastIndexOf("@")
        val userInfo = if (userSplit >= 0) authority.substring(0, userSplit) else ""
        val hostPortRaw = if (userSplit >= 0) authority.substring(userSplit + 1) else authority
        val hostPort = parseHostPort(hostPortRaw, defaultPort)
        return StandardLink(displayName, userInfo, hostPort.host, hostPort.port, query)
    }

    private fun parseShadowsocksLink(link: String, index: Int): StandardLink {
        val withoutScheme = link.substringAfter("ss://")
        val bodyAndFragment = withoutScheme.split("#", limit = 2)
        val displayName = bodyAndFragment.getOrNull(1)?.let(::percentDecode).orEmpty().ifBlank { "ss-$index" }
        val bodyAndQuery = bodyAndFragment[0].split("?", limit = 2)
        val query = parseQuery(bodyAndQuery.getOrNull(1).orEmpty())
        val main = bodyAndQuery[0]
        val decodedMain = if ("@" in main) main else decodeUrlSafeBase64(main)
        val userSplit = decodedMain.lastIndexOf("@")
        require(userSplit > 0)
        val userInfo = decodedMain.substring(0, userSplit)
        val hostPort = parseHostPort(decodedMain.substring(userSplit + 1), defaultPort = -1)
        require(hostPort.port > 0)
        return StandardLink(displayName, userInfo, hostPort.host, hostPort.port, query)
    }

    private fun parsedProfile(
        type: String,
        standard: StandardLink,
        outbound: JSONObject,
        transport: String,
        index: Int,
    ): ParsedProfile {
        val validationHost = standard.query["host"]
            ?: standard.query["sni"]
            ?: standard.query["peer"]
            ?: standard.host
        return ParsedProfile(
            type = type,
            displayName = standard.displayName.ifBlank { "$type-$index" },
            server = standard.host,
            port = standard.port,
            transport = transport,
            validationHost = validationHost,
            outboundWithoutTag = outbound,
        )
    }

    private fun baseOutbound(type: String, server: String, port: Int): JSONObject {
        return JSONObject()
            .put("type", type)
            .put("server", server)
            .put("server_port", port)
    }

    private fun putTls(
        outbound: JSONObject,
        security: String,
        query: Map<String, String>,
        server: String,
        defaultEnabled: Boolean,
    ) {
        if (!defaultEnabled && security.lowercase(Locale.US) != "tls" && security.lowercase(Locale.US) != "reality") return
        val tls = JSONObject()
            .put("enabled", true)
            .put("server_name", query["sni"] ?: query["peer"] ?: query["host"] ?: server)
        if (query["allowInsecure"] == "1" || query["allow_insecure"] == "1" || query["insecure"] == "1") {
            tls.put("insecure", true)
        }
        query["alpn"]?.takeIf(String::isNotBlank)?.let { tls.put("alpn", it.split(",")) }
        query["fp"]?.takeIf(String::isNotBlank)?.let {
            tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", it))
        }
        if (security.lowercase(Locale.US) == "reality") {
            val reality = JSONObject()
                .put("enabled", true)
                .put("public_key", query["pbk"].orEmpty())
            query["sid"]?.takeIf(String::isNotBlank)?.let { reality.put("short_id", it) }
            tls.put(
                "reality",
                reality,
            )
        }
        outbound.put("tls", tls)
    }

    private fun putVlessPacketEncoding(outbound: JSONObject, query: Map<String, String>) {
        val packetEncoding = query["packetEncoding"]
            ?: query["packet_encoding"]
            ?: query["packet-encoding"]
            ?: return
        val normalized = packetEncoding.lowercase(Locale.US)
        require(normalized == "packetaddr" || normalized == "xudp")
        outbound.put("packet_encoding", normalized)
    }

    private fun putV2RayTransport(outbound: JSONObject, type: String, query: Map<String, String>): Boolean {
        val normalizedType = type.lowercase(Locale.US)
        if (normalizedType.isBlank() || normalizedType == "tcp") return true
        val transport = when (normalizedType) {
            "ws", "websocket" -> JSONObject()
                .put("type", "ws")
                .put("path", query["path"].orEmpty())
                .also { transport ->
                    query["host"]?.takeIf(String::isNotBlank)?.let {
                        transport.put("headers", JSONObject().put("Host", it))
                    }
                }
            "grpc" -> JSONObject()
                .put("type", "grpc")
                .put("service_name", query["serviceName"] ?: query["service_name"] ?: query["path"].orEmpty())
            "http", "h2" -> JSONObject()
                .put("type", "http")
                .put("path", query["path"].orEmpty())
                .also { transport ->
                    query["host"]?.takeIf(String::isNotBlank)?.let { transport.put("host", listOf(it)) }
                }
            "httpupgrade", "http_upgrade" -> JSONObject()
                .put("type", "httpupgrade")
                .put("path", query["path"].orEmpty())
                .also { transport ->
                    query["host"]?.takeIf(String::isNotBlank)?.let { transport.put("host", it) }
                }
            "quic" -> JSONObject().put("type", "quic")
            else -> return false
        }
        outbound.put("transport", transport)
        return true
    }

    private fun parseQuery(raw: String): Map<String, String> {
        if (raw.isBlank()) return emptyMap()
        return raw.split("&")
            .mapNotNull { part ->
                if (part.isBlank()) return@mapNotNull null
                val pieces = part.split("=", limit = 2)
                val key = percentDecode(pieces[0]).takeIf(String::isNotBlank) ?: return@mapNotNull null
                val value = pieces.getOrNull(1)?.let(::percentDecode).orEmpty()
                key to value
            }
            .toMap()
    }

    private fun parseHostPort(raw: String, defaultPort: Int): HostPort {
        val value = raw.substringBefore("/").trim()
        if (value.startsWith("[")) {
            val end = value.indexOf("]")
            require(end > 0)
            val host = value.substring(1, end)
            val port = value.substring(end + 1).removePrefix(":").toIntOrNull() ?: defaultPort
            return HostPort(host, port)
        }
        val split = value.lastIndexOf(":")
        require(split > 0)
        return HostPort(value.substring(0, split), value.substring(split + 1).toIntOrNull() ?: defaultPort)
    }

    private fun decodeUrlSafeBase64(value: String): String {
        val padded = value + "=".repeat((4 - value.length % 4) % 4)
        return decodeBase64(padded).toString(Charsets.UTF_8)
    }

    private fun percentDecode(value: String): String {
        return URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
    }

    private fun uniqueTag(
        displayName: String,
        type: String,
        fingerprint: String,
        usedTags: MutableSet<String>,
    ): String {
        val baseName = displayName
            .replace(Regex("\\p{Cntrl}+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
            .take(48)
            .ifBlank { type }
        val base = "$baseName ${fingerprint.take(6)}"
        var tag = base
        var suffix = 2
        while (!usedTags.add(tag)) {
            tag = "$base-$suffix"
            suffix += 1
        }
        return tag
    }

    private data class ParsedProfile(
        val type: String,
        val displayName: String,
        val server: String,
        val port: Int,
        val transport: String,
        val validationHost: String,
        val outboundWithoutTag: JSONObject,
    )

    private data class StandardLink(
        val displayName: String,
        val userInfo: String,
        val host: String,
        val port: Int,
        val query: Map<String, String>,
    )

    private data class HostPort(val host: String, val port: Int)
}

@OptIn(ExperimentalEncodingApi::class)
private fun decodeBase64(value: String): ByteArray {
    return runCatching { Base64.Default.decode(value) }
        .getOrElse { Base64.UrlSafe.decode(value) }
}
