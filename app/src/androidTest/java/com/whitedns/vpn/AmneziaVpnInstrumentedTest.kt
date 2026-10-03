package com.whitedns.vpn

import android.content.Intent
import android.net.VpnService
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.URL

/** External fixture keys are generated afresh. No server or test keys are bundled in the APK. */
@RunWith(AndroidJUnit4::class)
class AmneziaVpnInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun configuration(): String {
        val file = File(context.cacheDir, "awgfixture.conf")
        assumeTrue("Provide a local test gateway config in cache/awgfixture.conf", file.isFile)
        return file.readText()
    }
    private suspend fun action(action: String) = withContext(Dispatchers.Main) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.adoptShellPermissionIdentity("android.permission.START_FOREGROUND_SERVICES_FROM_BACKGROUND")
        try {
            context.startForegroundService(Intent(context, WhiteDnsVpnService::class.java)
                .setAction(action).putExtra(Actions.EXTRA_APP_INITIATED, true))
        } finally { automation.dropShellPermissionIdentity() }
    }
    private suspend fun awaitState(expected: VpnState) = withTimeout(100_000) {
        while (VpnRuntimeStateStore.read(context) != expected) {
            val state = VpnRuntimeStateStore.read(context)
            check(expected == VpnState.Stopped || state !is VpnState.Error) { "VPN service failed" }
            delay(100)
        }
    }

    @Test fun nativeRoutesHttpsTcpUdpCountersAndRepeatedDisconnect() = runBlocking {
        assertNull("Grant ACTIVATE_VPN before this test", VpnService.prepare(context))
        val store = EngineProfileStore(context)
        val previousSelection = store.selectedEngineId()
        val mode = ConnectionModePreferenceStore(context); val previousMode = mode.read()
        val split = SplitTunnelPreferenceStore(context); val previousSplit = split.readSettings()
        val p = EngineProfile(name = "AmneziaWG integration fixture", kind = EngineKind.AMNEZIAWG,
            config = EngineConfig.AmneziaWg(mapOf("config" to configuration())))
        store.save(p); store.selectEngine(p.id); mode.save(ConnectionMode.Vpn)
        split.saveSettings(SplitTunnelSettings(mode = SplitTunnelMode.Off, selectedPackages = emptySet()))
        try {
            repeat(3) { cycle ->
                VpnRuntimeStateStore.save(context, VpnState.Stopped)
                action(Actions.CONNECT); awaitState(VpnState.Started)
                assertEquals(p.id, VpnRuntimeStateStore.readActiveConnectionFingerprint(context))
                assertFalse(File("/proc/self/maps").readText().contains("libamneziawg.so"))
                val network = requireNotNull(EngineNetworkProbe.ownedVpn(context))
                var uploaded = 0L; var downloaded = 0L
                val sampler = launch {
                    while (isActive) {
                        EngineTrafficState.registry.read(p.id, SystemClock.elapsedRealtime())?.let {
                            uploaded = maxOf(uploaded, it.uploadBytesPerSecond)
                            downloaded = maxOf(downloaded, it.downloadBytesPerSecond)
                        }
                        delay(100)
                    }
                }
                try {
                    withContext(Dispatchers.IO) {
                        network.socketFactory.createSocket("10.55.0.1", 4444).use { socket ->
                            socket.soTimeout = 8000
                            val payload = ByteArray(32768) { (it % 251).toByte() }
                            socket.getOutputStream().write(payload)
                            val received = ByteArray(payload.size); var count = 0
                            while (count < received.size) {
                                val n = socket.getInputStream().read(received, count, received.size - count)
                                check(n > 0); count += n
                            }
                            assertArrayEquals(payload, received)
                        }
                        DatagramSocket().use { socket ->
                            network.bindSocket(socket); socket.soTimeout = 8000
                            val payload = "AmneziaWG routed UDP".toByteArray()
                            socket.send(DatagramPacket(payload, payload.size, InetAddress.getByName("10.55.0.1"), 4444))
                            val response = DatagramPacket(ByteArray(128), 128); socket.receive(response)
                            assertArrayEquals(payload, response.data.copyOf(response.length))
                        }
                        val connection = network.openConnection(URL("https://speed.cloudflare.com/__down?bytes=5000000")) as java.net.HttpURLConnection
                        connection.connectTimeout = 12000; connection.readTimeout = 20000
                        try {
                            assertEquals(200, connection.responseCode)
                            var bytes = 0L
                            connection.inputStream.use { stream ->
                                val buffer = ByteArray(16384)
                                while (true) { val n = stream.read(buffer); if (n < 0) break; bytes += n }
                            }
                            assertEquals(5000000L, bytes)
                        } finally { connection.disconnect() }
                    }
                    delay(1500)
                } finally { sampler.cancelAndJoin() }
                assertTrue("Native upload rate stayed zero", uploaded > 0)
                assertTrue("Native download rate stayed zero", downloaded > 0)
                action(Actions.DISCONNECT); awaitState(VpnState.Stopped)
                assertNull(EngineTrafficState.registry.read(p.id, SystemClock.elapsedRealtime()))
                assertNull(EngineNetworkProbe.ownedVpn(context))
                if (cycle == 0) {
                    // Psiphon initializes another Go runtime; confirmed worker disposal must permit switching.
                    val psiphon = RemoteEngineBackend(context, EngineProfile(name = "Switch fixture", kind = EngineKind.PSIPHON,
                        config = EngineConfig.Psiphon(mapOf("mode" to "auto"))), false)
                    try {
                        psiphon.start()
                        withContext(Dispatchers.IO) { EngineSocksHttp.measure(requireNotNull(psiphon.socksEndpoint).port, "https://www.gstatic.com/generate_204", false) }
                    } finally { assertTrue(psiphon.stop()) }
                }
            }
        } finally {
            if (VpnRuntimeStateStore.read(context) != VpnState.Stopped) {
                action(Actions.DISCONNECT); withTimeoutOrNull(30000) { awaitState(VpnState.Stopped) }
            }
            if (previousSelection != null && store.profile(previousSelection) != null) store.selectEngine(previousSelection) else store.selectMihomo()
            store.delete(p.id); mode.save(previousMode); split.saveSettings(previousSplit)
        }
    }

    @Test fun readinessTimeoutReportsFailureAndReleasesNativeSession() = runBlocking {
        assertNull("Grant ACTIVATE_VPN before this test", VpnService.prepare(context))
        val store = EngineProfileStore(context); val before = store.selectedEngineId()
        val mode = ConnectionModePreferenceStore(context); val previousMode = mode.read()
        val p = EngineProfile(name = "AWG timeout fixture", kind = EngineKind.AMNEZIAWG,
            config = EngineConfig.AmneziaWg(mapOf("config" to configuration().replace(":55182", ":55183"))))
        store.save(p); store.selectEngine(p.id); mode.save(ConnectionMode.Vpn)
        try {
            VpnRuntimeStateStore.save(context, VpnState.Stopped); action(Actions.CONNECT)
            withTimeout(75000) {
                while (VpnRuntimeStateStore.read(context) !is VpnState.Error) {
                    assertNotEquals(VpnState.Started, VpnRuntimeStateStore.read(context))
                    delay(100)
                }
            }
            assertNull(EngineNetworkProbe.ownedVpn(context))
            assertNull(EngineTrafficState.registry.read(p.id, SystemClock.elapsedRealtime()))
            assertEquals(p.id, store.selectedEngineId())
            assertFalse(File("/proc/self/maps").readText().contains("libamneziawg.so"))
            action(Actions.DISCONNECT); awaitState(VpnState.Stopped)
        } finally {
            if (VpnRuntimeStateStore.read(context) != VpnState.Stopped) { action(Actions.DISCONNECT); withTimeoutOrNull(30000) { awaitState(VpnState.Stopped) } }
            if (before != null && store.profile(before) != null) store.selectEngine(before) else store.selectMihomo()
            store.delete(p.id); mode.save(previousMode)
        }
    }

    @Test fun unreachablePeerNeverReportsConnectedAndCancellationReleasesNativeSession() = runBlocking {
        assertNull("Grant ACTIVATE_VPN before this test", VpnService.prepare(context))
        val store = EngineProfileStore(context); val before = store.selectedEngineId()
        val mode = ConnectionModePreferenceStore(context); val previousMode = mode.read()
        val p = EngineProfile(name = "Unreachable AWG fixture", kind = EngineKind.AMNEZIAWG,
            config = EngineConfig.AmneziaWg(mapOf("config" to configuration().replace(":55182", ":55183"))))
        store.save(p); store.selectEngine(p.id); mode.save(ConnectionMode.Vpn)
        try {
            VpnRuntimeStateStore.save(context, VpnState.Stopped); action(Actions.CONNECT)
            withTimeout(15000) { while (EngineNetworkProbe.ownedVpn(context) == null) delay(20) }
            delay(2000)
            assertEquals(VpnState.Starting, VpnRuntimeStateStore.read(context))
            action(Actions.DISCONNECT); awaitState(VpnState.Stopped)
            assertNull(EngineNetworkProbe.ownedVpn(context))
            assertNull(EngineTrafficState.registry.read(p.id, SystemClock.elapsedRealtime()))
            assertFalse(File("/proc/self/maps").readText().contains("libamneziawg.so"))
        } finally {
            if (VpnRuntimeStateStore.read(context) != VpnState.Stopped) { action(Actions.DISCONNECT); withTimeoutOrNull(30000) { awaitState(VpnState.Stopped) } }
            if (before != null && store.profile(before) != null) store.selectEngine(before) else store.selectMihomo()
            store.delete(p.id); mode.save(previousMode)
        }
    }
}
