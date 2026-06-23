package com.whitedns.vpn

import android.content.Context
import org.json.JSONObject

class WhiteDnsScanStateStore(context: Context) {
    private val prefs = context.getSharedPreferences("white_dns_scan_state", Context.MODE_PRIVATE)

    fun readLastEndpoint(): CleanIpResult? {
        return CleanIpCacheCodec.decode(prefs.getString(KEY_LAST_ENDPOINT, null)).firstOrNull()
    }

    fun saveLastEndpoint(endpoint: CleanIpResult) {
        prefs.edit()
            .putString(KEY_LAST_ENDPOINT, CleanIpCacheCodec.encode(listOf(endpoint)))
            .apply()
    }

    fun readLastSelectedProfile(profiles: List<ConnectionProfile>): ConnectionProfile? {
        return readLastSelectedProfileSelection(profiles)?.profile
    }

    fun readLastSelectedProfileSelection(
        profiles: List<ConnectionProfile>,
        nowMs: Long = System.currentTimeMillis(),
    ): SelectedConnectionProfile? {
        val value = prefs.getString(KEY_LAST_PROFILE, null) ?: return null
        val item = runCatching { JSONObject(value) }.getOrNull() ?: return null
        val fingerprint = item.optString("fingerprint").takeIf(String::isNotBlank)
        var profile: ConnectionProfile? = null
        if (fingerprint != null) {
            profile = profiles.firstOrNull { it.fingerprint == fingerprint }
        }
        if (profile == null) {
            val tag = item.optString("tag").takeIf(String::isNotBlank) ?: return null
            profile = profiles.firstOrNull { it.tag == tag }
        }
        profile ?: return null
        return SelectedConnectionProfile(
            profile = profile,
            delayMs = item.optInt("delayMs", Int.MAX_VALUE).takeIf { it > 0 } ?: Int.MAX_VALUE,
            selectedAt = item.optLong("selectedAt", nowMs).takeIf { it > 0L } ?: nowMs,
        )
    }

    fun saveLastSelectedProfile(selection: SelectedConnectionProfile) {
        prefs.edit()
            .putString(
                KEY_LAST_PROFILE,
                JSONObject()
                    .put("tag", selection.profile.tag)
                    .put("type", selection.profile.type)
                    .put("server", selection.profile.server)
                    .put("port", selection.profile.port)
                    .put("validationHost", selection.profile.validationHost)
                    .put("fingerprint", selection.profile.fingerprint)
                    .put("delayMs", selection.delayMs)
                    .put("selectedAt", selection.selectedAt)
                    .toString(),
            )
            .apply()
    }

    fun pruneLastSelectedProfile(profiles: List<ConnectionProfile>): Boolean {
        if (!prefs.contains(KEY_LAST_PROFILE)) return false
        if (readLastSelectedProfile(profiles) != null) return false
        prefs.edit().remove(KEY_LAST_PROFILE).apply()
        return true
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val KEY_LAST_ENDPOINT = "last_endpoint"
        const val KEY_LAST_PROFILE = "last_profile"
    }
}
