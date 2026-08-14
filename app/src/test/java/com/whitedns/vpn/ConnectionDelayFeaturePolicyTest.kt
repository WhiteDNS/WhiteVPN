package com.whitedns.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionDelayFeaturePolicyTest {
    @Test
    fun latestRecordReplacesHistoricalMinimumWithoutAResultLimit() {
        val oldFast = record("sub", "profile-0", 10, ConnectionDelayStatus.Success, 100)
        val latestFailure = record("sub", "profile-0", null, ConnectionDelayStatus.Failure, 200)
        val otherRecords = (1..30).map { index ->
            record("sub", "profile-$index", index, ConnectionDelayStatus.Success, 200)
        }

        val latest = ConnectionDelayRecordPolicy.latest(listOf(oldFast, latestFailure) + otherRecords)

        assertEquals(31, latest.size)
        assertEquals(ConnectionDelayStatus.Failure, latest.single { it.fingerprint == "profile-0" }.status)
        assertNull(latest.single { it.fingerprint == "profile-0" }.delayMs)
    }

    @Test
    fun automaticCandidatesUseFreshDelayThenLastSelectionThenSubscriptionOrder() {
        val first = profile("first", 1)
        val lastSelected = profile("last", 2)
        val slow = profile("slow", 3)
        val fast = profile("fast", 4)
        val records = listOf(
            record("sub", slow.fingerprint, 200, ConnectionDelayStatus.Success, 100),
            record("sub", fast.fingerprint, 50, ConnectionDelayStatus.Success, 100),
        )

        assertEquals(
            listOf("fast", "slow", "last", "first"),
            AutomaticConnectionCandidatePolicy.order(
                profiles = listOf(first, lastSelected, slow, fast),
                records = records,
                lastSelectedProfile = lastSelected,
            ).map(ConnectionProfile::tag),
        )
    }

    @Test
    fun activeTestSessionCanBeReattachedAndUpdated() {
        ConnectionDelayTestState.replace(
            ConnectionDelayTestSession(
                testId = "test",
                subscriptionId = "sub",
                connectionTypes = setOf("vless"),
                status = Actions.DELAY_TEST_STARTED,
                total = 2,
            ),
        )

        assertTrue(ConnectionDelayTestState.snapshot("sub")?.isRunning == true)
        assertNull(ConnectionDelayTestState.snapshot("other"))

        ConnectionDelayTestState.update("test") {
            it.copy(
                status = Actions.DELAY_TEST_COMPLETED,
                completed = 2,
                available = 1,
            )
        }

        val completed = ConnectionDelayTestState.snapshot("sub")
        assertFalse(completed?.isRunning == true)
        assertEquals(2, completed?.completed)
        assertEquals(1, completed?.available)

        ConnectionDelayTestState.replace(
            ConnectionDelayTestSession(
                testId = "other-test",
                subscriptionId = "other",
                connectionTypes = setOf("wireguard"),
            ),
        )

        assertEquals("test", ConnectionDelayTestState.snapshot("sub")?.testId)
        assertEquals("other-test", ConnectionDelayTestState.snapshot("other")?.testId)
    }

    @Test
    fun liveResultsUseSpeedThenDelayOrDelayOnly() {
        val pending = profile("pending", 1)
        val delayOnly = profile("delay-only", 2)
        val fast = profile("fast", 3)
        val lowDelay = profile("low-delay", 4)
        val profiles = listOf(pending, delayOnly, fast, lowDelay)
        val records = listOf(
            record("sub", pending.fingerprint, 1, ConnectionDelayStatus.Success, 100, 100_000),
            record("sub", delayOnly.fingerprint, 10, ConnectionDelayStatus.Success, 100),
            record("sub", fast.fingerprint, 80, ConnectionDelayStatus.Success, 100, 5_000),
            record("sub", lowDelay.fingerprint, 20, ConnectionDelayStatus.Success, 100, 2_000),
        ).associateBy(ConnectionDelayRecord::fingerprint)

        assertEquals(
            listOf("fast", "low-delay", "delay-only", "pending"),
            ConnectionTestResultOrder.order(
                profiles,
                records,
                speedTestEnabled = true,
                pendingFingerprints = setOf(pending.fingerprint),
            ).map(ConnectionProfile::tag),
        )
        assertEquals(
            listOf("delay-only", "low-delay", "fast", "pending"),
            ConnectionTestResultOrder.order(
                profiles,
                records,
                speedTestEnabled = false,
                pendingFingerprints = setOf(pending.fingerprint),
            ).map(ConnectionProfile::tag),
        )
        assertEquals(8_000, ConnectionSpeed.kbps(1_000_000, 1_000_000_000))
    }

    private fun record(
        subscriptionId: String,
        fingerprint: String,
        delayMs: Int?,
        status: ConnectionDelayStatus,
        testedAt: Long,
        speedKbps: Int? = null,
    ) = ConnectionDelayRecord(subscriptionId, fingerprint, delayMs, status, testedAt, speedKbps)

    private fun profile(tag: String, port: Int) = ConnectionProfile(
        tag = tag,
        type = "vless",
        server = "$tag.example.com",
        port = port,
        transport = "",
        validationHost = "$tag.example.com",
    )
}
