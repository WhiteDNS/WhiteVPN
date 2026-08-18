package com.whitedns.vpn

import android.content.Context

data class ConnectionTestSettings(
    val timeoutSeconds: Int = DEFAULT_TIMEOUT_SECONDS,
    val concurrency: Int = DEFAULT_CONCURRENCY,
    val speedTestMegabytes: Int = DEFAULT_SPEED_TEST_MEGABYTES,
) {
    fun normalized(): ConnectionTestSettings = copy(
        timeoutSeconds = timeoutSeconds.coerceIn(MIN_TIMEOUT_SECONDS, MAX_TIMEOUT_SECONDS),
        concurrency = concurrency.coerceIn(MIN_CONCURRENCY, MAX_CONCURRENCY),
        speedTestMegabytes = speedTestMegabytes.coerceIn(
            MIN_SPEED_TEST_MEGABYTES,
            MAX_SPEED_TEST_MEGABYTES,
        ),
    )

    val speedTestBytes: Long
        get() = speedTestMegabytes * BYTES_PER_MEGABYTE

    companion object {
        const val DEFAULT_TIMEOUT_SECONDS = 15
        const val MIN_TIMEOUT_SECONDS = 1
        const val MAX_TIMEOUT_SECONDS = 30
        const val DEFAULT_CONCURRENCY = 10
        const val MIN_CONCURRENCY = 1
        const val MAX_CONCURRENCY = 100
        const val DEFAULT_SPEED_TEST_MEGABYTES = 1
        const val MIN_SPEED_TEST_MEGABYTES = 1
        const val MAX_SPEED_TEST_MEGABYTES = 100
        const val BYTES_PER_MEGABYTE = 1_000_000L
    }
}

class ConnectionTestSettingsPreferenceStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun read(): ConnectionTestSettings = ConnectionTestSettings(
        timeoutSeconds = prefs.getInt(KEY_TIMEOUT_SECONDS, ConnectionTestSettings.DEFAULT_TIMEOUT_SECONDS),
        concurrency = prefs.getInt(KEY_CONCURRENCY, ConnectionTestSettings.DEFAULT_CONCURRENCY),
        speedTestMegabytes = prefs.getInt(
            KEY_SPEED_TEST_MEGABYTES,
            ConnectionTestSettings.DEFAULT_SPEED_TEST_MEGABYTES,
        ),
    ).normalized()

    fun save(settings: ConnectionTestSettings) {
        val normalized = settings.normalized()
        prefs.edit()
            .putInt(KEY_TIMEOUT_SECONDS, normalized.timeoutSeconds)
            .putInt(KEY_CONCURRENCY, normalized.concurrency)
            .putInt(KEY_SPEED_TEST_MEGABYTES, normalized.speedTestMegabytes)
            .apply()
    }

    private companion object {
        const val PREFS_NAME = "white_dns_connection_test"
        const val KEY_TIMEOUT_SECONDS = "timeout_seconds"
        const val KEY_CONCURRENCY = "concurrency"
        const val KEY_SPEED_TEST_MEGABYTES = "speed_test_megabytes"
    }
}
