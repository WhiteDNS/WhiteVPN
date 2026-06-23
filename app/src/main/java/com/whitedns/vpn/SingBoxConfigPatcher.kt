package com.whitedns.vpn

import org.json.JSONObject

data class ProfileProbeConfig(
    val config: String,
    val groupTag: String,
)

data class RuntimeConfigDiagnostics(
    val routeAutoDetectInterface: String,
    val defaultDomainResolver: String,
    val dnsDetours: List<String>,
)

object SingBoxConfigPatcher {
    fun runtimeDiagnostics(config: String): RuntimeConfigDiagnostics {
        val root = JSONObject(config)
        val route = root.optJSONObject("route")
        val resolver = route?.opt("default_domain_resolver")
        val resolverTag = when (resolver) {
            is JSONObject -> resolver.optString("server")
            is String -> resolver
            else -> ""
        }
        return RuntimeConfigDiagnostics(
            routeAutoDetectInterface = route?.opt("auto_detect_interface")?.toString().orEmpty(),
            defaultDomainResolver = resolverTag,
            dnsDetours = dnsDetours(root.optJSONObject("dns")),
        )
    }

    private fun dnsDetours(dns: JSONObject?): List<String> {
        val servers = dns?.optJSONArray("servers") ?: return emptyList()
        val values = mutableListOf<String>()
        for (index in 0 until servers.length()) {
            val server = servers.optJSONObject(index) ?: continue
            val tag = server.optString("tag").ifBlank { "<untagged-$index>" }
            values += "$tag->${server.optString("detour").ifBlank { "direct" }}"
        }
        return values
    }
}
