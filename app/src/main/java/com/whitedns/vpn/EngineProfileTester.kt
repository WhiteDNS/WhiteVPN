package com.whitedns.vpn

import android.content.Context
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.net.URLConnection
import android.os.SystemClock
import javax.net.ssl.HttpsURLConnection

internal object EngineProfileTester {
    private val mutex = Mutex()
    @Volatile private var testJob: Job? = null
    @Volatile private var unresolvedBackend: EngineBackend? = null
    suspend fun cancelAndJoin() {
        testJob?.cancelAndJoin()
        unresolvedBackend?.let { backend ->
            check(withContext(NonCancellable) { backend.stop() }) { "Engine test resources have not released" }
            EngineSessionOwnership.leases.confirmRelease(backend.lease)
            unresolvedBackend = null
        }
    }
    suspend fun isolated(context: Context, profile: EngineProfile, speed: Boolean): String {
        check(unresolvedBackend == null) { "Previous engine test resources have not released" }
        check(mutex.tryLock()) { "Another engine test is running" }
        val job = currentCoroutineContext().job
        testJob = job
        var backend: EngineBackend? = null
        try {
            check(VpnRuntimeStateStore.read(context) != VpnState.Started && EngineNetworkProbe.ownedVpn(context) == null) { "Stop the active connection first" }
            EnginePreflight.validate(profile, EngineNativeAvailability.check(context, profile), false, false)
            val candidate = EngineBackendFactory(context).create(profile)
            check(EngineSessionOwnership.leases.claim(candidate.lease)) { "Another engine owns connection resources" }
            backend = candidate
            backend.start()
            val endpoint = requireNotNull(backend.socksEndpoint)
            return throughProxy(context, endpoint.port, speed)
        } finally {
            try {
                withContext(NonCancellable) { backend?.let {
                    val stopped = runCatching { it.stop() }.getOrDefault(false)
                    if (stopped) EngineSessionOwnership.leases.confirmRelease(it.lease)
                    if (!stopped) unresolvedBackend = it
                    check(stopped) { "Engine test resources have not released" }
                } }
            } finally {
                if (testJob === job) testJob = null
                mutex.unlock()
            }
        }
    }
    suspend fun activeSocks(context: Context, speed: Boolean): String = measure(context, speed) { url ->
        URL(url).openConnection(Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", MihomoRuntimeDefaults.MIXED_PORT)))
    }
    suspend fun native(context: Context, speed: Boolean): String {
        val network = EngineNetworkProbe.ownedVpn(context) ?: error("Native VPN is unavailable")
        return measure(context, speed) { url -> network.openConnection(URL(url)) }
    }
    private suspend fun throughProxy(context: Context, port: Int, speed: Boolean): String = runInterruptible(Dispatchers.IO) {
        val settings = ConnectionTestSettingsPreferenceStore(context).read()
        EngineSocksHttp.measure(port, if (speed) MihomoRuntimeDefaults.SPEED_TEST_URL_PREFIX + settings.speedTestBytes else MihomoRuntimeDefaults.HEALTH_URLS.first(), speed,
            settings.timeoutSeconds * 1000, settings.speedTestBytes)
    }
    private suspend fun measure(context: Context, speed: Boolean, open: (String) -> URLConnection): String = runInterruptible(Dispatchers.IO) {
        val settings = ConnectionTestSettingsPreferenceStore(context).read()
        val url = if (speed) MihomoRuntimeDefaults.SPEED_TEST_URL_PREFIX + settings.speedTestBytes else MihomoRuntimeDefaults.HEALTH_URLS.first()
        val connection = open(url) as HttpsURLConnection
        val start = SystemClock.elapsedRealtime()
        try {
            connection.connectTimeout = settings.timeoutSeconds * 1000; connection.readTimeout = settings.timeoutSeconds * 1000; connection.instanceFollowRedirects = false
            check(connection.responseCode in 200..299) { "Probe failed" }
            if (!speed) return@runInterruptible (SystemClock.elapsedRealtime() - start).toString() + " ms"
            var bytes = 0L
            connection.inputStream.use { input ->
                val buffer = ByteArray(16384)
                while (bytes < settings.speedTestBytes) {
                    if (Thread.currentThread().isInterrupted) throw InterruptedException()
                    val count = input.read(buffer); if (count < 0) break; bytes += count
                }
            }
            check(bytes >= settings.speedTestBytes) { "Incomplete throughput response" }
            val seconds = (SystemClock.elapsedRealtime() - start).coerceAtLeast(1) / 1000.0
            "%.2f Mbps".format(bytes * 8.0 / seconds / 1_000_000)
        } finally { connection.disconnect() }
    }
}
