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
        require(ips.size <= MAX_FRONTING_IPS) { "Fronting IP accepts up to $MAX_FRONTING_IPS endpoints" }
        return ips
    }

    fun parseEndpoint(value: String): FrontingEndpoint {
        val endpoint = value.trim()
        require(endpoint.isNotBlank() && endpoint.none(Char::isWhitespace)) {
            "Fronting IPs must be comma-separated IP[:port] endpoints"
        }

        val (ip, portText) = when {
            endpoint.startsWith('[') -> {
                val closingBracket = endpoint.indexOf(']')
                require(closingBracket > 1) { "Invalid fronting IPv6 endpoint" }
                val suffix = endpoint.substring(closingBracket + 1)
                require(suffix.isEmpty() || suffix.startsWith(':')) { "Invalid fronting IPv6 endpoint" }
                require(suffix != ":") { "Fronting port must be a number from 1 to 65535" }
                endpoint.substring(1, closingBracket) to suffix.removePrefix(":").takeIf(String::isNotEmpty)
            }
            endpoint.count { it == ':' } == 1 -> {
                val port = endpoint.substringAfter(':')
                require(port.isNotEmpty()) { "Fronting port must be a number from 1 to 65535" }
                endpoint.substringBefore(':') to port
            }
            else -> endpoint to null
        }

        require(isValidIpv4(ip) || isValidIpv6(ip)) { "Fronting IP must be a valid IPv4 or IPv6 address" }
        val port = portText?.let {
            require(it.all(Char::isDigit)) { "Fronting port must be a number from 1 to 65535" }
            it.toIntOrNull()?.also { parsed ->
                require(parsed in 1..65535) { "Fronting port must be a number from 1 to 65535" }
            } ?: throw IllegalArgumentException("Fronting port must be a number from 1 to 65535")
        }
        require(!ip.contains(':') || endpoint.startsWith('[') || port == null) {
            "IPv6 endpoints with a port must use [IPv6]:port"
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
