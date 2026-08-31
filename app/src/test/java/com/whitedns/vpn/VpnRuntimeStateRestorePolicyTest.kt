package com.whitedns.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class VpnRuntimeStateRestorePolicyTest {
    @Test
    fun legacyActiveStatesRecoverAsStoppedAfterUpgrade() {
        listOf(VpnState.Starting, VpnState.Started, VpnState.Stopping).forEach { state ->
            assertEquals(VpnState.Stopped, restoredVpnState(state, persistedSchema = 0))
        }
    }

    @Test
    fun currentStoppingStateRemainsAuthoritativeDuringCleanup() {
        assertEquals(VpnState.Stopping, restoredVpnState(VpnState.Stopping, persistedSchema = 1))
    }
}
