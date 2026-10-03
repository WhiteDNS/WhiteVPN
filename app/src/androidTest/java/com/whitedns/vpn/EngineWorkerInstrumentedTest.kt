package com.whitedns.vpn

import android.app.ActivityManager
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.follow.clash.core.Core
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

/** Exercises actual Android IPC/JNI instead of mocks; Psiphon's public network must be reachable. */
@RunWith(AndroidJUnit4::class)
class EngineWorkerInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun profile() = EngineProfile(name = "Lifecycle test", kind = EngineKind.PSIPHON,
        config = EngineConfig.Psiphon(mapOf("mode" to "auto")))
    private fun workerPid(): Int? = context.getSystemService(ActivityManager::class.java).runningAppProcesses
        ?.firstOrNull { it.processName == context.applicationInfo.processName + ":enginecore" }?.pid
    private suspend fun waitForWorker(engine: RemoteEngineBackend): Int = withTimeout(20_000) {
        // ActivityManager can return a cached, unbound process from an earlier session.
        while (engine.ownedWorkerPid == null) delay(20)
        requireNotNull(engine.ownedWorkerPid)
    }
    private suspend fun waitForWorkerExit() = withTimeout(5_000) {
        while (workerPid() != null) delay(50)
    }
    private fun gomobileLoadedInApp(): Boolean = File("/proc/self/maps").readText().contains("libgojni.so")

    @Test fun a_realPsiphonRoutesHttpsAndSurvivesRepeatedDisconnect() = runBlocking {
        val appPid = Process.myPid()
        // This deliberately loads Mihomo's Go runtime in the app. Psiphon's runtime must remain elsewhere.
        Core.forceGC()
        repeat(3) {
            val profile = profile()
            assertEquals(EngineAvailability.Available, EngineNativeAvailability.check(context, profile))
            assertFalse(gomobileLoadedInApp())
            val engine = RemoteEngineBackend(context, profile, false)
            assertTrue(EngineSessionOwnership.leases.claim(engine.lease))
            try {
                withTimeout(200_000) { engine.start() }
                assertTrue(engine.running)
                val port = requireNotNull(engine.socksEndpoint).port
                val response = withContext(Dispatchers.IO) { EngineSocksHttp.measure(port, "https://www.gstatic.com/generate_204", false) }
                assertTrue(response.isNotBlank())
                Core.forceGC()
                assertEquals(appPid, Process.myPid())
                assertFalse(gomobileLoadedInApp())
                assertTrue(engine.stop())
                assertFalse(engine.running)
                waitForWorkerExit()
                assertFalse(runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 300) } }.isSuccess)
            } finally {
                if (engine.stop()) EngineSessionOwnership.leases.confirmRelease(engine.lease)
            }
        }
    }

    @Test fun b_cancelledStartupReleasesItsWorker() = runBlocking {
        val engine = RemoteEngineBackend(context, profile(), false)
        assertTrue(EngineSessionOwnership.leases.claim(engine.lease))
        try {
            val startup = launch { engine.start() }
            waitForWorker(engine)
            delay(100)
            startup.cancelAndJoin()
            assertTrue(engine.stop())
            waitForWorkerExit()
            assertFalse(gomobileLoadedInApp())
        } finally {
            if (engine.stop()) EngineSessionOwnership.leases.confirmRelease(engine.lease)
        }
    }

    @Test fun c_workerDeathDoesNotKillOrReconnectTheApp() = runBlocking {
        val appPid = Process.myPid()
        val engine = RemoteEngineBackend(context, profile(), false)
        assertTrue(EngineSessionOwnership.leases.claim(engine.lease))
        try {
            val startup = async { runCatching { engine.start() } }
            val pid = waitForWorker(engine)
            assertNotEquals(appPid, pid)
            Process.killProcess(pid)
            withTimeout(10_000) { startup.await() }
            withTimeout(5_000) { while (engine.events.value !is BackendEvent.Failed) delay(20) }
            assertTrue(engine.stop())
            delay(300)
            waitForWorkerExit()
            assertEquals(appPid, Process.myPid())
            assertFalse(engine.running)
        } finally {
            if (engine.stop()) EngineSessionOwnership.leases.confirmRelease(engine.lease)
        }
    }
}
