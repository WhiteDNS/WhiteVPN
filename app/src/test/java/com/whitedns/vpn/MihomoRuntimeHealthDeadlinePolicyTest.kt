package com.whitedns.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MihomoRuntimeHealthDeadlinePolicyTest {
    @Test
    fun worstCaseProbeBudgetsStayInsideFiveAndTwentySecondDeadlines() {
        assertTrue(worstCaseProbeDurationMs(5_000L, 3) <= 5_000L)
        assertTrue(worstCaseProbeDurationMs(20_000L, 3) <= 20_000L)
        assertEquals(3_000, MihomoRuntimeHealthDeadlinePolicy.probeTimeoutMs(20_000L, 0L, 3))
    }

    @Test
    fun probeBudgetUsesRemainingTimeUrlsAndBothHttpPhases() {
        assertEquals(833, MihomoRuntimeHealthDeadlinePolicy.probeTimeoutMs(5_000L, 0L, 3))
        assertEquals(1_000, MihomoRuntimeHealthDeadlinePolicy.probeTimeoutMs(5_000L, 1_000L, 2))
        assertNull(MihomoRuntimeHealthDeadlinePolicy.probeTimeoutMs(5_000L, 5_000L, 1))
        assertNull(MihomoRuntimeHealthDeadlinePolicy.probeTimeoutMs(5_000L, 0L, 0))
    }

    @Test
    fun pollingNeverExceedsFiveHundredMillisecondsOrRemainingTime() {
        assertEquals(500L, MihomoRuntimeHealthDeadlinePolicy.pollDelayMs(5_000L, 4_000L))
        assertEquals(200L, MihomoRuntimeHealthDeadlinePolicy.pollDelayMs(5_000L, 4_800L))
        assertEquals(100L, MihomoRuntimeHealthDeadlinePolicy.pollDelayMs(5_000L, 4_000L, 100L))
        assertEquals(0L, MihomoRuntimeHealthDeadlinePolicy.pollDelayMs(5_000L, 5_000L))
    }

    private fun worstCaseProbeDurationMs(totalTimeoutMs: Long, urlCount: Int): Long {
        val deadlineMs = MihomoRuntimeHealthDeadlinePolicy.deadlineMs(0L, totalTimeoutMs)
        var nowMs = 0L
        repeat(urlCount) { index ->
            val timeoutMs = MihomoRuntimeHealthDeadlinePolicy.probeTimeoutMs(
                deadlineMs = deadlineMs,
                nowMs = nowMs,
                remainingUrlCount = urlCount - index,
            ) ?: return nowMs
            nowMs += timeoutMs * 2L
        }
        return nowMs
    }
}
