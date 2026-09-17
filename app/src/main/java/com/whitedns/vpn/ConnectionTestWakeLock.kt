package com.whitedns.vpn

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Shares one timed wake lock across preparation, probes, downloads, and cleanup. */
internal class ConnectionTestWakeLock(
    private val scope: CoroutineScope,
    private val acquire: (Long) -> Unit,
    private val release: () -> Unit,
) {
    private var users = 0
    private var closed = false
    private var renewalJob: Job? = null

    suspend fun <T> hold(block: suspend () -> T): T {
        synchronized(this) {
            if (!closed && users == 0) {
                acquire(TIMEOUT_MS)
                renewalJob = scope.launch {
                    while (isActive) {
                        delay(RENEWAL_INTERVAL_MS)
                        synchronized(this@ConnectionTestWakeLock) {
                            if (!closed && users > 0) acquire(TIMEOUT_MS)
                        }
                    }
                }
            }
            users += 1
        }
        try {
            return block()
        } finally {
            synchronized(this) {
                users -= 1
                if (!closed && users == 0) {
                    renewalJob?.cancel()
                    renewalJob = null
                    release()
                }
            }
        }
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        renewalJob?.cancel()
        renewalJob = null
        if (users > 0) release()
        // Canceled jobs can still enter non-cancellable native cleanup after destruction.
    }

    companion object {
        const val TIMEOUT_MS = 120_000L
        const val RENEWAL_INTERVAL_MS = 60_000L
    }
}
