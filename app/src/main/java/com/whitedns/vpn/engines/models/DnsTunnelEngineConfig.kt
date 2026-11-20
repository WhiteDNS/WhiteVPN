// Adapted from ZedSecure, revision 2c01da26bed98b883503850df1b7f793d28f7f54.
package com.whitedns.vpn.engines.models

import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

object DnsTunnelEngineConfig {
    data class Via(val address: String, val user: String = "", val pass: String = "")

    fun build(profile: DnsTunnelProfile, listen: String, resolvers: String, via: Via? = null): String =
        buildJsonObject {
            put("listen", listen)
            put("domain", profile.domain.trim())
            put("serverKey", profile.publicKey.trim())
            put("resolvers", buildJsonArray {
                resolvers.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { add(JsonPrimitive(it)) }
            })
            put("strategy", if (profile.resolverMode == DnsTunnelProfile.MODE_ROUND_ROBIN) "rotate" else "all")
            put("perQuery", profile.rrSpreadCount)
            put("wire", if (!profile.isVaydns || profile.dnsttCompat) "dnstt" else "vaydns")
            if (profile.socksUser.isNotEmpty()) {
                putJsonObject("exitAuth") {
                    put("user", profile.socksUser)
                    put("pass", profile.socksPass)
                }
            }
            if (via != null) {
                putJsonObject("via") {
                    put("address", via.address)
                    put("user", via.user)
                    put("pass", via.pass)
                }
            }
            if (profile.isVaydns) {
                put("record", profile.recordType)
                if (profile.maxQnameLen != DEFAULT_NAME_LIMIT) put("nameLimit", profile.maxQnameLen)
                if (profile.rps > 0) put("queriesPerSecond", profile.rps)
                if (profile.idleTimeout > 0) put("idleSeconds", profile.idleTimeout)
                if (profile.keepalive > 0) put("keepaliveSeconds", profile.keepalive)
                if (profile.udpTimeout > 0) put("resolverTimeoutMs", profile.udpTimeout)
                if (profile.maxNumLabels > 0) put("labelLimit", profile.maxNumLabels)
                if (profile.clientIdSize > 0) put("idBytes", profile.clientIdSize)
            } else {
                put("authoritative", profile.authoritative)
                if (profile.dnsPayloadSize > 0) put("payloadLimit", profile.dnsPayloadSize)
            }
        }.toString()

    private const val DEFAULT_NAME_LIMIT = 101
}
