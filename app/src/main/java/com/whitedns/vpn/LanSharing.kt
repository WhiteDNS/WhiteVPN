package com.whitedns.vpn

import android.content.Context
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.SecureRandom
import java.util.Base64
import java.util.Collections

data class LanSharingSettings(
    val enabled: Boolean = false,
    val passwordRequired: Boolean = true,
    val username: String = USERNAME,
    val password: String = "",
) {
    companion object {
        const val USERNAME = "whitedns"
    }
}

class LanSharingPreferenceStore(context: Context) {
    private val prefs = context.getSharedPreferences("white_dns_lan_sharing", Context.MODE_PRIVATE)

    fun read(): LanSharingSettings = LanSharingSettings(
        enabled = prefs.getBoolean(KEY_ENABLED, false),
        passwordRequired = prefs.getBoolean(KEY_PASSWORD_REQUIRED, true),
        password = password(),
    )

    fun saveEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun savePasswordRequired(required: Boolean) {
        prefs.edit().putBoolean(KEY_PASSWORD_REQUIRED, required).apply()
    }

    fun regeneratePassword(): String = LanSharingPassword.generate().also { password ->
        prefs.edit().putString(KEY_PASSWORD, password).apply()
    }

    private fun password(): String {
        prefs.getString(KEY_PASSWORD, null)?.takeIf { it.isNotBlank() }?.let { return it }
        return regeneratePassword()
    }

    private companion object {
        const val KEY_ENABLED = "enabled"
        const val KEY_PASSWORD_REQUIRED = "password_required"
        const val KEY_PASSWORD = "password"
    }
}

internal object LanSharingPassword {
    fun generate(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(18)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }
}

object LanSharingAddresses {
    private const val WHITEVPN_TUN_ADDRESS = "172.19.0.1"
    private val lanInterfacePrefixes =
        listOf("wlan", "ap", "softap", "swlan", "eth", "rndis", "usb", "bt-pan", "bnep")

    fun reachablePrivateIpv4Addresses(): List<String> {
        return runCatching {
            Collections.list(NetworkInterface.getNetworkInterfaces())
                .asSequence()
                .filter { it.isUp && !it.isLoopback && !it.isVirtual }
                .filter { network -> lanInterfacePrefixes.any(network.name.lowercase()::startsWith) }
                .flatMap { Collections.list(it.inetAddresses).asSequence() }
                .filterIsInstance<Inet4Address>()
                .filterNot { it.isLoopbackAddress || it.isLinkLocalAddress }
                .mapNotNull(Inet4Address::getHostAddress)
                .filter { it != WHITEVPN_TUN_ADDRESS && isPrivateIpv4(it) }
                .distinct()
                .sorted()
                .toList()
        }.getOrDefault(emptyList())
    }

    internal fun isPrivateIpv4(address: String): Boolean {
        val octets = address.split('.').mapNotNull(String::toIntOrNull)
        if (octets.size != 4 || octets.any { it !in 0..255 }) return false
        return octets[0] == 10 ||
            octets[0] == 127 ||
            (octets[0] == 172 && octets[1] in 16..31) ||
            (octets[0] == 192 && octets[1] == 168) ||
            (octets[0] == 100 && octets[1] in 64..127)
    }
}
