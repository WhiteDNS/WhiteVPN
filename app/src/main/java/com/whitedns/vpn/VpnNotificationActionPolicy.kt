package com.whitedns.vpn

object VpnNotificationActionPolicy {
    fun actionsFor(state: VpnState): List<String> = when (state) {
        VpnState.Starting -> listOf(Actions.DISCONNECT)
        VpnState.Started -> listOf(Actions.DISCONNECT, Actions.RECONNECT)
        VpnState.Stopping,
        VpnState.Stopped,
        VpnState.DailyLimitReached,
        is VpnState.Error,
        -> emptyList()
    }
}
