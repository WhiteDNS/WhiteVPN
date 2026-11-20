package com.whitedns.vpn

import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Manual public-endpoint diagnostic, using no saved profiles or user credentials. */
@RunWith(AndroidJUnit4::class)
class ConjureDiagnosticInstrumentedTest {
    @Test fun capturePublicBridgeBootstrapFailure() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dir = File(context.noBackupFilesDir, "conjure-diagnostic").apply { mkdirs() }
        File(dir, "assets").apply { check(mkdirs() || isDirectory) }
        val native = context.applicationInfo.nativeLibraryDir
        val profile = EngineProfile(name = "Public diagnostic", kind = EngineKind.TOR,
            config = EngineConfig.Tor(mapOf("transport" to "conjure", "bridgeMode" to "default")))
        val defaults = context.assets.open("tor/bridges_default.lst").bufferedReader().use { it.readText() }
        val raw = TorEngineConfig.build(profile, native, dir.absolutePath, EngineNativeAvailability.allocatePort(), defaults)
            .replace("exec $native/libconjure.so", "exec $native/libconjure.so -log-to-state-dir -log diagnostic.log")
        val config = File(dir, "torrc").apply { writeText(raw) }
        val lines = mutableListOf<String>()
        val ready = AtomicBoolean(false)
        val child = ProcessBuilder("$native/libtor.so", "--__OwningControllerProcess", Process.myPid().toString(), "-f", config.absolutePath)
            .directory(dir).redirectErrorStream(true).apply { environment()["LD_LIBRARY_PATH"] = native }.start()
        val reader = Thread {
            runCatching { child.inputStream.bufferedReader().useLines { output -> output.forEach { line ->
                synchronized(lines) { lines.add(line); if (lines.size > 60) lines.removeAt(0) }
                if (line.contains("Bootstrapped 100%")) ready.set(true)
            } } }
        }.apply { isDaemon = true; start() }
        try {
            withTimeoutOrNull(120_000) { while (!ready.get() && child.isAlive) delay(200) }
        } finally {
            child.destroy()
            if (!child.waitFor(5, TimeUnit.SECONDS)) { child.destroyForcibly(); child.waitFor(5, TimeUnit.SECONDS) }
            reader.join(1000)
            fun safe(value: String): String = with(DiagnosticLogger) { value.sanitizeForLog() }
                .replace(Regex("[A-Za-z0-9+/_=-]{48,}"), "<redacted-data>")
            synchronized(lines) { lines.forEach { println("Tor diagnostic: " + safe(it)) } }
            dir.walkTopDown().filter { it.name == "diagnostic.log" }.forEach { file ->
                file.readLines().takeLast(40).forEach { println("Conjure diagnostic: " + safe(it)) }
            }
            assertFalse("Diagnostic Tor process did not exit", child.isAlive)
        }
        assertTrue("Conjure did not persist the station client configuration", File(dir, "assets/ClientConf").isFile)
        assertTrue("Public Conjure bridge did not bootstrap; see sanitized test log", ready.get())
    }
}
