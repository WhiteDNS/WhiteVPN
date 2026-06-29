package com.whitedns.vpn

import java.io.IOException

object StartupScanPolicy {
    private val primaryPorts = listOf(443, 8443)
    private val secondaryPorts = listOf(2053, 2083, 2087, 2096)

    fun priorityPorts(subscriptionPorts: List<Int>): List<Int> {
        val validPorts = subscriptionPorts.filter { it > 0 }
        val available = validPorts.toSet()
        primaryPorts.filter { it in available }
            .takeIf { it.isNotEmpty() }
            ?.let { return it }
        secondaryPorts.filter { it in available }
            .takeIf { it.isNotEmpty() }
            ?.let { return it }
        return mostCommonPort(validPorts)?.let(::listOf).orEmpty()
    }

    fun fallbackPorts(subscriptionPorts: List<Int>): List<Int> {
        return subscriptionPorts
            .filter { it > 0 }
            .distinct()
            .sorted()
    }

    fun cachedRuntimeCandidates(
        selection: SelectedConnectionProfile?,
        lastEndpoint: CleanIpResult?,
        cachedResults: List<CleanIpResult>,
        frontingIpOverrideEnabled: Boolean,
        excludedEndpoint: CleanIpResult? = null,
    ): List<CleanIpResult> {
        if (frontingIpOverrideEnabled || selection == null) return emptyList()
        val selectedPort = selection.profile.port
        val last = lastEndpoint?.takeIf { it.port == selectedPort }
        val lastKey = last?.endpointKey()
        val candidates = listOfNotNull(last) + cachedResults
            .filter { it.port == selectedPort && it.endpointKey() != lastKey }
            .distinctBy { it.endpointKey() }
            .sortedForConnection()
        return excludeEndpoint(candidates, excludedEndpoint)
    }

    fun excludeEndpoint(
        candidates: List<CleanIpResult>,
        excludedEndpoint: CleanIpResult?,
    ): List<CleanIpResult> {
        val excludedKey = excludedEndpoint?.endpointKey() ?: return candidates
        return candidates.filter { it.endpointKey() != excludedKey }
    }

    private fun mostCommonPort(ports: List<Int>): Int? {
        if (ports.isEmpty()) return null
        val counts = ports.groupingBy { it }.eachCount()
        val firstIndex = mutableMapOf<Int, Int>()
        ports.forEachIndexed { index, port ->
            firstIndex.putIfAbsent(port, index)
        }
        return ports.distinct()
            .sortedWith(
                compareByDescending<Int> { counts.getValue(it) }
                    .thenBy { firstIndex.getValue(it) },
            )
            .firstOrNull()
    }

    private fun CleanIpResult.endpointKey(): String = "$ip:$port"
}

object StartupTopIpConnector {
    suspend fun <T> connectFirst(
        candidates: List<CleanIpResult>,
        probeProfiles: suspend (CleanIpResult) -> List<SelectedConnectionProfile>,
        connectRuntime: suspend (CleanIpResult, List<SelectedConnectionProfile>) -> T,
        onAttempt: (attempt: Int, total: Int, endpoint: CleanIpResult) -> Unit = { _, _, _ -> },
        onRejected: (attempt: Int, total: Int, endpoint: CleanIpResult, reason: String, error: Throwable?) -> Unit = { _, _, _, _, _ -> },
        onConnected: (attempt: Int, total: Int, endpoint: CleanIpResult) -> Unit = { _, _, _ -> },
    ): T {
        var lastFailure: Throwable? = null
        for ((index, endpoint) in candidates.withIndex()) {
            val attempt = index + 1
            onAttempt(attempt, candidates.size, endpoint)
            val selections = try {
                probeProfiles(endpoint)
            } catch (error: Throwable) {
                lastFailure = error
                onRejected(attempt, candidates.size, endpoint, "profileProbeFailed", error)
                continue
            }
            if (selections.isEmpty()) {
                onRejected(attempt, candidates.size, endpoint, "noRealDelay", null)
                continue
            }
            try {
                return connectRuntime(endpoint, selections).also {
                    onConnected(attempt, candidates.size, endpoint)
                }
            } catch (error: Throwable) {
                lastFailure = error
                onRejected(attempt, candidates.size, endpoint, "runtimeFailed", error)
            }
        }
        throw IOException("No quick top-IP candidate connected", lastFailure)
    }
}
