package com.whitedns.vpn

import android.content.Context
import android.net.VpnService
import com.whitedns.vpn.engines.models.DnsTunnelEngineConfig
import com.whitedns.vpn.engines.models.DnsTunnelProfile
import com.whitedns.vpn.engines.models.MasterDnsProfile
import com.whitedns.vpn.engines.models.PsiphonConfigBuilder
import com.whitedns.vpn.engines.models.PsiphonProfile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean

internal class PsiphonEngineBackend(private val context: Context, private val profile: EngineProfile, private val service: VpnService?, private val vpnMode: Boolean = service != null) : AbstractEngineBackend() {
    private var tunnel: Any? = null
    private val active = AtomicBoolean(false)
    private val connected = AtomicBoolean(false)
    private var port = EngineNativeAvailability.allocatePort()
    override val socksEndpoint get() = SocksEndpoint(port)
    override val running get() = active.get()
    override suspend fun start() {
        val dir = File(context.noBackupFilesDir, "psiphon").apply { mkdirs() }
        val config = PsiphonConfigBuilder.build(PsiphonProfile(profile.value("country"), profile.value("mode", "auto"),
            profile.value("cdnIps"), profile.value("cdnSni")), dir.absolutePath, port, EngineNativeAvailability.allocatePort())
        val hostClass = Class.forName("ca.psiphon.PsiphonTunnel\$HostService")
        val host = Proxy.newProxyInstance(hostClass.classLoader, arrayOf(hostClass)) { _, method, args ->
            when (method.name) {
                "getContext" -> context
                "getPsiphonConfig" -> config
                "loadLibrary" -> { System.loadLibrary(args!![0] as String); null }
                "bindToDevice" -> { check(service == null || service.protect((args!![0] as Number).toInt())); null }
                "onListeningSocksProxyPort" -> { port = (args!![0] as Number).toInt(); null }
                "onConnected" -> { connected.set(true); null }
                "onAvailableEgressRegions" -> {
                    if (active.get()) (args?.firstOrNull() as? Collection<*>)?.let {
                        runCatching { PsiphonRegions.record(context, it) }
                    }
                    null
                }
                "onExiting", "onSocksProxyPortInUse", "onUpstreamProxyError", "onInproxyMustUpgrade" -> { active.set(false); null }
                "toString" -> "WhiteVPN Psiphon host"
                "hashCode" -> System.identityHashCode(this)
                "equals" -> false
                else -> when (method.returnType) { java.lang.Boolean.TYPE -> false; java.lang.Integer.TYPE -> 0; else -> null }
            }
        }
        withContext(Dispatchers.IO) {
            val instance = MobileCore.call("ca.psiphon.PsiphonTunnel", "newPsiphonTunnel", host)!!
            tunnel = instance; active.set(true)
            MobileCore.callOn(instance, "setVpnMode", vpnMode)
            MobileCore.callOn(instance, "startTunneling", "")
        }
        withTimeout(120_000) { while (!connected.get()) { check(running) { "Psiphon stopped before connecting" }; delay(200) } }
        waitForPort(port, { running })
        state.value = BackendEvent.Ready
    }
    override suspend fun stop(): Boolean {
        val instance = tunnel
        if (instance != null) withContext(Dispatchers.IO) { MobileCore.callOn(instance, "stop") }
        tunnel = null; active.set(false); connected.set(false)
        state.value = BackendEvent.Stopped
        return true
    }
}

internal class DnsEngineBackend(private val context: Context, private val profile: EngineProfile) : AbstractEngineBackend() {
    private val master = profile.value("engine", "dnstt") == "masterdns"
    private var session: Any? = null
    private var started = false
    private val port = EngineNativeAvailability.allocatePort()
    private val workDir = File(context.noBackupFilesDir, "dns-engine-" + lease.id)
    override val socksEndpoint get() = SocksEndpoint(port)
    override val running get() = started && runCatching {
        if (master) MobileCore.call("masterdns.Masterdns", "isRunning") == true
        else session?.let { MobileCore.callOn(it, "active") == true } == true
    }.getOrDefault(false)
    override suspend fun start() {
        withContext(Dispatchers.IO) {
            if (master) {
                workDir.mkdirs()
                val settings = MasterDnsProfile(domains = profile.value("domains"), encryptionKey = profile.value("encryptionKey"),
                    encryptionMethod = profile.number("encryptionMethod", 2), resolvers = profile.value("resolvers"), listenPort = port)
                require(MasterDnsProfile.invalidResolvers(settings.resolvers).isEmpty()) { "MasterDNS requires numeric resolvers" }
                val config = File(workDir, "config.toml").apply { writeText(settings.toToml()); setReadable(false, false); setReadable(true, true) }
                val resolvers = File(workDir, "resolvers.txt").apply { writeText(settings.resolversText()) }
                started = true
                MobileCore.call("masterdns.Masterdns", "start", config.absolutePath, resolvers.absolutePath, File(workDir, "client.log").absolutePath)
            } else {
                val settings = DnsTunnelProfile(engine = profile.value("engine", "dnstt"), domain = profile.value("domain"), publicKey = profile.value("publicKey"),
                    resolvers = profile.value("resolvers"), dnsTransport = profile.value("dnsTransport", "udp"), dohUrl = profile.value("dohUrl"),
                    socksUser = profile.value("socksUser"), socksPass = profile.value("socksPass"))
                session = MobileCore.call("zeddns.Zeddns", "open", DnsTunnelEngineConfig.build(settings, "127.0.0.1:$port", settings.dnsAddress()))
            }
            started = true
        }
        waitForPort(port, { running }, 180_000)
        state.value = BackendEvent.Ready
    }
    override suspend fun stop(): Boolean {
        if (started || session != null) withContext(Dispatchers.IO) {
            if (master) MobileCore.call("masterdns.Masterdns", "stop") else session?.let { MobileCore.callOn(it, "close") }
        }
        val confirmed = if (master) MobileCore.call("masterdns.Masterdns", "isRunning") != true
            else session?.let { MobileCore.callOn(it, "active") != true } != false
        if (!confirmed) return false
        started = false; session = null
        // All files are confined to this session's own directory.
        if (workDir.exists()) { workDir.listFiles()?.forEach { it.delete() }; workDir.delete() }
        state.value = BackendEvent.Stopped
        return true
    }
}
