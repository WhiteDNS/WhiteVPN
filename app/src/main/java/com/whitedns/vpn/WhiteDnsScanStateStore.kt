package com.whitedns.vpn

import android.content.Context
import org.json.JSONObject

class WhiteDnsScanStateStore(context: Context) {
    private val prefs = context.getSharedPreferences("white_dns_scan_state", Context.MODE_PRIVATE)

    fun readLastEndpoint(
        nowMs: Long = System.currentTimeMillis(),
        ttlMs: Long = ProfileDelayCacheDefaults.DELAY_CACHE_TTL_MS,
    ): CleanIpResult? {
        val endpoint = CleanIpCacheCodec.decode(prefs.getString(KEY_LAST_ENDPOINT, null)).firstOrNull()
            ?: return null
        if (isFreshConnectionHint(endpoint.checkedAt, nowMs, ttlMs)) return endpoint
        prefs.edit().remove(KEY_LAST_ENDPOINT).apply()
        return null
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
        ttlMs: Long = ProfileDelayCacheDefaults.DELAY_CACHE_TTL_MS,
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
        val selectedAt = item.optLong("selectedAt", 0L)
        if (!isFreshConnectionHint(selectedAt, nowMs, ttlMs)) {
            prefs.edit().remove(KEY_LAST_PROFILE).apply()
            return null
        }
        return SelectedConnectionProfile(
            profile = profile,
            delayMs = item.optInt("delayMs", Int.MAX_VALUE).takeIf { it > 0 } ?: Int.MAX_VALUE,
            selectedAt = selectedAt,
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

    fun clearLastSelectedProfile(profile: ConnectionProfile): Boolean {
        val item = prefs.getString(KEY_LAST_PROFILE, null)
            ?.let { runCatching { JSONObject(it) }.getOrNull() }
            ?: return false
        val fingerprint = item.optString("fingerprint")
        val matches = if (fingerprint.isNotBlank()) {
            fingerprint == profile.fingerprint
        } else {
            item.optString("tag") == profile.tag
        }
        if (!matches) return false
        prefs.edit().remove(KEY_LAST_PROFILE).apply()
        return true
    }

    fun quarantineTlsEndpoint(
        scope: String,
        endpoint: CleanIpResult,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        prefs.edit()
            .putLong(tlsQuarantineKey(scope, endpoint), TlsIntegrityPolicy.quarantineUntil(nowMs))
            .apply()
    }

    fun isTlsEndpointQuarantined(
        scope: String,
        endpoint: CleanIpResult,
        nowMs: Long = System.currentTimeMillis(),
    ): Boolean {
        val key = tlsQuarantineKey(scope, endpoint)
        val untilMs = prefs.getLong(key, 0L)
        if (TlsIntegrityPolicy.isQuarantined(untilMs, nowMs)) return true
        if (prefs.contains(key)) prefs.edit().remove(key).apply()
        return false
    }

    fun clearTlsQuarantine() {
        val keys = prefs.all.keys.filter { it.startsWith(KEY_TLS_QUARANTINE_PREFIX) }
        if (keys.isEmpty()) return
        val editor = prefs.edit()
        keys.forEach(editor::remove)
        editor.apply()
    }

    fun clearConnectionHints() {
        prefs.edit()
            .remove(KEY_LAST_ENDPOINT)
            .remove(KEY_LAST_PROFILE)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun tlsQuarantineKey(scope: String, endpoint: CleanIpResult): String {
        return "$KEY_TLS_QUARANTINE_PREFIX$scope:${TlsIntegrityPolicy.endpointKey(endpoint)}"
    }

    private companion object {
        const val KEY_LAST_ENDPOINT = "last_endpoint"
        const val KEY_LAST_PROFILE = "last_profile"
        const val KEY_TLS_QUARANTINE_PREFIX = "tls_quarantine:"
    }
}
