package com.whitedns.vpn

import androidx.annotation.StringRes

enum class QuickSettingsTileVisualState {
    Active,
    Inactive,
    Unavailable,
}

data class QuickSettingsTilePresentation(
    @param:StringRes val labelRes: Int,
    @param:StringRes val subtitleRes: Int,
    val visualState: QuickSettingsTileVisualState,
)

object QuickSettingsTileStateMapper {
    fun presentationFor(state: VpnState): QuickSettingsTilePresentation = when (state) {
        VpnState.Started -> QuickSettingsTilePresentation(
            labelRes = R.string.app_name,
            subtitleRes = R.string.tile_connected,
            visualState = QuickSettingsTileVisualState.Active,
        )
        VpnState.Starting -> QuickSettingsTilePresentation(
            labelRes = R.string.app_name,
            subtitleRes = R.string.tile_connecting,
            visualState = QuickSettingsTileVisualState.Unavailable,
        )
        VpnState.Stopping -> QuickSettingsTilePresentation(
            labelRes = R.string.app_name,
            subtitleRes = R.string.tile_disconnecting,
            visualState = QuickSettingsTileVisualState.Unavailable,
        )
        is VpnState.Error -> QuickSettingsTilePresentation(
            labelRes = R.string.app_name,
            subtitleRes = R.string.tile_error,
            visualState = QuickSettingsTileVisualState.Inactive,
        )
        VpnState.DailyLimitReached -> QuickSettingsTilePresentation(
            labelRes = R.string.app_name,
            subtitleRes = R.string.tile_disconnected,
            visualState = QuickSettingsTileVisualState.Inactive,
        )
        VpnState.Stopped -> QuickSettingsTilePresentation(
            labelRes = R.string.app_name,
            subtitleRes = R.string.tile_disconnected,
            visualState = QuickSettingsTileVisualState.Inactive,
        )
    }
}
