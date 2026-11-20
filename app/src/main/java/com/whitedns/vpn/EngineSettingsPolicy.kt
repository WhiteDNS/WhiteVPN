package com.whitedns.vpn

/** Which saved preferences actually participate in the selected route. */
internal data class EngineSettingsPolicy(
    val routing: Boolean, val dns: Boolean, val apps: Boolean, val sharing: Boolean,
    val proxy: Boolean, val chains: Boolean, val location: Boolean,
    val fronting: Boolean, val tlsIntegrity: Boolean, val mihomoNoise: Boolean,
) {
    companion object {
        fun forRoute(kind: EngineKind?, hasWireGuard: Boolean = true): EngineSettingsPolicy {
            if (kind == null) return EngineSettingsPolicy(true, true, true, true, true, true, true, true, true, hasWireGuard)
            val caps = EngineCapabilities.forKind(kind)
            return EngineSettingsPolicy(caps.destinationRules, caps.customDns, caps.appSplitTunnel, caps.lanSharing,
                caps.proxyAccess, false, false, false, false, false)
        }
    }
}
