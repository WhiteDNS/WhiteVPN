package com.whitedns.vpn

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicInteger

class CleanIpScannerTest {
    @Test
    fun cachedEndpointValidationUsesOnlyCachedCandidates() = runBlocking {
        val calls = mutableListOf<CleanIpCandidate>()
        val cached = CleanIpResult("104.16.0.1", 443, 100, 0.0, 1)
        val scanner = CleanIpScanner(
            socketProtector = SocketProtector { true },
            concurrency = 1,
            candidateProvider = { emptyList() },
            customProbe = { candidate ->
                calls += candidate
                CleanIpResult(candidate.ip, candidate.port, 90, 0.0, 2)
            },
        )

        val result = scanner.findFirstCachedWorking(listOf(cached))

        assertEquals(cached.ip, result?.ip)
        assertEquals(listOf(cached.candidate()), calls)
    }

    @Test
    fun qualityScanUsesOnlyProvidedCandidatesAndPrefersLowerLoss() = runBlocking {
        val calls = mutableListOf<CleanIpCandidate>()
        val lossyFast = CleanIpCandidate("104.16.0.1", 443)
        val cleanSlow = CleanIpCandidate("104.16.0.2", 8443)
        val scanner = CleanIpScanner(
            socketProtector = SocketProtector { true },
            concurrency = 1,
            candidateProvider = { listOf(lossyFast, cleanSlow) },
            customProbe = { candidate ->
                calls += candidate
                if (candidate == lossyFast) {
                    CleanIpResult(candidate.ip, candidate.port, 20, 0.5, 1, downloadBytesPerSecond = 1_000_000)
                } else {
                    CleanIpResult(candidate.ip, candidate.port, 120, 0.0, 1, downloadBytesPerSecond = 1)
                }
            },
        )

        val result = scanner.findBestByQuality(maxScanDurationMs = 5_000)

        assertEquals(listOf(lossyFast, cleanSlow), calls)
        assertEquals(cleanSlow.ip, result?.ip)
        assertEquals(cleanSlow.port, result?.port)
    }

    @Test
    fun qualityScanPrefersDownloadSpeedWhenLossMatches() = runBlocking {
        val slowDownload = CleanIpCandidate("104.16.0.1", 443)
        val fastDownload = CleanIpCandidate("104.16.0.2", 443)
        val scanner = CleanIpScanner(
            socketProtector = SocketProtector { true },
            concurrency = 1,
            candidateProvider = { listOf(slowDownload, fastDownload) },
            customProbe = { candidate ->
                val speed = if (candidate == fastDownload) 5_000_000L else 10L
                val latency = if (candidate == fastDownload) 80L else 20L
                CleanIpResult(candidate.ip, candidate.port, latency, 0.0, 1, downloadBytesPerSecond = speed)
            },
        )

        val result = scanner.findBestByQuality(maxScanDurationMs = 5_000)

        assertEquals(fastDownload.ip, result?.ip)
    }

    @Test
    fun quickScanStopsAfterTargetSuccesses() = runBlocking {
        val candidates = (1..10).map { CleanIpCandidate("104.16.0.$it", 443) }
        val calls = mutableListOf<CleanIpCandidate>()
        val scanner = CleanIpScanner(
            socketProtector = SocketProtector { true },
            concurrency = 1,
            candidateProvider = { candidates },
            customProbe = { candidate ->
                calls += candidate
                CleanIpResult(candidate.ip, candidate.port, 10, 0.0, calls.size.toLong())
            },
        )

        val results = scanner.findQuickCandidates(maxDurationMs = 5_000, targetResults = 2)

        assertEquals(2, results.size)
        assertTrue(calls.size < candidates.size)
    }

    @Test
    fun quickScanUsesOneTcpProbeAndNoSpeedTest() = runBlocking {
        val accepted = AtomicInteger(0)
        val server = ServerSocket(0)
        val acceptThread = Thread {
            runCatching {
                while (accepted.get() < 2) {
                    server.accept().use {
                        accepted.incrementAndGet()
                    }
                }
            }
        }.apply { start() }

        try {
            val scanner = CleanIpScanner(
                socketProtector = SocketProtector { true },
                concurrency = 1,
                packetLossProbes = 4,
                speedTestCandidates = 10,
                tcpTimeoutMs = 500,
                candidateProvider = { listOf(CleanIpCandidate("127.0.0.1", server.localPort)) },
            )

            val results = scanner.findQuickCandidates(maxDurationMs = 5_000, targetResults = 1)
            Thread.sleep(100)

            assertEquals(1, results.size)
            assertEquals(0.0, results.first().lossRate, 0.0)
            assertEquals(0L, results.first().downloadBytesPerSecond)
            assertEquals(1, accepted.get())
        } finally {
            server.close()
            acceptThread.join(1_000)
        }
    }

    @Test
    fun defaultProbeUsesTcpConnectWithoutHttpTlsValidation() = runBlocking {
        val server = ServerSocket(0)
        val acceptThread = Thread {
            runCatching {
                repeat(2) {
                    server.accept().use { }
                }
            }
        }.apply { start() }

        try {
            val scanner = CleanIpScanner(
                socketProtector = SocketProtector { true },
                concurrency = 1,
                packetLossProbes = 2,
                speedTestCandidates = 0,
                tcpTimeoutMs = 500,
                candidateProvider = { listOf(CleanIpCandidate("127.0.0.1", server.localPort)) },
            )

            val result = scanner.findBestByQuality(maxScanDurationMs = 5_000)

            assertEquals("127.0.0.1", result?.ip)
            assertEquals(server.localPort, result?.port)
            assertEquals(0.0, result?.lossRate ?: -1.0, 0.0)
        } finally {
            server.close()
            acceptThread.join(1_000)
        }
    }

    @Test
    fun defaultProbeContinuesWhenSocketProtectionReturnsFalse() = runBlocking {
        val server = ServerSocket(0)
        val acceptThread = Thread {
            runCatching {
                server.accept().use { }
            }
        }.apply { start() }

        try {
            val scanner = CleanIpScanner(
                socketProtector = SocketProtector { false },
                concurrency = 1,
                packetLossProbes = 1,
                speedTestCandidates = 0,
                tcpTimeoutMs = 500,
                candidateProvider = { listOf(CleanIpCandidate("127.0.0.1", server.localPort)) },
            )

            val result = scanner.findBestByQuality(maxScanDurationMs = 5_000)

            assertEquals("127.0.0.1", result?.ip)
            assertEquals(server.localPort, result?.port)
        } finally {
            server.close()
            acceptThread.join(1_000)
        }
    }

    @Test
    fun scannerDefaultsMatchCloudflareScannerStartupPolicy() {
        assertEquals("speed.cloudflare.com", CleanIpDefaults.SPEED_TEST_HOST)
        assertEquals("/__down?bytes=52428800", CleanIpDefaults.SPEED_TEST_PATH)
        assertEquals(200, CleanIpDefaults.SCANNER_CONCURRENCY)
        assertEquals(4, CleanIpDefaults.PACKET_LOSS_PROBES)
        assertEquals(10, CleanIpDefaults.SPEED_TEST_CANDIDATES)
        assertEquals(60_000L, CleanIpDefaults.STARTUP_SCAN_MS)
        assertEquals(3_000L, CleanIpDefaults.STARTUP_QUICK_SCAN_MS)
        assertEquals(12_000L, CleanIpDefaults.STARTUP_FALLBACK_SCAN_MS)
        assertEquals(8, CleanIpDefaults.STARTUP_QUICK_TARGET_RESULTS)
        assertEquals(1, CleanIpDefaults.STARTUP_QUICK_PACKET_LOSS_PROBES)
        assertEquals(0, CleanIpDefaults.STARTUP_QUICK_SPEED_TEST_CANDIDATES)
        assertEquals(5, CleanIpDefaults.STARTUP_RUNTIME_ATTEMPTS)
    }

    @Test
    fun cleanIpCacheKeysAreGlobalPerPort() {
        val profile443 = profileForCache("cf-443-a", 443)
        val another443 = profileForCache("cf-443-b", 443)
        val profile8443 = profileForCache("cf-8443", 8443)

        assertEquals(CleanIpCacheKeys.forPort(profile443.port), CleanIpCacheKeys.forPort(another443.port))
        assertNotEquals(CleanIpCacheKeys.forPort(profile443.port), CleanIpCacheKeys.forPort(profile8443.port))
        assertEquals("clean_ip_results:port:443", CleanIpCacheKeys.forPort(443))
    }

    @Test
    fun cacheCodecDropsInvalidEntriesAndSortsResults() {
        val encoded = """
            [
              {"ip": "104.16.0.2", "port": 443, "latencyMs": 150, "lossRate": 0.0, "checkedAt": 1},
              {"ip": "", "port": 443, "latencyMs": 150, "lossRate": 0.0, "checkedAt": 1},
              {"ip": "104.16.0.1", "port": 8443, "latencyMs": 90, "lossRate": 0.0, "checkedAt": 2, "downloadBytesPerSecond": 1000}
            ]
        """.trimIndent()

        val decoded = CleanIpCacheCodec.decode(encoded)

        assertEquals(2, decoded.size)
        assertEquals("104.16.0.1", decoded.first().ip)
        assertEquals(1_000L, decoded.first().downloadBytesPerSecond)
    }

    @Test
    fun socketProtectorCanProtectScannerSockets() {
        val protector = SocketProtector { socket: Socket -> !socket.isClosed }

        Socket().use { socket ->
            assertTrue(protector.protect(socket))
        }
    }

    private fun profileForCache(tag: String, port: Int): ConnectionProfile {
        return ConnectionProfile(
            tag = tag,
            type = "vless",
            server = "$tag.example.com",
            port = port,
            transport = "ws",
            validationHost = "$tag.example.com",
        )
    }
}
