package com.whitedns.vpn

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

object LibboxNetworkAddressFormatter {
    fun format(address: InetAddress?, prefixLength: Short): String? {
        if (address == null) return null

        val maxPrefix = when (address) {
            is Inet4Address -> 32
            is Inet6Address -> 128
            else -> return null
        }
        val prefix = prefixLength.toInt()
        if (prefix !in 0..maxPrefix) return null

        val host = address.hostAddress
            ?.substringBefore('%')
            ?.takeIf(String::isNotBlank)
            ?: return null

        return "$host/$prefix"
    }
}
