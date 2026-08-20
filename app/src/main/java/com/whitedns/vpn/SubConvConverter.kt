/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this file,
 * You can obtain one at https://mozilla.org/MPL/2.0/.
 *
 * Kotlin adaptation of the VLESS, VMess, Trojan, Hysteria2, and Shadowsocks conversion
 * flow from https://github.com/SubConv/SubConv.
 */
package com.whitedns.vpn

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import org.json.JSONArray
import org.json.JSONObject

internal object SubConvConverter {
    private val userAgents = listOf(
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:133.0) Gecko/20100101 Firefox/133.0",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10.15; rv:133.0) Gecko/20100101 Firefox/133.0",
        "Mozilla/5.0 (X11; Linux x86_64; rv:133.0) Gecko/20100101 Firefox/133.0",
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.2 Safari/605.1.15",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36 Edg/131.0.0.0",
    )

    fun convert(input: String): List<JSONObject> {
        val data = decodeBase64Text(input) ?: input
        val names = NameRegistry()
        val proxies = data.lineSequence().mapNotNull { raw ->
            val line = raw.trimEnd(' ', '\r').takeIf { "://" in it } ?: return@mapNotNull null
            try {
                when (line.substringBefore("://").lowercase()) {
                    "vless" -> parseVless(line, names)
                    "vmess" -> parseVmess(line, names)
                    "trojan" -> parseTrojan(line, names)
                    "hysteria2", "hy2" -> parseHysteria2(line, names)
                    "ss" -> parseShadowsocks(line, names)
                    "wireguard" -> parseWireGuard(line, names)
                    else -> null
                }
            } catch (_: ParseError) {
                null
            }
        }.toList()
        require(proxies.isNotEmpty()) { "No valid proxies found" }
        return proxies
    }

    private fun parseVless(line: String, names: NameRegistry): JSONObject {
        val uri = parseUri(line)
        val query = parseQuery(uri.rawQuery)
        val proxy = parseVShare(uri, query, names, "vless", decodeHost = true)
        query["flow"]?.takeIf(String::isNotBlank)?.let { proxy.put("flow", it.lowercase()) }
        query["encryption"]?.takeIf(String::isNotBlank)?.let { proxy.put("encryption", it) }
        return proxy
    }

    private fun parseVShare(
        uri: URI,
        query: Map<String, String>,
        names: NameRegistry,
        type: String,
        decodeHost: Boolean = false,
    ): JSONObject {
        val endpoint = endpoint(uri, decodeHost)
        if (endpoint.userInfo.isBlank()) throw ParseError()
        val proxy = JSONObject()
            .put("name", names.register(name(uri)))
            .put("type", type)
            .put("server", endpoint.host)
            .put("port", endpoint.port)
            .put("uuid", endpoint.userInfo)
            .put("udp", true)

        val security = query["security"].orEmpty().lowercase()
        if (security.endsWith("tls") || security == "reality") {
            proxy.put("tls", true)
                .put("skip-cert-verify", false)
                .put("client-fingerprint", query["fp"].orEmpty().ifBlank { "chrome" })
            query["alpn"]?.takeIf(String::isNotBlank)?.let { proxy.put("alpn", it.split(',')) }
            query["pcs"]?.takeIf(String::isNotBlank)?.let { proxy.put("fingerprint", it) }
        }
        query["sni"]?.takeIf(String::isNotBlank)?.let { proxy.put("servername", it) }
        query["pbk"]?.takeIf(String::isNotBlank)?.let { publicKey ->
            proxy.put(
                "reality-opts",
                JSONObject().put("public-key", publicKey).apply {
                    query["sid"]?.takeIf(String::isNotBlank)?.let { put("short-id", it) }
                },
            )
        }

        when (query["packetEncoding"].orEmpty()) {
            "packet" -> proxy.put("packet-addr", true)
            "none" -> Unit
            else -> proxy.put("xudp", true)
        }

        var network = query["type"].orEmpty().lowercase().ifBlank { "tcp" }
        val fakeType = query["headerType"].orEmpty().lowercase()
        if (network == "tcp" && fakeType == "http") network = "http"
        else if (network == "http") network = "h2"
        proxy.put("network", network)

        when (network) {
            "http" -> proxy.put("http-opts", httpOptions(query))
            "h2" -> proxy.put("h2-opts", vShareH2Options(query))
            "ws", "httpupgrade" -> proxy.put("ws-opts", vShareWsOptions(query, network))
            "grpc" -> proxy.put(
                "grpc-opts",
                JSONObject().put("grpc-service-name", query["serviceName"].orEmpty()),
            )
            "xhttp" -> proxy.put("xhttp-opts", xhttpOptions(query))
        }
        return proxy
    }

    private fun parseVmess(line: String, names: NameRegistry): JSONObject {
        val body = line.removePrefix("vmess://")
        val decoded = decodeBase64Text(body)
        if (decoded == null) return parseVmessAead(line, names)
        val values = try {
            JSONObject(decoded)
        } catch (_: Exception) {
            throw ParseError()
        }
        val server = values.optString("add")
        val uuid = values.optString("id")
        val port = jsonInt(values, "port")
        if (server.isBlank() || uuid.isBlank() || port !in 1..65535) throw ParseError()

        val proxy = JSONObject()
            .put("name", names.register(values.optString("ps")))
            .put("type", "vmess")
            .put("server", server)
            .put("port", port)
            .put("uuid", uuid)
            .put("alterId", jsonInt(values, "aid", 0))
            .put("cipher", values.optString("scy").ifBlank { "auto" })
            .put("udp", true)
            .put("xudp", true)
            .put("tls", false)
            .put("skip-cert-verify", false)
        values.optString("sni").takeIf(String::isNotBlank)?.let { proxy.put("servername", it) }
        val tls = values.optString("tls")
        if (tls.lowercase().endsWith("tls")) {
            proxy.put("tls", true)
            splitCsv(values.optString("alpn"))?.let { proxy.put("alpn", it) }
        }

        var network = values.optString("net").lowercase()
        if (values.optString("type").lowercase() == "http") network = "http"
        else if (network == "http") network = "h2"
        if (network.isNotBlank()) proxy.put("network", network)
        val host = values.optString("host").takeIf(String::isNotBlank)
        val path = values.optString("path").takeIf(String::isNotBlank)
        when (network) {
            "http" -> proxy.put("http-opts", httpOptions(path, host))
            "h2" -> proxy.put(
                "h2-opts",
                JSONObject().apply {
                    path?.let { put("path", it) }
                    put("headers", JSONObject().apply {
                        host?.let { put("Host", JSONArray().put(it)) }
                    })
                },
            )
            "ws", "httpupgrade" -> proxy.put("ws-opts", vmessWsOptions(path, host, network))
            "grpc" -> proxy.put("grpc-opts", JSONObject().put("grpc-service-name", path.orEmpty()))
        }
        return proxy
    }

    private fun parseVmessAead(line: String, names: NameRegistry): JSONObject {
        val uri = parseUri(line)
        val query = parseQuery(uri.rawQuery)
        return parseVShare(uri, query, names, "vless").apply {
            put("type", "vmess")
            remove("flow")
            remove("encryption")
            put("alterId", 0)
            put("cipher", query["encryption"].orEmpty().ifBlank { "auto" })
            if (!has("udp")) put("udp", true)
            if (!has("tls")) put("tls", false)
            if (!has("xudp")) put("xudp", true)
            if (!has("skip-cert-verify")) put("skip-cert-verify", false)
        }
    }

    private fun parseTrojan(line: String, names: NameRegistry): JSONObject {
        val uri = parseUri(line)
        val endpoint = endpoint(uri)
        if (endpoint.userInfo.isBlank()) throw ParseError()
        val query = parseQuery(uri.rawQuery)
        return JSONObject()
            .put("name", names.register(name(uri)))
            .put("type", "trojan")
            .put("server", endpoint.host)
            .put("port", endpoint.port)
            .put("password", endpoint.userInfo)
            .put("udp", true)
            .apply {
                // `allowInsecure` is honoured as an explicit false only. Letting a share link turn
                // certificate validation off would hand anyone who can hand the user a link the
                // ability to read the tunnelled traffic.
                put("skip-cert-verify", false)
                query["sni"]?.takeIf(String::isNotBlank)?.let { put("sni", it) }
                query["alpn"]?.takeIf(String::isNotBlank)?.let { put("alpn", it.split(',')) }
                val network = query["type"].orEmpty().lowercase()
                if (network.isNotBlank()) put("network", network)
                when (network) {
                    "ws" -> put("ws-opts", JSONObject().apply {
                        query["path"]?.takeIf(String::isNotBlank)?.let { put("path", it) }
                        put("headers", JSONObject().put("User-Agent", userAgents.random()))
                    })
                    "grpc" -> put(
                        "grpc-opts",
                        JSONObject().put("grpc-service-name", query["serviceName"].orEmpty()),
                    )
                }
                put("client-fingerprint", query["fp"].orEmpty().ifBlank { "chrome" })
                query["pcs"]?.takeIf(String::isNotBlank)?.let { put("fingerprint", it) }
            }
    }

    private fun parseHysteria2(line: String, names: NameRegistry): JSONObject {
        val uri = parseUri(line)
        val endpoint = endpoint(uri)
        if (endpoint.userInfo.isBlank()) throw ParseError()
        val query = parseQuery(uri.rawQuery)
        return JSONObject()
            .put("name", names.register(name(uri)))
            .put("type", "hysteria2")
            .put("server", endpoint.host)
            .put("port", endpoint.port)
            .put("password", endpoint.userInfo)
            .put("skip-cert-verify", false)
            .apply {
                listOf("obfs", "obfs-password", "sni").forEach { key ->
                    query[key]?.takeIf(String::isNotBlank)?.let { put(key, it) }
                }
            }
    }

    private fun parseShadowsocks(line: String, names: NameRegistry): JSONObject {
        val content = line.removePrefix("ss://")
        val fragmentSplit = content.split('#', limit = 2)
        val querySplit = fragmentSplit[0].split('?', limit = 2)
        val query = parseQuery(querySplit.getOrNull(1))
        var authority = querySplit[0]
        if ('@' !in authority) authority = decodeBase64Text(authority) ?: throw ParseError()
        val at = authority.lastIndexOf('@')
        if (at < 1) throw ParseError()
        val rawCredentials = authority.substring(0, at)
        val credentials = if (':' in rawCredentials) {
            decode(rawCredentials)
        } else {
            decodeBase64Text(rawCredentials) ?: throw ParseError()
        }
        val separator = credentials.indexOf(':')
        if (separator < 1) throw ParseError()
        val endpoint = parseHostPort(authority.substring(at + 1)) ?: throw ParseError()
        val proxy = JSONObject()
            .put("name", names.register(decode(fragmentSplit.getOrNull(1).orEmpty())))
            .put("type", "ss")
            .put("server", endpoint.host)
            .put("port", endpoint.port)
            .put("password", credentials.substring(separator + 1))
            .put("cipher", credentials.substring(0, separator))
            .put("udp", true)
        query["plugin"]?.let { plugin -> applyPlugin(proxy, plugin) }
        if (query["udp-over-tcp"] == "true" || query["uot"] == "1") {
            proxy.put("udp-over-tcp", true)
        }
        return proxy
    }

    private fun parseWireGuard(line: String, names: NameRegistry): JSONObject {
        val uri = parseUri(line)
        val endpoint = endpoint(uri)
        val query = parseQuery(uri.rawQuery)
        val publicKey = (query["publickey"] ?: query["public-key"])
            ?.takeIf(String::isNotBlank) ?: throw ParseError()
        val addresses = splitCsv(query["address"].orEmpty()).orEmpty()
            .map { it.substringBefore('/').trim() }
        val ipv4 = addresses.firstOrNull { ':' !in it } ?: throw ParseError()
        val ipv6 = addresses.firstOrNull { ':' in it }
        if (endpoint.userInfo.isBlank()) throw ParseError()

        return JSONObject()
            .put("name", names.register(name(uri).ifBlank { "WireGuard" }))
            .put("type", "wireguard")
            .put("server", endpoint.host)
            .put("port", endpoint.port)
            .put("ip", ipv4)
            .put("ip-version", if (ipv6 == null) "ipv4" else "ipv4-prefer")
            .put("private-key", endpoint.userInfo)
            .put("public-key", publicKey)
            .put(
                "allowed-ips",
                JSONArray(splitCsv(query["allowed-ips"] ?: query["allowedips"].orEmpty())
                    ?: listOf("0.0.0.0/0", "::/0")),
            )
            .put("udp", true)
            .apply {
                ipv6?.let { put("ipv6", it) }
                splitCsv(query["reserved"].orEmpty())
                    ?.mapNotNull(String::toIntOrNull)
                    ?.takeIf { it.size == 3 && it.all { byte -> byte in 0..255 } }
                    ?.let { put("reserved", JSONArray(it)) }
                query["mtu"]?.toIntOrNull()?.takeIf { it > 0 }?.let { put("mtu", it) }
                (query["keepalive"] ?: query["persistent-keepalive"])
                    ?.toIntOrNull()?.takeIf { it > 0 }?.let { put("persistent-keepalive", it) }
                (query["presharedkey"] ?: query["pre-shared-key"])
                    ?.takeIf(String::isNotBlank)?.let { put("pre-shared-key", it) }
                wireGuardAmneziaOptions(query)?.let { put("amnezia-wg-option", it) }
                wireGuardDpiOptions(query)?.let { put("wireguard-dpi-option", it) }
                wireGuardIpStackOptions(query)?.let { put("ip-stack", it) }
            }
    }

    private fun wireGuardAmneziaOptions(query: Map<String, String>): JSONObject? = JSONObject().apply {
        listOf("version", "jc", "jmin", "jmax").forEach { key ->
            query[key]?.toIntOrNull()?.let { put(key, it) }
        }
        listOf(
            "header-protection-key",
            "content-padding-addition",
            "rekey-after-time",
            "rekey-timeout",
            "reject-after-time",
            "keepalive-timeout",
            "max-handshake-attempts",
        ).forEach { key ->
            query[key]?.takeIf(String::isNotBlank)?.let { put(key, it) }
        }
        listOf("random-trailers", "disable-cookies").forEach { key ->
            query[key]?.lowercase()?.let { value ->
                if (value == "true" || value == "false") put(key, value.toBoolean())
            }
        }
    }.takeIf { it.length() > 0 }

    private fun wireGuardDpiOptions(query: Map<String, String>): JSONObject? {
        val legacyNoiseEnabled = !query["wnoise"].equals("none", ignoreCase = true) &&
            ("wnoisecount" in query || "wpayloadsize" in query)
        val payloadRange = query["wpayloadsize"]?.let(::integerRange)
        val explicitCount = query["fake-count"]?.toIntOrNull()?.takeIf { it in 1..20 }
        val count = explicitCount
            ?: query["wnoisecount"]?.let(::integerRange)?.first?.coerceAtMost(20)?.takeIf { it > 0 }
        val minSize = query["fake-min-size"]?.toIntOrNull()?.takeIf { it in 1..1280 }
            ?: payloadRange?.first?.takeIf { legacyNoiseEnabled && it in 1..1280 }
        val maxSize = query["fake-max-size"]?.toIntOrNull()?.takeIf { it in 1..1280 }
            ?: payloadRange?.second?.takeIf { legacyNoiseEnabled && it in 1..1280 }
        val ttl = query["fake-ttl"]?.toIntOrNull()?.takeIf { it in 0..255 }
            ?: 3.takeIf { legacyNoiseEnabled }
        // ponytail: Mihomo has no noise header/delay fields; preserve its supported count/size/TTL subset.
        return JSONObject().apply {
            count?.takeIf { explicitCount != null || legacyNoiseEnabled }?.let { put("fake-count", it) }
            minSize?.let { put("fake-min-size", it) }
            maxSize?.takeIf { minSize == null || it >= minSize }?.let { put("fake-max-size", it) }
            ttl?.let { put("fake-ttl", it) }
        }.takeIf { it.length() > 0 }
    }

    private fun wireGuardIpStackOptions(query: Map<String, String>): JSONObject? = JSONObject().apply {
        (query["ip-stack"] ?: query["ip-stack-mode"])
            ?.takeIf(String::isNotBlank)?.let { put("mode", it) }
        query["congestion-controller"]?.takeIf(String::isNotBlank)
            ?.let { put("congestion-controller", it) }
    }.takeIf { it.length() > 0 }

    private fun integerRange(value: String): Pair<Int, Int>? {
        val parts = value.split('-', limit = 2).map(String::trim)
        val min = parts.firstOrNull()?.toIntOrNull() ?: return null
        val max = parts.getOrNull(1)?.toIntOrNull() ?: min
        return (min to max).takeIf { min <= max }
    }

    private fun applyPlugin(proxy: JSONObject, plugin: String) {
        val segments = plugin.split(';')
        val name = segments.firstOrNull().orEmpty()
        val options = segments.drop(1).mapNotNull { segment ->
            val split = segment.split('=', limit = 2)
            split.takeIf { it.size == 2 }?.let { it[0] to it[1] }
        }.toMap()
        val output = JSONObject()
        when {
            "obfs" in name -> {
                options["obfs"]?.takeIf(String::isNotBlank)?.let { output.put("mode", it) }
                options["obfs-host"]?.takeIf(String::isNotBlank)?.let { output.put("host", it) }
                proxy.put("plugin", "obfs")
            }
            "v2ray-plugin" in name -> {
                listOf("mode", "host", "path").forEach { key ->
                    options[key]?.takeIf(String::isNotBlank)?.let { output.put(key, it) }
                }
                if ("tls" in plugin) output.put("tls", true)
                proxy.put("plugin", "v2ray-plugin")
            }
            else -> return
        }
        if (output.length() > 0) proxy.put("plugin-opts", output)
    }

    private fun httpOptions(query: Map<String, String>): JSONObject =
        httpOptions(query["path"].orEmpty().ifBlank { "/" }, query["host"]).apply {
            query["method"]?.takeIf(String::isNotBlank)?.let { put("method", it) }
        }

    private fun httpOptions(path: String?, host: String?): JSONObject = JSONObject()
        .put("path", JSONArray().put(path.orEmpty().ifBlank { "/" }))
        .put("headers", JSONObject().apply {
            host?.takeIf(String::isNotBlank)?.let { put("Host", JSONArray().put(it)) }
        })

    private fun vShareH2Options(query: Map<String, String>): JSONObject = JSONObject()
        .put("path", JSONArray().put(query["path"].orEmpty().ifBlank { "/" }))
        .put("headers", JSONObject())
        .apply {
            query["host"]?.takeIf(String::isNotBlank)?.let { put("host", JSONArray().put(it)) }
        }

    private fun vShareWsOptions(query: Map<String, String>, network: String): JSONObject = JSONObject()
        .put("path", query["path"].orEmpty())
        .put(
            "headers",
            JSONObject()
                .put("User-Agent", userAgents.random())
                .put("Host", query["host"].orEmpty()),
        )
        .apply {
            query["ed"]?.takeIf(String::isNotBlank)?.let { raw ->
                val earlyData = raw.toIntOrNull() ?: throw ParseError()
                if (network == "ws") {
                    put("max-early-data", earlyData)
                    put("early-data-header-name", "Sec-WebSocket-Protocol")
                } else {
                    put("v2ray-http-upgrade-fast-open", true)
                }
            }
            query["eh"]?.takeIf(String::isNotBlank)?.let { put("early-data-header-name", it) }
        }

    private fun vmessWsOptions(path: String?, host: String?, network: String): JSONObject {
        var outputPath = path.orEmpty().ifBlank { "/" }
        val query = parseQuery(outputPath.substringAfter('?', ""))
        val output = JSONObject()
            .put("path", outputPath)
            .put("headers", JSONObject().apply {
                host?.takeIf(String::isNotBlank)?.let { put("Host", it) }
            })
        query["ed"]?.toIntOrNull()?.let { earlyData ->
            if (network == "ws") {
                output.put("max-early-data", earlyData)
                    .put("early-data-header-name", "Sec-WebSocket-Protocol")
            } else {
                output.put("v2ray-http-upgrade-fast-open", true)
            }
            outputPath = removeQueryParameter(outputPath, "ed")
            output.put("path", outputPath)
        }
        query["eh"]?.takeIf(String::isNotBlank)?.let { output.put("early-data-header-name", it) }
        return output
    }

    private fun xhttpOptions(query: Map<String, String>): JSONObject = JSONObject().apply {
        listOf("path", "host", "mode").forEach { key ->
            query[key]?.takeIf(String::isNotBlank)?.let { put(key, it) }
        }
        query["extra"]?.let { raw ->
            val extra = runCatching { JSONObject(raw) }.getOrNull() ?: return@let
            applyXhttpExtra(this, extra)
        }
    }

    private fun applyXhttpExtra(target: JSONObject, extra: JSONObject) {
        if (extra.optBoolean("noGRPCHeader")) target.put("no-grpc-header", true)
        val stringFields = mapOf(
            "xPaddingBytes" to "x-padding-bytes",
            "xPaddingKey" to "x-padding-key",
            "xPaddingHeader" to "x-padding-header",
            "xPaddingPlacement" to "x-padding-placement",
            "xPaddingMethod" to "x-padding-method",
            "uplinkHttpMethod" to "uplink-http-method",
            "sessionPlacement" to "session-placement",
            "sessionKey" to "session-key",
            "seqPlacement" to "seq-placement",
            "seqKey" to "seq-key",
            "uplinkDataPlacement" to "uplink-data-placement",
            "uplinkDataKey" to "uplink-data-key",
        )
        stringFields.forEach { (source, destination) ->
            extra.optString(source).takeIf(String::isNotBlank)?.let { target.put(destination, it) }
        }
        if (extra.has("xPaddingObfsMode")) target.put("x-padding-obfs-mode", extra.optBoolean("xPaddingObfsMode"))
        mapOf(
            "uplinkChunkSize" to "uplink-chunk-size",
            "scMaxEachPostBytes" to "sc-max-each-post-bytes",
            "scMinPostsIntervalMs" to "sc-min-posts-interval-ms",
        ).forEach { (source, destination) ->
            (extra.opt(source) as? Number)?.let { target.put(destination, it.toInt()) }
        }
        extra.optJSONObject("xmux")?.let(::reuseSettings)?.let { target.put("reuse-settings", it) }
        extra.optJSONObject("downloadSettings")?.let(::downloadSettings)?.let { target.put("download-settings", it) }
    }

    private fun reuseSettings(xmux: JSONObject): JSONObject? {
        val output = JSONObject()
        mapOf(
            "maxConnections" to "max-connections",
            "maxConcurrency" to "max-concurrency",
            "cMaxReuseTimes" to "c-max-reuse-times",
            "hMaxRequestTimes" to "h-max-request-times",
            "hMaxReusableSecs" to "h-max-reusable-secs",
        ).forEach { (source, destination) ->
            when (val value = xmux.opt(source)) {
                is String -> value.takeIf(String::isNotBlank)?.let { output.put(destination, it) }
                is Number -> output.put(destination, value.toInt().toString())
            }
        }
        (xmux.opt("hKeepAlivePeriod") as? Number)?.let { output.put("h-keep-alive-period", it.toInt()) }
        return output.takeIf { it.length() > 0 }
    }

    private fun downloadSettings(settings: JSONObject): JSONObject? {
        val output = JSONObject()
        settings.optString("address").takeIf(String::isNotBlank)?.let { output.put("server", it) }
        (settings.opt("port") as? Number)?.let { output.put("port", it.toInt()) }
        val security = settings.optString("security").lowercase()
        if (security == "tls" || security == "reality") {
            output.put("tls", true)
            settings.optJSONObject("tlsSettings")?.let { tls ->
                tls.optString("serverName").takeIf(String::isNotBlank)?.let { output.put("servername", it) }
                tls.optString("fingerprint").takeIf(String::isNotBlank)?.let { output.put("client-fingerprint", it) }
                tls.optJSONArray("alpn")?.takeIf { it.length() > 0 }?.let { output.put("alpn", it) }
                output.put("skip-cert-verify", false)
            }
            if (security == "reality") settings.optJSONObject("realitySettings")?.let { reality ->
                reality.optString("publicKey").takeIf(String::isNotBlank)?.let { key ->
                    output.put("reality-opts", JSONObject().put("public-key", key).apply {
                        reality.optString("shortId").takeIf(String::isNotBlank)?.let { put("short-id", it) }
                    })
                }
            }
        }
        settings.optJSONObject("xhttpSettings")?.let { xhttp ->
            listOf("path", "host").forEach { key ->
                xhttp.optString(key).takeIf(String::isNotBlank)?.let { output.put(key, it) }
            }
            xhttp.optJSONObject("headers")?.takeIf { it.length() > 0 }?.let { output.put("headers", it) }
            applyXhttpExtra(output, xhttp)
        }
        return output.takeIf { it.length() > 0 }
    }

    private fun endpoint(uri: URI, decodeHost: Boolean = false): Endpoint {
        val authority = uri.rawAuthority ?: throw ParseError()
        val at = authority.lastIndexOf('@')
        val userInfo = if (at >= 0) decode(authority.substring(0, at)) else ""
        var hostPort = authority.substring(at + 1)
        var parsed = parseHostPort(hostPort)
        if (parsed == null && decodeHost) {
            hostPort = decodeBase64Text(hostPort) ?: throw ParseError()
            parsed = parseHostPort(hostPort)
        }
        val endpoint = parsed ?: throw ParseError()
        return Endpoint(userInfo, endpoint.host, endpoint.port)
    }

    private fun parseHostPort(raw: String): HostPort? {
        val value = raw.substringBefore('/').trim()
        if (value.startsWith('[')) {
            val end = value.indexOf(']')
            val port = value.substring(end + 1).removePrefix(":").toIntOrNull()
            return if (end > 0 && port in 1..65535) HostPort(value.substring(1, end), port!!) else null
        }
        val separator = value.lastIndexOf(':')
        val port = value.substring(separator + 1).toIntOrNull()
        return if (separator > 0 && port in 1..65535) HostPort(value.substring(0, separator), port!!) else null
    }

    private fun parseUri(value: String): URI = try {
        URI(value.replace(" ", "%20"))
    } catch (_: Exception) {
        throw ParseError()
    }

    private fun parseQuery(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        val output = linkedMapOf<String, String>()
        raw.split('&').forEach { part ->
            val split = part.split('=', limit = 2)
            val key = decode(split[0])
            if (key.isNotBlank() && key !in output) output[key] = decode(split.getOrNull(1).orEmpty())
        }
        return output
    }

    private fun name(uri: URI): String = decode(uri.rawFragment.orEmpty())

    private fun decode(value: String): String =
        URLDecoder.decode(value, StandardCharsets.UTF_8.name())

    private fun removeQueryParameter(path: String, key: String): String {
        val base = path.substringBefore('?')
        val query = parseQuery(path.substringAfter('?', ""))
            .filterKeys { it != key }
            .entries.joinToString("&") { (name, value) ->
                "${URLEncoder.encode(name, StandardCharsets.UTF_8.name())}=${URLEncoder.encode(value, StandardCharsets.UTF_8.name())}"
            }
        return if (query.isBlank()) base else "$base?$query"
    }

    private fun jsonInt(json: JSONObject, key: String, default: Int = -1): Int =
        when (val value = json.opt(key)) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull() ?: default
            else -> default
        }

    private fun splitCsv(value: String): List<String>? =
        value.split(',').map(String::trim).filter(String::isNotBlank).takeIf(List<String>::isNotEmpty)

    @OptIn(ExperimentalEncodingApi::class)
    internal fun decodeBase64Text(value: String): String? {
        val compact = value.filterNot(Char::isWhitespace)
        val padded = compact + "=".repeat((4 - compact.length % 4) % 4)
        return runCatching { Base64.Default.decode(padded) }
            .recoverCatching { Base64.UrlSafe.decode(padded) }
            .getOrNull()
            ?.toString(Charsets.UTF_8)
    }

    private class NameRegistry {
        private val names = mutableMapOf<String, Int>()

        fun register(name: String): String {
            val index = names[name]
            if (index == null) {
                names[name] = 0
                return name
            }
            val next = index + 1
            names[name] = next
            return "$name-${next.toString().padStart(2, '0')}"
        }
    }

    private data class Endpoint(val userInfo: String, val host: String, val port: Int)
    private data class HostPort(val host: String, val port: Int)
    private class ParseError : IllegalArgumentException()
}
