package com.whitedns.vpn

class ConnectButtonModel(initialState: VpnState = VpnState.Stopped) {
    var state: VpnState = initialState
        private set

    fun onStateChanged(newState: VpnState) {
        state = newState
    }

    fun label(): String = when (state) {
        VpnState.Starting -> "در حال اتصال…"
        VpnState.Started -> "قطع اتصال"
        VpnState.Stopping -> "در حال قطع اتصال…"
        VpnState.DailyLimitReached, is VpnState.Error -> "تلاش دوباره"
        VpnState.Stopped -> "اتصال"
    }

    fun isEnabled(): Boolean = state != VpnState.Stopping

    fun nextAction(): String? = when (state) {
        VpnState.Starting,
        VpnState.Started -> Actions.DISCONNECT
        VpnState.Stopped,
        VpnState.DailyLimitReached,
        is VpnState.Error,
        -> Actions.CONNECT
        VpnState.Stopping,
        -> null
    }
}
