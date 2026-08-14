package com.whitedns.vpn

import android.content.Context

object VpnRuntimeStateStore {
    private const val PREFS = "white_dns_runtime_state"
    private const val KEY_STATE = "state"
    private const val KEY_ERROR = "error"
    private const val KEY_SESSION_STARTED_AT_ELAPSED_MS = "session_started_at_elapsed_ms"
    private const val KEY_CONNECTION_COUNTRY_FLAG = "connection_country_flag"
    private const val KEY_DEBUG_FRONTING_IP = "debug_fronting_ip"
    private const val KEY_CONNECTION_DETAILS = "connection_details"
    private const val KEY_ALWAYS_ON = "always_on"
    private const val KEY_LOCKDOWN = "lockdown"

    fun save(
        context: Context,
        state: VpnState,
        sessionStartedAtElapsedMs: Long = 0L,
        connectionCountryFlag: String = "",
        debugFrontingIp: String = "",
        connectionDetails: String = "",
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
            .putBoolean(KEY_ALWAYS_ON, alwaysOn)
            .putBoolean(KEY_LOCKDOWN, lockdown)
            .apply()
    }

    fun read(context: Context): VpnState {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val state = VpnState.fromWireName(
            prefs.getString(KEY_STATE, VpnState.Stopped.wireName),
            prefs.getString(KEY_ERROR, null),
        )
        if (state == VpnState.DailyLimitReached) {
            save(context, VpnState.Stopped)
            return VpnState.Stopped
        }
        return state
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

    fun readAlwaysOn(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ALWAYS_ON, false)
    }

    fun readLockdown(context: Context): Boolean {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_LOCKDOWN, false)
    }
}
