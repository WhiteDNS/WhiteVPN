package com.whitedns.vpn

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class ConnectionTestWakeLockTest {
    @Test
    fun overlappingTestsAndReplacementKeepTheLockUntilTheLastFinishes() = runBlocking {
        val power = RecordingPower(this)
        val first = launch(start = CoroutineStart.UNDISPATCHED) {
            power.lock.hold { CompletableDeferred<Unit>().await() }
        }
        val secondDone = CompletableDeferred<Unit>()
        val second = launch(start = CoroutineStart.UNDISPATCHED) {
            power.lock.hold { secondDone.await() }
        }
        first.cancelAndJoin()
        assertTrue(power.held)
        val replacementDone = CompletableDeferred<Unit>()
        val replacement = launch(start = CoroutineStart.UNDISPATCHED) {
            power.lock.hold { replacementDone.await() }
        }
        secondDone.complete(Unit)
        second.join()
        assertTrue(power.held)
        replacementDone.complete(Unit)
        replacement.join()
        assertFalse(power.held)
        assertEquals(listOf(120_000L), power.acquisitions)
        assertEquals(1, power.releases)
    }

    @Test
    fun pausedWorkersReleaseAfterTheirLastProbeAndReacquireOnResume() = runBlocking {
        val power = RecordingPower(this)
        val probeDone = CompletableDeferred<Unit>()
        val paused = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val worker = launch(start = CoroutineStart.UNDISPATCHED) {
            power.lock.hold { probeDone.await() }
            paused.complete(Unit)
            resume.await()
            power.lock.hold { assertTrue(power.held) }
        }
        assertTrue(power.held)
        probeDone.complete(Unit)
        paused.await()
        assertFalse(power.held)
        resume.complete(Unit)
        worker.join()
        assertFalse(power.held)
        assertEquals(2, power.acquisitions.size)
        assertEquals(2, power.releases)
    }

    @Test
    fun cancellationKeepsPowerThroughCleanupAndFailureReleasesIt() = runBlocking {
        val power = RecordingPower(this)
        val cleaning = CompletableDeferred<Unit>()
        val cleanupDone = CompletableDeferred<Unit>()
        val worker = launch(start = CoroutineStart.UNDISPATCHED) {
            power.lock.hold {
                try {
                    CompletableDeferred<Unit>().await()
                } finally {
                    withContext(NonCancellable) {
                        cleaning.complete(Unit)
                        cleanupDone.await()
                        assertTrue(power.held)
                    }
                }
            }
        }
        worker.cancel()
        cleaning.await()
        assertTrue(power.held)
        cleanupDone.complete(Unit)
        worker.join()
        assertFalse(power.held)
        val failure = runCatching {
            power.lock.hold { throw IOException("Probe failed") }
        }.exceptionOrNull()
        assertTrue(failure is IOException)
        assertFalse(power.held)
        assertEquals(2, power.releases)
    }

    @Test
    fun destructionReleasesOnceAndAllowsCleanupWithoutReacquiring() = runBlocking {
        val power = RecordingPower(this)
        var cleaned = false
        val worker = launch(start = CoroutineStart.UNDISPATCHED) {
            power.lock.hold {
                try {
                    CompletableDeferred<Unit>().await()
                } finally {
                    withContext(NonCancellable) {
                        power.lock.hold { cleaned = true }
                    }
                }
            }
        }
        power.lock.close()
        power.lock.close()
        assertFalse(power.held)
        worker.cancelAndJoin()
        assertTrue(cleaned)
        assertEquals(1, power.acquisitions.size)
        assertEquals(1, power.releases)
    }

    private class RecordingPower(scope: CoroutineScope) {
        var held = false
        var releases = 0
        val acquisitions = mutableListOf<Long>()
        val lock = ConnectionTestWakeLock(
            scope,
            acquire = { timeout ->
                acquisitions += timeout
                held = true
            },
            release = {
                check(held)
                releases += 1
                held = false
            },
        )
    }
}
