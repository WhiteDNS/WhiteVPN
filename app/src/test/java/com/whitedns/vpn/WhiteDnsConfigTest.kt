package com.whitedns.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.IOException
import java.net.URL

class WhiteDnsConfigTest {
    @Test
    fun subscriptionUrlComesFromBuildTimeConfiguration() {
        assertEquals(BuildConfig.MIHOMO_SUBSCRIPTION_URL, WhiteDnsConfig.MIHOMO_SUBSCRIPTION_URL)
    }

    @Test
    fun subscriptionRefreshIntervalIsThirtyMinutes() {
        assertEquals(30 * 60 * 1_000L, WhiteDnsConfig.SUBSCRIPTION_REFRESH_INTERVAL_MS)
    }

    @Test
    fun decryptsOnlySubscriptionsWithEncryptedInThePath() {
        val plaintext = "proxies:\n  - name: Example\n"

        assertEquals(
            plaintext,
            decodeSubscriptionPayload(
                URL("https://raw.githubusercontent.com/iampedii/whitedns-sub/refs/heads/main/mihomo.yaml"),
                plaintext,
                key = "",
            ),
        )
        assertThrows(IOException::class.java) {
            decodeSubscriptionPayload(
                URL("https://whitedns-sub.whitedns.workers.dev/mihomo/encrypted"),
                plaintext,
                key = "",
            )
        }
    }

    @Test
    fun encryptedIpListUrlComesFromBuildTimeConfiguration() {
        assertEquals(BuildConfig.ENCRYPTED_IP_LIST_URL, WhiteDnsConfig.ENCRYPTED_IP_LIST_URL)
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
