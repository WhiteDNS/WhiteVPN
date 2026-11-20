// Adapted from ZedSecure, revision 2c01da26bed98b883503850df1b7f793d28f7f54.
package com.whitedns.vpn.engines.models

import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

data class PsiphonProfile(

    val country: String = "",

    val mode: String = PsiphonConfigBuilder.MODE_AUTO,

    val cdnIps: String = "",

    val cdnSni: String = "",
)

object PsiphonConfigBuilder {
    const val MODE_AUTO = "auto"
    const val MODE_CDN = "cdn"
    const val MODE_DIRECT = "direct"

    val MODES = listOf(MODE_AUTO, MODE_CDN, MODE_DIRECT)

    private val CDN_PROTOCOLS = listOf(
        "FRONTED-MEEK-CDN-OSSH",
        "FRONTED-MEEK-CDN-HTTP-OSSH",
        "FRONTED-MEEK-CDN-QUIC-OSSH",
    )

    private val NON_INPROXY_PROTOCOLS = listOf(
        "SSH", "OSSH", "TLS-OSSH",
        "UNFRONTED-MEEK-OSSH", "UNFRONTED-MEEK-HTTPS-OSSH", "UNFRONTED-MEEK-SESSION-TICKET-OSSH",
        "QUIC-OSSH", "SHADOWSOCKS-OSSH",
        "FRONTED-MEEK-OSSH", "FRONTED-MEEK-CDN-OSSH", "FRONTED-MEEK-HTTP-OSSH",
        "FRONTED-MEEK-CDN-HTTP-OSSH", "FRONTED-MEEK-QUIC-OSSH", "FRONTED-MEEK-CDN-QUIC-OSSH",
    )

    private const val PROPAGATION_CHANNEL_ID = "FFFFFFFFFFFFFFFF"
    private const val SPONSOR_ID = "FFFFFFFFFFFFFFFF"

    private const val SERVER_LIST_URL =
        "https://s3.amazonaws.com//psiphon/web/mjr4-p23r-puwl/server_list_compressed"

    private const val SERVER_LIST_SIGNATURE_KEY =
        "MIICIDANBgkqhkiG9w0BAQEFAAOCAg0AMIICCAKCAgEAt7Ls+/39r+T6zNW7GiVpJfzq/xvL9SBH" +
            "5rIFnk0RXYEYavax3WS6HOD35eTAqn8AniOwiH+DOkvgSKF2caqk/y1dfq47Pdymtwzp9ikpB1C5" +
            "OfAysXzBiwVJlCdajBKvBZDerV1cMvRzCKvKwRmvDmHgphQQ7WfXIGbRbmmk6opMBh3roE42Kcot" +
            "LFtqp0RRwLtcBRNtCdsrVsjiI1Lqz/lH+T61sGjSjQ3CHMuZYSQJZo/KrvzgQXpkaCTdbObxHqb6" +
            "/+i1qaVOfEsvjoiyzTxJADvSytVtcTjijhPEV6XskJVHE1Zgl+7rATr/pDQkw6DPCNBS1+Y6fy7G" +
            "stZALQXwEDN/qhQI9kWkHijT8ns+i1vGg00Mk/6J75arLhqcodWsdeG/M/moWgqQAnlZAGVtJI1O" +
            "geF5fsPpXu4kctOfuZlGjVZXQNW34aOzm8r8S0eVZitPlbhcPiR4gT/aSMz/wd8lZlzZYsje/Jr8" +
            "u/YtlwjjreZrGRmG8KMOzukV3lLmMppXFMvl4bxv6YFEmIuTsOhbLTwFgh7KYNjodLj/LsqRVfwz" +
            "31PgWQFTEPICV7GCvgVlPRxnofqKSjgTWI4mxDhBpVcATvaoBl1L/6WLbFvBsoAUBItWwctO2xal" +
            "KxF5szhGm8lccoc5MZr8kfE0uxMgsxz4er68iCID+rsCAQM="

    fun mode(raw: String): String = when (raw.trim()) {
        MODE_CDN -> MODE_CDN
        MODE_DIRECT -> MODE_DIRECT
        else -> MODE_AUTO
    }

    @OptIn(ExperimentalEncodingApi::class)
    fun build(profile: PsiphonProfile, dataDirPath: String, socksPort: Int, httpPort: Int): String {
        val region = profile.country.trim().uppercase()
        val config = buildJsonObject {
            put("PropagationChannelId", PROPAGATION_CHANNEL_ID)
            put("SponsorId", SPONSOR_ID)
            put("ClientVersion", "1")
            put("DataRootDirectory", dataDirPath)
            put("LocalSocksProxyPort", socksPort)
            put("LocalHttpProxyPort", httpPort)
            put("EmitDiagnosticNotices", true)
            put("EmitDiagnosticNetworkParameters", true)

            if (region.isNotEmpty()) put("EgressRegion", region)

            putJsonArray("RemoteServerListURLs") {
                addJsonObject {
                    put("URL", Base64.encode(SERVER_LIST_URL.encodeToByteArray()))
                }
            }
            put("RemoteServerListSignaturePublicKey", SERVER_LIST_SIGNATURE_KEY)

            put("InproxyTunnelProtocolPreferProbability", 0.0)
            put("InproxyTunnelProtocolSelectionProbability", 0.0)

            putCdnFronting(profile)

            when (mode(profile.mode)) {
                MODE_CDN -> {
                    putJsonArray("LimitTunnelProtocols") { CDN_PROTOCOLS.forEach { add(it) } }
                    put("DisableTactics", true)
                }
                MODE_DIRECT -> {
                    putJsonArray("LimitTunnelProtocols") { NON_INPROXY_PROTOCOLS.filterNot { it.startsWith("FRONTED-") }.forEach { add(it) } }
                    put("DisableTactics", true)
                }
                else -> putJsonArray("LimitTunnelProtocols") { NON_INPROXY_PROTOCOLS.forEach { add(it) } }
            }
        }
        return config.toString()
    }

    fun withUpstreamProxy(configJson: String, socksPort: Int): String {
        val root = Json.parseToJsonElement(configJson).jsonObject
        val filtered = (root["LimitTunnelProtocols"] as? JsonArray)
            ?.mapNotNull { it.jsonPrimitive.contentOrNull }
            ?.filterNot { it.contains("QUIC") }
            .orEmpty()
        return buildJsonObject {
            root.forEach { (k, v) -> if (k != "LimitTunnelProtocols") put(k, v) }
            put("UpstreamProxyURL", "socks5://127.0.0.1:$socksPort")
            if (filtered.isNotEmpty()) putJsonArray("LimitTunnelProtocols") { filtered.forEach { add(it) } }
        }.toString()
    }

    fun protocolsLostToUpstreamProxy(profile: PsiphonProfile): List<String> =
        when (mode(profile.mode)) {
            MODE_CDN -> CDN_PROTOCOLS
            else -> NON_INPROXY_PROTOCOLS
        }.filter { it.contains("QUIC") }

    private fun JsonObjectBuilder.putCdnFronting(profile: PsiphonProfile) {
        val addresses = ipCandidates(profile.cdnIps)
        val serverNames = sniCandidates(profile.cdnSni)
        if (addresses.isEmpty()) {
            put("FrontedMeekCDNScanUseBuiltInSpec", true)
        } else {
            put("FrontedMeekCDNScanSpec", buildJsonObject {
                putJsonArray("IPCandidates") { addresses.forEach { add(it) } }
                if (serverNames.isNotEmpty()) putJsonArray("SNIServerNames") { serverNames.forEach { add(it) } }
            })
        }
        val dialAddresses = addresses.ifEmpty { serverNames }
        if (dialAddresses.isEmpty() || serverNames.isEmpty()) return
        putJsonArray("FrontedMeekDialOverrides") {
            addJsonObject {
                put("OverrideID", "user")
                putJsonArray("MatchDialAddressRegexes") { add(".*") }
                putJsonArray("DialAddresses") { dialAddresses.forEach { add(it) } }
                put("SNIServerName", serverNames.first())
                putJsonArray("VerifyServerNames") { serverNames.forEach { add(it) } }
            }
        }
    }

    private val SEPARATORS = charArrayOf(' ', '\t', '\n', '\r', ',', ';')

    fun ipCandidates(raw: String): List<String> = raw.split(*SEPARATORS)
        .map { it.trim() }.filter { it.isNotEmpty() && (isIpv4(it) || isIpv4Cidr(it)) }.distinct()

    fun sniCandidates(raw: String): List<String> = raw.split(*SEPARATORS)
        .map { normalizeHostname(it) }.filter { it.isNotEmpty() }.distinct()

    private fun isIpv4(value: String): Boolean {
        val parts = value.split('.')
        if (parts.size != 4) return false
        return parts.all { p -> p.isNotEmpty() && p.length <= 3 && p.all { it.isDigit() } && (p.toIntOrNull() ?: 256) <= 255 }
    }

    private fun isIpv4Cidr(value: String): Boolean {
        val parts = value.split('/')
        if (parts.size != 2) return false
        return isIpv4(parts[0]) && parts[1].isNotEmpty() && parts[1].all { it.isDigit() } && (parts[1].toIntOrNull() ?: 33) <= 32
    }

    private fun normalizeHostname(raw: String): String {
        var v = raw.trim().lowercase()
        for (prefix in listOf("https://", "http://")) v = v.removePrefix(prefix)
        v = v.substringBefore('/').trim('.')
        if (v.isEmpty() || !v.contains('.')) return ""
        if (v.any { it.code > 127 || !(it.isLetterOrDigit() || it == '.' || it == '-') }) return ""
        return v
    }
}
