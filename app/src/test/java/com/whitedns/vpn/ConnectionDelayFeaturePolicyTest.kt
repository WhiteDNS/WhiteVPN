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
    fun automaticCandidatesUseSuccessfulResultsThenUntestedAndExcludeFailures() {
        val first = profile("first", 1)
        val lastSelected = profile("last", 2)
        val failed = profile("failed", 3)
        val slow = profile("slow", 4)
        val fast = profile("fast", 5)
        val records = listOf(
            record("sub", failed.fingerprint, null, ConnectionDelayStatus.Failure, 100),
            record("sub", slow.fingerprint, 200, ConnectionDelayStatus.Success, 100),
            record("sub", fast.fingerprint, 50, ConnectionDelayStatus.Success, 100),
        )

        assertEquals(
            listOf("fast", "slow", "last", "first"),
            AutomaticConnectionCandidatePolicy.order(
                profiles = listOf(first, lastSelected, failed, slow, fast),
                records = records,
                lastSelectedProfile = lastSelected,
            ).map(ConnectionProfile::tag),
        )
        assertTrue(
            AutomaticConnectionCandidatePolicy.order(
                profiles = listOf(failed),
                records = records,
                lastSelectedProfile = failed,
            ).isEmpty(),
        )
    }

    @Test
    fun startupCandidatesExcludePreviousLeafAndStopAtFive() {
        val profiles = (0..7).map { index -> profile("profile-$index", index + 1) }

        val ordered = AutomaticConnectionCandidatePolicy.order(
            profiles = profiles,
            records = emptyList(),
            lastSelectedProfile = profiles.first(),
            excludedFingerprint = profiles.first().fingerprint,
            limit = 5,
        )

        assertEquals(5, ordered.size)
        assertFalse(ordered.any { it.fingerprint == profiles.first().fingerprint })
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
    fun manualSpeedSessionCanBeReattachedWithoutChangingDelayState() {
        ConnectionDelayTestState.replace(
            ConnectionDelayTestSession(
                testId = "delay-test",
                subscriptionId = "speed-sub",
                connectionTypes = setOf("vless"),
                status = Actions.DELAY_TEST_COMPLETED,
            ),
        )
        ConnectionSpeedTestState.replace(
            ConnectionSpeedTestSession(
                testId = "speed-test",
                subscriptionId = "speed-sub",
                fingerprint = "profile",
                status = Actions.SPEED_TEST_STARTED,
            ),
        )

        assertTrue(ConnectionSpeedTestState.snapshot("speed-sub")?.isRunning == true)
        assertTrue(ConnectionSpeedTestState.isAnyRunning())
        assertNull(ConnectionSpeedTestState.snapshot("other-speed-sub"))

        ConnectionSpeedTestState.update("speed-test") {
            it.copy(status = Actions.SPEED_TEST_COMPLETED)
        }

        assertFalse(ConnectionSpeedTestState.snapshot("speed-sub")?.isRunning == true)
        assertFalse(ConnectionSpeedTestState.isAnyRunning())
        assertEquals("delay-test", ConnectionDelayTestState.snapshot("speed-sub")?.testId)
    }

    @Test
    fun liveResultsAlwaysUseDelayOrderAndIgnoreMeasuredSpeed() {
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
            listOf("delay-only", "low-delay", "fast", "pending"),
            ConnectionTestResultOrder.order(
                profiles,
                records,
                pendingFingerprints = setOf(pending.fingerprint),
            ).map(ConnectionProfile::tag),
        )
        assertEquals(8_000, ConnectionSpeed.kbps(1_000_000, 1_000_000_000))
    }

    @Test
    fun connectionTestSettingsUseExpectedDefaultsAndClampInvalidValues() {
        assertEquals(ConnectionTestSettings(15, 10, 1), ConnectionTestSettings())
        assertEquals(ConnectionTestSettings(1, 100, 1), ConnectionTestSettings(-1, 999, 0).normalized())
        assertEquals(ConnectionTestSettings(30, 1, 100), ConnectionTestSettings(99, 0, 999).normalized())
        assertEquals(5_000_000L, ConnectionTestSettings(speedTestMegabytes = 5).speedTestBytes)
    }

    @Test
    fun delayResultUiRefreshesAreLimitedToTwicePerSecond() {
        assertEquals(0L, ConnectionDelayUiRefreshPolicy.delayUntilNextRefresh(1_000L, null))
        assertEquals(500L, ConnectionDelayUiRefreshPolicy.delayUntilNextRefresh(1_000L, 1_000L))
        assertEquals(1L, ConnectionDelayUiRefreshPolicy.delayUntilNextRefresh(1_499L, 1_000L))
        assertEquals(0L, ConnectionDelayUiRefreshPolicy.delayUntilNextRefresh(1_500L, 1_000L))
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
