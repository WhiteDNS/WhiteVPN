package com.whitedns.vpn

import android.content.Context

enum class ConnectionMode(val wireName: String) {
    Vpn("vpn"),
    Proxy("proxy"),
    ;

    companion object {
        fun fromWireName(value: String?): ConnectionMode =
            entries.firstOrNull { it.wireName == value } ?: Vpn
    }
}

object ConnectionModePolicy {
    fun shouldStartTun(mode: ConnectionMode, alwaysOn: Boolean, lockdown: Boolean): Boolean =
        mode == ConnectionMode.Vpn || alwaysOn || lockdown
}

class ConnectionModePreferenceStore(context: Context) {
    private val prefs = context.getSharedPreferences("white_dns_connection_mode", Context.MODE_PRIVATE)

    fun read(): ConnectionMode = ConnectionMode.fromWireName(prefs.getString(KEY_MODE, null))

    fun save(mode: ConnectionMode) {
        prefs.edit().putString(KEY_MODE, mode.wireName).apply()
    }

    private companion object {
        const val KEY_MODE = "mode"
    }
}
