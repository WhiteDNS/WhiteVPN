package com.whitedns.vpn

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.net.Socket

/** Uses the packaged executable and public Tor network, including authenticated controller ownership. */
@RunWith(AndroidJUnit4::class)
class TorEngineInstrumentedTest {
    @Test fun directTorBootstrapsRoutesHttpsAndFullyStops() = connect("none", "obfs4")
    @Test fun defaultObfs4BootstrapsRoutesHttpsAndFullyStops() = connect("default", "obfs4")
    @Test fun defaultSnowflakeBootstrapsRoutesHttpsAndFullyStops() = connect("default", "snowflake")
    @Test fun defaultConjureBootstrapsRoutesHttpsAndFullyStops() = connect("default", "conjure")

    @Test fun referenceConjureBootstrapsRoutesHttpsAndFullyStops() = connect("custom", "conjure",
        "conjure 143.110.214.222:80 50B99540A96C5E9F9F7704BAAE11DF01564711F4 url=https://registration.refraction.network")

    private fun connect(bridgeMode: String, transport: String, bridges: String = "") = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val profile = EngineProfile(name = "Tor integration test", kind = EngineKind.TOR,
            config = EngineConfig.Tor(mapOf("bridgeMode" to bridgeMode, "transport" to transport, "bridges" to bridges)))
        assertEquals(EngineAvailability.Available, EngineNativeAvailability.check(context, profile))
        val engine = TorEngineBackend(context, profile)
        assertTrue(EngineSessionOwnership.leases.claim(engine.lease))
        val progress = launch { engine.events.collect { event ->
            if (event is BackendEvent.Progress) println("Tor $transport bootstrap=${event.percent}")
        } }
        try {
            withTimeout(200_000) { engine.start() }
            assertTrue(engine.running)
            val port = requireNotNull(engine.socksEndpoint).port
            withContext(Dispatchers.IO) { EngineSocksHttp.measure(port, "https://www.gstatic.com/generate_204", false) }
            assertTrue(engine.stop())
            assertFalse(engine.running)
            assertFalse(Socket().use { socket -> runCatching { socket.connect(InetSocketAddress("127.0.0.1", port), 300) }.isSuccess })
        } finally {
            progress.cancelAndJoin()
            if (engine.stop()) EngineSessionOwnership.leases.confirmRelease(engine.lease)
        }
    }
}
