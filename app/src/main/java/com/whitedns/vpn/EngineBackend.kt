package com.whitedns.vpn

import android.content.Context
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

internal data class SocksEndpoint(val port: Int, val host: String = "127.0.0.1")
internal data class BackendLease(val id: String = UUID.randomUUID().toString())
internal sealed interface BackendEvent {
    data object Starting : BackendEvent
    data class Progress(val percent: Int) : BackendEvent
    data object Ready : BackendEvent
    data class Failed(val reason: String) : BackendEvent
    data object Stopped : BackendEvent
}
internal interface EngineBackend {
    val lease: BackendLease
    val events: StateFlow<BackendEvent>
    val socksEndpoint: SocksEndpoint?
    val running: Boolean
    val traffic: EngineTrafficRate? get() = null
    suspend fun start()
    suspend fun stop(): Boolean
}
internal abstract class AbstractEngineBackend : EngineBackend {
    final override val lease = BackendLease()
    protected val state = MutableStateFlow<BackendEvent>(BackendEvent.Starting)
    final override val events: StateFlow<BackendEvent> = state
    override val socksEndpoint: SocksEndpoint? = null
    protected suspend fun waitForPort(port: Int, alive: () -> Boolean, timeout: Long = 120_000) {
        withTimeout(timeout) {
            while (true) {
                check(alive()) { "Engine exited before connecting" }
                val open = withContext(Dispatchers.IO) { runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 300) } }.isSuccess }
                if (open) break
                delay(150)
            }
        }
    }
}

internal object MobileCore {
    fun present(className: String): Boolean = runCatching { Class.forName(className, false, javaClass.classLoader) }.isSuccess
    fun call(className: String, method: String, vararg args: Any?): Any? = invoke(null, Class.forName(className), method, args)
    fun callOn(target: Any, method: String, vararg args: Any?): Any? = invoke(target, target.javaClass, method, args)
    private fun invoke(target: Any?, type: Class<*>, method: String, args: Array<out Any?>): Any? {
        val fn = type.methods.first { it.name == method && it.parameterCount == args.size }
        return fn.invoke(target, *args)
    }
}

internal object EngineNativeAvailability {
    fun check(context: Context, profile: EngineProfile): EngineAvailability {
        fun missing(reason: EngineAvailability.Reason = EngineAvailability.Reason.NATIVE_LIBRARY) = EngineAvailability.Unavailable(reason)
        fun binary(name: String) = File(context.applicationInfo.nativeLibraryDir, name).isFile
        fun loadable(name: String): Boolean = binary("lib$name.so") && runCatching { System.loadLibrary(name) }.isSuccess
        return when (profile.kind) {
            EngineKind.SSH -> EngineAvailability.Available
            EngineKind.IKEV2 -> when {
                Build.VERSION.SDK_INT < 30 -> missing(EngineAvailability.Reason.ANDROID_VERSION)
                !context.packageManager.hasSystemFeature(PackageManager.FEATURE_IPSEC_TUNNELS) -> missing(EngineAvailability.Reason.IPSEC_UNSUPPORTED)
                else -> EngineAvailability.Available
            }
            EngineKind.OPENCONNECT -> missing(EngineAvailability.Reason.RETIRED)
            EngineKind.AMNEZIAWG -> if (binary("libamneziawg.so") &&
                (!EngineWorkerService.isWorkerProcess(context) || loadable("amneziawg"))) EngineAvailability.Available else missing()
            EngineKind.PSIPHON -> if (gomobileAvailable(context) && MobileCore.present("ca.psiphon.PsiphonTunnel")) EngineAvailability.Available else missing()
            EngineKind.DNS -> if (gomobileAvailable(context) && MobileCore.present(if (profile.value("engine", "dnstt") == "masterdns") "masterdns.Masterdns" else "zeddns.Zeddns")) EngineAvailability.Available else missing()
            EngineKind.TOR -> {
                if (!binary("libtor.so") || !File(context.applicationInfo.nativeLibraryDir, "libtor.so").canExecute()) return missing()
                if (profile.value("bridgeMode", "default") != "none") {
                    val transport = when (profile.value("transport", "obfs4")) {
                        "snowflake" -> "libsnowflake.so"; "conjure" -> "libconjure.so"; else -> "libobfs4proxy.so"
                    }
                    if (!binary(transport) || !File(context.applicationInfo.nativeLibraryDir, transport).canExecute()) return missing(EngineAvailability.Reason.TOR_TRANSPORT)
                    if (profile.value("bridgeMode", "default") == "default" &&
                        runCatching { context.assets.open("tor/bridges_default.lst").close() }.isFailure) return missing(EngineAvailability.Reason.TOR_ASSETS)
                }
                EngineAvailability.Available
            }
        }
    }
    private fun gomobileAvailable(context: Context): Boolean {
        if (!File(context.applicationInfo.nativeLibraryDir, "libgojni.so").isFile) return false
        // Loading here in the app process would initialize a second Go runtime beside Mihomo.
        return !EngineWorkerService.isWorkerProcess(context) || runCatching { System.loadLibrary("gojni") }.isSuccess
    }
    fun allocatePort(): Int = ServerSocket().use { it.bind(InetSocketAddress("127.0.0.1", 0)); it.localPort }
}

/** Interactive trust cannot be silently accepted by a worker or restored from a stale session. */
internal object EngineTrustPrompts {
    data class Prompt(val message: String, internal val answer: CompletableFuture<Boolean>)
    val pending = MutableStateFlow<Prompt?>(null)
    @Synchronized fun ask(message: String): Boolean {
        val prompt = Prompt(message, CompletableFuture())
        pending.value = prompt
        return try { prompt.answer.get(120, TimeUnit.SECONDS) } catch (_: Exception) { false }
        finally { if (pending.value === prompt) pending.value = null }
    }
    fun answer(prompt: Prompt, accepted: Boolean) { if (pending.value === prompt) prompt.answer.complete(accepted) }
    fun cancel() { pending.value?.answer?.complete(false) }
}

internal class EngineBackendFactory(private val context: Context, private val service: VpnService? = null) {
    fun create(profile: EngineProfile, configureTun: (VpnService.Builder) -> Unit = {}): EngineBackend = when (profile.kind) {
        EngineKind.SSH -> SshEngineBackend(context, profile)
        EngineKind.TOR -> TorEngineBackend(context, profile)
        EngineKind.PSIPHON -> RemoteEngineBackend(context, profile, service != null)
        EngineKind.DNS -> RemoteEngineBackend(context, profile, service != null)
        EngineKind.AMNEZIAWG -> AmneziaEngineBackend(requireNotNull(service), profile, configureTun)
        EngineKind.OPENCONNECT -> error("OpenConnect has been retired")
        EngineKind.IKEV2 -> error("IKEv2 is owned by the platform controller")
    }
}
