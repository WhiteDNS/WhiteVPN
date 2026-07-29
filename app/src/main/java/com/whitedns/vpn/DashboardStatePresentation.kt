package com.whitedns.vpn

import androidx.annotation.StringRes

enum class DashboardTone {
    Neutral,
    Progress,
    Connected,
    Error,
}

data class DashboardStatePresentation(
    @param:StringRes val titleRes: Int,
    val tone: DashboardTone,
    val showProgress: Boolean,
)

object DashboardStatePresenter {
    fun forState(state: VpnState): DashboardStatePresentation = when (state) {
        VpnState.Stopped -> DashboardStatePresentation(
            titleRes = R.string.state_ready,
            tone = DashboardTone.Neutral,
            showProgress = false,
        )
        VpnState.Starting -> DashboardStatePresentation(
            titleRes = R.string.state_connecting,
            tone = DashboardTone.Progress,
            showProgress = true,
        )
        VpnState.Started -> DashboardStatePresentation(
            titleRes = R.string.state_connected,
            tone = DashboardTone.Connected,
            showProgress = false,
        )
        VpnState.Stopping -> DashboardStatePresentation(
            titleRes = R.string.state_disconnecting,
            tone = DashboardTone.Progress,
            showProgress = true,
        )
        VpnState.DailyLimitReached -> DashboardStatePresentation(
            titleRes = R.string.state_daily_limit,
            tone = DashboardTone.Neutral,
            showProgress = false,
        )
        is VpnState.Error -> DashboardStatePresentation(
            titleRes = R.string.state_connection_error,
            tone = DashboardTone.Error,
            showProgress = false,
        )
    }
}

object ConnectionDetailsPresenter {
    fun forDashboard(
        selectedSource: String,
        runtimeDetails: String,
        stringFor: (Int) -> String = ::englishString,
    ): String = buildList {
        add(stringFor(R.string.connection_detail_source).format(selectedSource))
        if (runtimeDetails.isNotBlank()) add(runtimeDetails)
    }.joinToString("\n")

    fun forProfile(
        profile: ConnectionProfile,
        showServer: Boolean = false,
        stringFor: (Int) -> String = ::englishString,
    ): String {
        val normalizedType = profile.type.lowercase()
        val outbound = when (normalizedType) {
            "mihomo-group" -> "Mihomo"
            "wireguard" -> "WireGuard"
            "vless" -> "VLESS"
            "vmess" -> "VMess"
            "ss" -> "Shadowsocks"
            "trojan" -> "Trojan"
            else -> profile.type
        }
        val ech = when {
            normalizedType == "wireguard" -> stringFor(R.string.connection_detail_ech_not_applicable)
            normalizedType == "mihomo-group" -> stringFor(R.string.connection_detail_ech_unknown)
            !profile.echCapable -> stringFor(R.string.connection_detail_ech_not_applicable)
            profile.echEnabled -> stringFor(R.string.connection_detail_ech_enabled)
            else -> stringFor(R.string.connection_detail_ech_disabled)
        }
        return buildList {
            add(stringFor(R.string.connection_detail_outbound).format(outbound))
            if (showServer) add(truncateServer(profile.server))
            profile.amneziaNoise?.let {
                add(stringFor(R.string.connection_detail_amnezia).format(it.count, it.minSize, it.maxSize))
            }
            add(ech)
        }.joinToString("  •  ")
    }

    private fun englishString(@StringRes id: Int): String = when (id) {
        R.string.connection_detail_source -> "Selected · %1\$s"
        R.string.connection_detail_outbound -> "%1\$s outbound"
        R.string.connection_detail_ech_not_applicable -> "ECH not applicable"
        R.string.connection_detail_ech_unknown -> "ECH unknown"
        R.string.connection_detail_ech_enabled -> "ECH enabled"
        R.string.connection_detail_ech_disabled -> "ECH disabled"
        R.string.connection_detail_amnezia -> "Amnezia %1\$d×%2\$d–%3\$d B"
        else -> error("Unsupported connection detail string: $id")
    }

    private fun truncateServer(server: String, maxLength: Int = 24): String =
        server.trim().let { value ->
            if (value.length <= maxLength) value else value.take(maxLength - 1) + "…"
        }
}
