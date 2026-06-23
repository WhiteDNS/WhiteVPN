package com.whitedns.vpn

object Actions {
    const val CONNECT = "com.whitedns.vpn.action.CONNECT"
    const val DISCONNECT = "com.whitedns.vpn.action.DISCONNECT"
    const val RECONNECT = "com.whitedns.vpn.action.RECONNECT"
    const val REFRESH = "com.whitedns.vpn.action.REFRESH"
    const val STATE_CHANGED = "com.whitedns.vpn.action.STATE_CHANGED"

    const val EXTRA_STATE = "extra_state"
    const val EXTRA_ERROR = "extra_error"
    const val EXTRA_SESSION_STARTED_AT_ELAPSED_MS = "extra_session_started_at_elapsed_ms"
    const val EXTRA_CONNECTION_COUNTRY_FLAG = "extra_connection_country_flag"
}
