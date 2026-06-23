package com.whitedns.vpn

enum class NetworkRecoveryFailureAction {
    PreserveActiveVpn,
    IgnoreStaleFailure,
}

object NetworkChangeRecoveryPolicy {
    const val DEFAULT_DEBOUNCE_MS = 2_000L

    fun shouldRecover(
        state: VpnState,
        nowMs: Long,
        lastRecoveryAtMs: Long,
        isRecoveryActive: Boolean,
        debounceMs: Long = DEFAULT_DEBOUNCE_MS,
    ): Boolean {
        if (state != VpnState.Started) return false
        if (isRecoveryActive) return false
        return lastRecoveryAtMs <= 0L || nowMs >= lastRecoveryAtMs + debounceMs
    }

    fun failureActionFor(state: VpnState): NetworkRecoveryFailureAction {
        return if (state == VpnState.Started) {
            NetworkRecoveryFailureAction.PreserveActiveVpn
        } else {
            NetworkRecoveryFailureAction.IgnoreStaleFailure
        }
    }
}
