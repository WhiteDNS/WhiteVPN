package com.whitedns.vpn

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class VpnServicePolicyTest {
    @Test
    fun builtInAutomaticConnectionsTryPrivateThenPublic() {
        assertEquals(
            listOf(
                SubscriptionStore.PRIVATE_SUBSCRIPTION_ID,
                SubscriptionStore.PUBLIC_SUBSCRIPTION_ID,
            ),
            BuiltInSubscriptionStartupPolicy.sourceIds(
                SubscriptionStore.PUBLIC_SUBSCRIPTION_ID,
                explicitProfile = null,
            ),
        )
        assertEquals(
            listOf("custom"),
            BuiltInSubscriptionStartupPolicy.sourceIds("custom", explicitProfile = null),
        )

        val explicit = ConnectionProfile(
            tag = "Explicit",
            type = "vless",
            server = "example.com",
            port = 443,
            transport = "tcp",
            validationHost = "example.com",
            fingerprint = "explicit",
            outboundJson = "{}",
        )
        assertEquals(
            listOf(SubscriptionStore.PUBLIC_SUBSCRIPTION_ID),
            BuiltInSubscriptionStartupPolicy.sourceIds(
                SubscriptionStore.PUBLIC_SUBSCRIPTION_ID,
                explicit,
            ),
        )
    }

    @Test
    fun builtInFallbackOnlyConsumesOrdinaryIoFailures() {
        assertTrue(BuiltInSubscriptionStartupPolicy.canFallback(IOException("unavailable")))
        assertFalse(BuiltInSubscriptionStartupPolicy.canFallback(CancellationException("canceled")))
        assertFalse(BuiltInSubscriptionStartupPolicy.canFallback(MihomoCoreBusyException()))
        assertFalse(BuiltInSubscriptionStartupPolicy.canFallback(MihomoCoreSetupTimeoutException()))
    }

    @Test
    fun builtInFallbackStopsAtTheFirstSuccessAndPropagatesTerminalFailures() = runBlocking {
        val attempts = mutableListOf<String>()
        assertEquals(
            "private connected",
            BuiltInSubscriptionStartupPolicy.firstSuccessful(
                SubscriptionStore.BUILT_IN_SUBSCRIPTION_IDS,
            ) { sourceId ->
                attempts += sourceId
                "private connected"
            },
        )
        assertEquals(listOf(SubscriptionStore.PRIVATE_SUBSCRIPTION_ID), attempts)

        attempts.clear()
        val result = BuiltInSubscriptionStartupPolicy.firstSuccessful(
            SubscriptionStore.BUILT_IN_SUBSCRIPTION_IDS,
        ) { sourceId ->
            attempts += sourceId
            if (sourceId == SubscriptionStore.PRIVATE_SUBSCRIPTION_ID) {
                throw IOException("private unavailable")
            }
            "public connected"
        }
        assertEquals("public connected", result)
        assertEquals(SubscriptionStore.BUILT_IN_SUBSCRIPTION_IDS, attempts)

        val terminal = runCatching {
            BuiltInSubscriptionStartupPolicy.firstSuccessful(
                SubscriptionStore.BUILT_IN_SUBSCRIPTION_IDS,
            ) { throw IOException(it) }
        }.exceptionOrNull()
        assertEquals(SubscriptionStore.PUBLIC_SUBSCRIPTION_ID, terminal?.message)

        attempts.clear()
        val canceled = runCatching {
            BuiltInSubscriptionStartupPolicy.firstSuccessful(
                SubscriptionStore.BUILT_IN_SUBSCRIPTION_IDS,
            ) { sourceId ->
                attempts += sourceId
                throw CancellationException("canceled")
            }
        }.exceptionOrNull()
        assertTrue(canceled is CancellationException)
        assertEquals(listOf(SubscriptionStore.PRIVATE_SUBSCRIPTION_ID), attempts)
    }

    @Test
    fun publicRuntimeUsesPublicConnectionNotice() {
        assertEquals(
            R.string.notification_connected_public,
            connectedNotificationTextRes(SubscriptionStore.PUBLIC_SUBSCRIPTION_ID),
        )
        assertEquals(
            R.string.notification_connected,
            connectedNotificationTextRes(SubscriptionStore.PRIVATE_SUBSCRIPTION_ID),
        )
    }

    @Test
    fun recoveryExclusionsApplyOnlyToTheirRuntimeSource() {
        val exclusion = ConnectionStartupExclusion(
            subscriptionId = SubscriptionStore.PUBLIC_SUBSCRIPTION_ID,
            profileFingerprint = "public-profile",
        )

        assertEquals(
            "public-profile",
            exclusion.forSubscription(SubscriptionStore.PUBLIC_SUBSCRIPTION_ID).profileFingerprint,
        )
        assertEquals(
            "",
            exclusion.forSubscription(SubscriptionStore.PRIVATE_SUBSCRIPTION_ID).profileFingerprint,
        )
    }

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
    fun failedRefreshCanKeepConnectedStateOnlyWhenTheOldRuntimeIsHealthy() {
        listOf(200, 204, 302, 399).forEach { status ->
            assertTrue(PostConnectHealthPolicy.isHealthyStatus(status))
        }
        listOf(-1, 0, 199, 400, 500).forEach { status ->
            assertFalse(PostConnectHealthPolicy.isHealthyStatus(status))
        }
        assertEquals(VpnState.Starting, PostConnectHealthPolicy.preservedRuntimeState(false, -1))
        assertEquals(VpnState.Started, PostConnectHealthPolicy.preservedRuntimeState(true, 204))
        assertEquals(null, PostConnectHealthPolicy.preservedRuntimeState(true, -1))
        assertTrue(shouldRunPostConnectHealthWatchdog(VpnState.Starting, awaitingPreservedRuntimeHealth = true))
        assertTrue(shouldRunPostConnectHealthWatchdog(VpnState.Started, awaitingPreservedRuntimeHealth = false))
        assertFalse(shouldRunPostConnectHealthWatchdog(VpnState.Starting, awaitingPreservedRuntimeHealth = false))
        assertTrue(canStartVpnRefresh(VpnState.Started, automatic = false, awaitingPreservedRuntimeHealth = false))
        assertTrue(canStartVpnRefresh(VpnState.Starting, automatic = true, awaitingPreservedRuntimeHealth = true))
        assertFalse(canStartVpnRefresh(VpnState.Starting, automatic = false, awaitingPreservedRuntimeHealth = true))
        assertFalse(canStartVpnRefresh(VpnState.Starting, automatic = true, awaitingPreservedRuntimeHealth = false))
    }

    @Test
    fun vpnTunnelCapturesBothAddressFamilies() {
        assertEquals(
            listOf("172.19.0.1" to 30, "fdfe:dcba:9876::1" to 126),
            VpnTunnelNetwork.addresses,
        )
        assertEquals(listOf("0.0.0.0", "::"), VpnTunnelNetwork.defaultRoutes)
        assertEquals(listOf("172.19.0.2", "fdfe:dcba:9876::2"), VpnTunnelNetwork.dnsServers)
        assertEquals("172.19.0.1/30,fdfe:dcba:9876::1/126", VpnTunnelNetwork.coreAddresses)
        assertEquals("172.19.0.2,fdfe:dcba:9876::2", VpnTunnelNetwork.coreDnsServers)
        assertEquals("0.0.0.0", VpnTunnelNetwork.IPV4_DEFAULT_ROUTE)
        assertEquals("::", VpnTunnelNetwork.IPV6_DEFAULT_ROUTE)
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
    fun disconnectNeverReturnsToStartedWhenCleanupTimesOut() {
        assertEquals(VpnState.Stopped, disconnectTerminalState(true, "shutdown failed"))
        assertTrue(disconnectTerminalState(false, "shutdown failed") is VpnState.Error)
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
