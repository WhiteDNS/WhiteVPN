package com.whitedns.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class WhiteDnsConfigTest {
    @Test
    fun subscriptionUrlIsPinned() {
        assertEquals(
            "https://whitedns-sub.whitedns.workers.dev/mihomo/encrypted",
            WhiteDnsConfig.MIHOMO_SUBSCRIPTION_URL,
        )
    }

    @Test
    fun subscriptionRefreshIntervalIsThreeHours() {
        assertEquals(3 * 60 * 60 * 1_000L, WhiteDnsConfig.SUBSCRIPTION_REFRESH_INTERVAL_MS)
    }

    @Test
    fun encryptedIpListUrlIsPinned() {
        assertEquals(
            "https://whitedns-encrypted-ip-list.whitedns.workers.dev/v1/results/ips/encrypted",
            WhiteDnsConfig.ENCRYPTED_IP_LIST_URL,
        )
    }

    /**
     * The decryption passphrases must never be literals in this repository again. They are injected
     * from secrets.properties or the environment, so the only thing worth asserting here is that
     * the config reads them from BuildConfig rather than carrying its own copy.
     */
    @Test
    fun payloadKeysComeFromBuildTimeInjection() {
        assertEquals(BuildConfig.MIHOMO_SUBSCRIPTION_KEY, WhiteDnsConfig.MIHOMO_SUBSCRIPTION_KEY)
        assertEquals(BuildConfig.ENCRYPTED_IP_LIST_KEY, WhiteDnsConfig.ENCRYPTED_IP_LIST_KEY)
    }
}
