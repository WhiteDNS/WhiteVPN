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
        assertEquals(
            "#2gzwj1##z%BVq*7M2sfxe6sV23ut1LQr87JagD4D#&",
            WhiteDnsConfig.MIHOMO_SUBSCRIPTION_KEY,
        )
    }

    @Test
    fun subscriptionRefreshIntervalIsThreeHours() {
        assertEquals(3 * 60 * 60 * 1_000L, WhiteDnsConfig.SUBSCRIPTION_REFRESH_INTERVAL_MS)
    }

    @Test
    fun encryptedIpListConfigIsPinned() {
        assertEquals(
            "https://whitedns-encrypted-ip-list.whitedns.workers.dev/v1/results/ips/encrypted",
            WhiteDnsConfig.ENCRYPTED_IP_LIST_URL,
        )
        assertEquals("kc*P\$Hfw\$YqRSf%Ypyfzx#F\$kncPk9QG5%!W8M83K@f", WhiteDnsConfig.ENCRYPTED_IP_LIST_KEY)
    }
}
