package com.whitedns.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardStatePresentationTest {
    @Test
    fun stoppedStateUsesNeutralConnectPresentation() {
        val presentation = DashboardStatePresenter.forState(VpnState.Stopped)

        assertEquals(R.string.state_ready, presentation.titleRes)
        assertEquals(DashboardTone.Neutral, presentation.tone)
        assertFalse(presentation.showProgress)
    }

    @Test
    fun transitionalStatesUseProgressPresentation() {
        val starting = DashboardStatePresenter.forState(VpnState.Starting)
        val stopping = DashboardStatePresenter.forState(VpnState.Stopping)

        assertEquals(R.string.state_connecting, starting.titleRes)
        assertEquals(DashboardTone.Progress, starting.tone)
        assertTrue(starting.showProgress)
        assertEquals(R.string.state_disconnecting, stopping.titleRes)
        assertEquals(DashboardTone.Progress, stopping.tone)
        assertTrue(stopping.showProgress)
    }

    @Test
    fun startedStateUsesConnectedPresentation() {
        val presentation = DashboardStatePresenter.forState(VpnState.Started)

        assertEquals(R.string.state_connected, presentation.titleRes)
        assertEquals(DashboardTone.Connected, presentation.tone)
        assertFalse(presentation.showProgress)
    }

    @Test
    fun errorStateUsesMinimalErrorPresentation() {
        val presentation = DashboardStatePresenter.forState(VpnState.Error("failed"))

        assertEquals(R.string.state_connection_error, presentation.titleRes)
        assertEquals(DashboardTone.Error, presentation.tone)
        assertFalse(presentation.showProgress)
    }

    @Test
    fun legacyDailyLimitStateUsesStoppedPresentation() {
        val presentation = DashboardStatePresenter.forState(VpnState.DailyLimitReached)

        assertEquals(R.string.state_daily_limit, presentation.titleRes)
        assertEquals(DashboardTone.Neutral, presentation.tone)
        assertFalse(presentation.showProgress)
    }

    @Test
    fun connectionDetailsDescribeProtocolAndEchTruthfully() {
        assertEquals(
            "WireGuard outbound  •  Amnezia 5×50–100 B  •  ECH not applicable",
            ConnectionDetailsPresenter.forProfile(
                profile("wireguard", amneziaNoise = AmneziaNoiseSettings(5, 50, 100)),
            ),
        )
        assertEquals(
            "VLESS outbound  •  ECH enabled",
            ConnectionDetailsPresenter.forProfile(profile("vless", echEnabled = true, echCapable = true)),
        )
        assertEquals(
            "Mihomo outbound  •  ECH unknown",
            ConnectionDetailsPresenter.forProfile(profile("mihomo-group")),
        )
        assertEquals(
            "VLESS outbound  •  abcdefghijklmnopqrstuvw…  •  ECH disabled",
            ConnectionDetailsPresenter.forProfile(
                profile(
                    type = "vless",
                    server = "abcdefghijklmnopqrstuvwxyz.example",
                    echCapable = true,
                ),
                showServer = true,
            ),
        )
    }

    @Test
    fun dashboardConnectionDetailsKeepTheSelectedSourceVisible() {
        assertEquals(
            "Selected · Public source\nVLESS outbound  •  ECH enabled",
            ConnectionDetailsPresenter.forDashboard(
                selectedSource = "Public source",
                runtimeDetails = "VLESS outbound  •  ECH enabled",
            ),
        )
        assertEquals(
            "Selected · WhiteVPN",
            ConnectionDetailsPresenter.forDashboard(
                selectedSource = "WhiteVPN",
                runtimeDetails = "",
            ),
        )
    }

    private fun profile(
        type: String,
        server: String = "example.com",
        echEnabled: Boolean = false,
        echCapable: Boolean = false,
        amneziaNoise: AmneziaNoiseSettings? = null,
    ) = ConnectionProfile(
        tag = "Test",
        type = type,
        server = server,
        port = 443,
        transport = "",
        validationHost = "example.com",
        echEnabled = echEnabled,
        echCapable = echCapable,
        amneziaNoise = amneziaNoise,
    )
}
