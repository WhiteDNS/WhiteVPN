package com.whitedns.vpn.engines.partner

/** Partner libraries can include credentials in diagnostics. Only structured lifecycle events are retained. */
internal object AppLog {
    fun i(tag: String, message: String) = Unit
    fun w(tag: String, message: String) = Unit
    fun e(tag: String, message: String, error: Throwable? = null) = Unit
}
