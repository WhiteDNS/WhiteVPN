package com.whitedns.vpn

import android.content.Context

private const val CURRENT_RUNTIME_STATE_SCHEMA = 1

internal fun restoredVpnState(state: VpnState, persistedSchema: Int): VpnState {
    if (state == VpnState.DailyLimitReached) return VpnState.Stopped
    if (persistedSchema >= CURRENT_RUNTIME_STATE_SCHEMA) return state
    return if (state == VpnState.Starting || state == VpnState.Started || state == VpnState.Stopping) {
        VpnState.Stopped
    } else {
        state
    }
}

object VpnRuntimeStateStore {
    private const val PREFS = "white_dns_runtime_state"
    private const val KEY_STATE = "state"
    private const val KEY_ERROR = "error"
    private const val KEY_SESSION_STARTED_AT_ELAPSED_MS = "session_started_at_elapsed_ms"
    private const val KEY_CONNECTION_COUNTRY_FLAG = "connection_country_flag"
    private const val KEY_DEBUG_FRONTING_IP = "debug_fronting_ip"
    private const val KEY_CONNECTION_DETAILS = "connection_details"
    private const val KEY_ACTIVE_SUBSCRIPTION_ID = "active_subscription_id"
    private const val KEY_ACTIVE_CONNECTION_TAG = "active_connection_tag"
    private const val KEY_ACTIVE_CONNECTION_FINGERPRINT = "active_connection_fingerprint"
    private const val KEY_CHAIN_HOP_COUNT = "chain_hop_count"
    private const val KEY_LIVE_SELECTOR_READY = "live_selector_ready"
    private const val KEY_SELECTABLE_CONNECTION_FINGERPRINTS = "selectable_connection_fingerprints"
    private const val KEY_ALWAYS_ON = "always_on"
    private const val KEY_LOCKDOWN = "lockdown"
    private const val KEY_STATE_SCHEMA = "state_schema"

    fun save(
        context: Context,
        state: VpnState,
        sessionStartedAtElapsedMs: Long = 0L,
        connectionCountryFlag: String = "",
        debugFrontingIp: String = "",
        connectionDetails: String = "",
        activeSubscriptionId: String = "",
        activeConnectionTag: String = "",
        activeConnectionFingerprint: String = "",
        chainHopCount: Int = 0,
        liveSelectorReady: Boolean = false,
        selectableConnectionFingerprints: Set<String> = emptySet(),
        alwaysOn: Boolean = false,
        lockdown: Boolean = false,
    ) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_STATE, state.wireName)
            .putString(KEY_ERROR, (state as? VpnState.Error)?.message.orEmpty())
            .putLong(
                KEY_SESSION_STARTED_AT_ELAPSED_MS,
                if (state == VpnState.Started) sessionStartedAtElapsedMs else 0L,
            )
            .putString(KEY_CONNECTION_COUNTRY_FLAG, if (state == VpnState.Started) connectionCountryFlag else "")
            .putString(KEY_DEBUG_FRONTING_IP, if (state == VpnState.Started) debugFrontingIp else "")
            .putString(KEY_CONNECTION_DETAILS, if (state == VpnState.Started) connectionDetails else "")
            .putString(KEY_ACTIVE_SUBSCRIPTION_ID, if (state == VpnState.Started) activeSubscriptionId else "")
            .putString(KEY_ACTIVE_CONNECTION_TAG, if (state == VpnState.Started) activeConnectionTag else "")
            .putString(
                KEY_ACTIVE_CONNECTION_FINGERPRINT,
                if (state == VpnState.Started) activeConnectionFingerprint else "",
            )
            .putInt(KEY_CHAIN_HOP_COUNT, if (state == VpnState.Started) chainHopCount else 0)
            .putBoolean(KEY_LIVE_SELECTOR_READY, state == VpnState.Started && liveSelectorReady)
            .putStringSet(
                KEY_SELECTABLE_CONNECTION_FINGERPRINTS,
                if (state == VpnState.Started) selectableConnectionFingerprints else emptySet(),
            )
            .putBoolean(KEY_ALWAYS_ON, alwaysOn)
            .putBoolean(KEY_LOCKDOWN, lockdown)
            .putInt(KEY_STATE_SCHEMA, CURRENT_RUNTIME_STATE_SCHEMA)
            .apply()
    }

    fun read(context: Context): VpnState {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val state = VpnState.fromWireName(
            prefs.getString(KEY_STATE, VpnState.Stopped.wireName),
            prefs.getString(KEY_ERROR, null),
        )
        val restoredState = restoredVpnState(state, prefs.getInt(KEY_STATE_SCHEMA, 0))
        if (restoredState != state) {
            save(context, restoredState)
        }
        return restoredState
    }

    fun readSessionStartedAtElapsedMs(context: Context): Long {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getLong(KEY_SESSION_STARTED_AT_ELAPSED_MS, 0L)
    }

    fun readConnectionCountryFlag(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CONNECTION_COUNTRY_FLAG, null)
            .orEmpty()
    }

    fun readDebugFrontingIp(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_DEBUG_FRONTING_IP, null)
            .orEmpty()
    }

    fun readConnectionDetails(context: Context): String {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_CONNECTION_DETAILS, null)
            .orEmpty()
    }

    fun readActiveSubscriptionId(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ACTIVE_SUBSCRIPTION_ID, null)
            .orEmpty()

    fun readActiveConnectionTag(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ACTIVE_CONNECTION_TAG, null)
            .orEmpty()

    fun readActiveConnectionFingerprint(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_ACTIVE_CONNECTION_FINGERPRINT, null)
            .orEmpty()

    fun readChainHopCount(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY_CHAIN_HOP_COUNT, 0)

    fun readLiveSelectorReady(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_LIVE_SELECTOR_READY, false)

    fun readSelectableConnectionFingerprints(context: Context): Set<String> =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getStringSet(KEY_SELECTABLE_CONNECTION_FINGERPRINTS, emptySet())
            .orEmpty()
            .toSet()

    fun readAlwaysOn(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ALWAYS_ON, false)
    }

    fun readLockdown(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_LOCKDOWN, false)
    }
}
