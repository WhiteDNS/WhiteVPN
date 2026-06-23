package com.whitedns.vpn

enum class DashboardTone {
    Neutral,
    Progress,
    Connected,
    Error,
}

data class DashboardStatePresentation(
    val title: String,
    val tone: DashboardTone,
    val showProgress: Boolean,
)

object DashboardStatePresenter {
    fun forState(state: VpnState): DashboardStatePresentation = when (state) {
        VpnState.Stopped -> DashboardStatePresentation(
            title = "No signal",
            tone = DashboardTone.Neutral,
            showProgress = false,
        )
        VpnState.Starting -> DashboardStatePresentation(
            title = "Searching...",
            tone = DashboardTone.Progress,
            showProgress = true,
        )
        VpnState.Started -> DashboardStatePresentation(
            title = "Connected",
            tone = DashboardTone.Connected,
            showProgress = false,
        )
        VpnState.Stopping -> DashboardStatePresentation(
            title = "Disconnecting",
            tone = DashboardTone.Progress,
            showProgress = true,
        )
        VpnState.DailyLimitReached -> DashboardStatePresentation(
            title = "No signal",
            tone = DashboardTone.Neutral,
            showProgress = false,
        )
        is VpnState.Error -> DashboardStatePresentation(
            title = "Connection error",
            tone = DashboardTone.Error,
            showProgress = false,
        )
    }
}
