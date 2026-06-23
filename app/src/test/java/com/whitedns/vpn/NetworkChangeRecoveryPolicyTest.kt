package com.whitedns.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkChangeRecoveryPolicyTest {
    @Test
    fun recoveryRequiresStartedState() {
        assertFalse(NetworkChangeRecoveryPolicy.shouldRecover(VpnState.Stopped, 5_000, 0, false))
        assertFalse(NetworkChangeRecoveryPolicy.shouldRecover(VpnState.Starting, 5_000, 0, false))
        assertFalse(NetworkChangeRecoveryPolicy.shouldRecover(VpnState.Stopping, 5_000, 0, false))
        assertFalse(NetworkChangeRecoveryPolicy.shouldRecover(VpnState.DailyLimitReached, 5_000, 0, false))
        assertFalse(NetworkChangeRecoveryPolicy.shouldRecover(VpnState.Error("failed"), 5_000, 0, false))
    }

    @Test
    fun recoverySkipsWhileAlreadyActive() {
        assertFalse(NetworkChangeRecoveryPolicy.shouldRecover(VpnState.Started, 5_000, 0, true))
    }

    @Test
    fun recoveryDebouncesRecentRecovery() {
        assertFalse(
            NetworkChangeRecoveryPolicy.shouldRecover(
                state = VpnState.Started,
                nowMs = 6_999,
                lastRecoveryAtMs = 5_000,
                isRecoveryActive = false,
            ),
        )
    }

    @Test
    fun recoveryAllowsFirstAndExpiredRecovery() {
        assertTrue(NetworkChangeRecoveryPolicy.shouldRecover(VpnState.Started, 5_000, 0, false))
        assertTrue(
            NetworkChangeRecoveryPolicy.shouldRecover(
                state = VpnState.Started,
                nowMs = 7_000,
                lastRecoveryAtMs = 5_000,
                isRecoveryActive = false,
            ),
        )
    }

    @Test
    fun recoveryFailurePreservesActiveVpnWhenStarted() {
        assertEquals(
            NetworkRecoveryFailureAction.PreserveActiveVpn,
            NetworkChangeRecoveryPolicy.failureActionFor(VpnState.Started),
        )
    }

    @Test
    fun recoveryFailureIgnoresStaleNonStartedStates() {
        listOf(
            VpnState.Stopped,
            VpnState.Starting,
            VpnState.Stopping,
            VpnState.DailyLimitReached,
            VpnState.Error("failed"),
        ).forEach { state ->
            assertEquals(
                NetworkRecoveryFailureAction.IgnoreStaleFailure,
                NetworkChangeRecoveryPolicy.failureActionFor(state),
            )
        }
    }
}
