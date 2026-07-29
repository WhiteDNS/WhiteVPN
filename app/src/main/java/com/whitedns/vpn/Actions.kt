package com.whitedns.vpn

object Actions {
    const val CONNECT = "com.whitedns.vpn.action.CONNECT"
    const val DISCONNECT = "com.whitedns.vpn.action.DISCONNECT"
    const val RECONNECT = "com.whitedns.vpn.action.RECONNECT"
    const val REFRESH = "com.whitedns.vpn.action.REFRESH"
    const val TEST_CONNECTION_DELAYS = "com.whitedns.vpn.action.TEST_CONNECTION_DELAYS"
    const val PAUSE_CONNECTION_DELAY_TEST = "com.whitedns.vpn.action.PAUSE_CONNECTION_DELAY_TEST"
    const val RESUME_CONNECTION_DELAY_TEST = "com.whitedns.vpn.action.RESUME_CONNECTION_DELAY_TEST"
    const val CANCEL_CONNECTION_DELAY_TEST = "com.whitedns.vpn.action.CANCEL_CONNECTION_DELAY_TEST"
    const val STATE_CHANGED = "com.whitedns.vpn.action.STATE_CHANGED"
    const val CONNECTION_DELAY_TEST_CHANGED = "com.whitedns.vpn.action.CONNECTION_DELAY_TEST_CHANGED"

    const val EXTRA_STATE = "extra_state"
    const val EXTRA_ERROR = "extra_error"
    const val EXTRA_NOTICE = "extra_notice"
    const val EXTRA_SESSION_STARTED_AT_ELAPSED_MS = "extra_session_started_at_elapsed_ms"
    const val EXTRA_CONNECTION_COUNTRY_FLAG = "extra_connection_country_flag"
    const val EXTRA_DEBUG_FRONTING_IP = "extra_debug_fronting_ip"
    const val EXTRA_CONNECTION_DETAILS = "extra_connection_details"
    const val EXTRA_APP_INITIATED = "extra_app_initiated"
    const val EXTRA_ALWAYS_ON = "extra_always_on"
    const val EXTRA_LOCKDOWN = "extra_lockdown"
    const val EXTRA_CONNECTION_TYPES = "extra_connection_types"
    const val EXTRA_DELAY_TEST_ID = "extra_delay_test_id"
    const val EXTRA_DELAY_TEST_STATUS = "extra_delay_test_status"
    const val EXTRA_DELAY_TEST_COMPLETED = "extra_delay_test_completed"
    const val EXTRA_DELAY_TEST_TOTAL = "extra_delay_test_total"
    const val EXTRA_DELAY_TEST_AVAILABLE = "extra_delay_test_available"
    const val EXTRA_DELAY_TEST_FINISHED = "extra_delay_test_finished"
    const val EXTRA_DELAY_TEST_PAUSED = "extra_delay_test_paused"
    const val EXTRA_DELAY_TEST_ERROR = "extra_delay_test_error"

    const val DELAY_TEST_PREPARING = "preparing"
    const val DELAY_TEST_STARTED = "started"
    const val DELAY_TEST_PROGRESS = "progress"
    const val DELAY_TEST_COMPLETED = "completed"
    const val DELAY_TEST_FAILED = "failed"
    const val DELAY_TEST_CANCELED = "canceled"

    fun resolveServiceAction(action: String?, appInitiated: Boolean): String? {
        return if (appInitiated) action else CONNECT
    }
}
