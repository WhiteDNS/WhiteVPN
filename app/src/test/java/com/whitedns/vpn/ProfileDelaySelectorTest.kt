package com.whitedns.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class ProfileDelaySelectorTest {
    @Test
    fun profileDelaySelectionChoosesLowestPositiveDelay() {
        val profiles = listOf(
            profile("vless", 443),
            profile("trojan", 8443),
            profile("slow", 2053),
        )

        val selected = ProfileDelaySelector.chooseBest(
            profiles,
            mapOf("vless" to 120, "trojan" to 40, "slow" to 300),
            selectedAt = 1_000,
        )

        assertEquals("trojan", selected?.profile?.tag)
        assertEquals(40, selected?.delayMs)
        assertEquals(1_000L, selected?.selectedAt)
    }

    @Test
    fun profileDelayRankingReturnsAllPositiveDelaysInOrder() {
        val profiles = listOf(
            profile("vless", 443),
            profile("trojan", 8443),
            profile("slow", 2053),
        )

        val ranked = ProfileDelaySelector.rankByDelay(
            profiles,
            mapOf("vless" to 120, "trojan" to 40, "slow" to 300),
            selectedAt = 1_000,
        )

        assertEquals(listOf("trojan", "vless", "slow"), ranked.map { it.profile.tag })
    }

    @Test
    fun profileDelaySelectionIgnoresMissingOrNonPositiveDelay() {
        val profiles = listOf(profile("vless", 443), profile("trojan", 8443))

        assertNull(ProfileDelaySelector.chooseBest(profiles, mapOf("vless" to 0, "trojan" to -1)))
    }

    @Test
    fun connectionTestProfilesAreShuffledWithProvidedRandom() {
        val profiles = listOf(
            profile("profile-1", 443),
            profile("profile-2", 443),
            profile("profile-3", 443),
            profile("profile-4", 443),
            profile("profile-5", 443),
        )

        val shuffled = ConnectionProfileSelectionPolicy.shuffledForConnectionTest(
            profiles,
            random = Random(7),
        )

        assertEquals(profiles.map { it.tag }.toSet(), shuffled.map { it.tag }.toSet())
        assertFalse(profiles.map { it.tag } == shuffled.map { it.tag })
    }

    @Test
    fun profileCacheKeyIncludesProfileIdentityPortAndHost() {
        val port443 = profile("vless", 443)
        val port8443 = profile("vless", 8443)

        assertEquals(
            "${port443.fingerprint}|vless|443|whitedns.whitedns.workers.dev",
            port443.cacheKey,
        )
        assertEquals(
            "${port8443.fingerprint}|vless|8443|whitedns.whitedns.workers.dev",
            port8443.cacheKey,
        )
        assertFalse(port443.fingerprint == port8443.fingerprint)
    }

    @Test
    fun connectionProfileDetectsIpv6LiteralServers() {
        assertTrue(profile("ipv6", 443, "[2606:4700:3034::6815:4c30]").isIpv6Literal)
        assertTrue(profile("ipv6-no-brackets", 443, "2606:4700:3034::6815:4c30").isIpv6Literal)
        assertFalse(profile("domain", 443, "whitedns.whitedns.workers.dev").isIpv6Literal)
        assertFalse(profile("ipv4", 443, "172.67.187.177").isIpv6Literal)
    }

    @Test
    fun delayTimeoutsUseFastConnectPolicy() {
        assertEquals(8_000L, ProfileDelayDefaults.PROFILE_TEST_TIMEOUT_MS)
        assertEquals(1_000L, ProfileDelayDefaults.PROFILE_TEST_QUIET_MS)
    }

    private fun profile(tag: String, port: Int): ConnectionProfile {
        return profile(tag, port, "whitedns.whitedns.workers.dev")
    }

    private fun profile(tag: String, port: Int, server: String): ConnectionProfile {
        return ConnectionProfile(
            tag = tag,
            type = if (tag == "trojan") "trojan" else "vless",
            server = server,
            port = port,
            transport = "ws",
            validationHost = "whitedns.whitedns.workers.dev",
        )
    }
}
