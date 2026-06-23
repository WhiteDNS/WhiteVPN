package com.whitedns.vpn

enum class QuickSettingsTileVisualState {
    Active,
    Inactive,
    Unavailable,
}

data class QuickSettingsTilePresentation(
    val label: String,
    val subtitle: String,
    val visualState: QuickSettingsTileVisualState,
)

object QuickSettingsTileStateMapper {
    fun presentationFor(state: VpnState): QuickSettingsTilePresentation = when (state) {
        VpnState.Started -> QuickSettingsTilePresentation(
            label = "WhiteDNS",
            subtitle = "Connected",
            visualState = QuickSettingsTileVisualState.Active,
        )
        VpnState.Starting -> QuickSettingsTilePresentation(
            label = "WhiteDNS",
            subtitle = "Connecting",
            visualState = QuickSettingsTileVisualState.Unavailable,
        )
        VpnState.Stopping -> QuickSettingsTilePresentation(
            label = "WhiteDNS",
            subtitle = "Disconnecting",
            visualState = QuickSettingsTileVisualState.Unavailable,
        )
        is VpnState.Error -> QuickSettingsTilePresentation(
            label = "WhiteDNS",
            subtitle = "Error",
            visualState = QuickSettingsTileVisualState.Inactive,
        )
        VpnState.DailyLimitReached -> QuickSettingsTilePresentation(
            label = "WhiteDNS",
            subtitle = "Disconnected",
            visualState = QuickSettingsTileVisualState.Inactive,
        )
        VpnState.Stopped -> QuickSettingsTilePresentation(
            label = "WhiteDNS",
            subtitle = "Disconnected",
            visualState = QuickSettingsTileVisualState.Inactive,
        )
    }
}
