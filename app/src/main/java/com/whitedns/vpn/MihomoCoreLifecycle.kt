package com.whitedns.vpn

internal enum class MihomoCoreState {
    Idle,
    SettingUp,
    Active,
    Stopping,
}

internal enum class MihomoCoreCleanupRequest {
    Claimed,
    PendingSetup,
    AlreadyStopping,
    AlreadyIdle,
}

internal enum class MihomoCoreSetupCompletion {
    Active,
    CleanupClaimed,
    Ignored,
}

/**
 * Serializes setup and cleanup of the process-wide Mihomo core.
 *
 * FlClash runs quickSetup and shutdown on native goroutines. Kotlin coroutine
 * timeouts do not cancel that work, so a new setup must not start until the
 * matching native callback has moved this lifecycle back to [MihomoCoreState.Idle].
 */
internal class MihomoCoreLifecycle {
    private var state = MihomoCoreState.Idle
    private var cleanupPending = false

    @Synchronized
    fun beginSetup(): Boolean {
        if (state != MihomoCoreState.Idle) return false
        state = MihomoCoreState.SettingUp
        cleanupPending = false
        return true
    }

    @Synchronized
    fun finishSetup(): MihomoCoreSetupCompletion {
        if (state != MihomoCoreState.SettingUp) return MihomoCoreSetupCompletion.Ignored
        return if (cleanupPending) {
            cleanupPending = false
            state = MihomoCoreState.Stopping
            MihomoCoreSetupCompletion.CleanupClaimed
        } else {
            state = MihomoCoreState.Active
            MihomoCoreSetupCompletion.Active
        }
    }

    @Synchronized
    fun requestCleanup(): MihomoCoreCleanupRequest = when (state) {
        MihomoCoreState.Idle -> MihomoCoreCleanupRequest.AlreadyIdle
        MihomoCoreState.SettingUp -> {
            cleanupPending = true
            MihomoCoreCleanupRequest.PendingSetup
        }
        MihomoCoreState.Active -> {
            state = MihomoCoreState.Stopping
            MihomoCoreCleanupRequest.Claimed
        }
        MihomoCoreState.Stopping -> MihomoCoreCleanupRequest.AlreadyStopping
    }

    @Synchronized
    fun finishCleanup(): Boolean {
        if (state != MihomoCoreState.Stopping) return false
        state = MihomoCoreState.Idle
        cleanupPending = false
        return true
    }

    @Synchronized
    fun currentState(): MihomoCoreState = state

    @Synchronized
    fun isIdle(): Boolean = state == MihomoCoreState.Idle

    @Synchronized
    fun isActive(): Boolean = state == MihomoCoreState.Active
}
