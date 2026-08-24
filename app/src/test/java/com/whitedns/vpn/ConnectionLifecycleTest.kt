package com.whitedns.vpn

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ConnectionLifecycleTest {
    @Test
    fun connectBecomesActiveOnlyAfterSessionReadiness() = runBlocking {
        val lifecycleJob = SupervisorJob()
        val dataPlane = DeferredDataPlane()
        val lifecycle = ConnectionLifecycle(
            scope = CoroutineScope(coroutineContext + lifecycleJob),
            dataPlane = dataPlane,
        )

        try {
            val decision = withTimeout(1_000) {
                lifecycle.request(ConnectionRequest.Connect)
            }
            val establishing = lifecycle.observation.value

            assertEquals(RequestDecision.Accepted, decision)
            assertEquals(LifecyclePhase.Establishing, establishing.phase)
            assertEquals(DataPlaneOwnership.Absent, establishing.ownership)
            assertEquals(RouteHealth.Unknown, establishing.routeHealth)
            assertNull(establishing.session)

            val lease = DataPlaneLease("lease-1")
            dataPlane.completeReadiness(lease)

            val active = withTimeout(1_000) {
                lifecycle.observation.first { observation ->
                    observation.revision > establishing.revision &&
                        observation.phase == LifecyclePhase.Active
                }
            }

            assertEquals(DataPlaneOwnership.Owned, active.ownership)
            assertEquals(RouteHealth.Unknown, active.routeHealth)
            assertEquals(ConnectionSession(lease), active.session)
        } finally {
            lifecycleJob.cancelAndJoin()
        }
    }

    @Test
    fun disconnectStaysReleasingUntilReleaseCompletion() = runBlocking {
        val lifecycleJob = SupervisorJob()
        val dataPlane = DeferredDataPlane()
        val lifecycle = ConnectionLifecycle(
            scope = CoroutineScope(coroutineContext + lifecycleJob),
            dataPlane = dataPlane,
        )

        try {
            val lease = DataPlaneLease("lease-1")
            lifecycle.request(ConnectionRequest.Connect)
            dataPlane.completeReadiness(lease)
            val active = withTimeout(1_000) {
                lifecycle.observation.first { it.phase == LifecyclePhase.Active }
            }

            val decision = withTimeout(1_000) {
                lifecycle.request(
                    ConnectionRequest.Disconnect(DisconnectAuthority.User),
                )
            }
            val releasing = lifecycle.observation.value

            assertEquals(RequestDecision.Accepted, decision)
            assertEquals(LifecyclePhase.Releasing, releasing.phase)
            assertEquals(DataPlaneOwnership.Owned, releasing.ownership)
            assertEquals(active.session, releasing.session)

            dataPlane.completeRelease(lease)

            val inactive = withTimeout(1_000) {
                lifecycle.observation.first { observation ->
                    observation.revision > releasing.revision &&
                        observation.phase == LifecyclePhase.Inactive
                }
            }

            assertEquals(DataPlaneOwnership.Absent, inactive.ownership)
            assertEquals(RouteHealth.Unknown, inactive.routeHealth)
            assertNull(inactive.session)
        } finally {
            lifecycleJob.cancelAndJoin()
        }
    }

    @Test
    fun repeatedDisconnectCannotCompleteBeforeExistingRelease() = runBlocking {
        val lifecycleJob = SupervisorJob()
        val releaseDeadline = CompletableDeferred<Unit>()
        val existingRelease = CompletableDeferred<Unit>()
        val repeatedRelease = CompletableDeferred<Unit>()
        val releaseGates = Channel<CompletableDeferred<Unit>>(Channel.UNLIMITED).apply {
            check(trySend(existingRelease).isSuccess)
            check(trySend(repeatedRelease).isSuccess)
        }
        val dataPlane = SequencedReleaseDataPlane(releaseGates)
        val lifecycle = ConnectionLifecycle(
            scope = CoroutineScope(Dispatchers.Unconfined + lifecycleJob),
            dataPlane = dataPlane,
            awaitReleaseDeadline = { releaseDeadline.await() },
        )

        try {
            val lease = DataPlaneLease("lease-1")
            lifecycle.request(ConnectionRequest.Connect)
            dataPlane.completeReadiness(lease)
            withTimeout(1_000) {
                lifecycle.observation.first { it.phase == LifecyclePhase.Active }
            }

            val disconnect = ConnectionRequest.Disconnect(DisconnectAuthority.User)
            val firstDecision = lifecycle.request(disconnect)
            val releasing = lifecycle.observation.value
            val repeatedDecision = lifecycle.request(disconnect)

            assertEquals(RequestDecision.Accepted, firstDecision)
            assertEquals(RequestDecision.Accepted, repeatedDecision)

            check(repeatedRelease.complete(Unit))

            assertEquals(releasing, lifecycle.observation.value)

            check(existingRelease.complete(Unit))
            val inactive = withTimeout(1_000) {
                lifecycle.observation.first { observation ->
                    observation.revision > releasing.revision &&
                        observation.phase == LifecyclePhase.Inactive
                }
            }

            assertEquals(DataPlaneOwnership.Absent, inactive.ownership)
            assertNull(inactive.session)
        } finally {
            lifecycleJob.cancelAndJoin()
        }
    }

    @Test
    fun disconnectDuringEstablishmentNeverPublishesActive() = runBlocking {
        val lifecycleJob = SupervisorJob()
        val dataPlane = DeferredDataPlane()
        val lifecycle = ConnectionLifecycle(
            scope = CoroutineScope(coroutineContext + lifecycleJob),
            dataPlane = dataPlane,
        )

        try {
            lifecycle.request(ConnectionRequest.Connect)

            val decision = withTimeout(1_000) {
                lifecycle.request(
                    ConnectionRequest.Disconnect(DisconnectAuthority.User),
                )
            }
            val releasing = lifecycle.observation.value

            assertEquals(RequestDecision.Accepted, decision)
            assertEquals(LifecyclePhase.Releasing, releasing.phase)
            assertNull(releasing.session)

            val afterLateReadiness = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(1_000) {
                    lifecycle.observation.first { observation ->
                        observation.revision > releasing.revision
                    }
                }
            }
            val lateLease = DataPlaneLease("late-lease")
            dataPlane.completeReadiness(lateLease)

            val quarantined = afterLateReadiness.await()
            assertEquals(LifecyclePhase.Releasing, quarantined.phase)
            assertEquals(DataPlaneOwnership.Owned, quarantined.ownership)
            assertNull(quarantined.session)

            dataPlane.completeRelease(lateLease)

            val inactive = withTimeout(1_000) {
                lifecycle.observation.first { observation ->
                    observation.revision > quarantined.revision &&
                        observation.phase == LifecyclePhase.Inactive
                }
            }
            assertEquals(DataPlaneOwnership.Absent, inactive.ownership)
            assertNull(inactive.session)
        } finally {
            lifecycleJob.cancelAndJoin()
        }
    }

    @Test
    fun releaseTimeoutKeepsOwnershipUncertain() = runBlocking {
        val lifecycleJob = SupervisorJob()
        val releaseDeadline = CompletableDeferred<Unit>()
        val dataPlane = DeferredDataPlane()
        val lifecycle = ConnectionLifecycle(
            scope = CoroutineScope(coroutineContext + lifecycleJob),
            dataPlane = dataPlane,
            awaitReleaseDeadline = { releaseDeadline.await() },
        )

        try {
            val lease = DataPlaneLease("lease-1")
            lifecycle.request(ConnectionRequest.Connect)
            dataPlane.completeReadiness(lease)
            val active = withTimeout(1_000) {
                lifecycle.observation.first { it.phase == LifecyclePhase.Active }
            }

            lifecycle.request(
                ConnectionRequest.Disconnect(DisconnectAuthority.User),
            )
            val releasing = lifecycle.observation.value
            releaseDeadline.complete(Unit)

            val timedOut = withTimeout(1_000) {
                lifecycle.observation.first { observation ->
                    observation.revision > releasing.revision
                }
            }

            assertEquals(LifecyclePhase.Releasing, timedOut.phase)
            assertEquals(DataPlaneOwnership.Uncertain, timedOut.ownership)
            assertEquals(active.session, timedOut.session)
            assertEquals(
                OperationFailure(
                    operation = OperationFailure.Operation.Release,
                    stage = OperationFailure.Stage.CompletionDeadlineExceeded,
                    resultingOwnership = DataPlaneOwnership.Uncertain,
                ),
                timedOut.failure,
            )
        } finally {
            lifecycleJob.cancelAndJoin()
        }
    }

    @Test
    fun releaseFailureKeepsOwnershipUncertain() = runBlocking {
        val lifecycleJob = SupervisorJob()
        val releaseDeadline = CompletableDeferred<Unit>()
        val dataPlane = FailingReleaseDataPlane()
        val lifecycle = ConnectionLifecycle(
            scope = CoroutineScope(coroutineContext + lifecycleJob),
            dataPlane = dataPlane,
            awaitReleaseDeadline = { releaseDeadline.await() },
        )

        try {
            val lease = DataPlaneLease("lease-1")
            lifecycle.request(ConnectionRequest.Connect)
            dataPlane.completeReadiness(lease)
            val active = withTimeout(1_000) {
                lifecycle.observation.first { it.phase == LifecyclePhase.Active }
            }

            lifecycle.request(
                ConnectionRequest.Disconnect(DisconnectAuthority.User),
            )
            val releasing = lifecycle.observation.value
            dataPlane.failRelease()

            val failed = withTimeout(1_000) {
                lifecycle.observation.first { observation ->
                    observation.revision > releasing.revision &&
                        observation.failure != null
                }
            }

            assertEquals(LifecyclePhase.Releasing, failed.phase)
            assertEquals(DataPlaneOwnership.Uncertain, failed.ownership)
            assertEquals(active.session, failed.session)
            assertEquals(OperationFailure.Operation.Release, failed.failure?.operation)
            assertEquals(
                OperationFailure.Stage.DataPlaneReleaseFailed,
                failed.failure?.stage,
            )
            assertEquals(DataPlaneOwnership.Uncertain, failed.failure?.resultingOwnership)
        } finally {
            lifecycleJob.cancelAndJoin()
        }
    }

    @Test
    fun disconnectRetriesFailedSessionRelease() = runBlocking {
        val lifecycleJob = SupervisorJob()
        val releaseDeadline = CompletableDeferred<Unit>()
        val dataPlane = RetryableReleaseDataPlane()
        val lifecycle = ConnectionLifecycle(
            scope = CoroutineScope(coroutineContext + lifecycleJob),
            dataPlane = dataPlane,
            awaitReleaseDeadline = { releaseDeadline.await() },
        )

        try {
            val lease = DataPlaneLease("lease-1")
            lifecycle.request(ConnectionRequest.Connect)
            dataPlane.completeReadiness(lease)
            withTimeout(1_000) {
                lifecycle.observation.first { it.phase == LifecyclePhase.Active }
            }

            lifecycle.request(
                ConnectionRequest.Disconnect(DisconnectAuthority.User),
            )
            val releasing = lifecycle.observation.value
            dataPlane.failFirstRelease()
            val failed = withTimeout(1_000) {
                lifecycle.observation.first { observation ->
                    observation.revision > releasing.revision &&
                        observation.failure?.stage ==
                        OperationFailure.Stage.DataPlaneReleaseFailed
                }
            }

            val retryDecision = lifecycle.request(
                ConnectionRequest.Disconnect(DisconnectAuthority.User),
            )

            assertEquals(RequestDecision.Accepted, retryDecision)
            assertEquals(failed, lifecycle.observation.value)

            dataPlane.completeRetry()
            val inactive = withTimeout(1_000) {
                lifecycle.observation.first { observation ->
                    observation.revision > failed.revision &&
                        observation.phase == LifecyclePhase.Inactive
                }
            }

            assertEquals(DataPlaneOwnership.Absent, inactive.ownership)
            assertNull(inactive.session)
            assertEquals(failed.failure, inactive.failure)
        } finally {
            lifecycleJob.cancelAndJoin()
        }
    }

    @Test
    fun lateReleaseCompletionFinishesTimedOutRelease() = runBlocking {
        val lifecycleJob = SupervisorJob()
        val releaseDeadline = CompletableDeferred<Unit>()
        val dataPlane = DeferredDataPlane()
        val lifecycle = ConnectionLifecycle(
            scope = CoroutineScope(coroutineContext + lifecycleJob),
            dataPlane = dataPlane,
            awaitReleaseDeadline = { releaseDeadline.await() },
        )

        try {
            val lease = DataPlaneLease("lease-1")
            lifecycle.request(ConnectionRequest.Connect)
            dataPlane.completeReadiness(lease)
            withTimeout(1_000) {
                lifecycle.observation.first { it.phase == LifecyclePhase.Active }
            }

            lifecycle.request(
                ConnectionRequest.Disconnect(DisconnectAuthority.User),
            )
            releaseDeadline.complete(Unit)
            val timedOut = withTimeout(1_000) {
                lifecycle.observation.first { observation ->
                    observation.ownership == DataPlaneOwnership.Uncertain
                }
            }

            dataPlane.completeRelease(lease)

            val inactive = withTimeout(1_000) {
                lifecycle.observation.first { observation ->
                    observation.revision > timedOut.revision &&
                        observation.phase == LifecyclePhase.Inactive
                }
            }

            assertEquals(DataPlaneOwnership.Absent, inactive.ownership)
            assertNull(inactive.session)
            assertEquals(timedOut.failure, inactive.failure)
        } finally {
            lifecycleJob.cancelAndJoin()
        }
    }

    @Test
    fun connectIsRejectedUntilReleaseCompletion() = runBlocking {
        val lifecycleJob = SupervisorJob()
        val releaseDeadline = CompletableDeferred<Unit>()
        val dataPlane = DeferredDataPlane()
        val lifecycle = ConnectionLifecycle(
            scope = CoroutineScope(coroutineContext + lifecycleJob),
            dataPlane = dataPlane,
            awaitReleaseDeadline = { releaseDeadline.await() },
        )

        try {
            val lease = DataPlaneLease("lease-1")
            lifecycle.request(ConnectionRequest.Connect)
            dataPlane.completeReadiness(lease)
            withTimeout(1_000) {
                lifecycle.observation.first { it.phase == LifecyclePhase.Active }
            }

            lifecycle.request(
                ConnectionRequest.Disconnect(DisconnectAuthority.User),
            )
            val releasing = lifecycle.observation.value
            releaseDeadline.complete(Unit)
            val timedOut = withTimeout(1_000) {
                lifecycle.observation.first { observation ->
                    observation.revision > releasing.revision &&
                        observation.ownership == DataPlaneOwnership.Uncertain
                }
            }

            val decision = lifecycle.request(ConnectionRequest.Connect)

            assertEquals(
                RequestDecision.Rejected(RequestDecision.Reason.ReleaseInProgress),
                decision,
            )
            assertEquals(timedOut, lifecycle.observation.value)

            dataPlane.completeRelease(lease)
            val inactive = withTimeout(1_000) {
                lifecycle.observation.first { observation ->
                    observation.revision > timedOut.revision &&
                        observation.phase == LifecyclePhase.Inactive
                }
            }

            val retryDecision = lifecycle.request(ConnectionRequest.Connect)

            assertEquals(RequestDecision.Accepted, retryDecision)
            assertEquals(LifecyclePhase.Establishing, lifecycle.observation.value.phase)
            assertEquals(DataPlaneOwnership.Absent, lifecycle.observation.value.ownership)
            assertNull(lifecycle.observation.value.session)
            assertEquals(timedOut.failure, inactive.failure)
        } finally {
            lifecycleJob.cancelAndJoin()
        }
    }

    private class DeferredDataPlane : DataPlane {
        private val readiness = Channel<EstablishmentOutcome>(Channel.UNLIMITED)
        private val releaseCompletions = mutableMapOf<DataPlaneLease, CompletableDeferred<Unit>>()

        override suspend fun establish(): EstablishmentOutcome = readiness.receive()

        override suspend fun release(lease: DataPlaneLease) {
            releaseCompletion(lease).await()
        }

        fun completeReadiness(lease: DataPlaneLease) {
            check(readiness.trySend(EstablishmentOutcome.Ready(lease)).isSuccess)
        }

        fun completeRelease(lease: DataPlaneLease) {
            releaseCompletion(lease).complete(Unit)
        }

        private fun releaseCompletion(lease: DataPlaneLease): CompletableDeferred<Unit> {
            return releaseCompletions.getOrPut(lease) { CompletableDeferred() }
        }
    }

    private class SequencedReleaseDataPlane(
        private val releaseGates: Channel<CompletableDeferred<Unit>>,
    ) : DataPlane {
        private val readiness = CompletableDeferred<EstablishmentOutcome>()

        override suspend fun establish(): EstablishmentOutcome = readiness.await()

        override suspend fun release(lease: DataPlaneLease) {
            releaseGates.receive().await()
        }

        fun completeReadiness(lease: DataPlaneLease) {
            readiness.complete(EstablishmentOutcome.Ready(lease))
        }
    }

    private class FailingReleaseDataPlane : DataPlane {
        private val readiness = CompletableDeferred<EstablishmentOutcome>()
        private val releaseFailure = CompletableDeferred<Unit>()

        override suspend fun establish(): EstablishmentOutcome = readiness.await()

        override suspend fun release(lease: DataPlaneLease) {
            releaseFailure.await()
            error("release failed")
        }

        fun completeReadiness(lease: DataPlaneLease) {
            readiness.complete(EstablishmentOutcome.Ready(lease))
        }

        fun failRelease() {
            releaseFailure.complete(Unit)
        }
    }

    private class RetryableReleaseDataPlane : DataPlane {
        private val readiness = CompletableDeferred<EstablishmentOutcome>()
        private val firstFailure = CompletableDeferred<Unit>()
        private val retryCompletion = CompletableDeferred<Unit>()
        private var releaseAttempt = 0

        override suspend fun establish(): EstablishmentOutcome = readiness.await()

        override suspend fun release(lease: DataPlaneLease) {
            releaseAttempt += 1
            when (releaseAttempt) {
                1 -> {
                    firstFailure.await()
                    error("release failed")
                }

                2 -> retryCompletion.await()
                else -> error("unexpected release attempt")
            }
        }

        fun completeReadiness(lease: DataPlaneLease) {
            readiness.complete(EstablishmentOutcome.Ready(lease))
        }

        fun failFirstRelease() {
            firstFailure.complete(Unit)
        }

        fun completeRetry() {
            retryCompletion.complete(Unit)
        }
    }
}
