package com.whitedns.vpn

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

internal enum class LifecyclePhase {
    Inactive,
    Establishing,
    Active,
    Releasing,
}

internal enum class DataPlaneOwnership {
    Absent,
    Owned,
    Uncertain,
}

internal enum class RouteHealth {
    Unknown,
    Healthy,
    Unhealthy,
    Recovering,
}

@JvmInline
internal value class DataPlaneLease(val value: String)

internal data class ConnectionSession(
    val lease: DataPlaneLease,
)

internal data class OperationFailure(
    val operation: Operation,
    val stage: Stage,
    val resultingOwnership: DataPlaneOwnership,
) {
    internal enum class Operation {
        Release,
    }

    internal enum class Stage {
        CompletionDeadlineExceeded,
        DataPlaneReleaseFailed,
    }
}

internal data class ConnectionObservation(
    val revision: Long,
    val phase: LifecyclePhase,
    val ownership: DataPlaneOwnership,
    val routeHealth: RouteHealth,
    val session: ConnectionSession?,
    val failure: OperationFailure? = null,
)

internal sealed interface ConnectionRequest {
    data object Connect : ConnectionRequest

    data class Disconnect(
        val authority: DisconnectAuthority,
    ) : ConnectionRequest
}

internal enum class DisconnectAuthority {
    User,
}

internal sealed interface RequestDecision {
    data object Accepted : RequestDecision

    data class Rejected(
        val reason: Reason,
    ) : RequestDecision

    enum class Reason {
        ReleaseInProgress,
    }
}

internal sealed interface EstablishmentOutcome {
    data class Ready(val lease: DataPlaneLease) : EstablishmentOutcome
}

internal interface DataPlane {
    suspend fun establish(): EstablishmentOutcome

    suspend fun release(lease: DataPlaneLease)
}

internal class ConnectionLifecycle(
    private val scope: CoroutineScope,
    private val dataPlane: DataPlane,
    private val awaitReleaseDeadline: suspend () -> Unit = {
        delay(RELEASE_COMPLETION_DEADLINE_MS)
    },
) {
    private val messages = Channel<Message>()
    private val mutableObservation = MutableStateFlow(INITIAL_OBSERVATION)
    private var releaseInFlight: DataPlaneLease? = null

    val observation: StateFlow<ConnectionObservation> = mutableObservation.asStateFlow()

    init {
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            for (message in messages) {
                when (message) {
                    is Message.Request -> accept(message)
                    is Message.Established -> publishReadiness(message.outcome)
                    is Message.ReleaseDeadlineExceeded -> {
                        publishReleaseDeadlineExceeded(message.lease)
                    }
                    is Message.ReleaseFailed -> publishReleaseFailure(message.lease)
                    is Message.Released -> publishReleaseCompletion(message.lease)
                }
            }
        }
    }

    suspend fun request(request: ConnectionRequest): RequestDecision {
        val decision = CompletableDeferred<RequestDecision>()
        messages.send(Message.Request(request, decision))
        return decision.await()
    }

    private fun accept(message: Message.Request) {
        when (message.request) {
            ConnectionRequest.Connect -> {
                val previous = mutableObservation.value
                if (previous.phase == LifecyclePhase.Releasing) {
                    message.decision.complete(
                        RequestDecision.Rejected(RequestDecision.Reason.ReleaseInProgress),
                    )
                    return
                }
                mutableObservation.value = previous.copy(
                    revision = previous.revision + 1,
                    phase = LifecyclePhase.Establishing,
                )
                message.decision.complete(RequestDecision.Accepted)
                scope.launch {
                    messages.send(Message.Established(dataPlane.establish()))
                }
            }

            is ConnectionRequest.Disconnect -> {
                val previous = mutableObservation.value
                if (previous.phase == LifecyclePhase.Releasing) {
                    message.decision.complete(RequestDecision.Accepted)
                    if (releaseInFlight == null) {
                        previous.session?.let { session ->
                            startRelease(session.lease)
                        }
                    }
                    return
                }
                mutableObservation.value = previous.copy(
                    revision = previous.revision + 1,
                    phase = LifecyclePhase.Releasing,
                )
                message.decision.complete(RequestDecision.Accepted)
                previous.session?.let { session ->
                    startRelease(session.lease)
                }
            }
        }
    }

    private fun publishReadiness(outcome: EstablishmentOutcome) {
        when (outcome) {
            is EstablishmentOutcome.Ready -> {
                val previous = mutableObservation.value
                if (previous.phase == LifecyclePhase.Releasing) {
                    mutableObservation.value = previous.copy(
                        revision = previous.revision + 1,
                        ownership = DataPlaneOwnership.Owned,
                        routeHealth = RouteHealth.Unknown,
                        session = null,
                    )
                    startRelease(outcome.lease)
                    return
                }
                mutableObservation.value = ConnectionObservation(
                    revision = previous.revision + 1,
                    phase = LifecyclePhase.Active,
                    ownership = DataPlaneOwnership.Owned,
                    routeHealth = RouteHealth.Unknown,
                    session = ConnectionSession(outcome.lease),
                )
            }
        }
    }

    private fun startRelease(lease: DataPlaneLease) {
        releaseInFlight = lease
        scope.launch {
            val deadline = launch {
                awaitReleaseDeadline()
                messages.send(Message.ReleaseDeadlineExceeded(lease))
            }
            try {
                dataPlane.release(lease)
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (_: Throwable) {
                deadline.cancelAndJoin()
                messages.send(Message.ReleaseFailed(lease))
                return@launch
            }
            deadline.cancelAndJoin()
            messages.send(Message.Released(lease))
        }
    }

    private fun publishReleaseDeadlineExceeded(lease: DataPlaneLease) {
        if (releaseInFlight != lease) return
        val previous = mutableObservation.value
        if (previous.phase != LifecyclePhase.Releasing) return
        val failure = OperationFailure(
            operation = OperationFailure.Operation.Release,
            stage = OperationFailure.Stage.CompletionDeadlineExceeded,
            resultingOwnership = DataPlaneOwnership.Uncertain,
        )
        mutableObservation.value = previous.copy(
            revision = previous.revision + 1,
            ownership = failure.resultingOwnership,
            failure = failure,
        )
    }

    private fun publishReleaseCompletion(lease: DataPlaneLease) {
        if (releaseInFlight != lease) return
        releaseInFlight = null
        val previous = mutableObservation.value
        if (previous.phase != LifecyclePhase.Releasing) return
        mutableObservation.value = previous.copy(
            revision = previous.revision + 1,
            phase = LifecyclePhase.Inactive,
            ownership = DataPlaneOwnership.Absent,
            routeHealth = RouteHealth.Unknown,
            session = null,
        )
    }

    private fun publishReleaseFailure(lease: DataPlaneLease) {
        if (releaseInFlight != lease) return
        releaseInFlight = null
        val previous = mutableObservation.value
        if (previous.phase != LifecyclePhase.Releasing) return
        val failure = OperationFailure(
            operation = OperationFailure.Operation.Release,
            stage = OperationFailure.Stage.DataPlaneReleaseFailed,
            resultingOwnership = DataPlaneOwnership.Uncertain,
        )
        mutableObservation.value = previous.copy(
            revision = previous.revision + 1,
            ownership = failure.resultingOwnership,
            failure = failure,
        )
    }

    private sealed interface Message {
        data class Request(
            val request: ConnectionRequest,
            val decision: CompletableDeferred<RequestDecision>,
        ) : Message

        data class Established(val outcome: EstablishmentOutcome) : Message

        data class ReleaseDeadlineExceeded(val lease: DataPlaneLease) : Message

        data class ReleaseFailed(val lease: DataPlaneLease) : Message

        data class Released(val lease: DataPlaneLease) : Message
    }

    private companion object {
        const val RELEASE_COMPLETION_DEADLINE_MS = 30_000L

        val INITIAL_OBSERVATION = ConnectionObservation(
            revision = 0,
            phase = LifecyclePhase.Inactive,
            ownership = DataPlaneOwnership.Absent,
            routeHealth = RouteHealth.Unknown,
            session = null,
        )
    }
}
