package com.whitedns.vpn

import androidx.annotation.StringRes

class ConnectButtonModel(initialState: VpnState = VpnState.Stopped) {
    var state: VpnState = initialState
        private set
    private var alwaysOn: Boolean = false

    fun onStateChanged(newState: VpnState, alwaysOn: Boolean = this.alwaysOn) {
        state = newState
        this.alwaysOn = alwaysOn
    }

    @StringRes
    fun labelRes(): Int {
        if (state == VpnState.Started && alwaysOn) return R.string.connect_action_always_on
        return when (state) {
            VpnState.Starting -> R.string.connect_action_connecting
            VpnState.Started -> R.string.connect_action_disconnect
            VpnState.Stopping -> R.string.connect_action_disconnecting
            VpnState.DailyLimitReached, is VpnState.Error -> R.string.connect_action_retry
            VpnState.Stopped -> R.string.connect_action_connect
        }
    }

    fun isEnabled(): Boolean {
        return state != VpnState.Stopping &&
            !(alwaysOn && (state == VpnState.Starting || state == VpnState.Started))
    }

    fun nextAction(): String? {
        if (alwaysOn && (state == VpnState.Starting || state == VpnState.Started)) return null
        return when (state) {
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
}
