package com.whitedns.vpn

import android.content.Context

class ConnectionLocationPreferenceStore(context: Context) {
    private val prefs = context.getSharedPreferences("white_dns_connection_location", Context.MODE_PRIVATE)

    fun readSelectedCountryCode(): String? {
        return ConnectionLocationPolicy.normalizeCountryCode(prefs.getString(KEY_COUNTRY_CODE, null))
    }

    fun saveSelectedCountryCode(countryCode: String?) {
        val normalized = ConnectionLocationPolicy.normalizeCountryCode(countryCode)
        val editor = prefs.edit()
        if (normalized == null) {
            editor.remove(KEY_COUNTRY_CODE)
        } else {
            editor.putString(KEY_COUNTRY_CODE, normalized)
        }
        editor.apply()
    }

    fun clearSelectedCountry() {
        saveSelectedCountryCode(null)
    }

    private companion object {
        const val KEY_COUNTRY_CODE = "country_code"
    }
}
