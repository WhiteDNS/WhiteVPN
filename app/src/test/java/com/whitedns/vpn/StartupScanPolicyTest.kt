package com.whitedns.vpn

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
    fun cachedRuntimeCandidatesPreferLastEndpointAndMatchSelectedProfilePort() {
        val last = CleanIpResult("104.16.0.2", 443, 120, 0.0, 1)
        val duplicateLast = CleanIpResult("104.16.0.2", 443, 20, 0.0, 2)
        val fasterCached = CleanIpResult("104.16.0.1", 443, 10, 0.0, 3)
        val wrongPort = CleanIpResult("104.16.0.3", 8443, 1, 0.0, 4)

        val candidates = StartupScanPolicy.cachedRuntimeCandidates(
            selection = selection(port = 443),
            lastEndpoint = last,
            cachedResults = listOf(fasterCached, duplicateLast, wrongPort),
            frontingIpOverrideEnabled = false,
        )

        assertEquals(listOf(last, fasterCached), candidates)
    }

    @Test
    fun cachedRuntimeCandidatesAreEmptyWithFrontingIpOverride() {
        val candidates = StartupScanPolicy.cachedRuntimeCandidates(
            selection = selection(port = 443),
            lastEndpoint = CleanIpResult("104.16.0.2", 443, 120, 0.0, 1),
            cachedResults = listOf(CleanIpResult("104.16.0.1", 443, 10, 0.0, 2)),
            frontingIpOverrideEnabled = true,
        )

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun cachedRuntimeCandidatesIgnoreMismatchedProfilePorts() {
        val candidates = StartupScanPolicy.cachedRuntimeCandidates(
            selection = selection(port = 443),
            lastEndpoint = CleanIpResult("104.16.0.2", 8443, 120, 0.0, 1),
            cachedResults = listOf(CleanIpResult("104.16.0.1", 8443, 10, 0.0, 2)),
            frontingIpOverrideEnabled = false,
        )

        assertTrue(candidates.isEmpty())
    }

    @Test
    fun cachedRuntimeCandidatesExcludeActiveEndpoint() {
        val active = CleanIpResult("104.16.0.2", 443, 120, 0.0, 1)
        val cached = CleanIpResult("104.16.0.1", 443, 10, 0.0, 2)

        val candidates = StartupScanPolicy.cachedRuntimeCandidates(
            selection = selection(port = 443),
            lastEndpoint = active,
            cachedResults = listOf(cached),
            frontingIpOverrideEnabled = false,
            excludedEndpoint = active,
        )

        assertEquals(listOf(cached), candidates)
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

    private fun selection(port: Int = 443): SelectedConnectionProfile {
        return SelectedConnectionProfile(
            profile = ConnectionProfile(
                tag = "profile-$port",
                type = "vless",
                server = "example.com",
                port = port,
                transport = "ws",
                validationHost = "example.com",
            ),
            delayMs = 100,
            selectedAt = 1,
        )
    }
}
