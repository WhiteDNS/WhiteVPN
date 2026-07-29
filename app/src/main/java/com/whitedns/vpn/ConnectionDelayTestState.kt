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

object ConnectionDelayTestState {
    @Volatile
    private var session: ConnectionDelayTestSession? = null

    @Synchronized
    fun replace(value: ConnectionDelayTestSession): ConnectionDelayTestSession {
        session = value
        return value
    }

    @Synchronized
    fun update(
        testId: String,
        transform: (ConnectionDelayTestSession) -> ConnectionDelayTestSession,
    ): ConnectionDelayTestSession? {
        val current = session?.takeIf { it.testId == testId } ?: return null
        return transform(current).also { session = it }
    }

    fun snapshot(subscriptionId: String): ConnectionDelayTestSession? =
        session?.takeIf { it.subscriptionId == subscriptionId }
}
