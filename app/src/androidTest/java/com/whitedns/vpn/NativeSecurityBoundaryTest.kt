package com.whitedns.vpn

import android.os.Build
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.follow.clash.core.Core
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.URL

@RunWith(AndroidJUnit4::class)
class NativeSecurityBoundaryTest {
    @Test
    fun bundledCoreRejectsHostileConfigAndPreservesTrafficAndShutdown() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.cacheDir, "native-security-${System.nanoTime()}").apply { mkdirs() }
        val config = File(directory, "config.yaml")
        val normal = """
            proxies:
              - &proxy {name: unused, type: socks5, server: 127.0.0.1, port: 1080}
              - {<<: *proxy, name: unused-alias}
            proxy-groups: [{name: test, type: select, proxies: [DIRECT, unused, unused-alias]}]
            rules: ['MATCH,DIRECT']
            geo-auto-update: true
            geo-update-interval: 168
        """.trimIndent()
        fun runtime(raw: String) = MihomoRuntimeConfigBuilder.flClashRuntimeYaml(raw, "test-only", 19090)
        suspend fun setup(raw: String): String {
            config.writeText(runtime(raw))
            val result = CompletableDeferred<String>()
            Core.quickSetup(
                MihomoRuntimeConfigBuilder.initParamsJson(directory.absolutePath, Build.VERSION.SDK_INT).toString(),
                MihomoRuntimeConfigBuilder.setupParamsJson().toString(),
            ) { result.complete(it.orEmpty()) }
            return withTimeout(30_000) { result.await() }
        }
        suspend fun action(method: String): JSONObject {
            val result = CompletableDeferred<String>()
            Core.invokeAction(JSONObject().put("id", method).put("method", method).put("data", "").toString()) {
                result.complete(it.orEmpty())
            }
            return JSONObject(withTimeout(10_000) { result.await() })
        }
        fun socketOpen(port: Int): Boolean = runCatching {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 500) }
        }.isSuccess
        try {
            val attacks = listOf(
                "listeners: [{name: injected, type: http, listen: 0.0.0.0, port: 19080}]",
                "'listeners': [{name: injected, type: http, listen: 0.0.0.0, port: 19080}]",
                "tunnels: [{network: [tcp, udp], address: '0.0.0.0:19081', target: '127.0.0.1:80'}]",
                "<<: {listeners: [{name: injected, type: http, listen: 0.0.0.0, port: 19080}]}",
                "future-server: {enable: true}",
                "'mixed-port': 19080",
                "---\nlisteners: []",
            )
            for (attack in attacks) {
                assertTrue("Hostile config accepted: $attack", setup("$normal\n$attack").isNotBlank())
                assertFalse("Injected HTTP listener reachable", socketOpen(19080))
                assertFalse("Injected tunnel reachable", socketOpen(19081))
            }
            assertEquals("", setup(normal))
            assertEquals(0, action("startListener").getInt("code"))
            assertTrue("Mixed listener did not start", socketOpen(MihomoRuntimeDefaults.MIXED_PORT))
            val sockets = ParcelFileDescriptor.AutoCloseInputStream(
                InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand("netstat -an"),
            ).bufferedReader().use { it.readText() }
            for (port in listOf(2080, 1053, 19090)) {
                val rows = sockets.lineSequence().filter { it.contains(":$port ") }.toList()
                assertTrue("Expected native socket absent: $port", rows.isNotEmpty())
                assertTrue("Native socket escaped loopback: $rows", rows.all { it.contains("127.0.0.1:$port ") })
                Log.i("WhiteVPNSecurity", rows.joinToString("\n"))
            }
            val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", MihomoRuntimeDefaults.MIXED_PORT))
            val connection = URL("https://www.gstatic.com/generate_204").openConnection(proxy) as HttpURLConnection
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            try { assertEquals(204, connection.responseCode) } finally { connection.disconnect() }

            // The controller reload is a sibling entry point; rejected YAML must
            // leave the active configuration working, with no injected socket.
            val hostile = runtime("$normal\n${attacks.first()}")
            val payload = JSONObject().put("payload", hostile).toString().toByteArray(Charsets.UTF_8)
            // Exercise the native loopback HTTP endpoint without changing the
            // app's cleartext-traffic policy (normal app control uses JNI).
            Socket().use { socket ->
                socket.connect(InetSocketAddress("127.0.0.1", 19090), 5_000)
                socket.soTimeout = 5_000
                val headers = "PUT /configs?force=true HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
                    "Authorization: Bearer test-only\r\nContent-Type: application/json\r\n" +
                    "Content-Length: ${payload.size}\r\nConnection: close\r\n\r\n"
                socket.getOutputStream().apply {
                    write(headers.toByteArray(Charsets.US_ASCII))
                    write(payload)
                    flush()
                }
                val status = socket.getInputStream().bufferedReader().readLine().split(" ")[1].toInt()
                assertTrue("Controller accepted hostile reload: $status", status in 400..499)
            }
            assertFalse(socketOpen(19080))
            assertTrue(socketOpen(MihomoRuntimeDefaults.MIXED_PORT))
            assertEquals(0, action("stopListener").getInt("code"))
            assertFalse("Mixed listener survived stop", socketOpen(MihomoRuntimeDefaults.MIXED_PORT))
            assertEquals(0, action("shutdown").getInt("code"))
            assertFalse(socketOpen(19080))
            assertFalse(socketOpen(19081))
        } finally {
            action("shutdown")
            directory.deleteRecursively()
        }
    }
}
