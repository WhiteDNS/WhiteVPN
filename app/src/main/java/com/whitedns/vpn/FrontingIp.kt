package com.whitedns.vpn

import android.content.Context
import java.net.Inet6Address
import java.net.InetAddress

data class FrontingEndpoint(val ip: String, val port: Int?) {
    fun encoded(): String {
        if (port == null) return ip
        val host = if (ip.contains(':')) "[$ip]" else ip
        return "$host:$port"
    }
}

object FrontingIpPolicy {
    fun normalize(value: String?): String? {
        return normalizeIps(value).takeIf { it.isNotEmpty() }?.joinToString(",")
    }

    fun normalizeIps(value: String?): List<String> {
        val trimmed = value?.trim().orEmpty()
        if (trimmed.isBlank()) return emptyList()
        val ips = trimmed
            .split(",")
            .map { it.trim() }
            .map(::parseEndpoint)
            .map(FrontingEndpoint::encoded)
            .distinct()
        require(ips.size <= MAX_FRONTING_IPS) { "IP Fronting حداکثر $MAX_FRONTING_IPS آدرس می‌پذیرد" }
        return ips
    }

    fun parseEndpoint(value: String): FrontingEndpoint {
        val endpoint = value.trim()
        require(endpoint.isNotBlank() && endpoint.none(Char::isWhitespace)) {
            "آدرس‌های IP Fronting باید به‌شکل IP[:port] و با ویرگول جدا شوند"
        }

        val (ip, portText) = when {
            endpoint.startsWith('[') -> {
                val closingBracket = endpoint.indexOf(']')
                require(closingBracket > 1) { "آدرس IPv6 برای IP Fronting نامعتبر است" }
                val suffix = endpoint.substring(closingBracket + 1)
                require(suffix.isEmpty() || suffix.startsWith(':')) { "آدرس IPv6 برای IP Fronting نامعتبر است" }
                require(suffix != ":") { "پورت IP Fronting باید عددی بین 1 تا 65535 باشد" }
                endpoint.substring(1, closingBracket) to suffix.removePrefix(":").takeIf(String::isNotEmpty)
            }
            endpoint.count { it == ':' } == 1 -> {
                val port = endpoint.substringAfter(':')
                require(port.isNotEmpty()) { "پورت IP Fronting باید عددی بین 1 تا 65535 باشد" }
                endpoint.substringBefore(':') to port
            }
            else -> endpoint to null
        }

        require(isValidIpv4(ip) || isValidIpv6(ip)) { "IP Fronting باید یک آدرس معتبر IPv4 یا IPv6 باشد" }
        val port = portText?.let {
            require(it.all(Char::isDigit)) { "پورت IP Fronting باید عددی بین 1 تا 65535 باشد" }
            it.toIntOrNull()?.also { parsed ->
                require(parsed in 1..65535) { "پورت IP Fronting باید عددی بین 1 تا 65535 باشد" }
            } ?: throw IllegalArgumentException("پورت IP Fronting باید عددی بین 1 تا 65535 باشد")
        }
        require(!ip.contains(':') || endpoint.startsWith('[') || port == null) {
            "آدرس IPv6 دارای پورت باید به‌شکل [IPv6]:port باشد"
        }
        return FrontingEndpoint(ip, port)
    }

    fun matchingValue(values: List<String>, ip: String, port: Int): String? {
        val endpoints = values.map { it to parseEndpoint(it) }
        return endpoints.firstOrNull { (_, endpoint) ->
            endpoint.ip == ip && endpoint.port == port
        }?.first ?: endpoints.firstOrNull { (_, endpoint) ->
            endpoint.ip == ip && endpoint.port == null
        }?.first
    }

    fun explicitPortFor(values: List<String>, ip: String, port: Int): Int? {
        return matchingValue(values, ip, port)?.let(::parseEndpoint)?.port
    }

    private fun isValidIpv4(value: String): Boolean {
        val octets = value.split(".")
        return octets.size == 4 && octets.all { octet ->
            octet.length in 1..3 && octet.all(Char::isDigit) && octet.toInt() in 0..255
        }
    }

    private fun isValidIpv6(value: String): Boolean {
        if (!value.contains(':')) return false
        return runCatching { InetAddress.getByName(value) is Inet6Address }.getOrDefault(false)
    }

    private const val MAX_FRONTING_IPS = 5
}

class FrontingIpPreferenceStore(context: Context) {
    private val prefs = context.getSharedPreferences("white_dns_fronting_ip", Context.MODE_PRIVATE)

    fun readFrontingIp(): String? {
        return runCatching { FrontingIpPolicy.normalize(prefs.getString(KEY_FRONTING_IP, null)) }.getOrNull()
    }

    fun readFrontingIps(): List<String> {
        return runCatching { FrontingIpPolicy.normalizeIps(prefs.getString(KEY_FRONTING_IP, null)) }.getOrDefault(emptyList())
    }

    fun saveFrontingIp(value: String?) {
        val normalized = FrontingIpPolicy.normalize(value)
        val editor = prefs.edit()
        if (normalized == null) {
            editor.remove(KEY_FRONTING_IP)
        } else {
            editor.putString(KEY_FRONTING_IP, normalized)
        }
        editor.apply()
    }

    private companion object {
        const val KEY_FRONTING_IP = "fronting_ip"
    }
}
