package com.whitedns.vpn

data class ConnectionDelayTestSession(
    val testId: String,
    val subscriptionId: String,
    val connectionTypes: Set<String>,
    val targetFingerprints: List<String> = emptyList(),
    val finishedFingerprints: Set<String> = emptySet(),
    val status: String = Actions.DELAY_TEST_PREPARING,
    val completed: Int = 0,
    val total: Int = 0,
    val available: Int = 0,
    val paused: Boolean = false,
    val error: String = "",
) {
    val isRunning: Boolean
        get() = status == Actions.DELAY_TEST_PREPARING ||
            status == Actions.DELAY_TEST_STARTED ||
            status == Actions.DELAY_TEST_PROGRESS
}

data class ConnectionSpeedTestSession(
    val testId: String,
    val subscriptionId: String,
    val fingerprint: String,
    val status: String = Actions.SPEED_TEST_PREPARING,
    val error: String = "",
) {
    val isRunning: Boolean
        get() = status == Actions.SPEED_TEST_PREPARING || status == Actions.SPEED_TEST_STARTED
}

object ConnectionSpeedTestState {
    private val sessions = mutableMapOf<String, ConnectionSpeedTestSession>()

    @Synchronized
    fun replace(value: ConnectionSpeedTestSession): ConnectionSpeedTestSession {
        sessions[value.subscriptionId] = value
        return value
    }

    @Synchronized
    fun update(
        testId: String,
        transform: (ConnectionSpeedTestSession) -> ConnectionSpeedTestSession,
    ): ConnectionSpeedTestSession? {
        val current = sessions.values.firstOrNull { it.testId == testId } ?: return null
        return transform(current).also { sessions[current.subscriptionId] = it }
    }

    @Synchronized
    fun snapshot(subscriptionId: String): ConnectionSpeedTestSession? = sessions[subscriptionId]

    @Synchronized
    fun isAnyRunning(): Boolean = sessions.values.any(ConnectionSpeedTestSession::isRunning)
}

object ConnectionDelayTestState {
    private val sessions = mutableMapOf<String, ConnectionDelayTestSession>()

    @Synchronized
    fun replace(value: ConnectionDelayTestSession): ConnectionDelayTestSession {
        sessions[value.subscriptionId] = value
        return value
    }

    @Synchronized
    fun update(
        testId: String,
        transform: (ConnectionDelayTestSession) -> ConnectionDelayTestSession,
    ): ConnectionDelayTestSession? {
        val current = sessions.values.firstOrNull { it.testId == testId } ?: return null
        return transform(current).also { sessions[current.subscriptionId] = it }
    }

    @Synchronized
    fun snapshot(subscriptionId: String): ConnectionDelayTestSession? = sessions[subscriptionId]

    @Synchronized
    fun isAnyRunning(): Boolean = sessions.values.any(ConnectionDelayTestSession::isRunning)
}

object ConnectionTestResultOrder {
    fun order(
        profiles: List<ConnectionProfile>,
        records: Map<String, ConnectionDelayRecord>,
        pendingFingerprints: Set<String> = emptySet(),
    ): List<ConnectionProfile> {
        val originalOrder = profiles.mapIndexed { index, profile -> profile.fingerprint to index }.toMap()
        fun record(profile: ConnectionProfile) =
            records[profile.fingerprint]?.takeUnless { profile.fingerprint in pendingFingerprints }

        return profiles.sortedWith(
            compareBy<ConnectionProfile> { profile ->
                val result = record(profile)
                when {
                    result?.status == ConnectionDelayStatus.Success && result.delayMs != null -> 0
                    else -> 1
                }
            }.thenBy { profile ->
                record(profile)?.delayMs ?: Int.MAX_VALUE
            }.thenBy { profile ->
                originalOrder[profile.fingerprint] ?: Int.MAX_VALUE
            },
        )
    }
}
