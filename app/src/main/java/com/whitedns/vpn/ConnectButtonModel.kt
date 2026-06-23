package com.whitedns.vpn

class ConnectButtonModel(initialState: VpnState = VpnState.Stopped) {
    var state: VpnState = initialState
        private set

    fun onStateChanged(newState: VpnState) {
        state = newState
    }

    fun label(): String = when (state) {
        VpnState.Starting -> "Connecting..."
        VpnState.Started -> "Disconnect"
        VpnState.Stopping -> "Disconnecting..."
        VpnState.DailyLimitReached,
        is VpnState.Error,
        VpnState.Stopped,
        -> "Connect"
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
