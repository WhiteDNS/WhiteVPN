package com.whitedns.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MihomoCoreLifecycleTest {
    @Test
    fun cleanupRequestedDuringSetupWaitsForSetupCallback() {
        val lifecycle = MihomoCoreLifecycle()

        assertTrue(lifecycle.beginSetup())
        assertEquals(MihomoCoreCleanupRequest.PendingSetup, lifecycle.requestCleanup())
        assertEquals(MihomoCoreState.SettingUp, lifecycle.currentState())
        assertEquals(MihomoCoreSetupCompletion.CleanupClaimed, lifecycle.finishSetup())
        assertEquals(MihomoCoreState.Stopping, lifecycle.currentState())
        assertFalse(lifecycle.beginSetup())
    }

    @Test
    fun cleanupCanOnlyBeClaimedOnceAndNeedsNativeCompletion() {
        val lifecycle = MihomoCoreLifecycle()

        assertTrue(lifecycle.beginSetup())
        assertEquals(MihomoCoreSetupCompletion.Active, lifecycle.finishSetup())
        assertEquals(MihomoCoreCleanupRequest.Claimed, lifecycle.requestCleanup())
        assertEquals(MihomoCoreCleanupRequest.AlreadyStopping, lifecycle.requestCleanup())
        assertFalse(lifecycle.beginSetup())

        assertTrue(lifecycle.finishCleanup())
        assertEquals(MihomoCoreState.Idle, lifecycle.currentState())
        assertTrue(lifecycle.beginSetup())
    }

    @Test
    fun setupWithoutPendingCleanupBecomesActive() {
        val lifecycle = MihomoCoreLifecycle()

        assertTrue(lifecycle.beginSetup())
        assertEquals(MihomoCoreSetupCompletion.Active, lifecycle.finishSetup())
        assertTrue(lifecycle.isActive())
        assertFalse(lifecycle.isIdle())
    }

    @Test
    fun staleCallbacksCannotResetAnotherLifecyclePhase() {
        val lifecycle = MihomoCoreLifecycle()

        assertEquals(MihomoCoreSetupCompletion.Ignored, lifecycle.finishSetup())
        assertFalse(lifecycle.finishCleanup())
        assertEquals(MihomoCoreState.Idle, lifecycle.currentState())
    }
}
