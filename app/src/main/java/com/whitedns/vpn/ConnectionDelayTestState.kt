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
    val speedTestEnabled: Boolean = false,
    val error: String = "",
) {
    val isRunning: Boolean
        get() = status == Actions.DELAY_TEST_PREPARING ||
            status == Actions.DELAY_TEST_STARTED ||
            status == Actions.DELAY_TEST_PROGRESS
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
}

object ConnectionTestResultOrder {
    fun order(
        profiles: List<ConnectionProfile>,
        records: Map<String, ConnectionDelayRecord>,
        speedTestEnabled: Boolean,
        pendingFingerprints: Set<String> = emptySet(),
    ): List<ConnectionProfile> {
        val originalOrder = profiles.mapIndexed { index, profile -> profile.fingerprint to index }.toMap()
        fun record(profile: ConnectionProfile) =
            records[profile.fingerprint]?.takeUnless { profile.fingerprint in pendingFingerprints }

        return profiles.sortedWith(
            compareBy<ConnectionProfile> { profile ->
                val result = record(profile)
                when {
                    speedTestEnabled && result?.speedKbps != null -> 0
                    result?.status == ConnectionDelayStatus.Success && result.delayMs != null -> 1
                    else -> 2
                }
            }.thenByDescending { profile ->
                record(profile)?.speedKbps?.takeIf { speedTestEnabled } ?: -1
            }.thenBy { profile ->
                record(profile)?.delayMs ?: Int.MAX_VALUE
            }.thenBy { profile ->
                originalOrder[profile.fingerprint] ?: Int.MAX_VALUE
            },
        )
    }
}
