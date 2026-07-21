package com.whitedns.vpn

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class StartupScanPolicyTest {
    @Test
    fun priorityPortsPreferCommonTlsPorts() {
        assertEquals(listOf(443, 8443), StartupScanPolicy.priorityPorts(listOf(22, 80, 443, 8443)))
    }

    @Test
    fun priorityPortsUseSecondaryTlsPortsWhenPrimaryMissing() {
        assertEquals(listOf(2053, 2083), StartupScanPolicy.priorityPorts(listOf(2053, 2083, 8880)))
    }

    @Test
    fun priorityPortsFallBackToMostCommonPortWithFirstSeenTieBreak() {
        assertEquals(listOf(8880), StartupScanPolicy.priorityPorts(listOf(22, 8880, 8880)))
        assertEquals(listOf(22), StartupScanPolicy.priorityPorts(listOf(22, 8880)))
    }

    @Test
    fun fallbackPortsUseAllDistinctSortedPorts() {
        assertEquals(listOf(22, 80, 443, 8443), StartupScanPolicy.fallbackPorts(listOf(8443, 22, 443, 22, 80)))
    }

    @Test
    fun orderedConnectionPortsPreferPriorityThenAllRemainingPorts() {
        assertEquals(listOf(443, 8443, 22, 80), StartupScanPolicy.orderedConnectionPorts(listOf(8443, 22, 443, 80)))
    }

    @Test
    fun frontingCandidatesUseProvidedIpsOnceInUserOrder() {
        val active = CleanIpResult("162.159.192.1", 443, 1, 0.0, 1)

        val candidates = StartupScanPolicy.frontingCandidates(
            frontingIps = listOf("162.159.192.1", " 162.159.192.2 ", "162.159.192.1"),
            subscriptionPorts = listOf(8443, 443),
            checkedAt = 2,
            excludedEndpoint = active,
        )

        assertEquals(
            listOf("162.159.192.2:443"),
            candidates.map { "${it.ip}:${it.port}" },
        )
    }

    @Test
    fun frontingCandidatesUseExplicitPortsAndSubscriptionFallback() {
        val candidates = StartupScanPolicy.frontingCandidates(
            frontingIps = listOf("162.159.192.1:2053", "162.159.192.2"),
            subscriptionPorts = listOf(8443, 443),
            checkedAt = 2,
        )

        assertEquals(
            listOf("162.159.192.1:2053", "162.159.192.2:443"),
            candidates.map { "${it.ip}:${it.port}" },
        )
    }

    @Test
    fun exhaustiveEncryptedCandidatesCoverEveryIpPortPair() {
        val candidates = StartupScanPolicy.exhaustiveEncryptedCandidates(
            candidateIps = listOf("104.16.0.1", "", "104.16.0.2"),
            subscriptionPorts = listOf(443, 2053),
            checkedAt = 3,
        )

        assertEquals(
            listOf("104.16.0.1:443", "104.16.0.1:2053", "104.16.0.2:443", "104.16.0.2:2053"),
            candidates.map { "${it.ip}:${it.port}" },
        )
    }

    @Test
    fun untriedFallbackCandidatesSkipQuickCandidatesAfterQuickFailure() {
        val quick = listOf(CleanIpResult("104.16.0.1", 443, 10, 0.0, 1))
        val exhaustive = listOf(
            CleanIpResult("104.16.0.1", 443, 1, 0.0, 2),
            CleanIpResult("104.16.0.1", 8443, 1, 0.0, 2),
            CleanIpResult("104.16.0.2", 443, 1, 0.0, 2),
        )

        val fallback = StartupScanPolicy.untriedFallbackCandidates(quick, exhaustive)

        assertEquals(listOf("104.16.0.1:8443", "104.16.0.2:443"), fallback.map { "${it.ip}:${it.port}" })
    }

    @Test
    fun cachedEncryptedCandidatesUseAllSubscriptionPortsAndPreferLastEndpoint() {
        val last = CleanIpResult("104.16.0.2", 8443, 120, 0.0, 1)
        val fast = CleanIpResult("104.16.0.1", 443, 10, 0.0, 2)
        val wrongPort = CleanIpResult("104.16.0.3", 2053, 1, 0.0, 3)

        val candidates = StartupScanPolicy.cachedEncryptedCandidates(
            subscriptionPorts = listOf(443, 8443),
            lastEndpoint = last,
            cachedResults = listOf(fast, wrongPort),
        )

        assertEquals(listOf(last, fast), candidates)
    }

    @Test
    fun excludeEndpointRemovesOnlyMatchingIpAndPort() {
        val active = CleanIpResult("104.16.0.1", 443, 10, 0.0, 1)
        val sameIpDifferentPort = CleanIpResult("104.16.0.1", 8443, 20, 0.0, 2)
        val other = CleanIpResult("104.16.0.2", 443, 30, 0.0, 3)

        val candidates = StartupScanPolicy.excludeEndpoint(
            candidates = listOf(active, sameIpDifferentPort, other),
            excludedEndpoint = active,
        )

        assertEquals(listOf(sameIpDifferentPort, other), candidates)
    }

    @Test
    fun excludeEndpointKeepsCandidatesWhenNoEndpointIsExcluded() {
        val candidates = listOf(CleanIpResult("104.16.0.1", 443, 10, 0.0, 1))

        assertEquals(candidates, StartupScanPolicy.excludeEndpoint(candidates, null))
    }

    @Test
    fun connectorSkipsCandidateWithoutRealDelayAndConnectsNext() = runBlocking {
        val first = CleanIpResult("104.16.0.1", 443, 10, 0.0, 1)
        val second = CleanIpResult("104.16.0.2", 443, 20, 0.0, 2)
        val attempts = mutableListOf<String>()
        val rejections = mutableListOf<String>()

        val result = StartupTopIpConnector.connectFirst(
            candidates = listOf(first, second),
            probeProfiles = { endpoint ->
                attempts += endpoint.ip
                if (endpoint == first) emptyList() else listOf(selection())
            },
            connectRuntime = { endpoint, _ -> "connected:${endpoint.ip}" },
            onRejected = { _, _, endpoint, reason, _ -> rejections += "${endpoint.ip}:$reason" },
        )

        assertEquals("connected:104.16.0.2", result)
        assertEquals(listOf("104.16.0.1", "104.16.0.2"), attempts)
        assertEquals(listOf("104.16.0.1:noRealDelay"), rejections)
    }

    @Test
    fun connectorRetriesWhenRuntimeFails() = runBlocking {
        val first = CleanIpResult("104.16.0.1", 443, 10, 0.0, 1)
        val second = CleanIpResult("104.16.0.2", 443, 20, 0.0, 2)
        val connected = mutableListOf<String>()
        val rejections = mutableListOf<String>()

        val result = StartupTopIpConnector.connectFirst(
            candidates = listOf(first, second),
            probeProfiles = { listOf(selection()) },
            connectRuntime = { endpoint, _ ->
                connected += endpoint.ip
                if (endpoint == first) throw IOException("runtime failed")
                "connected:${endpoint.ip}"
            },
            onRejected = { _, _, endpoint, reason, error ->
                rejections += "${endpoint.ip}:$reason:${error?.message.orEmpty()}"
            },
        )

        assertEquals("connected:104.16.0.2", result)
        assertEquals(listOf("104.16.0.1", "104.16.0.2"), connected)
        assertEquals(listOf("104.16.0.1:runtimeFailed:runtime failed"), rejections)
    }

    @Test
    fun connectorFailsWhenAllCandidatesRejected() = runBlocking {
        val failure = runCatching {
            StartupTopIpConnector.connectFirst(
                candidates = listOf(CleanIpResult("104.16.0.1", 443, 10, 0.0, 1)),
                probeProfiles = { emptyList() },
                connectRuntime = { _, _ -> "unused" },
            )
        }.exceptionOrNull()

        assertTrue(failure is IOException)
    }

    @Test
    fun startupFallbackRunsAfterPrimaryFailure() = runBlocking {
        val calls = mutableListOf<String>()

        val result = connectWithStartupFallback<String>(
            primary = {
                calls += "original"
                throw IOException("health check failed")
            },
            fallback = { error ->
                calls += "fallback:${error.message}"
                "connected"
            },
        )

        assertEquals("connected", result)
        assertEquals(listOf("original", "fallback:health check failed"), calls)
    }

    @Test
    fun startupFallbackRethrowsExcludedFailures() = runBlocking {
        val failures = listOf(
            CancellationException("canceled"),
            MihomoCoreBusyException(),
            MihomoCoreSetupTimeoutException(),
        )

        failures.forEach { expected ->
            var fallbackCalls = 0
            val failure = runCatching {
                connectWithStartupFallback<String>(
                    primary = { throw expected },
                    fallback = {
                        fallbackCalls += 1
                        "unused"
                    },
                )
            }.exceptionOrNull()

            assertSame(expected, failure)
            assertEquals(0, fallbackCalls)
        }
    }

    private fun selection(
        port: Int = 443,
        tag: String = "profile-$port",
        server: String = "example.com",
    ): SelectedConnectionProfile {
        return SelectedConnectionProfile(
            profile = ConnectionProfile(
                tag = tag,
                type = "vless",
                server = server,
                port = port,
                transport = "ws",
                validationHost = server,
            ),
            delayMs = 100,
            selectedAt = 1,
        )
    }
}
