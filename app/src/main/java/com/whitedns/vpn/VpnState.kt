package com.whitedns.vpn

sealed class VpnState(val wireName: String) {
    data object Stopped : VpnState("Stopped")
    data object Starting : VpnState("Starting")
    data object Started : VpnState("Started")
    data object Stopping : VpnState("Stopping")
    data object DailyLimitReached : VpnState("DailyLimitReached")
    data class Error(val message: String) : VpnState("Error")

    companion object {
        fun fromWireName(wireName: String?, message: String? = null): VpnState = when (wireName) {
            Starting.wireName -> Starting
            Started.wireName -> Started
            Stopping.wireName -> Stopping
            DailyLimitReached.wireName -> DailyLimitReached
            Error("").wireName -> Error(message.orEmpty())
            else -> Stopped
        }
    }
}

enum class ServiceStopAction {
    StartupFailure,
    StopVpn,
    Ignore,
}

object ServiceStopDecision {
    fun forState(state: VpnState): ServiceStopAction = when (state) {
        VpnState.Starting -> ServiceStopAction.StartupFailure
        VpnState.Started -> ServiceStopAction.StopVpn
        VpnState.Stopping,
        VpnState.Stopped,
        VpnState.DailyLimitReached,
        is VpnState.Error,
        -> ServiceStopAction.Ignore
    }
}
