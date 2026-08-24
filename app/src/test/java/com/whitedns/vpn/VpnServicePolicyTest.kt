package com.whitedns.vpn

import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class VpnServicePolicyTest {
    @Test
    fun systemStartedServiceConnectsWhileAppActionsStayExplicit() {
        assertEquals(Actions.CONNECT, Actions.resolveServiceAction(null, appInitiated = false))
        assertEquals(Actions.DISCONNECT, Actions.resolveServiceAction(Actions.DISCONNECT, appInitiated = true))
    }

    @Test
    fun postConnectRecoveryRequiresConsecutiveFailuresAndCooldown() {
        val lastRecovery = 1_000L

        assertEquals(5_000L, PostConnectHealthPolicy.INITIAL_CHECK_DELAY_MS)
        assertEquals(5_000L, PostConnectHealthPolicy.FAILURE_RECHECK_DELAY_MS)
        assertFalse(PostConnectHealthPolicy.shouldRecover(1, Long.MAX_VALUE, 0L))
        assertTrue(PostConnectHealthPolicy.shouldRecover(2, 2_000L, 0L))
        assertFalse(PostConnectHealthPolicy.shouldRecover(2, lastRecovery + 1L, lastRecovery))
        assertTrue(
            PostConnectHealthPolicy.shouldRecover(
                2,
                lastRecovery + PostConnectHealthPolicy.RECOVERY_COOLDOWN_MS,
                lastRecovery,
            ),
        )
    }

    @Test
    fun canceledOrStoppedStartupCannotPublishAnError() {
        assertTrue(shouldPublishStartupError(startupActive = true, VpnState.Starting))
        assertTrue(shouldPublishStartupError(startupActive = true, VpnState.Started))
        assertFalse(shouldPublishStartupError(startupActive = false, VpnState.Starting))
        assertFalse(shouldPublishStartupError(startupActive = true, VpnState.Stopping))
        assertFalse(shouldPublishStartupError(startupActive = true, VpnState.Stopped))
        assertFalse(shouldPublishStartupError(startupActive = true, VpnState.Error("failed")))
    }

    @Test
    fun connectionTestCompletionCannotDestroyAStoppingService() {
        assertFalse(shouldStopServiceAfterConnectionTest(VpnState.Starting))
        assertFalse(shouldStopServiceAfterConnectionTest(VpnState.Started))
        assertFalse(shouldStopServiceAfterConnectionTest(VpnState.Stopping))
        assertTrue(shouldStopServiceAfterConnectionTest(VpnState.Stopped))
        assertTrue(shouldStopServiceAfterConnectionTest(VpnState.DailyLimitReached))
        assertTrue(shouldStopServiceAfterConnectionTest(VpnState.Error("failed")))
    }

    @Test
    fun recoveryExclusionOnlyRemovesAnAutomaticFinalChainLeaf() {
        assertTrue(
            shouldExcludeRecoveryChainPlan(
                excludedFingerprint = "current",
                finalFingerprint = "current",
                finalHopMode = ConnectionChainHopMode.Automatic,
            ),
        )
        assertFalse(
            shouldExcludeRecoveryChainPlan(
                excludedFingerprint = "current",
                finalFingerprint = "other",
                finalHopMode = ConnectionChainHopMode.Automatic,
            ),
        )
        assertFalse(
            shouldExcludeRecoveryChainPlan(
                excludedFingerprint = "current",
                finalFingerprint = "current",
                finalHopMode = ConnectionChainHopMode.Fixed,
            ),
        )
    }

    @Test
    fun quickSpeedStopsWhenTheDefaultNetworkChanges() {
        assertTrue(quickSpeedNetworkUnchanged("wifi|1", "wifi|1"))
        assertFalse(quickSpeedNetworkUnchanged("wifi|1", "cellular|2"))
        assertFalse(defaultNetworkStateChanged(false, "wifi|1", "1.1.1.1", "wifi|1", "1.1.1.1"))
        assertTrue(defaultNetworkStateChanged(false, "cellular|2", "1.1.1.1", "wifi|1", "1.1.1.1"))
        assertTrue(defaultNetworkStateChanged(false, "wifi|1", "8.8.8.8", "wifi|1", "1.1.1.1"))
        assertTrue(defaultNetworkStateChanged(true, "wifi|1", "1.1.1.1", "wifi|1", "1.1.1.1"))
    }

    @Test
    fun automaticBridgeRetryOnlyCoversImmediateStructuralFailures() {
        val structuralFailure = IOException("selector missing")

        assertTrue(
            shouldRetryOriginalAfterAutomaticBridgeFailure(
                bridgeApplied = true,
                phase = AutomaticBridgeFailurePhase.ConfigCoreOrController,
                error = structuralFailure,
            ),
        )
        assertTrue(
            shouldRetryOriginalAfterAutomaticBridgeFailure(
                bridgeApplied = true,
                phase = AutomaticBridgeFailurePhase.Selector,
                error = structuralFailure,
            ),
        )
        assertFalse(
            shouldRetryOriginalAfterAutomaticBridgeFailure(
                bridgeApplied = true,
                phase = AutomaticBridgeFailurePhase.HttpHealth,
                error = IOException("all nodes failed health"),
            ),
        )
        listOf(
            CancellationException("canceled"),
            MihomoCoreBusyException(),
            MihomoCoreSetupTimeoutException(),
        ).forEach { error ->
            assertFalse(
                shouldRetryOriginalAfterAutomaticBridgeFailure(
                    bridgeApplied = true,
                    phase = AutomaticBridgeFailurePhase.Selector,
                    error = error,
                ),
            )
        }
        assertFalse(
            shouldRetryOriginalAfterAutomaticBridgeFailure(
                bridgeApplied = false,
                phase = AutomaticBridgeFailurePhase.Selector,
                error = structuralFailure,
            ),
        )
        assertEquals(1, DefaultNetworkDnsReplayPolicy.MAX_RETRY_ATTEMPTS)
    }
}
