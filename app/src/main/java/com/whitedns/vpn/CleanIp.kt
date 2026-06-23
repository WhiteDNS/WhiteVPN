package com.whitedns.vpn

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.OutputStreamWriter
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import kotlin.coroutines.coroutineContext
import kotlin.system.measureTimeMillis

data class CleanIpCandidate(val ip: String, val port: Int)

data class ConnectionProfile(
    val tag: String,
    val type: String,
    val server: String,
    val port: Int,
    val transport: String,
    val validationHost: String,
    val fingerprint: String = ProfileFingerprint.from(
        type = type,
        server = server,
        port = port,
        validationHost = validationHost,
        outboundJson = null,
    ),
    val outboundJson: String? = null,
) {
    val cacheKey: String
        get() = listOf(fingerprint, type, port.toString(), validationHost.lowercase()).joinToString("|")

    val isIpv6Literal: Boolean
        get() = server.trim().removePrefix("[").removeSuffix("]").contains(":")

    val supportsUdpApps: Boolean
        get() = RuntimeCompatibilityPolicy.profileSupportsUdpApps(this)
}

enum class RuntimeCompatibilityMode(val wireName: String) {
    Compatible("compatible"),
    UdpApps("udp_apps"),
}

object RuntimeCompatibilityPolicy {
    fun effectiveMode(profile: ConnectionProfile): RuntimeCompatibilityMode {
        return if (profile.supportsUdpApps) {
            RuntimeCompatibilityMode.UdpApps
        } else {
            RuntimeCompatibilityMode.Compatible
        }
    }

    fun profileSupportsUdpApps(profile: ConnectionProfile): Boolean {
        return when (profile.type.lowercase()) {
            "hysteria", "hysteria2", "hy2" -> true
            "vless" -> {
                val outbound = runCatching { JSONObject(profile.outboundJson.orEmpty()) }.getOrNull()
                outbound?.optString("packet_encoding").orEmpty().lowercase() in setOf("xudp", "packetaddr")
            }
            else -> false
        }
    }
}

data class SelectedConnectionProfile(
    val profile: ConnectionProfile,
    val delayMs: Int,
    val selectedAt: Long,
)

object ProfileFingerprint {
    fun from(
        type: String,
        server: String,
        port: Int,
        validationHost: String,
        outboundJson: String?,
    ): String {
        return sha256(
            listOf(
                type.lowercase(),
                server.lowercase(),
                port.toString(),
                validationHost.lowercase(),
                outboundJson.orEmpty(),
            ).joinToString("|"),
        ).take(16)
    }

    fun sha256(value: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}

data class CleanIpResult(
    val ip: String,
    val port: Int,
    val latencyMs: Long,
    val lossRate: Double,
    val checkedAt: Long,
    val downloadBytesPerSecond: Long = 0L,
) {
    fun candidate(): CleanIpCandidate = CleanIpCandidate(ip, port)
}

data class SubscriptionProfile(val tag: String, val type: String)

fun interface SocketProtector {
    fun protect(socket: Socket): Boolean
}

object CleanIpDefaults {
    const val WORKER_HOST = "whitedns.whitedns.workers.dev"
    const val SPEED_TEST_HOST = "speed.cloudflare.com"
    const val SPEED_TEST_PATH = "/__down?bytes=52428800"
    const val SCANNER_CONCURRENCY = 200
    const val PACKET_LOSS_PROBES = 4
    const val SPEED_TEST_CANDIDATES = 10
    const val SPEED_TEST_MAX_BYTES = 50 * 1_024 * 1_024
    const val SPEED_TEST_TIMEOUT_MS = 10_000
    const val TCP_TIMEOUT_MS = 1_000
    const val STARTUP_SCAN_MS = 60 * 1_000L
    const val STARTUP_QUICK_SCAN_MS = 3_000L
    const val STARTUP_FALLBACK_SCAN_MS = 12_000L
    const val STARTUP_QUICK_TARGET_RESULTS = 8
    const val STARTUP_QUICK_PACKET_LOSS_PROBES = 1
    const val STARTUP_QUICK_SPEED_TEST_CANDIDATES = 0
    const val STARTUP_RUNTIME_ATTEMPTS = 5
}

object CleanIpCacheCodec {
    fun encode(results: List<CleanIpResult>): String {
        val items = JSONArray()
        results.forEach { result ->
            items.put(
                JSONObject()
                    .put("ip", result.ip)
                    .put("port", result.port)
                    .put("latencyMs", result.latencyMs)
                    .put("lossRate", result.lossRate)
                    .put("downloadBytesPerSecond", result.downloadBytesPerSecond)
                    .put("checkedAt", result.checkedAt),
            )
        }
        return items.toString()
    }

    fun decode(value: String?): List<CleanIpResult> {
        if (value.isNullOrBlank()) return emptyList()
        val items = runCatching { JSONArray(value) }.getOrNull() ?: return emptyList()
        val results = mutableListOf<CleanIpResult>()
        for (index in 0 until items.length()) {
            val item = items.optJSONObject(index) ?: continue
            val ip = item.optString("ip")
            val port = item.optInt("port", -1)
            val latencyMs = item.optLong("latencyMs", -1)
            if (ip.isBlank() || port <= 0 || latencyMs < 0) continue
            results += CleanIpResult(
                ip = ip,
                port = port,
                latencyMs = latencyMs,
                lossRate = item.optDouble("lossRate", 0.0),
                checkedAt = item.optLong("checkedAt", 0L),
                downloadBytesPerSecond = item.optLong("downloadBytesPerSecond", 0L),
            )
        }
        return results.sortedForConnection()
    }
}

class CleanIpScanner(
    private val socketProtector: SocketProtector,
    private val speedTestHost: String = CleanIpDefaults.SPEED_TEST_HOST,
    private val concurrency: Int = CleanIpDefaults.SCANNER_CONCURRENCY,
    private val packetLossProbes: Int = CleanIpDefaults.PACKET_LOSS_PROBES,
    private val speedTestCandidates: Int = CleanIpDefaults.SPEED_TEST_CANDIDATES,
    private val speedTestMaxBytes: Int = CleanIpDefaults.SPEED_TEST_MAX_BYTES,
    private val speedTestTimeoutMs: Int = CleanIpDefaults.SPEED_TEST_TIMEOUT_MS,
    private val tcpTimeoutMs: Int = CleanIpDefaults.TCP_TIMEOUT_MS,
    private val candidateProvider: () -> List<CleanIpCandidate>,
    private val customProbe: (suspend (CleanIpCandidate) -> CleanIpResult?)? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val logger: (event: String, message: String) -> Unit = { _, _ -> },
) {
    private val failureCounts = ConcurrentHashMap<String, AtomicInteger>()
    private val protectFailureLogCount = AtomicInteger(0)

    suspend fun findFirstCachedWorking(cachedResults: List<CleanIpResult>): CleanIpResult? {
        logger("scanner.cached.start", "count=${cachedResults.size}")
        for (cached in cachedResults.sortedForConnection()) {
            logger("scanner.cached.probe", "endpoint=${cached.ip}:${cached.port} latencyMs=${cached.latencyMs}")
            probe(cached.candidate())?.let {
                logger("scanner.cached.success", "endpoint=${it.ip}:${it.port} latencyMs=${it.latencyMs}")
                return it
            }
            logger("scanner.cached.failed", "endpoint=${cached.ip}:${cached.port}")
        }
        return null
    }

    suspend fun findBestByQuality(maxScanDurationMs: Long = CleanIpDefaults.STARTUP_SCAN_MS): CleanIpResult? {
        val results = ConcurrentHashMap<String, CleanIpResult>()
        scanPass(maxScanDurationMs) { result ->
            results[result.endpointKey()] = result
        }
        val ranked = results.values.toList().sortedForConnection()
        if (ranked.isEmpty()) {
            logger("scanner.quality.noResult", "timeoutMs=$maxScanDurationMs")
            return null
        }
        if (customProbe != null) {
            return ranked.first().also { best ->
                logger(
                    "scanner.quality.best",
                    "endpoint=${best.ip}:${best.port} latencyMs=${best.latencyMs} lossRate=${best.lossRate} speedBps=${best.downloadBytesPerSecond}",
                )
            }
        }

        val speedMeasured = ranked
            .take(speedTestCandidates.coerceAtLeast(0))
            .map { result ->
                val speed = measureDownloadBytesPerSecond(result.candidate())
                result.copy(downloadBytesPerSecond = speed, checkedAt = System.currentTimeMillis())
                    .also { measured ->
                        logger(
                            "scanner.quality.speed",
                            "endpoint=${measured.ip}:${measured.port} latencyMs=${measured.latencyMs} lossRate=${measured.lossRate} speedBps=${measured.downloadBytesPerSecond}",
                        )
                    }
            }
        return (speedMeasured + ranked.drop(speedMeasured.size))
            .sortedForConnection()
            .first()
            .also { best ->
                logger(
                    "scanner.quality.best",
                    "endpoint=${best.ip}:${best.port} latencyMs=${best.latencyMs} lossRate=${best.lossRate} speedBps=${best.downloadBytesPerSecond}",
                )
            }
    }

    suspend fun findQuickCandidates(
        maxDurationMs: Long = CleanIpDefaults.STARTUP_QUICK_SCAN_MS,
        targetResults: Int = CleanIpDefaults.STARTUP_QUICK_TARGET_RESULTS,
    ): List<CleanIpResult> {
        val results = ConcurrentHashMap<String, CleanIpResult>()
        val startedAtMs = System.currentTimeMillis()
        scanPass(
            maxDurationMs = maxDurationMs,
            targetResults = targetResults.coerceAtLeast(1),
            probeCountOverride = CleanIpDefaults.STARTUP_QUICK_PACKET_LOSS_PROBES,
        ) { result ->
            results[result.endpointKey()] = result
        }
        val ranked = results.values.toList().sortedForConnection()
        logger(
            "scanner.quick.done",
            "results=${ranked.size} elapsedMs=${System.currentTimeMillis() - startedAtMs} timeoutMs=$maxDurationMs target=$targetResults",
        )
        return ranked
    }

    private suspend fun scanPass(
        maxDurationMs: Long,
        targetResults: Int? = null,
        probeCountOverride: Int? = null,
        onResult: suspend (CleanIpResult) -> Unit,
    ) {
        failureCounts.clear()
        withTimeoutOrNull(maxDurationMs) {
            coroutineScope {
                val candidates = candidateProvider()
                logger("scanner.pass.start", "candidates=${candidates.size} timeoutMs=$maxDurationMs")
                val channel = Channel<CleanIpCandidate>(capacity = concurrency * 2)
                val resultCount = AtomicInteger(0)
                val targetReached = AtomicBoolean(false)
                val producer = launch {
                    for (candidate in candidates) {
                        if (!coroutineContext.isActive || targetReached.get()) break
                        channel.send(candidate)
                    }
                    channel.close()
                }
                val workers = List(concurrency) {
                    launch {
                        for (candidate in channel) {
                            if (targetReached.get()) break
                            val result = probe(candidate, probeCountOverride) ?: continue
                            logger(
                                "scanner.pass.result",
                                "endpoint=${result.ip}:${result.port} latencyMs=${result.latencyMs} lossRate=${result.lossRate}",
                            )
                            val count = resultCount.incrementAndGet()
                            onResult(result)
                            if (targetResults != null && count >= targetResults) {
                                targetReached.set(true)
                            }
                        }
                    }
                }
                workers.joinAll()
                producer.join()
                logger(
                    "scanner.pass.done",
                    "candidates=${candidates.size} results=${resultCount.get()} failures=${failureCounts.values.sumOf { it.get() }}",
                )
                failureSummary()?.let { summary ->
                    logger("scanner.pass.failures", summary)
                }
            }
        }
    }

    private suspend fun probe(candidate: CleanIpCandidate, probeCountOverride: Int? = null): CleanIpResult? {
        customProbe?.let { return it(candidate) }
        return probeCandidate(candidate, probeCountOverride)
    }

    private fun CleanIpCandidate.endpointKey(): String = "$ip:$port"

    private fun CleanIpResult.endpointKey(): String = "$ip:$port"

    private suspend fun probeCandidate(candidate: CleanIpCandidate, probeCountOverride: Int?): CleanIpResult? = withContext(dispatcher) {
        val probeCount = (probeCountOverride ?: packetLossProbes).coerceAtLeast(1)
        val latencies = mutableListOf<Long>()
        repeat(probeCount) {
            runCatching {
                tcping(candidate)
            }.onSuccess { latencyMs ->
                latencies += latencyMs
            }.onFailure { error ->
                recordFailure(error)
            }
        }
        if (latencies.isEmpty()) {
            null
        } else {
            CleanIpResult(
                ip = candidate.ip,
                port = candidate.port,
                latencyMs = latencies.average().toLong().coerceAtLeast(1L),
                lossRate = (probeCount - latencies.size).toDouble() / probeCount.toDouble(),
                checkedAt = System.currentTimeMillis(),
            )
        }
    }

    private fun tcping(candidate: CleanIpCandidate): Long {
        val socket = Socket()
        socket.use { rawSocket ->
            protectBestEffort(rawSocket, "scanner.socket.protect.failed")

            return measureTimeMillis {
                rawSocket.connect(InetSocketAddress(candidate.ip, candidate.port), tcpTimeoutMs)
            }
        }
    }

    private suspend fun measureDownloadBytesPerSecond(candidate: CleanIpCandidate): Long = withContext(dispatcher) {
        runCatching {
            val socket = Socket()
            socket.use { rawSocket ->
                rawSocket.soTimeout = speedTestTimeoutMs
                protectBestEffort(rawSocket, "scanner.speed.socket.protect.failed")
                rawSocket.connect(InetSocketAddress(candidate.ip, candidate.port), tcpTimeoutMs)
                if (candidate.port.isTlsPort()) {
                    val sslSocket = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                        .createSocket(rawSocket, speedTestHost, candidate.port, true) as SSLSocket
                    sslSocket.use { tlsSocket ->
                        tlsSocket.soTimeout = speedTestTimeoutMs
                        tlsSocket.startHandshake()
                        writeDownloadRequest(tlsSocket)
                        readDownloadBytesPerSecond(tlsSocket)
                    }
                } else {
                    writeDownloadRequest(rawSocket)
                    readDownloadBytesPerSecond(rawSocket)
                }
            }
        }.getOrNull()
            ?: 0L
    }

    private fun protectBestEffort(socket: Socket, event: String) {
        val protected = runCatching { socketProtector.protect(socket) }.getOrDefault(false)
        if (protected) return
        val count = protectFailureLogCount.incrementAndGet()
        if (count <= 5) {
            logger(event, "continuingUnprotected=true count=$count")
        }
    }

    private fun recordFailure(error: Throwable) {
        val key = buildString {
            append(error::class.java.simpleName.ifBlank { "Exception" })
            val message = error.message.orEmpty()
            if (message.isNotBlank()) {
                append(":")
                append(message.take(80))
            }
        }
        failureCounts.getOrPut(key) { AtomicInteger(0) }.incrementAndGet()
    }

    private fun failureSummary(): String? {
        val failures = failureCounts.entries
            .sortedByDescending { it.value.get() }
            .take(5)
            .joinToString(" ") { "${it.key}=${it.value.get()}" }
        return failures.takeIf(String::isNotBlank)
    }

    private fun writeDownloadRequest(socket: Socket) {
        val writer = OutputStreamWriter(socket.outputStream, Charsets.US_ASCII)
        writer.write(
            "GET ${CleanIpDefaults.SPEED_TEST_PATH} HTTP/1.1\r\n" +
                "Host: $speedTestHost\r\n" +
                "User-Agent: Mozilla/5.0 (Macintosh; Intel Mac OS X 10_12_6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/98.0.4758.80 Safari/537.36\r\n" +
                "Connection: close\r\n\r\n",
        )
        writer.flush()
    }

    private fun readDownloadBytesPerSecond(socket: Socket): Long {
        val startedAt = System.nanoTime()
        val deadline = startedAt + speedTestTimeoutMs * 1_000_000L
        val buffer = ByteArray(1_024)
        var bytesRead = 0
        while (bytesRead < speedTestMaxBytes && System.nanoTime() < deadline) {
            val read = try {
                socket.inputStream.read(buffer, 0, minOf(buffer.size, speedTestMaxBytes - bytesRead))
            } catch (_: SocketTimeoutException) {
                break
            }
            if (read <= 0) break
            bytesRead += read
        }
        val elapsedNs = (System.nanoTime() - startedAt).coerceAtLeast(1L)
        return ((bytesRead.toDouble() * 1_000_000_000.0) / elapsedNs.toDouble()).toLong().coerceAtLeast(0L)
    }

    private fun Int.isTlsPort(): Boolean {
        return this !in setOf(80, 8080, 8880)
    }
}

fun List<CleanIpResult>.sortedForConnection(): List<CleanIpResult> {
    return sortedWith(
        compareBy<CleanIpResult> { it.lossRate }
            .thenByDescending { it.downloadBytesPerSecond }
            .thenBy { it.latencyMs }
            .thenByDescending { it.checkedAt },
    )
}
