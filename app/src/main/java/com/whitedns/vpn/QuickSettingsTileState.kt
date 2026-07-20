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
            subtitle = "متصل",
            visualState = QuickSettingsTileVisualState.Active,
        )
        VpnState.Starting -> QuickSettingsTilePresentation(
            label = "WhiteDNS",
            subtitle = "در حال اتصال",
            visualState = QuickSettingsTileVisualState.Unavailable,
        )
        VpnState.Stopping -> QuickSettingsTilePresentation(
            label = "WhiteDNS",
            subtitle = "در حال قطع اتصال",
            visualState = QuickSettingsTileVisualState.Unavailable,
        )
        is VpnState.Error -> QuickSettingsTilePresentation(
            label = "WhiteDNS",
            subtitle = "خطا",
            visualState = QuickSettingsTileVisualState.Inactive,
        )
        VpnState.DailyLimitReached -> QuickSettingsTilePresentation(
            label = "WhiteDNS",
            subtitle = "قطع اتصال",
            visualState = QuickSettingsTileVisualState.Inactive,
        )
        VpnState.Stopped -> QuickSettingsTilePresentation(
            label = "WhiteDNS",
            subtitle = "قطع اتصال",
            visualState = QuickSettingsTileVisualState.Inactive,
        )
    }
}
