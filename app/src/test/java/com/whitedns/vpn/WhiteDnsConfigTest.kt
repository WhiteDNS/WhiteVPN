package com.whitedns.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.nio.file.Files

class WhiteDnsConfigTest {
    @Test
    fun subscriptionUrlComesFromBuildTimeConfiguration() {
        assertEquals(BuildConfig.MIHOMO_SUBSCRIPTION_URL, WhiteDnsConfig.MIHOMO_SUBSCRIPTION_URL)
        assertEquals(
            BuildConfig.PRIVATE_MIHOMO_SUBSCRIPTION_URL,
            WhiteDnsConfig.PRIVATE_MIHOMO_SUBSCRIPTION_URL,
        )
    }

    @Test
    fun subscriptionRefreshIntervalIsThirtyMinutes() {
        assertEquals(30 * 60 * 1_000L, WhiteDnsConfig.SUBSCRIPTION_REFRESH_INTERVAL_MS)
    }

    @Test
    fun subscriptionCacheFreshnessUsesThirtyMinuteBoundary() {
        val nowMs = 2_000_000L
        val intervalMs = WhiteDnsConfig.SUBSCRIPTION_REFRESH_INTERVAL_MS

        assertTrue(WhiteDnsConfig.isSubscriptionCacheFresh(nowMs, nowMs))
        assertTrue(WhiteDnsConfig.isSubscriptionCacheFresh(nowMs - intervalMs + 1L, nowMs))
        assertFalse(WhiteDnsConfig.isSubscriptionCacheFresh(nowMs - intervalMs, nowMs))
        assertFalse(WhiteDnsConfig.isSubscriptionCacheFresh(nowMs + 1L, nowMs))
        assertFalse(WhiteDnsConfig.isSubscriptionCacheFresh(0L, nowMs))
    }

    @Test
    fun invalidCachedConfigIsRejectedBeforeFreshnessCanReuseIt() {
        assertThrows(IOException::class.java) {
            parseCachedMihomoConfig("proxy-groups:\n  - name: Empty\n    type: select", fetchedAt = 123L)
        }
    }

    @Test
    fun corruptFreshUserCacheFallsThroughToRefresh() {
        var refreshCalls = 0

        val snapshot = resolveFreshOrRefreshedUserSubscription(
            cacheIsFresh = true,
            readCached = {
                parseCachedMihomoConfig("proxy-groups: [corrupt", fetchedAt = 100L)
            },
            refresh = {
                refreshCalls += 1
                parseCachedMihomoConfig(sanitizedSubscriptionYaml("Refreshed Node"), fetchedAt = 200L)
            },
        )

        assertEquals(1, refreshCalls)
        assertEquals("Refreshed Node", snapshot.catalog.profiles.single().tag)
        assertEquals(200L, snapshot.catalog.fetchedAt)
    }

    @Test
    fun validStaleUserCacheFallsBackWhenRefreshFails() {
        var refreshCalls = 0

        val snapshot = resolveFreshOrRefreshedUserSubscription(
            cacheIsFresh = false,
            readCached = {
                parseCachedMihomoConfig(sanitizedSubscriptionYaml("Cached Node"), fetchedAt = 100L)
            },
            refresh = {
                refreshCalls += 1
                throw IOException("sanitized network failure")
            },
        )

        assertEquals(1, refreshCalls)
        assertEquals("Cached Node", snapshot.catalog.profiles.single().tag)
        assertEquals(100L, snapshot.catalog.fetchedAt)
    }

    @Test
    fun cacheWritesReplaceTheTargetThroughASiblingTemporaryFile() {
        val directory = Files.createTempDirectory("whitedns-config-test").toFile()
        try {
            val target = File(directory, "subscription.yaml").apply { writeText("old") }

            writeTextAtomically(target, "new")

            assertEquals("new", target.readText())
            assertTrue(
                directory.listFiles().orEmpty().none { candidate ->
                    candidate.name.startsWith(".${target.name}.") && candidate.name.endsWith(".tmp")
                },
            )
        } finally {
            directory.deleteRecursively()
        }
    }

    @Test
    fun buildDoesNotPackagePayloadDecryptionSecrets() {
        val fields = BuildConfig::class.java.declaredFields.map { it.name }.toSet()
        assertFalse("MIHOMO_SUBSCRIPTION_KEY" in fields)
        assertFalse("ENCRYPTED_IP_LIST_KEY" in fields)
        assertFalse("ENCRYPTED_IP_LIST_URL" in fields)
    }

    private fun sanitizedSubscriptionYaml(name: String): String = """
        proxies:
          - name: '$name'
            type: vless
            server: 203.0.113.10
            port: 443
        proxy-groups:
          - name: WhiteDNS Proxy
            type: select
            proxies:
              - '$name'
    """.trimIndent()
}
