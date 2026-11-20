package com.whitedns.vpn

import android.app.ActivityManager
import android.app.Application
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.*
import android.os.Process
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.amnezia.awg.GoBackend
import android.net.VpnService

/** Private IPC carries configuration in memory; no credentials are logged or written by the broker. */
internal object EngineWorkerProtocol {
    const val START = 1
    const val STOP = 2
    const val HELLO = 3
    const val READY = 4
    const val PROGRESS = 5
    const val FAILED = 6
    const val STOPPED = 7
    const val PROTECT = 8
    const val PROTECTED = 9
    const val TRAFFIC = 10
    const val LEASE = "lease"
}

/** One Go runtime per process: gomobile must never share Mihomo's native runtime. */
class EngineWorkerService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val cleanupMutex = Mutex()
    private var owner: String? = null
    private var client: Messenger? = null
    private var backend: EngineBackend? = null
    private var startup: Job? = null
    private var progress: Job? = null
    private var watcher: Job? = null
    private val protection = ConcurrentHashMap<Int, CompletableFuture<Boolean>>()
    private val requestIds = AtomicInteger()
    private val messenger by lazy {
        Messenger(Handler(Looper.getMainLooper()) { message ->
            if (message.sendingUid != Process.myUid()) return@Handler true
            when (message.what) {
                EngineWorkerProtocol.PROTECTED -> if (message.data.getString(EngineWorkerProtocol.LEASE) == owner) {
                    protection.remove(message.data.getInt("request"))?.complete(message.data.getBoolean("protected"))
                }
                EngineWorkerProtocol.START -> begin(message)
                EngineWorkerProtocol.STOP -> if (message.data.getString(EngineWorkerProtocol.LEASE) == owner) {
                    scope.launch { reply(EngineWorkerProtocol.STOPPED, Bundle().apply { putBoolean("confirmed", shutdown()) }) }
                }
            }
            true
        })
    }

    override fun onBind(intent: Intent?): IBinder = messenger.binder

    private fun begin(message: Message) {
        val receivedTun = message.data.getParcelable<ParcelFileDescriptor>("tun")
        val lease = message.data.getString(EngineWorkerProtocol.LEASE) ?: run { receivedTun?.close(); return }
        if (owner != null) {
            receivedTun?.close()
            // A second owner cannot replace a live native singleton.
            message.replyTo?.let { response ->
                runCatching { response.send(Message.obtain(null, EngineWorkerProtocol.FAILED).apply {
                    data = Bundle().apply { putString(EngineWorkerProtocol.LEASE, lease) }
                }) }
            }
            return
        }
        owner = lease
        client = message.replyTo ?: run { receivedTun?.close(); return }
        reply(EngineWorkerProtocol.HELLO, Bundle().apply { putInt("pid", Process.myPid()) })
        startup = scope.launch {
            try {
                val profile = EngineProfileCodec.decode(JSONObject(message.data.getString("profile") ?: error("Missing profile")))
                check(EngineNativeAvailability.check(this@EngineWorkerService, profile) == EngineAvailability.Available)
                val local = when (profile.kind) {
                    EngineKind.PSIPHON -> PsiphonEngineBackend(applicationContext, profile, null, message.data.getBoolean("vpnMode"))
                    EngineKind.DNS -> DnsEngineBackend(applicationContext, profile)
                    EngineKind.AMNEZIAWG -> AmneziaWorkerBackend(requireNotNull(receivedTun),
                        requireNotNull(message.data.getString("userspace")), GoBackend.SocketProtector { protectSocket(it) })
                    else -> error("Unsupported worker engine")
                }
                backend = local
                progress = scope.launch {
                    local.events.collect { event ->
                        if (event is BackendEvent.Progress) reply(EngineWorkerProtocol.PROGRESS, Bundle().apply { putInt("percent", event.percent) })
                    }
                }
                local.start()
                ensureActive()
                reply(EngineWorkerProtocol.READY, Bundle().apply { local.socksEndpoint?.let { putInt("port", it.port) } })
                watcher = scope.launch {
                    while (true) {
                        delay(500)
                        if (!local.running) { reply(EngineWorkerProtocol.FAILED); break }
                        if (local is AmneziaWorkerBackend) {
                            withContext(Dispatchers.IO) { local.sample() }?.let { rate ->
                                reply(EngineWorkerProtocol.TRAFFIC, Bundle().apply {
                                    putLong("up", rate.uploadBytesPerSecond); putLong("down", rate.downloadBytesPerSecond)
                                    putLong("time", SystemClock.elapsedRealtime())
                                })
                            }
                        }
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Throwable) {
                if (backend == null) receivedTun?.close()
                // Native errors can contain credentials. Only a fixed failure crosses IPC.
                reply(EngineWorkerProtocol.FAILED)
            }
        }
    }

    private fun protectSocket(fd: Int): Boolean {
        val id = requestIds.incrementAndGet()
        val answer = CompletableFuture<Boolean>()
        protection[id] = answer
        return try {
            ParcelFileDescriptor.fromFd(fd).use { copy ->
                reply(EngineWorkerProtocol.PROTECT, Bundle().apply { putInt("request", id); putParcelable("socket", copy) })
            }
            answer.get(5, TimeUnit.SECONDS)
        } catch (_: Exception) { false } finally { protection.remove(id) }
    }

    private fun reply(what: Int, extras: Bundle = Bundle()) {
        extras.putString(EngineWorkerProtocol.LEASE, owner)
        runCatching { client?.send(Message.obtain(null, what).apply { data = extras }) }
    }

    private suspend fun shutdown(): Boolean = cleanupMutex.withLock {
        protection.values.forEach { it.complete(false) }; protection.clear()
        startup?.cancelAndJoin()
        progress?.cancelAndJoin()
        watcher?.cancelAndJoin()
        val stopped = try { withContext(NonCancellable) { backend?.stop() ?: true } } catch (_: Throwable) { false }
        if (stopped) backend = null
        stopped
    }

    override fun onDestroy() {
        scope.launch(NonCancellable) { shutdown(); scope.cancel() }
        super.onDestroy()
    }

    companion object {
        fun isWorkerProcess(context: Context): Boolean {
            val processName = if (Build.VERSION.SDK_INT >= 28) Application.getProcessName() else {
                context.getSystemService(ActivityManager::class.java).runningAppProcesses
                    ?.firstOrNull { it.pid == Process.myPid() }?.processName
            }
            return processName == context.applicationInfo.processName + ":enginecore"
        }
    }
}

internal class RemoteEngineBackend(context: Context, private val profile: EngineProfile,
    private val vpnMode: Boolean, private val vpnService: VpnService? = null,
    private val tunnel: ParcelFileDescriptor? = null, private val userspace: String? = null) : AbstractEngineBackend() {
    private val context = context.applicationContext
    private val bound = CompletableDeferred<Messenger>()
    private val ready = CompletableDeferred<Unit>()
    private val stopped = CompletableDeferred<Boolean>()
    private val processDeath = CompletableDeferred<Unit>()
    private val released = AtomicBoolean(false)
    private val stopRequested = AtomicBoolean(false)
    @Volatile private var messenger: Messenger? = null
    @Volatile private var workerPid = 0
    @Volatile private var binding = false
    @Volatile private var startSent = false
    @Volatile private var endpoint: SocksEndpoint? = null
    internal val ownedWorkerPid: Int? get() = workerPid.takeIf { it > 0 && !released.get() }
    @Volatile private var rate: EngineTrafficRate? = null
    @Volatile private var rateTime = 0L
    override val traffic get() = rate.takeIf { running && SystemClock.elapsedRealtime() - rateTime in 0..5_000 }
    override val socksEndpoint get() = endpoint
    override val running get() = state.value == BackendEvent.Ready && !released.get() && !stopRequested.get()
    private val deathRecipient = IBinder.DeathRecipient { processGone() }
    private val replies = Messenger(Handler(Looper.getMainLooper()) { message ->
        val socket = if (message.what == EngineWorkerProtocol.PROTECT) message.data.getParcelable<ParcelFileDescriptor>("socket") else null
        val valid = message.sendingUid == Process.myUid() && message.data.getString(EngineWorkerProtocol.LEASE) == lease.id && !released.get()
        if (message.what == EngineWorkerProtocol.PROTECT) {
            val accepted = socket?.use { valid && !stopRequested.get() && vpnService?.protect(it.fd) == true } ?: false
            if (valid) runCatching { messenger?.send(Message.obtain(null, EngineWorkerProtocol.PROTECTED).apply {
                data = Bundle().apply { putString(EngineWorkerProtocol.LEASE, lease.id); putInt("request", message.data.getInt("request")); putBoolean("protected", accepted) }
            }) }
            return@Handler true
        }
        if (!valid) return@Handler true
        when (message.what) {
            EngineWorkerProtocol.HELLO -> workerPid = message.data.getInt("pid").takeIf { it > 0 && it != Process.myPid() } ?: 0
            EngineWorkerProtocol.READY -> if (!stopRequested.get()) {
                val port = message.data.getInt("port")
                if (profile.kind == EngineKind.AMNEZIAWG && tunnel != null || port in 1..65535) {
                    endpoint = if (profile.kind.socks) SocksEndpoint(port) else null
                    state.value = BackendEvent.Ready; ready.complete(Unit)
                }
                else fail()
            }
            EngineWorkerProtocol.PROGRESS -> if (!stopRequested.get()) state.value = BackendEvent.Progress(message.data.getInt("percent"))
            EngineWorkerProtocol.FAILED -> if (!stopRequested.get()) fail()
            EngineWorkerProtocol.TRAFFIC -> if (!stopRequested.get() && profile.kind == EngineKind.AMNEZIAWG) {
                val time = message.data.getLong("time")
                val up = message.data.getLong("up", -1); val down = message.data.getLong("down", -1)
                if (up >= 0 && down >= 0 && time > rateTime && SystemClock.elapsedRealtime() - time in 0..5_000) {
                    rate = EngineTrafficRate(up, down); rateTime = time
                }
            }
            EngineWorkerProtocol.STOPPED -> stopped.complete(message.data.getBoolean("confirmed"))
        }
        true
    })
    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            if (released.get() || stopRequested.get()) { unbind(); bound.completeExceptionally(IOException("Engine startup cancelled")); return }
            try {
                binder.linkToDeath(deathRecipient, 0)
                val channel = Messenger(binder)
                messenger = channel
                bound.complete(channel)
            } catch (_: RemoteException) { processGone() }
        }
        override fun onServiceDisconnected(name: ComponentName) = processGone()
        override fun onBindingDied(name: ComponentName) = processGone()
        override fun onNullBinding(name: ComponentName) = processGone()
    }

    override suspend fun start() {
        withContext(Dispatchers.Main.immediate) {
            check(!stopRequested.get() && !released.get())
            binding = context.bindService(Intent(context, EngineWorkerService::class.java), connection,
                Context.BIND_AUTO_CREATE or Context.BIND_IMPORTANT)
            check(binding) { "Cannot bind engine worker" }
        }
        val channel = withTimeout(15_000) { bound.await() }
        withContext(Dispatchers.Main.immediate) {
            ensureActive()
            check(!stopRequested.get() && !released.get())
            tunnel?.dup().use { copy -> channel.send(Message.obtain(null, EngineWorkerProtocol.START).apply {
                replyTo = replies
                data = Bundle().apply {
                    putString(EngineWorkerProtocol.LEASE, lease.id)
                    putString("profile", EngineProfileCodec.encode(profile).toString())
                    putBoolean("vpnMode", vpnMode)
                    putParcelable("tun", copy)
                    putString("userspace", userspace)
                }
            }) }
            startSent = true
        }
        withTimeout(200_000) { ready.await() }
    }

    private fun fail() {
        state.value = BackendEvent.Failed("Engine connection failed")
        ready.completeExceptionally(IOException("Engine connection failed"))
    }

    private fun processGone() {
        if (!released.compareAndSet(false, true)) return
        processDeath.complete(Unit)
        bound.completeExceptionally(IOException("Engine worker exited"))
        ready.completeExceptionally(IOException("Engine worker exited"))
        stopped.complete(true)
        state.value = if (stopRequested.get()) BackendEvent.Stopped else BackendEvent.Failed("Engine worker exited")
        Handler(Looper.getMainLooper()).post { unbind() }
    }

    private fun unbind() {
        if (binding) { binding = false; runCatching { context.unbindService(connection) } }
    }

    override suspend fun stop(): Boolean {
        stopRequested.set(true)
        if (released.get()) return true
        val sent = withContext(Dispatchers.Main.immediate) {
            if (!startSent) { unbind(); released.set(true); state.value = BackendEvent.Stopped; false }
            else {
                runCatching { messenger?.send(Message.obtain(null, EngineWorkerProtocol.STOP).apply {
                    data = Bundle().apply { putString(EngineWorkerProtocol.LEASE, lease.id) }
                }) }
                true
            }
        }
        if (!sent || released.get()) return true
        // A native shutdown cannot block the app indefinitely. Only this session's private process is terminated.
        withTimeoutOrNull(15_000) { stopped.await() }
        if (!released.get()) {
            val binder = messenger?.binder
            val pid = workerPid
            if (pid <= 0 || binder?.isBinderAlive != true) return released.get()
            Process.killProcess(pid)
        }
        val confirmed = withTimeoutOrNull(5_000) { processDeath.await(); true } == true
        if (confirmed) withContext(Dispatchers.Main.immediate) { unbind() }
        return confirmed
    }
}
