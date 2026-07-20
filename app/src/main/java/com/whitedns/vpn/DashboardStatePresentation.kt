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
            title = "آماده",
            tone = DashboardTone.Neutral,
            showProgress = false,
        )
        VpnState.Starting -> DashboardStatePresentation(
            title = "در حال اتصال…",
            tone = DashboardTone.Progress,
            showProgress = true,
        )
        VpnState.Started -> DashboardStatePresentation(
            title = "متصل",
            tone = DashboardTone.Connected,
            showProgress = false,
        )
        VpnState.Stopping -> DashboardStatePresentation(
            title = "در حال قطع اتصال",
            tone = DashboardTone.Progress,
            showProgress = true,
        )
        VpnState.DailyLimitReached -> DashboardStatePresentation(
            title = "سقف مصرف روزانه",
            tone = DashboardTone.Neutral,
            showProgress = false,
        )
        is VpnState.Error -> DashboardStatePresentation(
            title = "خطای اتصال",
            tone = DashboardTone.Error,
            showProgress = false,
        )
    }
}
