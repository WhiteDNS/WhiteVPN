package com.whitedns.vpn

import android.content.Intent
import android.net.VpnService
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.net.Socket

/** Requires ACTIVATE_VPN permission; routes real HTTPS through worker, Mihomo and VpnService. */
@RunWith(AndroidJUnit4::class)
class EngineVpnInstrumentedTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private suspend fun action(action: String) = withContext(Dispatchers.Main) {
        val automation = InstrumentationRegistry.getInstrumentation().uiAutomation
        automation.adoptShellPermissionIdentity("android.permission.START_FOREGROUND_SERVICES_FROM_BACKGROUND")
        try {
            context.startForegroundService(Intent(context, WhiteDnsVpnService::class.java)
                .setAction(action).putExtra(Actions.EXTRA_APP_INITIATED, true))
        } finally { automation.dropShellPermissionIdentity() }
    }
    private suspend fun awaitState(expected: VpnState) = withTimeout(200_000) {
        while (VpnRuntimeStateStore.read(context) != expected) {
            val state = VpnRuntimeStateStore.read(context)
            check(expected == VpnState.Stopped || state !is VpnState.Error) { "VPN service failed: " + (state as VpnState.Error).message }
            delay(100)
        }
    }

    @Test fun disconnectWithoutActiveServiceDoesNotCrash() = runBlocking {
        assertNull(EngineNetworkProbe.ownedVpn(context))
        VpnRuntimeStateStore.save(context, VpnState.Stopped)
        val pid = android.os.Process.myPid()
        action(Actions.DISCONNECT)
        delay(7000)
        assertEquals(pid, android.os.Process.myPid())
        assertEquals(VpnState.Stopped, VpnRuntimeStateStore.read(context))
    }

    @Test fun psiphonVpnRoutesHttpsPublishesTrafficAndDisconnects() = runBlocking {
        assertNull("Grant ACTIVATE_VPN to the test app before running this test", VpnService.prepare(context))
        val store = EngineProfileStore(context)
        val previousSelection = store.selectedEngineId()
        val mode = ConnectionModePreferenceStore(context)
        val previousMode = mode.read()
        val profile = EngineProfile(name = "VPN lifecycle test", kind = EngineKind.PSIPHON,
            config = EngineConfig.Psiphon(mapOf("mode" to "auto")))
        store.save(profile); store.selectEngine(profile.id); mode.save(ConnectionMode.Vpn)
        try {
            VpnRuntimeStateStore.save(context, VpnState.Stopped)
            action(Actions.CONNECT)
            awaitState(VpnState.Started)
            assertEquals(profile.id, VpnRuntimeStateStore.readActiveConnectionFingerprint(context))
            assertNotNull(EngineNetworkProbe.ownedVpn(context))
            var uploaded = 0L
            var downloaded = 0L
            val sampling = launch {
                while (isActive) {
                    EngineTrafficState.registry.read(profile.id, SystemClock.elapsedRealtime())?.let {
                        uploaded = maxOf(uploaded, it.uploadBytesPerSecond)
                        downloaded = maxOf(downloaded, it.downloadBytesPerSecond)
                    }
                    delay(100)
                }
            }
            try {
                withContext(Dispatchers.IO) {
                    EngineSocksHttp.measure(MihomoRuntimeDefaults.MIXED_PORT, "https://www.gstatic.com/generate_204", false)
                    EngineSocksHttp.measure(MihomoRuntimeDefaults.MIXED_PORT, "https://speed.cloudflare.com/__down?bytes=5000000", true)
                }
                delay(2500)
            } finally { sampling.cancelAndJoin() }
            assertTrue("Routed upload counter stayed zero", uploaded > 0)
            assertTrue("Routed download counter stayed zero", downloaded > 0)
            action(Actions.DISCONNECT)
            awaitState(VpnState.Stopped)
            assertNull(EngineTrafficState.registry.read(profile.id, SystemClock.elapsedRealtime()))
            assertFalse(Socket().use { socket -> runCatching { socket.connect(InetSocketAddress("127.0.0.1", MihomoRuntimeDefaults.MIXED_PORT), 300) }.isSuccess })
        } finally {
            if (VpnRuntimeStateStore.read(context) != VpnState.Stopped) {
                action(Actions.DISCONNECT)
                withTimeoutOrNull(40_000) { awaitState(VpnState.Stopped) }
            }
            if (previousSelection != null && store.profile(previousSelection) != null) store.selectEngine(previousSelection) else store.selectMihomo()
            store.delete(profile.id); mode.save(previousMode)
        }
    }
}
