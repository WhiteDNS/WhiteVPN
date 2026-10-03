package com.whitedns.vpn

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withTimeout
import java.io.File
import java.net.Socket
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.TimeUnit

internal object TorEngineConfig {
    // Default broker/front/STUN settings from pinned Snowflake v2.14.1 client/torrc.
    private const val SNOWFLAKE_BROKER = "https://1098762253.rsc.cdn77.org/"
    private const val SNOWFLAKE_FRONTS = "www.cdn77.com,www.phpmyadmin.net"
    private const val SNOWFLAKE_ICE = "stun:stun.antisip.com:3478,stun:stun.epygi.com:3478," +
        "stun:stun.uls.co.za:3478,stun:stun.voipgate.com:3478,stun:stun.mixvoip.com:3478," +
        "stun:stun.nextcloud.com:3478,stun:stun.bethesda.net:3478,stun:stun.nextcloud.com:443"
    fun build(profile: EngineProfile, nativeDir: String, dataDir: String, port: Int, defaultBridges: String): String {
        fun quoted(value: String): String = "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        return buildString {
            appendLine("SocksPort 127.0.0.1:$port")
            appendLine("DataDirectory " + quoted(dataDir))
            appendLine("ClientOnly 1"); appendLine("AvoidDiskWrites 1"); appendLine("Log notice stdout")
            if (profile.value("bridgeMode", "default") != "none") {
                val transport = profile.value("transport", "obfs4")
                val executable = when (transport) { "snowflake" -> "libsnowflake.so"; "conjure" -> "libconjure.so"; else -> "libobfs4proxy.so" }
                appendLine("UseBridges 1")
                // Tor splits this option into argv on whitespace; it does not strip quotes.
                require(nativeDir.startsWith("/") && nativeDir.none { it.isWhitespace() || it == '"' || it == '\'' })
                val arguments = if (transport == "snowflake")
                    " -url $SNOWFLAKE_BROKER -fronts $SNOWFLAKE_FRONTS -ice $SNOWFLAKE_ICE" else ""
                appendLine("ClientTransportPlugin $transport exec $nativeDir/$executable$arguments")
                val raw = if (profile.value("bridgeMode", "default") == "custom") profile.value("bridges") else defaultBridges
                TorBridgeCompatibility.validate(transport, raw)
                val bridges = raw.lineSequence().map { it.trim().removePrefix("Bridge ") }
                    .filter { it.startsWith(transport + " ") }.toList()
                require(bridges.isNotEmpty()) { "No bridges for the selected transport" }
                bridges.forEach { appendLine("Bridge $it") }
            }
        }
    }
}

internal class TorEngineBackend(private val context: Context, private val profile: EngineProfile) : AbstractEngineBackend() {
    private var process: Process? = null
    private var reader: Thread? = null
    private var forcedTermination = false
    private var controlSocket: Socket? = null
    private val controlPort = EngineNativeAvailability.allocatePort()
    private val bootstrapped = AtomicBoolean(false)
    private val port = EngineNativeAvailability.allocatePort()
    private val dir = File(context.noBackupFilesDir, "tor-" + profile.id)
    override val socksEndpoint get() = SocksEndpoint(port)
    override val running get() = process?.isAlive == true
    override suspend fun start() {
        runInterruptible(Dispatchers.IO) {
            check(dir.mkdirs() || dir.isDirectory)
            // The pinned Conjure dependency persists client updates in ./assets.
            // Keep its working directory and writable state inside this profile directory.
            if (profile.value("transport", "obfs4") == "conjure") {
                val assets = File(dir, "assets")
                check(assets.mkdirs() || assets.isDirectory)
            }
            val defaults = runCatching { context.assets.open("tor/bridges_default.lst").bufferedReader().use { it.readText() } }.getOrDefault("")
            val config = File(dir, "torrc").apply { writeText(TorEngineConfig.build(profile, context.applicationInfo.nativeLibraryDir, dir.absolutePath, port, defaults) + "ControlPort 127.0.0.1:$controlPort\nCookieAuthentication 1\n") }
            val p = ProcessBuilder(File(context.applicationInfo.nativeLibraryDir, "libtor.so").absolutePath, "--__OwningControllerProcess", android.os.Process.myPid().toString(), "-f", config.absolutePath)
                .directory(dir).redirectErrorStream(true).apply { environment()["LD_LIBRARY_PATH"] = context.applicationInfo.nativeLibraryDir }.start()
            process = p
            reader = Thread({
                runCatching {
                    p.inputStream.bufferedReader().useLines { lines -> lines.forEach { line ->
                        Regex("Bootstrapped (\\d+)%").find(line)?.groupValues?.get(1)?.toIntOrNull()?.let { percent ->
                            if (percent == 100) bootstrapped.set(true) else state.value = BackendEvent.Progress(percent)
                        }
                    } }
                }
                if (state.value != BackendEvent.Stopped) state.value = BackendEvent.Failed("Tor exited")
            }, "whitevpn-tor-output").apply { isDaemon = true; start() }
        }
        withTimeout(10_000) {
            val cookie = File(dir, "control_auth_cookie")
            while (!cookie.isFile) { check(running) { "Tor exited before controller startup" }; delay(100) }
            waitForPort(controlPort, { running }, 10_000)
            runInterruptible(Dispatchers.IO) {
                val socket = Socket("127.0.0.1", controlPort).apply { soTimeout = 5000 }
                controlSocket = socket
                TorControlConnection.attach(socket, cookie.readBytes())
            }
        }
        withTimeout(180_000) { while (!bootstrapped.get()) { check(running) { "Tor exited during bootstrap" }; delay(200) } }
        waitForPort(port, { running })
        state.value = BackendEvent.Ready
    }
    override suspend fun stop(): Boolean = runInterruptible(Dispatchers.IO) {
        val p = process
        p?.destroy()
        if (p != null && !p.waitFor(5000, TimeUnit.MILLISECONDS)) {
            forcedTermination = true
            p.destroyForcibly(); p.waitFor(5000, TimeUnit.MILLISECONDS)
        }
        // A forced kill cannot confirm managed transport shutdown; retain ownership.
        val stopped = !forcedTermination && p?.isAlive != true
        controlSocket?.close(); controlSocket = null
        if (stopped) {
            process = null; reader?.join(1000); reader = null
            File(dir, "torrc").delete(); state.value = BackendEvent.Stopped
        }
        stopped
    }
}
