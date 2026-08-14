package com.whitedns.vpn

object VpnNotificationActionPolicy {
    fun actionsFor(state: VpnState, disconnectAllowed: Boolean = true): List<String> = when (state) {
        VpnState.Starting -> if (disconnectAllowed) listOf(Actions.DISCONNECT) else emptyList()
        VpnState.Started -> if (disconnectAllowed) {
            listOf(Actions.DISCONNECT, Actions.RECONNECT)
        } else {
            listOf(Actions.RECONNECT)
        }
        VpnState.Stopping,
        VpnState.Stopped,
        VpnState.DailyLimitReached,
        is VpnState.Error,
        -> emptyList()
    }
}
