package com.whitedns.vpn

import android.content.Context

object VpnRuntimeStateStore {
    private const val PREFS = "white_dns_runtime_state"
    private const val KEY_STATE = "state"
    private const val KEY_ERROR = "error"
    private const val KEY_SESSION_STARTED_AT_ELAPSED_MS = "session_started_at_elapsed_ms"
    private const val KEY_CONNECTION_COUNTRY_FLAG = "connection_country_flag"

    fun save(
        context: Context,
        state: VpnState,
        sessionStartedAtElapsedMs: Long = 0L,
        connectionCountryFlag: String = "",
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
}
