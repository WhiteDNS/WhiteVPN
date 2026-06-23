package com.whitedns.vpn

import android.content.Context

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
            .onEach { ip ->
                require(ip.isNotBlank()) { "Fronting IPs must be comma-separated IPv4 addresses" }
                require(ip.none(Char::isWhitespace)) { "Fronting IPs must be comma-separated IPv4 addresses" }
                require(isValidIpv4(ip)) { "Fronting IP must be a valid IPv4 address" }
            }
            .distinct()
        require(ips.size <= MAX_FRONTING_IPS) { "Fronting IP accepts up to $MAX_FRONTING_IPS IPv4 addresses" }
        return ips
    }

    private fun isValidIpv4(value: String): Boolean {
        val octets = value.split(".")
        return octets.size == 4 && octets.all { octet ->
            octet.length in 1..3 && octet.all(Char::isDigit) && octet.toInt() in 0..255
        }
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
