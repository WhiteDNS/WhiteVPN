package com.whitedns.vpn

import org.json.JSONArray
import org.json.JSONObject

object SubscriptionSingBoxConfigBuilder {
    private const val GENERATED_PROFILE_PROBE_GROUP = "__whitedns_profile_probe"
    private const val PROFILE_TEST_URL = "https://www.gstatic.com/generate_204"
    private const val REMOTE_DNS_TAG = "dns-remote"
    private const val DIRECT_DNS_TAG = "dns-direct"
    private const val DNS_SERVER = "8.8.8.8"
    private const val DIRECT_TAG = "direct"
    private const val SELECTOR_TAG = "selector"
    private const val MIXED_PROXY_PORT = 2080
    private const val ANDROID_TUN_MTU = 1280

    fun profileProbeConfig(
        profiles: List<ConnectionProfile>,
        serverOverrideIp: String? = null,
    ): ProfileProbeConfig {
        require(profiles.isNotEmpty()) { "Probe profile chunk is empty" }
        val outbounds = JSONArray()
        profiles.forEach { profile ->
            outbounds.put(profile.outboundObject(serverOverrideIp))
        }
        outbounds.put(JSONObject().put("type", DIRECT_TAG).put("tag", DIRECT_TAG))
        outbounds.put(
            JSONObject()
                .put("type", "urltest")
                .put("tag", GENERATED_PROFILE_PROBE_GROUP)
                .put("outbounds", JSONArray(profiles.map { it.tag }))
                .put("url", PROFILE_TEST_URL)
                .put("interval", "30m")
                .put("interrupt_exist_connections", false),
        )

        return ProfileProbeConfig(
            config = baseRoot(
                inbounds = JSONArray(),
                outbounds = outbounds,
                finalOutbound = GENERATED_PROFILE_PROBE_GROUP,
                proxiedDnsOutbound = null,
            )
                .toString(),
            groupTag = GENERATED_PROFILE_PROBE_GROUP,
        )
    }

    fun runtimeConfig(
        profile: ConnectionProfile,
        serverOverrideIp: String? = null,
        runtimeMode: RuntimeCompatibilityMode = RuntimeCompatibilityMode.Compatible,
    ): String {
        return runtimeRoot(
            selectedProfile = profile,
            serverOverrideIp = serverOverrideIp,
            runtimeMode = runtimeMode,
        ).toString().also(SingBoxConfigValidator::requireValid)
    }

    private fun runtimeRoot(
        selectedProfile: ConnectionProfile,
        serverOverrideIp: String?,
        runtimeMode: RuntimeCompatibilityMode,
    ): JSONObject {
        val profile = selectedProfile
        val selectedOutbound = profile.outboundObject(serverOverrideIp)
        val profileOutbounds = listOf(selectedOutbound)

        val outbounds = JSONArray()
        profileOutbounds.forEach { outbounds.put(it) }
        outbounds
            .put(
                JSONObject()
                    .put("type", "selector")
                    .put("tag", SELECTOR_TAG)
                    .put("outbounds", JSONArray(profileOutbounds.map { it.optString("tag") }))
                    .put("default", profile.tag),
            )
            .put(JSONObject().put("type", DIRECT_TAG).put("tag", DIRECT_TAG))

        val inbounds = JSONArray()
            .put(
                JSONObject()
                    .put("type", "tun")
                    .put("tag", "tun-in")
                    .put("address", JSONArray(listOf("172.19.0.1/30", "fdfe:dcba:9876::1/126")))
                    .put("auto_route", true)
                    .put("strict_route", true)
                    .put("mtu", ANDROID_TUN_MTU),
            )
            .put(
                JSONObject()
                    .put("type", "mixed")
                    .put("tag", "mixed-in")
                    .put("listen", "127.0.0.1")
                    .put("listen_port", MIXED_PROXY_PORT),
            )

        return baseRoot(
            inbounds = inbounds,
            outbounds = outbounds,
            finalOutbound = SELECTOR_TAG,
            proxiedDnsOutbound = SELECTOR_TAG,
            runtimeMode = runtimeMode,
        )
    }

    private fun baseRoot(
        inbounds: JSONArray,
        outbounds: JSONArray,
        finalOutbound: String,
        proxiedDnsOutbound: String?,
        runtimeMode: RuntimeCompatibilityMode = RuntimeCompatibilityMode.Compatible,
    ): JSONObject {
        val dnsServers = JSONArray()
        if (proxiedDnsOutbound != null) {
            dnsServers.put(
                JSONObject()
                    .put("tag", REMOTE_DNS_TAG)
                    .put("type", "tcp")
                    .put("server", DNS_SERVER)
                    .put("detour", proxiedDnsOutbound),
            )
        }
        dnsServers.put(
            JSONObject()
                .put("tag", DIRECT_DNS_TAG)
                .put("type", "udp")
                .put("server", DNS_SERVER),
        )

        val dns = JSONObject()
            .put("strategy", "prefer_ipv4")
            .put("servers", dnsServers)
        if (proxiedDnsOutbound != null) {
            dns.put("final", REMOTE_DNS_TAG)
        }

        val route = JSONObject()
            .put("auto_detect_interface", true)
            .put(
                "default_domain_resolver",
                JSONObject()
                    .put("server", DIRECT_DNS_TAG)
                    .put("strategy", "prefer_ipv4")
                    .put("rewrite_ttl", 60),
            )
            .put("final", finalOutbound)
        if (proxiedDnsOutbound != null) {
            val rules = JSONArray()
            if (runtimeMode == RuntimeCompatibilityMode.Compatible) {
                rules.put(
                    JSONObject()
                        .put("network", JSONArray(listOf("udp")))
                        .put("port", JSONArray(listOf(443)))
                        .put("action", "reject")
                        .put("method", "default")
                        .put("no_drop", true),
                )
            }
            rules
                .put(
                    JSONObject()
                        .put("port", JSONArray(listOf(53)))
                        .put("action", "hijack-dns"),
                )
                .put(
                    JSONObject()
                        .put("protocol", JSONArray(listOf("dns")))
                        .put("action", "hijack-dns"),
                )
            route.put(
                "rules",
                rules,
            )
        }

        return JSONObject()
            .put("log", JSONObject().put("level", "warn"))
            .put("inbounds", inbounds)
            .put("outbounds", outbounds)
            .put("route", route)
            .put("dns", dns)
    }

    private fun ConnectionProfile.outboundObject(serverOverrideIp: String? = null): JSONObject {
        return JSONObject(outboundJson ?: error("Generated subscription profile is missing outbound JSON: $tag"))
            .also { outbound ->
                if (!serverOverrideIp.isNullOrBlank()) {
                    outbound.put("server", serverOverrideIp)
                }
            }
    }
}
