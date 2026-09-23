package com.whitedns.vpn

import android.content.Context

internal fun isFreshConnectionHint(
    timestampMs: Long,
    nowMs: Long,
    ttlMs: Long = ProfileDelayCacheDefaults.DELAY_CACHE_TTL_MS,
): Boolean = timestampMs > 0L && nowMs - timestampMs in 0..ttlMs

class CleanIpCache(context: Context) {
    private val prefs = context.getSharedPreferences("white_dns_clean_ip", Context.MODE_PRIVATE)

    fun readResults(
        nowMs: Long = System.currentTimeMillis(),
        ttlMs: Long = ProfileDelayCacheDefaults.DELAY_CACHE_TTL_MS,
    ): List<CleanIpResult> {
        return prefs.all.keys
            .filter(CleanIpCacheKeys::isScopedKey)
            .flatMap { key -> CleanIpCacheCodec.decode(prefs.getString(key, null)) }
            .filter { isFreshConnectionHint(it.checkedAt, nowMs, ttlMs) }
            .groupBy { "${it.ip}:${it.port}" }
            .map { (_, results) -> results.maxBy { it.checkedAt } }
            .sortedForConnection()
    }

    fun saveResult(result: CleanIpResult, nowMs: Long = System.currentTimeMillis()) {
        val merged = (CleanIpCacheCodec.decode(prefs.getString(CleanIpCacheKeys.forPort(result.port), null)) + result)
            .filter { it.port == result.port }
            .filter { isFreshConnectionHint(it.checkedAt, nowMs) }
            .groupBy { "${it.ip}:${it.port}" }
            .map { (_, results) -> results.maxBy { it.checkedAt } }
            .sortedForConnection()
            .take(MAX_RESULTS)
        prefs.edit().putString(CleanIpCacheKeys.forPort(result.port), CleanIpCacheCodec.encode(merged)).apply()
    }

    fun pruneForProfiles(profiles: List<ConnectionProfile>): Int {
        val validKeys = profiles.map { CleanIpCacheKeys.forPort(it.port) }.toSet()
        val staleKeys = prefs.all.keys
            .filter { CleanIpCacheKeys.isScopedKey(it) && it !in validKeys }
        if (staleKeys.isEmpty()) return 0
        val editor = prefs.edit()
        staleKeys.forEach(editor::remove)
        editor.apply()
        return staleKeys.size
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val MAX_RESULTS = 10
    }
}

object CleanIpCacheKeys {
    private const val KEY_RESULTS = "clean_ip_results"

    fun forPort(port: Int): String = "$KEY_RESULTS:port:$port"

    fun isScopedKey(key: String): Boolean = key.startsWith("$KEY_RESULTS:")
}
