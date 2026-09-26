package com.whitedns.vpn

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.security.cert.CertificateException

class VpnServicePolicyTest {
    @Test
    fun builtInAutomaticConnectionsUseAvailableSourcesInOrder() {
        assertEquals(
            SubscriptionStore.BUILT_IN_SUBSCRIPTION_IDS,
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
        assertFalse(
            BuiltInSubscriptionStartupPolicy.canFallback(
                IOException("wrapped", TlsIntegrityException(IOException("certificate"))),
            ),
        )
    }

    @Test
    fun builtInFallbackStopsAtTheFirstSuccessAndPropagatesTerminalFailures() = runBlocking {
        val sourceIds = listOf(SubscriptionStore.PRIVATE_SUBSCRIPTION_ID, SubscriptionStore.PUBLIC_SUBSCRIPTION_ID)
        val attempts = mutableListOf<String>()
        assertEquals(
            "private connected",
            BuiltInSubscriptionStartupPolicy.firstSuccessful(
                sourceIds,
            ) { sourceId ->
                attempts += sourceId
                "private connected"
            },
        )
        assertEquals(listOf(SubscriptionStore.PRIVATE_SUBSCRIPTION_ID), attempts)

        attempts.clear()
        val result = BuiltInSubscriptionStartupPolicy.firstSuccessful(
            sourceIds,
        ) { sourceId ->
            attempts += sourceId
            if (sourceId == SubscriptionStore.PRIVATE_SUBSCRIPTION_ID) {
                throw IOException("private unavailable")
            }
            "public connected"
        }
        assertEquals("public connected", result)
        assertEquals(sourceIds, attempts)

        val terminal = runCatching {
            BuiltInSubscriptionStartupPolicy.firstSuccessful(
                sourceIds,
            ) { throw IOException(it) }
        }.exceptionOrNull()
        assertEquals(SubscriptionStore.PUBLIC_SUBSCRIPTION_ID, terminal?.message)

        attempts.clear()
        val canceled = runCatching {
            BuiltInSubscriptionStartupPolicy.firstSuccessful(
                sourceIds,
            ) { sourceId ->
                attempts += sourceId
                throw CancellationException("canceled")
            }
        }.exceptionOrNull()
        assertTrue(canceled is CancellationException)
        assertEquals(listOf(SubscriptionStore.PRIVATE_SUBSCRIPTION_ID), attempts)

        val mixedFailure = runCatching {
            BuiltInSubscriptionStartupPolicy.firstSuccessful(sourceIds) { sourceId ->
                if (sourceId == SubscriptionStore.PRIVATE_SUBSCRIPTION_ID) {
                    throw AutomaticConnectionAttemptsExhaustedException()
                }
                throw IOException("public unavailable")
            }
        }.exceptionOrNull()
        assertTrue(automaticConnectionAttemptsExhausted(mixedFailure!!))
        assertEquals("public unavailable", mixedFailure.cause?.message)
        assertTrue(
            shouldAutomaticallyRepairStartup(
                mixedFailure,
                preservingRuntime = false,
                connectionChainActive = false,
                explicitProfileSelected = false,
            ),
        )
    }

    @Test
    fun startupFailurePriorityKeepsSecurityAndExhaustionSignals() {
        val ordinary = IOException("latest")
        val tls = TlsIntegrityException(IOException("certificate"))
        val exhaustion = AutomaticConnectionAttemptsExhaustedException(IOException("exhausted"))

        assertTrue(tlsIntegrityFailed(preferredStartupFailure(tls, ordinary)))
        assertFalse(
            shouldAutomaticallyRepairStartup(
                preferredStartupFailure(tls, ordinary),
                preservingRuntime = false,
                connectionChainActive = false,
                explicitProfileSelected = false,
            ),
        )
        assertTrue(automaticConnectionAttemptsExhausted(preferredStartupFailure(exhaustion, ordinary)))
        assertTrue(automaticConnectionAttemptsExhausted(preferredStartupFailure(ordinary, exhaustion)))
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
    fun onlyConfirmedExternalHealthKeepsTheActiveRuntime() {
        val reachable = RuntimeHealthPolicy.classify(mixedProxyReachable = true, statusCode = 204)
        val unknown = RuntimeHealthPolicy.classify(mixedProxyReachable = true)
        val filtered = RuntimeHealthPolicy.classify(mixedProxyReachable = true, statusCode = 451)
        val unavailable = RuntimeHealthPolicy.classify(mixedProxyReachable = false)
        val certificateFailure = RuntimeHealthPolicy.classify(
            mixedProxyReachable = true,
            certificateFailure = CertificateException("certificate"),
        )

        assertEquals(RuntimeHealthState.Reachable, reachable.state)
        assertEquals(RuntimeHealthState.Unknown, unknown.state)
        assertEquals(RuntimeHealthState.Unknown, filtered.state)
        assertEquals(
            RuntimeHealthState.Unavailable,
            certificateFailure.state,
        )
        assertEquals(VpnState.Starting, PostConnectHealthPolicy.preservedRuntimeState(false, unavailable))
        assertEquals(VpnState.Started, PostConnectHealthPolicy.preservedRuntimeState(true, reachable))
        assertEquals(null, PostConnectHealthPolicy.preservedRuntimeState(true, unknown))
        assertEquals(null, PostConnectHealthPolicy.preservedRuntimeState(true, unavailable))
        assertTrue(RuntimeHealthPolicy.shouldFinishWaiting(reachable))
        assertFalse(RuntimeHealthPolicy.shouldFinishWaiting(unknown))
        assertFalse(RuntimeHealthPolicy.shouldFinishWaiting(unavailable))
        assertTrue(RuntimeHealthPolicy.shouldFinishWaiting(certificateFailure))
        assertTrue(shouldRunPostConnectHealthWatchdog(VpnState.Starting, awaitingPreservedRuntimeHealth = true))
        assertTrue(shouldRunPostConnectHealthWatchdog(VpnState.Started, awaitingPreservedRuntimeHealth = false))
        assertFalse(shouldRunPostConnectHealthWatchdog(VpnState.Starting, awaitingPreservedRuntimeHealth = false))
        assertTrue(canStartVpnRefresh(VpnState.Started, automatic = false, awaitingPreservedRuntimeHealth = false))
        assertTrue(canStartVpnRefresh(VpnState.Starting, automatic = true, awaitingPreservedRuntimeHealth = true))
        assertFalse(canStartVpnRefresh(VpnState.Starting, automatic = false, awaitingPreservedRuntimeHealth = true))
        assertFalse(canStartVpnRefresh(VpnState.Starting, automatic = true, awaitingPreservedRuntimeHealth = false))
    }

    @Test
    fun automaticStartupRepairOnlyRetriesAutomaticColdStarts() {
        assertTrue(
            shouldAutomaticallyRepairStartup(
                AutomaticConnectionAttemptsExhaustedException(),
                preservingRuntime = false,
                connectionChainActive = false,
                explicitProfileSelected = false,
            ),
        )
        assertFalse(
            shouldAutomaticallyRepairStartup(
                AutomaticConnectionAttemptsExhaustedException(),
                preservingRuntime = false,
                connectionChainActive = false,
                explicitProfileSelected = true,
            ),
        )
        assertFalse(
            shouldAutomaticallyRepairStartup(
                MihomoCoreBusyException(),
                preservingRuntime = false,
                connectionChainActive = false,
                explicitProfileSelected = false,
            ),
        )
        assertFalse(
            shouldAutomaticallyRepairStartup(
                IOException("offline before candidates"),
                preservingRuntime = false,
                connectionChainActive = false,
                explicitProfileSelected = false,
            ),
        )
        assertFalse(
            shouldAutomaticallyRepairStartup(
                AutomaticConnectionAttemptsExhaustedException(
                    TlsIntegrityException(IOException("certificate")),
                ),
                preservingRuntime = false,
                connectionChainActive = false,
                explicitProfileSelected = false,
            ),
        )
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
    fun finishingOrRejectedTestsCannotStopOtherTestsOrAVpnHandoff() {
        val states = listOf(
            VpnState.Starting, VpnState.Started, VpnState.Stopping,
            VpnState.Stopped, VpnState.DailyLimitReached, VpnState.Error("failed"),
        )
        states.forEach { state ->
            assertFalse(shouldStopServiceAfterConnectionTest(state, hasTests = true))
        }
        listOf(VpnState.Starting, VpnState.Started, VpnState.Stopping).forEach { state ->
            assertFalse(shouldStopServiceAfterConnectionTest(state, hasTests = false))
        }
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
