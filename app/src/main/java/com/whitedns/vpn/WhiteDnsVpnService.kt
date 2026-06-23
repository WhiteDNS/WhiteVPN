package com.whitedns.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.IpPrefix
import android.net.ProxyInfo
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Base64
import androidx.core.app.NotificationCompat
import io.nekohasekai.libbox.CommandClient
import io.nekohasekai.libbox.CommandClientOptions
import io.nekohasekai.libbox.CommandServer
import io.nekohasekai.libbox.CommandServerHandler
import io.nekohasekai.libbox.ConnectionOwner
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.LocalDNSTransport
import io.nekohasekai.libbox.NetworkInterfaceIterator
import io.nekohasekai.libbox.Notification
import io.nekohasekai.libbox.OverrideOptions
import io.nekohasekai.libbox.PlatformInterface
import io.nekohasekai.libbox.StringIterator
import io.nekohasekai.libbox.SystemProxyStatus
import io.nekohasekai.libbox.TunOptions
import io.nekohasekai.libbox.WIFIState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.Proxy
import java.net.Socket
import java.net.URL
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicInteger
import io.nekohasekai.libbox.NetworkInterface as LibboxNetworkInterface

class WhiteDnsVpnService :
    VpnService(),
    PlatformInterface,
    CommandServerHandler {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var configRepository: ConfigRepository
    private lateinit var cleanIpCache: CleanIpCache
    private lateinit var scanStateStore: WhiteDnsScanStateStore
    private lateinit var subscriptionStore: SubscriptionStore
    private lateinit var locationPreferenceStore: ConnectionLocationPreferenceStore
    private lateinit var encryptedIpListRepository: EncryptedIpListRepository
    private lateinit var frontingIpPreferenceStore: FrontingIpPreferenceStore
    private lateinit var networkMonitor: DefaultNetworkMonitor
    private lateinit var splitTunnelPreferenceStore: SplitTunnelPreferenceStore
    private lateinit var installedAppRepository: InstalledAppRepository
    private val runtimeMutex = Mutex()
    private var commandServer: CommandServer? = null
    private var tunDescriptor: ParcelFileDescriptor? = null
    private var scannerJob: Job? = null
    private var profileSamplingJob: Job? = null
    private var subscriptionRefreshJob: Job? = null
    private var networkRecoveryJob: Job? = null
    private var stopJob: Job? = null
    @Volatile
    private var startupJob: Job? = null
    private val hotSwapMutex = Mutex()
    @Volatile
    private var state: VpnState = VpnState.Stopped
    @Volatile
    private var sessionStartedAtElapsedMs: Long = 0L
    @Volatile
    private var activeEndpoint: CleanIpResult? = null
    @Volatile
    private var activeDelayMs: Long = -1L
    @Volatile
    private var activeProfile: ConnectionProfile? = null
    @Volatile
    private var activeRuntimeConfig: String? = null
    @Volatile
    private var activeRuntimeMode: RuntimeCompatibilityMode = RuntimeCompatibilityMode.Compatible
    @Volatile
    private var lastNetworkRecoveryAtMs: Long = 0L
    @Volatile
    private var isNetworkRecoveryActive: Boolean = false
    @Volatile
    private var lastDefaultNetworkKey: String? = null
    @Volatile
    private var activeSplitTunnelPlan: SplitTunnelRuntimePlan = SplitTunnelRuntimePlan.off()
    private val protectedSocketLogCount = AtomicInteger(0)

    private data class StartedRuntimeSelection(
        val selection: SelectedConnectionProfile,
        val endpoint: CleanIpResult?,
    )

    private data class CachedRuntimeStart(
        val catalog: SubscriptionCatalog,
        val startedRuntime: StartedRuntimeSelection,
    )

    private data class ActiveRuntimeSnapshot(
        val config: String?,
        val profile: ConnectionProfile?,
        val endpoint: CleanIpResult?,
        val delayMs: Long,
        val sessionStartedAtElapsedMs: Long,
    )

    override fun onCreate() {
        super.onCreate()
        deleteStaleRuntimeConfig()
        configRepository = ConfigRepository(this)
        cleanIpCache = CleanIpCache(this)
        scanStateStore = WhiteDnsScanStateStore(this)
        subscriptionStore = SubscriptionStore(this)
        locationPreferenceStore = ConnectionLocationPreferenceStore(this)
        encryptedIpListRepository = EncryptedIpListRepository(this)
        frontingIpPreferenceStore = FrontingIpPreferenceStore(this)
        networkMonitor = DefaultNetworkMonitor(this)
        splitTunnelPreferenceStore = SplitTunnelPreferenceStore(this)
        installedAppRepository = InstalledAppRepository(this)
        networkMonitor.setDefaultNetworkChangeListener { candidate ->
            scheduleNetworkChangeRecovery(candidate)
        }
        createNotificationChannel()
        DiagnosticLogger.info(this, "service.onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        DiagnosticLogger.info(
            this,
            "service.onStartCommand",
            "action=${intent?.action} startId=$startId flags=$flags state=${state.wireName}",
        )
        when (intent?.action) {
            Actions.CONNECT -> startVpn()
            Actions.DISCONNECT -> stopVpn()
            Actions.RECONNECT -> reconnectVpn()
            Actions.REFRESH -> refreshVpn()
        }
        return Service.START_NOT_STICKY
    }

    override fun onBind(intent: Intent): IBinder? {
        return super.onBind(intent)
    }

    override fun onRevoke() {
        DiagnosticLogger.warn(this, "service.onRevoke", "vpn permission revoked")
        stopVpn()
        super.onRevoke()
    }

    override fun onDestroy() {
        DiagnosticLogger.info(this, "service.onDestroy", "state=${state.wireName}")
        startupJob?.cancel(CancellationException("Service destroyed"))
        startupJob = null
        scannerJob?.cancel()
        profileSamplingJob?.cancel()
        subscriptionRefreshJob?.cancel()
        networkRecoveryJob?.cancel()
        runCatching { closeRuntime() }
        scope.cancel()
        super.onDestroy()
    }

    private fun startVpn() {
        if (state == VpnState.Starting || state == VpnState.Started) {
            DiagnosticLogger.info(this, "connect.ignored", "state=${state.wireName}")
            return
        }
        DiagnosticLogger.clear(this)
        protectedSocketLogCount.set(0)
        DiagnosticLogger.info(this, "connect.start")
        publishState(VpnState.Starting)
        startForeground(NOTIFICATION_ID, serviceNotification(getString(R.string.notification_starting)))
        DiagnosticLogger.info(this, "foreground.start", "notification=starting")

        launchConnectionStartup("connect")
    }

    private fun reconnectVpn() {
        if (state == VpnState.Starting || state == VpnState.Stopping) {
            DiagnosticLogger.info(this, "reconnect.ignored", "state=${state.wireName}")
            return
        }
        if (state == VpnState.Stopped || state is VpnState.Error) {
            DiagnosticLogger.info(this, "reconnect.asConnect", "state=${state.wireName}")
            startVpn()
            return
        }

        DiagnosticLogger.clear(this)
        protectedSocketLogCount.set(0)
        DiagnosticLogger.info(this, "reconnect.start")
        scannerJob?.cancel()
        scannerJob = null
        profileSamplingJob?.cancel()
        profileSamplingJob = null
        subscriptionRefreshJob?.cancel()
        subscriptionRefreshJob = null
        networkRecoveryJob?.cancel()
        networkRecoveryJob = null
        publishState(VpnState.Starting)
        startForeground(NOTIFICATION_ID, serviceNotification(getString(R.string.notification_starting)))

        launchConnectionStartup("reconnect") {
            closeRuntimeWithDeadline("reconnect")
        }
    }

    private fun refreshVpn() {
        if (state != VpnState.Started) {
            DiagnosticLogger.info(this, "refresh.ignored", "state=${state.wireName}")
            return
        }

        val excludedEndpoint = activeEndpoint
        DiagnosticLogger.clear(this)
        protectedSocketLogCount.set(0)
        DiagnosticLogger.info(
            this,
            "refresh.start",
            "excludedEndpoint=${excludedEndpoint?.let { "${it.ip}:${it.port}" }.orEmpty()}",
        )
        scannerJob?.cancel()
        scannerJob = null
        profileSamplingJob?.cancel()
        profileSamplingJob = null
        subscriptionRefreshJob?.cancel()
        subscriptionRefreshJob = null
        networkRecoveryJob?.cancel()
        networkRecoveryJob = null
        publishState(VpnState.Starting)
        startForeground(NOTIFICATION_ID, serviceNotification(getString(R.string.notification_starting)))

        launchConnectionStartup(
            eventPrefix = "refresh",
            bypassConnectionCache = true,
            excludedEndpoint = excludedEndpoint,
        ) {
            closeRuntimeWithDeadline("refresh")
        }
    }

    private fun launchConnectionStartup(
        eventPrefix: String,
        bypassConnectionCache: Boolean = false,
        excludedEndpoint: CleanIpResult? = null,
        beforeStartup: suspend () -> Unit = {},
    ) {
        startupJob?.cancel(CancellationException("Startup superseded"))
        val job = scope.launch {
            beforeStartup()
            runConnectionStartup(eventPrefix, bypassConnectionCache, excludedEndpoint)
        }
        startupJob = job
        job.invokeOnCompletion {
            if (startupJob === job) {
                startupJob = null
            }
        }
    }

    private suspend fun runConnectionStartup(
        eventPrefix: String,
        bypassConnectionCache: Boolean = false,
        excludedEndpoint: CleanIpResult? = null,
    ) {
        try {
            ensureConnectionStartupActive(eventPrefix)
            ensureUnderlyingNetworkAvailable()
            ensureConnectionStartupActive(eventPrefix)
            if (bypassConnectionCache) {
                DiagnosticLogger.info(this, "startup.cache.skipped", "source=$eventPrefix reason=bypassConnectionCache")
            } else {
                startCachedRuntimeIfAvailable(eventPrefix)?.let { cached ->
                    ensureConnectionStartupActive(eventPrefix)
                    applyStartedRuntime(cached.catalog, cached.startedRuntime, eventPrefix)
                    return
                }
            }
            ensureConnectionStartupActive(eventPrefix)
            val catalog = configRepository.fetchOrCachedCatalog()
            ensureConnectionStartupActive(eventPrefix)
            val profiles = profilesForSelectedLocation(eligibleProfilesForRuntime(catalog))
            ensureConnectionStartupActive(eventPrefix)
            val startedRuntime = startFirstEncryptedTopIpRuntime(
                catalog = catalog,
                profiles = profiles,
                bypassConnectionCache = bypassConnectionCache,
                excludedEndpoint = excludedEndpoint,
            )
            ensureConnectionStartupActive(eventPrefix)
            applyStartedRuntime(catalog, startedRuntime, eventPrefix)
        } catch (error: CancellationException) {
            DiagnosticLogger.info(
                this@WhiteDnsVpnService,
                "$eventPrefix.canceled",
                "state=${state.wireName} message=${error.message.orEmpty()}",
            )
        } catch (error: Throwable) {
            DiagnosticLogger.error(this@WhiteDnsVpnService, "$eventPrefix.failed", error = error)
            stopAfterFailure(error)
        }
    }

    private fun ensureConnectionStartupActive(eventPrefix: String) {
        if (state == VpnState.Starting) return
        throw CancellationException("$eventPrefix canceled while state=${state.wireName}")
    }

    private fun eligibleProfilesForRuntime(catalog: SubscriptionCatalog): List<ConnectionProfile> {
        val profiles = catalog.profiles
        DiagnosticLogger.info(
            this,
            "config.profiles",
            "count=${profiles.size} types=${profiles.groupingBy { it.type }.eachCount()}",
        )
        if (profiles.isEmpty()) {
            throw IOException("Subscription has no supported VLESS or Trojan profiles")
        }

        val defaultNetworkHasIpv6 = networkMonitor.hasUsableIpv6DefaultNetwork()
        val runtimeProfiles = runtimeEligibleProfiles(profiles, defaultNetworkHasIpv6)
        val skippedProfiles = profiles.size - runtimeProfiles.size
        if (skippedProfiles > 0) {
            DiagnosticLogger.info(
                this,
                "config.profiles.filtered",
                "skippedIpv6Literal=$skippedProfiles runtimeProfiles=${runtimeProfiles.size} defaultNetworkHasIpv6=$defaultNetworkHasIpv6",
            )
        }
        return runtimeProfiles
    }

    private fun applyStartedRuntime(
        catalog: SubscriptionCatalog,
        startedRuntime: StartedRuntimeSelection,
        eventPrefix: String,
        resetSessionTimer: Boolean = true,
    ) {
        val selection = startedRuntime.selection
        val selectedProfile = selection.profile
        val endpoint = startedRuntime.endpoint
        scanStateStore.saveLastSelectedProfile(selection)
        DiagnosticLogger.info(
            this,
            "profile.selected",
            "tag=${selectedProfile.tag} type=${selectedProfile.type} server=${selectedProfile.server}:${selectedProfile.port} serverKind=${selectedProfile.server.addressKind()} delayMs=${selection.delayMs}",
        )
        activeProfile = selectedProfile
        activeEndpoint = endpoint
        activeDelayMs = endpoint?.latencyMs ?: selection.delayMs.takeUnless { it == Int.MAX_VALUE }?.toLong() ?: -1L
        if (resetSessionTimer || sessionStartedAtElapsedMs <= 0L) {
            sessionStartedAtElapsedMs = SystemClock.elapsedRealtime()
        }
        publishState(VpnState.Started)
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, serviceNotification(getString(R.string.notification_connected)))
        DiagnosticLogger.info(
            this,
            "$eventPrefix.started",
            "profile=${selectedProfile.tag} endpoint=${endpoint?.let { "${it.ip}:${it.port}" } ?: "${selectedProfile.server}:${selectedProfile.port}"}",
        )
        profileSamplingJob?.cancel()
        profileSamplingJob = null
        DiagnosticLogger.info(this, "profile.background.disabled", "reason=encrypted-top-ip-mode profiles=${catalog.profiles.size}")
        startBackgroundSubscriptionRefresh()
        scannerJob?.cancel()
        scannerJob = null
        DiagnosticLogger.info(this, "scanner.background.disabled", "reason=connected")
    }

    private fun ensureUnderlyingNetworkAvailable() {
        if (networkMonitor.hasUsableDefaultNetwork()) return
        DiagnosticLogger.warn(this, "network.default.unavailable", "connect blocked before subscription/runtime checks")
        throw IOException("No internet connection. Connect to Wi-Fi or cellular and try again.")
    }

    private fun stopVpn() {
        if (state == VpnState.Stopped) {
            DiagnosticLogger.info(this, "disconnect.ignored", "state=${state.wireName}")
            sessionStartedAtElapsedMs = 0L
            publishState(VpnState.Stopped)
            stopSelf()
            return
        }
        if (state == VpnState.Stopping) {
            DiagnosticLogger.info(this, "disconnect.ignored", "state=${state.wireName}")
            if (stopJob?.isActive != true) {
                stopJob = scope.launch {
                    finishStoppedState("disconnect.recovered")
                }
            }
            return
        }
        DiagnosticLogger.info(this, "disconnect.start", "state=${state.wireName}")
        val wasStarting = state == VpnState.Starting
        startupJob?.cancel(CancellationException("Disconnect requested"))
        startupJob = null
        if (wasStarting) {
            DiagnosticLogger.info(this, "connect.cancelRequested", "source=disconnect")
        }
        scannerJob?.cancel()
        scannerJob = null
        profileSamplingJob?.cancel()
        profileSamplingJob = null
        subscriptionRefreshJob?.cancel()
        subscriptionRefreshJob = null
        networkRecoveryJob?.cancel()
        networkRecoveryJob = null
        isNetworkRecoveryActive = false
        publishState(VpnState.Stopping)
        stopJob?.cancel()
        stopJob = scope.launch {
            closeRuntimeWithDeadline("disconnect")
            finishStoppedState("disconnect.stopped")
        }
    }

    private suspend fun stopAfterFailure(error: Throwable) {
        DiagnosticLogger.info(this, "connect.cleanupAfterFailure", "message=${error.message.orEmpty()}")
        scannerJob?.cancel()
        scannerJob = null
        profileSamplingJob?.cancel()
        profileSamplingJob = null
        subscriptionRefreshJob?.cancel()
        subscriptionRefreshJob = null
        if (!isNetworkRecoveryActive) {
            networkRecoveryJob?.cancel()
        }
        networkRecoveryJob = null
        isNetworkRecoveryActive = false
        closeRuntimeWithDeadline("connect.cleanupAfterFailure")
        sessionStartedAtElapsedMs = 0L
        stopForegroundCompat()
        publishState(VpnState.Error(error.message ?: "Unable to start VPN"))
        DiagnosticLogger.info(this, "connect.errorPublished", "message=${error.message.orEmpty()}")
        stopSelf()
    }

    private suspend fun closeRuntimeWithDeadline(eventPrefix: String) {
        var closeFailure: Throwable? = null
        val closeJob = scope.launch(Dispatchers.IO) {
            runCatching { closeRuntime() }
                .onFailure { closeFailure = it }
        }
        val closed = withTimeoutOrNull(DISCONNECT_CLOSE_TIMEOUT_MS) {
            closeJob.join()
            true
        } == true
        if (!closed) {
            DiagnosticLogger.warn(
                this,
                "$eventPrefix.close.timeout",
                "timeoutMs=$DISCONNECT_CLOSE_TIMEOUT_MS; continuing stopped-state finalization",
            )
            closeJob.cancel()
        }
        closeFailure?.let {
            DiagnosticLogger.warn(this, "$eventPrefix.close.failed", error = it)
        }
    }

    private fun finishStoppedState(event: String) {
        sessionStartedAtElapsedMs = 0L
        stopForegroundCompat()
        publishState(VpnState.Stopped)
        DiagnosticLogger.info(this, event)
        stopSelf()
    }

    private fun closeRuntime() {
        val server = commandServer
        val tun = tunDescriptor
        commandServer = null
        tunDescriptor = null
        profileSamplingJob?.cancel()
        profileSamplingJob = null
        subscriptionRefreshJob?.cancel()
        subscriptionRefreshJob = null
        activeEndpoint = null
        activeDelayMs = -1L
        activeProfile = null
        activeRuntimeConfig = null
        activeRuntimeMode = RuntimeCompatibilityMode.Compatible
        sessionStartedAtElapsedMs = 0L
        lastNetworkRecoveryAtMs = 0L
        isNetworkRecoveryActive = false
        lastDefaultNetworkKey = null
        protectedSocketLogCount.set(0)
        DiagnosticLogger.info(this, "runtime.close", "hasCommandServer=${server != null} hasTun=${tun != null}")
        runCatching { server?.closeService() }
            .onFailure { DiagnosticLogger.warn(this, "runtime.closeService.failed", error = it) }
        runCatching { server?.close() }
            .onFailure { DiagnosticLogger.warn(this, "runtime.close.failed", error = it) }
        runCatching { tun?.close() }
            .onFailure { DiagnosticLogger.warn(this, "runtime.tunClose.failed", error = it) }
        runCatching { networkMonitor.stop() }
            .onFailure { DiagnosticLogger.warn(this, "networkMonitor.stop.failed", error = it) }
    }

    private fun cleanIpScanner(
        ports: List<Int>,
        candidateIps: List<String>,
    ): CleanIpScanner {
        val selectedPorts = ports.distinct().sorted()
        val distinctIps = candidateIps.distinct()
        logScanInfo(
            "scanner.create",
            "mode=tcping speedTestHost=${CleanIpDefaults.SPEED_TEST_HOST} ports=$selectedPorts candidateIps=${candidateIps.size}",
        )
        return CleanIpScanner(
            socketProtector = SocketProtector { socket -> protect(socket) },
            speedTestHost = CleanIpDefaults.SPEED_TEST_HOST,
            candidateProvider = {
                distinctIps.flatMap { ip ->
                    selectedPorts.map { port -> CleanIpCandidate(ip, port) }
                }.distinctBy { "${it.ip}:${it.port}" }
            },
            logger = { event, message -> logScanInfo(event, message) },
        )
    }

    private fun logScanInfo(event: String, message: String = "") {
        if (SCAN_DIAGNOSTICS_ENABLED) {
            DiagnosticLogger.info(this, event, message)
        }
    }

    private fun logScanWarn(event: String, message: String = "", error: Throwable? = null) {
        if (SCAN_DIAGNOSTICS_ENABLED) {
            DiagnosticLogger.warn(this, event, message, error)
        }
    }

    private fun scheduleNetworkChangeRecovery(candidate: DefaultNetworkCandidate?) {
        val networkKey = candidate.networkRecoveryKey()
        if (networkKey == lastDefaultNetworkKey) return
        lastDefaultNetworkKey = networkKey
        DiagnosticLogger.info(this, "network.recovery.changed", "network=$networkKey state=${state.wireName}")
        if (state != VpnState.Started) return

        networkRecoveryJob?.cancel()
        networkRecoveryJob = scope.launch {
            delay(NetworkChangeRecoveryPolicy.DEFAULT_DEBOUNCE_MS)
            val nowMs = SystemClock.elapsedRealtime()
            if (!NetworkChangeRecoveryPolicy.shouldRecover(state, nowMs, lastNetworkRecoveryAtMs, isNetworkRecoveryActive)) {
                DiagnosticLogger.info(
                    this@WhiteDnsVpnService,
                    "network.recovery.skipped",
                    "state=${state.wireName} active=$isNetworkRecoveryActive lastRecoveryAtMs=$lastNetworkRecoveryAtMs",
                )
                return@launch
            }
            isNetworkRecoveryActive = true
            lastNetworkRecoveryAtMs = nowMs
            val runtimeSnapshot = activeRuntimeSnapshot()
            try {
                recoverAfterNetworkChange(networkKey)
            } catch (error: CancellationException) {
                DiagnosticLogger.info(
                    this@WhiteDnsVpnService,
                    "network.recovery.canceled",
                    "network=$networkKey state=${state.wireName} message=${error.message.orEmpty()}",
                )
                throw error
            } catch (error: Throwable) {
                DiagnosticLogger.error(this@WhiteDnsVpnService, "network.recovery.failed", "network=$networkKey", error)
                when (NetworkChangeRecoveryPolicy.failureActionFor(state)) {
                    NetworkRecoveryFailureAction.PreserveActiveVpn -> {
                        preserveActiveVpnAfterNetworkRecoveryFailure(networkKey, runtimeSnapshot, error)
                    }
                    NetworkRecoveryFailureAction.IgnoreStaleFailure -> {
                        DiagnosticLogger.info(
                            this@WhiteDnsVpnService,
                            "network.recovery.failure.ignored",
                            "network=$networkKey state=${state.wireName}",
                        )
                    }
                }
            } finally {
                isNetworkRecoveryActive = false
            }
        }
    }

    private fun activeRuntimeSnapshot(): ActiveRuntimeSnapshot {
        return ActiveRuntimeSnapshot(
            config = activeRuntimeConfig,
            profile = activeProfile,
            endpoint = activeEndpoint,
            delayMs = activeDelayMs,
            sessionStartedAtElapsedMs = sessionStartedAtElapsedMs,
        )
    }

    private fun restoreActiveRuntimeSnapshot(snapshot: ActiveRuntimeSnapshot) {
        activeRuntimeConfig = snapshot.config
        activeProfile = snapshot.profile
        activeEndpoint = snapshot.endpoint
        activeDelayMs = snapshot.delayMs
        sessionStartedAtElapsedMs = snapshot.sessionStartedAtElapsedMs
    }

    private suspend fun preserveActiveVpnAfterNetworkRecoveryFailure(
        networkKey: String,
        snapshot: ActiveRuntimeSnapshot,
        cause: Throwable,
    ) {
        restoreActiveRuntimeSnapshot(snapshot)
        val config = snapshot.config
        if (config.isNullOrBlank()) {
            DiagnosticLogger.warn(
                this,
                "network.recovery.preserve.skipped",
                "network=$networkKey reason=noActiveRuntimeConfig state=${state.wireName}",
                cause,
            )
            return
        }

        try {
            startOrReloadRuntime(config)
        } catch (restoreError: CancellationException) {
            throw restoreError
        } catch (restoreError: Throwable) {
            restoreActiveRuntimeSnapshot(snapshot)
            restoreError.addSuppressed(cause)
            DiagnosticLogger.warn(
                this,
                "network.recovery.restore.failed",
                "network=$networkKey; keeping service active",
                restoreError,
            )
            return
        }

        restoreActiveRuntimeSnapshot(snapshot)
        if (state == VpnState.Started) {
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, serviceNotification(getString(R.string.notification_connected)))
            publishState(VpnState.Started)
        }
        DiagnosticLogger.info(
            this,
            "network.recovery.preserved",
            "network=$networkKey restoredActiveConfig=true",
        )
    }

    private suspend fun recoverAfterNetworkChange(networkKey: String) {
        val profile = activeProfile
        if (profile == null) {
            DiagnosticLogger.info(this, "network.recovery.noProfile", "network=$networkKey")
            return
        }

        DiagnosticLogger.info(this, "network.recovery.health.start", "network=$networkKey profile=${profile.tag}")
        val currentHealthy = runCatching {
            verifyRuntimeHealth(profile)
        }.onFailure { error ->
            DiagnosticLogger.warn(this, "network.recovery.health.failed", "network=$networkKey", error)
        }.isSuccess

        if (currentHealthy) {
            DiagnosticLogger.info(this, "network.recovery.health.ok", "network=$networkKey profile=${profile.tag}")
            return
        }

        hotSwapMutex.withLock {
            if (state != VpnState.Started) {
                DiagnosticLogger.info(this, "network.recovery.aborted", "state=${state.wireName}")
                return@withLock
            }

            scannerJob?.cancel()
            scannerJob = null
            profileSamplingJob?.cancel()
            profileSamplingJob = null
            subscriptionRefreshJob?.cancel()
            subscriptionRefreshJob = null
            val catalog = configRepository.fetchOrCachedCatalog()
            val profiles = profilesForSelectedLocation(eligibleProfilesForRuntime(catalog))
            val startedRuntime = startFirstEncryptedTopIpRuntime(
                catalog,
                profiles,
                cleanupRuntimeOnFailedAttempt = false,
            )
            applyStartedRuntime(catalog, startedRuntime, "network.recovery", resetSessionTimer = false)
        }
    }

    private fun DefaultNetworkCandidate?.networkRecoveryKey(): String {
        if (this == null) return "none"
        return listOf(
            name,
            index.toString(),
            isValidated.toString(),
            isWifi.toString(),
            isEthernet.toString(),
            isCellular.toString(),
            isExpensive.toString(),
            isConstrained.toString(),
            hasIpv6.toString(),
        ).joinToString("|")
    }

    private fun runtimeEligibleProfiles(
        profiles: List<ConnectionProfile>,
        defaultNetworkHasIpv6: Boolean,
    ): List<ConnectionProfile> {
        if (defaultNetworkHasIpv6) return profiles
        val ipv4OrDomainProfiles = profiles.filterNot { it.isIpv6Literal }
        return ipv4OrDomainProfiles.ifEmpty { profiles }
    }

    private fun profilesForSelectedLocation(profiles: List<ConnectionProfile>): List<ConnectionProfile> {
        val selectedCountryCode = locationPreferenceStore.readSelectedCountryCode()
        val result = ConnectionLocationPolicy.filterProfiles(profiles, selectedCountryCode)
        if (result.resetToAuto) {
            locationPreferenceStore.clearSelectedCountry()
            DiagnosticLogger.warn(
                this,
                "location.selection.reset",
                "missingCountry=${selectedCountryCode.orEmpty()} fallback=Auto profiles=${profiles.size}",
            )
        }
        DiagnosticLogger.info(
            this,
            "location.selection",
            "code=${result.selectedCountryCode ?: "auto"} label=${result.selectedLabel} profiles=${result.profiles.size}/${profiles.size}",
        )
        return result.profiles
    }

    private suspend fun startCachedRuntimeIfAvailable(eventPrefix: String): CachedRuntimeStart? {
        return try {
            val frontingIps = frontingIpPreferenceStore.readFrontingIps()
            if (frontingIps.isNotEmpty()) {
                DiagnosticLogger.info(
                    this,
                    "startup.cache.skipped",
                    "source=$eventPrefix reason=frontingIpOverride count=${frontingIps.size}",
                )
                return null
            }

            val catalog = withContext(Dispatchers.IO) { subscriptionStore.readCatalog() }
            if (catalog == null) {
                DiagnosticLogger.info(this, "startup.cache.miss", "source=$eventPrefix reason=noCachedCatalog")
                return null
            }

            val profiles = profilesForSelectedLocation(eligibleProfilesForRuntime(catalog))
            val selection = scanStateStore.readLastSelectedProfileSelection(profiles)
            if (selection == null) {
                DiagnosticLogger.info(this, "startup.cache.miss", "source=$eventPrefix reason=noLastProfile")
                return null
            }

            val candidates = StartupScanPolicy.cachedRuntimeCandidates(
                selection = selection,
                lastEndpoint = scanStateStore.readLastEndpoint(),
                cachedResults = cleanIpCache.readResults(),
                frontingIpOverrideEnabled = false,
            )
            if (candidates.isEmpty()) {
                DiagnosticLogger.info(
                    this,
                    "startup.cache.miss",
                    "source=$eventPrefix reason=noEndpoint profile=${selection.profile.tag} port=${selection.profile.port}",
                )
                return null
            }

            var lastFailure: Throwable? = null
            for ((index, endpoint) in candidates.withIndex()) {
                try {
                    DiagnosticLogger.info(
                        this,
                        "startup.cache.try",
                        "source=$eventPrefix attempt=${index + 1}/${candidates.size} profile=${selection.profile.tag} endpoint=${endpoint.ip}:${endpoint.port}",
                    )
                    val startedRuntime = startFirstTopIpRuntime(
                        selections = listOf(selection),
                        topEndpoint = endpoint,
                        maxRuntimeAttempts = 1,
                    )
                    DiagnosticLogger.info(
                        this,
                        "startup.cache.connected",
                        "source=$eventPrefix profile=${selection.profile.tag} endpoint=${endpoint.ip}:${endpoint.port}",
                    )
                    return CachedRuntimeStart(catalog, startedRuntime)
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    lastFailure = error
                    DiagnosticLogger.warn(
                        this,
                        "startup.cache.rejected",
                        "source=$eventPrefix attempt=${index + 1}/${candidates.size} profile=${selection.profile.tag} endpoint=${endpoint.ip}:${endpoint.port}",
                        error,
                    )
                }
            }

            DiagnosticLogger.warn(
                this,
                "startup.cache.fallback",
                "source=$eventPrefix candidates=${candidates.size}",
                lastFailure,
            )
            null
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            DiagnosticLogger.warn(this, "startup.cache.failed", "source=$eventPrefix", error)
            null
        }
    }

    private suspend fun startFirstEncryptedTopIpRuntime(
        catalog: SubscriptionCatalog,
        profiles: List<ConnectionProfile>,
        cleanupRuntimeOnFailedAttempt: Boolean = true,
        bypassConnectionCache: Boolean = false,
        excludedEndpoint: CleanIpResult? = null,
    ): StartedRuntimeSelection {
        val candidates = findStartupTopIpCandidates(
            profiles = profiles,
            bypassConnectionCache = bypassConnectionCache,
            excludedEndpoint = excludedEndpoint,
        )
        val testProfiles = ConnectionProfileSelectionPolicy.shuffledForConnectionTest(profiles)
        DiagnosticLogger.info(
            this,
            "profile.connectionTest.shuffled",
            "profiles=${testProfiles.size} first=${testProfiles.take(5).joinToString { it.tag }}",
        )
        return StartupTopIpConnector.connectFirst(
            candidates = candidates,
            probeProfiles = { endpoint ->
                DiagnosticLogger.info(
                    this,
                    "profile.topIp.probe.start",
                    "profiles=${testProfiles.size} topIp=${endpoint.ip} scanPort=${endpoint.port} timeoutMs=${ProfileDelayDefaults.PROFILE_TEST_TIMEOUT_MS}",
                )
                runTopIpProfileProbe(
                    profiles = testProfiles,
                    serverOverrideIp = endpoint.ip,
                ).also { selections ->
                    if (selections.isEmpty()) {
                        if (cleanupRuntimeOnFailedAttempt) {
                            withContext(Dispatchers.IO) {
                                closeRuntime()
                            }
                        }
                    } else {
                        subscriptionStore.mergeTopDelaySelections(catalog.profiles, selections)
                        DiagnosticLogger.info(
                            this,
                            "profile.topIp.probe.success",
                            selections.joinToString(prefix = "measured=${selections.size} top=", limit = 5) {
                                "${it.profile.tag}:${it.delayMs}ms"
                            },
                        )
                    }
                }
            },
            connectRuntime = { endpoint, selections ->
                startFirstTopIpRuntime(
                    selections = selections,
                    topEndpoint = endpoint,
                    cleanupRuntimeOnFailedAttempt = cleanupRuntimeOnFailedAttempt,
                )
            },
            onAttempt = { attempt, total, endpoint ->
                DiagnosticLogger.info(
                    this,
                    "startup.topIp.try",
                    "attempt=$attempt/$total endpoint=${endpoint.ip}:${endpoint.port} latencyMs=${endpoint.latencyMs} lossRate=${endpoint.lossRate}",
                )
            },
            onRejected = { attempt, total, endpoint, reason, error ->
                DiagnosticLogger.warn(
                    this,
                    "startup.topIp.rejected",
                    "attempt=$attempt/$total endpoint=${endpoint.ip}:${endpoint.port} reason=$reason",
                    error,
                )
            },
            onConnected = { attempt, total, endpoint ->
                DiagnosticLogger.info(
                    this,
                    "startup.topIp.connected",
                    "attempt=$attempt/$total endpoint=${endpoint.ip}:${endpoint.port}",
                )
            },
        )
    }

    private suspend fun findStartupTopIpCandidates(
        profiles: List<ConnectionProfile>,
        bypassConnectionCache: Boolean = false,
        excludedEndpoint: CleanIpResult? = null,
    ): List<CleanIpResult> {
        val subscriptionPorts = profiles
            .map { it.port }
            .filter { it > 0 }
        if (subscriptionPorts.isEmpty()) throw IOException("Subscription has no usable profile ports")

        val priorityPorts = StartupScanPolicy.priorityPorts(subscriptionPorts)
        val fallbackPorts = StartupScanPolicy.fallbackPorts(subscriptionPorts)
        logScanInfo(
            "scanner.startup.ports",
            "priority=$priorityPorts fallback=$fallbackPorts subscription=${subscriptionPorts.distinct().sorted()}",
        )

        frontingIpPreferenceStore.readFrontingIps().takeIf { it.isNotEmpty() }?.let { frontingIps ->
            val port = priorityPorts.firstOrNull() ?: fallbackPorts.first()
            DiagnosticLogger.info(this, "frontingIp.override", "enabled=true count=${frontingIps.size} port=$port")
            val checkedAt = System.currentTimeMillis()
            // ponytail: user IPs are authoritative; profile URL-test supplies the real delay.
            return StartupScanPolicy.excludeEndpoint(
                candidates = frontingIps.map { frontingIp -> CleanIpResult(frontingIp, port, 1L, 0.0, checkedAt) },
                excludedEndpoint = excludedEndpoint,
            ).ifEmpty {
                throw IOException("No alternate fronting IP is available")
            }
        }

        val decryptedIps = runCatching {
            encryptedIpListRepository.fetchIps()
        }.onFailure { error ->
            DiagnosticLogger.warn(this, "encryptedIpList.fetchOrDecrypt.failed", error = error)
        }.getOrDefault(emptyList())

        val freshCandidates = mutableListOf<CleanIpResult>()
        if (decryptedIps.isNotEmpty()) {
            freshCandidates += scanQuickEncryptedIps(
                phase = "priority",
                candidateIps = decryptedIps,
                ports = priorityPorts,
                maxScanDurationMs = CleanIpDefaults.STARTUP_QUICK_SCAN_MS,
            )
            if (freshCandidates.isEmpty()) {
                freshCandidates += scanQuickEncryptedIps(
                    phase = "fallback",
                    candidateIps = decryptedIps,
                    ports = fallbackPorts,
                    maxScanDurationMs = CleanIpDefaults.STARTUP_FALLBACK_SCAN_MS,
                )
            }
            val rankedFresh = freshCandidates
                .distinctBy { result ->
                    if (excludedEndpoint == null) result.ip else "${result.ip}:${result.port}"
                }
                .sortedForConnection()
            val connectableFresh = StartupScanPolicy.excludeEndpoint(rankedFresh, excludedEndpoint)
            if (connectableFresh.isNotEmpty()) {
                if (!bypassConnectionCache) {
                    connectableFresh.firstOrNull()?.let { best ->
                        cleanIpCache.saveResult(best)
                        scanStateStore.saveLastEndpoint(best)
                    }
                }
                logScanInfo(
                    "scanner.encryptedTop.quick",
                    "candidates=${connectableFresh.size} best=${connectableFresh.first().ip}:${connectableFresh.first().port} latencyMs=${connectableFresh.first().latencyMs} lossRate=${connectableFresh.first().lossRate}",
                )
                return connectableFresh
            }
            logScanWarn(
                "scanner.encryptedTop.quick.empty",
                "ips=${decryptedIps.size} priority=$priorityPorts fallback=$fallbackPorts",
            )
        }

        if (bypassConnectionCache) {
            throw IOException("No fresh encrypted-list IP is available")
        }

        val cachedCandidates = (listOfNotNull(scanStateStore.readLastEndpoint()) + cleanIpCache.readResults())
            .filter { it.port in fallbackPorts }
            .distinctBy { "${it.ip}:${it.port}" }
            .sortedForConnection()
        val cachedScanner = cleanIpScanner(
            ports = fallbackPorts,
            candidateIps = emptyList(),
        )
        val cached = cachedScanner.findFirstCachedWorking(cachedCandidates)
        if (cached != null) {
            cleanIpCache.saveResult(cached)
            scanStateStore.saveLastEndpoint(cached)
            logScanInfo(
                "scanner.encryptedTop.cached",
                "endpoint=${cached.ip}:${cached.port} latencyMs=${cached.latencyMs} lossRate=${cached.lossRate} speedBps=${cached.downloadBytesPerSecond}",
            )
            return listOf(cached)
        }

        throw IOException("No working encrypted-list IP is available")
    }

    private suspend fun scanQuickEncryptedIps(
        phase: String,
        candidateIps: List<String>,
        ports: List<Int>,
        maxScanDurationMs: Long,
    ): List<CleanIpResult> {
        if (ports.isEmpty()) return emptyList()
        logScanInfo(
            "scanner.encryptedTop.quick.start",
            "phase=$phase ips=${candidateIps.size} ports=$ports timeoutMs=$maxScanDurationMs target=${CleanIpDefaults.STARTUP_QUICK_TARGET_RESULTS}",
        )
        val startedAtMs = SystemClock.elapsedRealtime()
        val results = cleanIpScanner(
            ports = ports,
            candidateIps = candidateIps,
        ).findQuickCandidates(
            maxDurationMs = maxScanDurationMs,
            targetResults = CleanIpDefaults.STARTUP_QUICK_TARGET_RESULTS,
        )
        logScanInfo(
            "scanner.encryptedTop.quick.done",
            "phase=$phase results=${results.size} elapsedMs=${SystemClock.elapsedRealtime() - startedAtMs}",
        )
        return results
    }

    private suspend fun runTopIpProfileProbe(
        profiles: List<ConnectionProfile>,
        serverOverrideIp: String,
    ): List<SelectedConnectionProfile> {
        if (profiles.isEmpty()) return emptyList()
        val probeConfig = SubscriptionSingBoxConfigBuilder.profileProbeConfig(
            profiles = profiles,
            serverOverrideIp = serverOverrideIp,
        )
        return runCatching {
            startOrReloadRuntime(probeConfig.config)
            runUrlTestGroupRanked(
                groupTag = probeConfig.groupTag,
                profiles = profiles,
                timeoutMs = ProfileDelayDefaults.PROFILE_TEST_TIMEOUT_MS,
                quietMs = 0L,
                eventPrefix = "profile.topIp",
                updateEvent = "profile.topIp.urlTest.update",
            )
        }.onFailure { error ->
            DiagnosticLogger.warn(this, "profile.topIp.probe.failed", "profiles=${profiles.size} topIp=$serverOverrideIp", error)
        }.getOrNull().orEmpty()
    }

    private suspend fun startFirstTopIpRuntime(
        selections: List<SelectedConnectionProfile>,
        topEndpoint: CleanIpResult,
        maxRuntimeAttempts: Int = CleanIpDefaults.STARTUP_RUNTIME_ATTEMPTS,
        cleanupRuntimeOnFailedAttempt: Boolean = true,
    ): StartedRuntimeSelection {
        var lastFailure: Throwable? = null
        for ((index, selection) in selections.take(maxRuntimeAttempts).withIndex()) {
            val runtimeEndpoint = runtimeEndpointForSelection(topEndpoint, selection)
            val endpointLabel = "${runtimeEndpoint.ip}:${runtimeEndpoint.port}"
            try {
                DiagnosticLogger.info(
                    this,
                    "runtime.topIp.candidate.start",
                    "attempt=${index + 1}/$maxRuntimeAttempts profile=${selection.profile.tag} endpoint=$endpointLabel delayMs=${selection.delayMs}",
                )
                startRuntimeCandidate(
                    profile = selection.profile,
                    endpoint = runtimeEndpoint,
                    serverOverrideIp = topEndpoint.ip,
                )
                if (topEndpoint.ip !in frontingIpPreferenceStore.readFrontingIps()) {
                    cleanIpCache.saveResult(runtimeEndpoint)
                    scanStateStore.saveLastEndpoint(runtimeEndpoint)
                }
                DiagnosticLogger.info(this, "runtime.topIp.candidate.success", "profile=${selection.profile.tag} endpoint=$endpointLabel")
                return StartedRuntimeSelection(selection, runtimeEndpoint)
            } catch (error: Throwable) {
                lastFailure = error
                DiagnosticLogger.warn(this, "runtime.topIp.candidate.failed", "profile=${selection.profile.tag} endpoint=$endpointLabel", error)
                if (cleanupRuntimeOnFailedAttempt) {
                    withContext(Dispatchers.IO) {
                        closeRuntime()
                    }
                }
            }
        }
        throw IOException("No patched top-IP profile passed runtime health check", lastFailure)
    }

    private fun runtimeEndpointForSelection(
        topEndpoint: CleanIpResult,
        selection: SelectedConnectionProfile,
    ): CleanIpResult {
        return topEndpoint.copy(
            port = selection.profile.port,
            latencyMs = selection.delayMs.toLong().coerceAtLeast(1L),
            checkedAt = System.currentTimeMillis(),
        )
    }

    private suspend fun startRuntimeCandidate(
        profile: ConnectionProfile,
        endpoint: CleanIpResult?,
        serverOverrideIp: String? = null,
    ) {
        DiagnosticLogger.info(
            this,
            "config.patch.start",
            "profile=${profile.tag} endpoint=${endpoint?.let { "${it.ip}:${it.port}" } ?: "original"} serverOverrideIp=${serverOverrideIp.orEmpty()}",
        )
        val effectiveMode = RuntimeCompatibilityPolicy.effectiveMode(profile)
        DiagnosticLogger.info(
            this,
            "runtime.compatibility.mode",
            "effective=${effectiveMode.wireName} profile=${profile.tag} supportsUdp=${profile.supportsUdpApps}",
        )
        val patchedConfig = SubscriptionSingBoxConfigBuilder.runtimeConfig(profile, serverOverrideIp, effectiveMode)
        DiagnosticLogger.info(this, "config.patch.success", "bytes=${patchedConfig.length}")
        val diagnostics = SingBoxConfigPatcher.runtimeDiagnostics(patchedConfig)
        DiagnosticLogger.info(
            this,
            "runtime.config.diagnostics",
            "routeAutoDetect=${diagnostics.routeAutoDetectInterface} defaultDomainResolver=${diagnostics.defaultDomainResolver} dnsDetours=${diagnostics.dnsDetours.joinToString()}",
        )
        activeRuntimeMode = effectiveMode
        startOrReloadRuntime(patchedConfig)
        verifyRuntimeHealth(profile)
        activeRuntimeConfig = patchedConfig
    }

    private suspend fun verifyRuntimeHealth(profile: ConnectionProfile) {
        verifyRuntimeProxyHealth(profile)
        verifyRuntimeTunHealth(profile)
    }

    private suspend fun verifyRuntimeProxyHealth(profile: ConnectionProfile) {
        DiagnosticLogger.info(
            this,
            "runtime.health.proxy.start",
            "profile=${profile.tag} url=$RUNTIME_HEALTH_URL proxy=127.0.0.1:$MIXED_PROXY_PORT timeoutMs=${RuntimeHealthDefaults.RUNTIME_HEALTH_TIMEOUT_MS}",
        )
        val responseCode = runCatching {
            val proxy = Proxy(Proxy.Type.HTTP, InetSocketAddress("127.0.0.1", MIXED_PROXY_PORT))
            runtimeHealthResponseCode(RUNTIME_HEALTH_URL, proxy)
        }.getOrElse { error ->
            DiagnosticLogger.warn(this, "runtime.health.proxy.failed", "profile=${profile.tag}", error)
            throw IOException("VPN started but local proxy health check failed", error)
        }
        if (responseCode !in 200..399) {
            DiagnosticLogger.warn(this, "runtime.health.proxy.failed", "profile=${profile.tag} code=$responseCode")
            throw IOException("VPN started but local proxy health check returned HTTP $responseCode")
        }
        DiagnosticLogger.info(
            this,
            "runtime.health.proxy.success",
            "profile=${profile.tag} code=$responseCode",
        )
    }

    private suspend fun verifyRuntimeTunHealth(profile: ConnectionProfile) {
        DiagnosticLogger.info(
            this,
            "runtime.health.tun.start",
            "profile=${profile.tag} urls=${RUNTIME_TUN_HEALTH_URLS.joinToString()} timeoutMs=${RuntimeHealthDefaults.RUNTIME_HEALTH_TIMEOUT_MS}",
        )
        val failures = mutableListOf<Throwable>()
        for (url in RUNTIME_TUN_HEALTH_URLS) {
            val responseCode = runCatching {
                runtimeHealthResponseCode(url, Proxy.NO_PROXY)
            }.onFailure { error ->
                DiagnosticLogger.warn(this, "runtime.health.tun.failed", "profile=${profile.tag} url=$url", error)
                failures += IOException("$url failed", error)
            }.getOrNull() ?: continue
            if (responseCode !in 200..399) {
                DiagnosticLogger.warn(this, "runtime.health.tun.failed", "profile=${profile.tag} url=$url code=$responseCode")
                failures += IOException("$url returned HTTP $responseCode")
            } else {
                DiagnosticLogger.info(
                    this,
                    "runtime.health.tun.success",
                    "profile=${profile.tag} url=$url code=$responseCode",
                )
            }
        }
        if (failures.isNotEmpty()) {
            val error = IOException("VPN started but browser compatibility health checks failed", failures.first())
            failures.drop(1).forEach(error::addSuppressed)
            throw error
        }
    }

    private suspend fun runtimeHealthResponseCode(
        url: String,
        proxy: Proxy,
        timeoutMs: Long = RuntimeHealthDefaults.RUNTIME_HEALTH_TIMEOUT_MS,
    ): Int = withContext(Dispatchers.IO) {
        val connection = URL(url).openConnection(proxy) as HttpURLConnection
        connection.connectTimeout = timeoutMs.toInt()
        connection.readTimeout = timeoutMs.toInt()
        connection.instanceFollowRedirects = false
        connection.requestMethod = "GET"
        try {
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }

    private suspend fun runUrlTestGroupRanked(
        groupTag: String,
        profiles: List<ConnectionProfile>,
        timeoutMs: Long,
        quietMs: Long,
        eventPrefix: String,
        updateEvent: String,
    ): List<SelectedConnectionProfile> = withContext(Dispatchers.IO) {
        val collector = ProfileDelayCollector(
            groupTag = groupTag,
            profileTags = profiles.map { it.tag }.toSet(),
            updateEvent = updateEvent,
            logger = { event, message -> DiagnosticLogger.info(this@WhiteDnsVpnService, event, message) },
        )
        val client: CommandClient = Libbox.newCommandClient(
            collector,
            CommandClientOptions().apply {
                addCommand(Libbox.CommandGroup)
            },
        )
        var triggerScope: CoroutineScope? = null
        try {
            client.connect()
            DiagnosticLogger.info(this@WhiteDnsVpnService, "$eventPrefix.urlTest.start", "group=$groupTag")
            triggerScope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scope ->
                scope.launch {
                    runCatching {
                        client.urlTest(groupTag)
                    }.onSuccess {
                        DiagnosticLogger.info(
                            this@WhiteDnsVpnService,
                            "$eventPrefix.urlTest.trigger.done",
                            "group=$groupTag",
                        )
                    }.onFailure { error ->
                        DiagnosticLogger.warn(
                            this@WhiteDnsVpnService,
                            "$eventPrefix.urlTest.trigger.failed",
                            "group=$groupTag",
                            error,
                        )
                    }
                }
            }
            DiagnosticLogger.info(this@WhiteDnsVpnService, "$eventPrefix.urlTest.triggered", "group=$groupTag")
            withTimeoutOrNull(timeoutMs + 1_000L) {
                collector.awaitRanked(profiles, timeoutMs, quietMs)
            }.orEmpty().also { selections ->
                DiagnosticLogger.info(
                    this@WhiteDnsVpnService,
                    "$eventPrefix.urlTest.done",
                    selections.joinToString(prefix = "ranked=") { "${it.profile.tag}:${it.delayMs}ms" },
                )
            }
        } finally {
            runCatching { client.disconnect() }
                .onFailure { DiagnosticLogger.warn(this@WhiteDnsVpnService, "profile.commandClient.disconnect.failed", error = it) }
            triggerScope?.cancel()
        }
    }

    private suspend fun startOrReloadRuntime(config: String) {
        withContext(Dispatchers.IO) {
            runtimeMutex.withLock {
                DiagnosticLogger.info(this@WhiteDnsVpnService, "runtime.startOrReload.begin", "hasCommandServer=${commandServer != null}")
                networkMonitor.start()
                val server = commandServer ?: createCommandServer()
                DiagnosticLogger.info(this@WhiteDnsVpnService, "runtime.checkConfig.begin", "bytes=${config.length}")
                server.checkConfig(config)
                DiagnosticLogger.info(this@WhiteDnsVpnService, "runtime.checkConfig.done")
                val splitTunnelPlan = resolveSplitTunnelRuntimePlan()
                activeSplitTunnelPlan = splitTunnelPlan
                DiagnosticLogger.info(this@WhiteDnsVpnService, "runtime.startOrReload.call.begin")
                server.startOrReloadService(
                    config,
                    OverrideOptions().apply {
                        autoRedirect = false
                    },
                )
                DiagnosticLogger.info(this@WhiteDnsVpnService, "runtime.startOrReload.success")
            }
        }
    }

    private fun resolveSplitTunnelRuntimePlan(): SplitTunnelRuntimePlan {
        val settings = splitTunnelPreferenceStore.readSettings()
        val plan = if (settings.mode == SplitTunnelMode.Off) {
            SplitTunnelPolicy.runtimePlan(
                settings = settings,
                launchablePackages = emptyList(),
                selfPackageName = packageName,
            )
        } else {
            val launchablePackages = installedAppRepository.loadLaunchableApps().map { it.packageName }
            SplitTunnelPolicy.runtimePlan(
                settings = settings,
                launchablePackages = launchablePackages,
                selfPackageName = packageName,
            )
        }
        DiagnosticLogger.info(
            this,
            "splitTunnel.runtime",
            "mode=${plan.mode.wireName} selected=${plan.selectedPackages.size} " +
                "applied=${plan.appliedPackages.size} allowed=${plan.allowedPackages.size} " +
                "disallowed=${plan.disallowedPackages.size} skipped=${plan.skippedPackages.size} " +
                "skippedPackages=${plan.skippedPackages.take(MAX_SPLIT_TUNNEL_LOG_PACKAGES).joinToString()}",
        )
        return plan
    }

    private fun createCommandServer(): CommandServer {
        DiagnosticLogger.info(this, "runtime.commandServer.create.begin")
        val server = Libbox.newCommandServer(
            this@WhiteDnsVpnService,
            this@WhiteDnsVpnService,
        )
        DiagnosticLogger.info(this, "runtime.commandServer.create.done")
        DiagnosticLogger.info(this, "runtime.commandServer.start.begin")
        server.start()
        DiagnosticLogger.info(this, "runtime.commandServer.start.done")
        commandServer = server
        return server
    }

    private fun deleteStaleRuntimeConfig() {
        val file = getFileStreamPath(LAST_RUNTIME_CONFIG_FILE)
        if (!file.exists()) return
        if (file.delete()) {
            DiagnosticLogger.info(this, "runtime.config.staleDeleted")
        } else {
            DiagnosticLogger.warn(this, "runtime.config.staleDelete.failed", "file=$LAST_RUNTIME_CONFIG_FILE")
        }
    }

    private fun startBackgroundSubscriptionRefresh() {
        subscriptionRefreshJob?.cancel()
        DiagnosticLogger.info(
            this,
            "subscription.background.start",
            "intervalMs=${WhiteDnsConfig.SUBSCRIPTION_REFRESH_INTERVAL_MS}",
        )
        subscriptionRefreshJob = scope.launch(Dispatchers.IO) {
            while (isActive && state == VpnState.Started) {
                delay(WhiteDnsConfig.SUBSCRIPTION_REFRESH_INTERVAL_MS)
                if (!isActive || state != VpnState.Started) break

                val refreshed = runCatching {
                    configRepository.fetchOrCachedCatalog()
                }.onFailure { error ->
                    DiagnosticLogger.warn(
                        this@WhiteDnsVpnService,
                        "subscription.background.refresh.failed",
                        error = error,
                    )
                }.getOrNull() ?: continue

                val activeFingerprint = activeProfile?.fingerprint
                if (activeFingerprint != null && refreshed.profiles.none { it.fingerprint == activeFingerprint }) {
                    DiagnosticLogger.warn(
                        this@WhiteDnsVpnService,
                        "subscription.background.activeRemoved",
                        "profile=${activeProfile?.tag.orEmpty()}",
                    )
                }

                DiagnosticLogger.info(
                    this@WhiteDnsVpnService,
                    "subscription.background.refresh.done",
                    "profiles=${refreshed.profiles.size}",
                )
            }
        }
    }

    private fun publishState(newState: VpnState) {
        state = newState
        val countryFlag = if (newState == VpnState.Started) {
            activeProfile?.let(ConnectionLocationPolicy::countryForProfile)?.flag.orEmpty()
        } else {
            ""
        }
        VpnRuntimeStateStore.save(this, newState, sessionStartedAtElapsedMs, countryFlag)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            WhiteDnsTileService.requestTileRefresh(this)
        }
        DiagnosticLogger.info(this, "state.publish", "state=${newState.wireName}")
        val intent = Intent(Actions.STATE_CHANGED)
            .setPackage(packageName)
            .putExtra(Actions.EXTRA_STATE, newState.wireName)
            .putExtra(Actions.EXTRA_SESSION_STARTED_AT_ELAPSED_MS, sessionStartedAtElapsedMs)
            .putExtra(Actions.EXTRA_CONNECTION_COUNTRY_FLAG, countryFlag)
        if (newState is VpnState.Error) {
            intent.putExtra(Actions.EXTRA_ERROR, newState.message)
        }
        sendBroadcast(intent)
    }

    override fun openTun(options: TunOptions): Int {
        if (prepare(this) != null) error("Android VPN permission is missing")
        DiagnosticLogger.info(
            this,
            "tun.open.begin",
            "mtu=${options.mtu} autoRoute=${options.autoRoute} httpProxy=${options.isHTTPProxyEnabled}",
        )

        val builder = Builder()
            .setSession(getString(R.string.app_name))
            .setMtu(options.mtu)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }
        DiagnosticLogger.info(this, "tun.include.self", "package=$packageName")

        var hasIpv4Address = false
        var hasIpv6Address = false
        var ipv4AddressCount = 0
        var ipv6AddressCount = 0
        var dnsServerCount = 0
        var ipv4RouteCount = 0
        var ipv6RouteCount = 0
        var routeExcludeCount = 0

        val inet4Address = options.inet4Address
        while (inet4Address.hasNext()) {
            val address = inet4Address.next()
            builder.addAddress(address.addressString(), address.prefixLength())
            hasIpv4Address = true
            ipv4AddressCount += 1
        }

        val inet6Address = options.inet6Address
        while (inet6Address.hasNext()) {
            val address = inet6Address.next()
            builder.addAddress(address.addressString(), address.prefixLength())
            hasIpv6Address = true
            ipv6AddressCount += 1
        }

        if (options.autoRoute) {
            builder.addDnsServer(TunDnsPolicy.requireAutoRouteDnsServer(options.dnsServerAddress?.value))
            dnsServerCount += 1

            var addedIpv4Route = false
            var addedIpv6Route = false

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                val inet4Routes = options.inet4RouteAddress
                while (inet4Routes.hasNext()) {
                    val route = inet4Routes.next()
                    builder.addRoute(route.toIpPrefix())
                    addedIpv4Route = true
                    ipv4RouteCount += 1
                }

                val inet6Routes = options.inet6RouteAddress
                while (inet6Routes.hasNext()) {
                    val route = inet6Routes.next()
                    builder.addRoute(route.toIpPrefix())
                    addedIpv6Route = true
                    ipv6RouteCount += 1
                }

                val inet4Excludes = options.inet4RouteExcludeAddress
                while (inet4Excludes.hasNext()) {
                    builder.excludeRoute(inet4Excludes.next().toIpPrefix())
                    routeExcludeCount += 1
                }

                val inet6Excludes = options.inet6RouteExcludeAddress
                while (inet6Excludes.hasNext()) {
                    builder.excludeRoute(inet6Excludes.next().toIpPrefix())
                    routeExcludeCount += 1
                }
            } else {
                val inet4Routes = options.inet4RouteRange
                while (inet4Routes.hasNext()) {
                    val route = inet4Routes.next()
                    builder.addRoute(route.addressString(), route.prefixLength())
                    addedIpv4Route = true
                    ipv4RouteCount += 1
                }

                val inet6Routes = options.inet6RouteRange
                while (inet6Routes.hasNext()) {
                    val route = inet6Routes.next()
                    builder.addRoute(route.addressString(), route.prefixLength())
                    addedIpv6Route = true
                    ipv6RouteCount += 1
                }
            }

            if (!addedIpv4Route && hasIpv4Address) {
                builder.addRoute("0.0.0.0", 0)
                ipv4RouteCount += 1
            }
            if (!addedIpv6Route && hasIpv6Address) {
                builder.addRoute("::", 0)
                ipv6RouteCount += 1
            }
        }

        if (options.isHTTPProxyEnabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setHttpProxy(
                ProxyInfo.buildDirectProxy(
                    options.httpProxyServer,
                    options.httpProxyServerPort,
                    options.httpProxyBypassDomain.toKotlinList(),
                ),
            )
        }

        val appPackageCounts = applySplitTunnelToBuilder(builder, activeSplitTunnelPlan)
        val previousDescriptor = tunDescriptor
        val descriptor = builder.establish() ?: error("Android refused to establish VPN")
        tunDescriptor = descriptor
        runCatching { previousDescriptor?.close() }
            .onFailure { DiagnosticLogger.warn(this, "runtime.tunClose.failed", "previousFd=${previousDescriptor?.fd}", it) }
        DiagnosticLogger.info(
            this,
            "tun.open.success",
            "fd=${descriptor.fd} hasIpv4=$hasIpv4Address hasIpv6=$hasIpv6Address " +
                "ipv4Addresses=$ipv4AddressCount ipv6Addresses=$ipv6AddressCount " +
                "dnsServers=$dnsServerCount ipv4Routes=$ipv4RouteCount ipv6Routes=$ipv6RouteCount " +
                "routeExcludes=$routeExcludeCount appAllowed=${appPackageCounts.allowed} " +
                "appDisallowed=${appPackageCounts.disallowed} appSkipped=${activeSplitTunnelPlan.skippedPackages.size} " +
                "splitTunnelMode=${activeSplitTunnelPlan.mode.wireName} runtimeMode=${activeRuntimeMode.wireName} " +
                "replacedPrevious=${previousDescriptor != null}",
        )
        return descriptor.fd
    }

    private fun applySplitTunnelToBuilder(
        builder: Builder,
        plan: SplitTunnelRuntimePlan,
    ): AppliedSplitTunnelPackageCounts {
        var allowedCount = 0
        var disallowedCount = 0

        plan.allowedPackages.forEach { packageName ->
            runCatching {
                builder.addAllowedApplication(packageName)
                allowedCount += 1
            }.onFailure { error ->
                DiagnosticLogger.warn(
                    this,
                    "splitTunnel.builder.allowed.failed",
                    "package=$packageName",
                    error,
                )
            }
        }

        plan.disallowedPackages.forEach { packageName ->
            runCatching {
                builder.addDisallowedApplication(packageName)
                disallowedCount += 1
            }.onFailure { error ->
                DiagnosticLogger.warn(
                    this,
                    "splitTunnel.builder.disallowed.failed",
                    "package=$packageName",
                    error,
                )
            }
        }

        if (plan.mode == SplitTunnelMode.VpnOnlySelected && allowedCount == 0) {
            error("VPN-only split tunneling could not apply any selected apps")
        }

        DiagnosticLogger.info(
            this,
            "splitTunnel.builder.applied",
            "mode=${plan.mode.wireName} allowed=$allowedCount disallowed=$disallowedCount " +
                "skipped=${plan.skippedPackages.size}",
        )
        return AppliedSplitTunnelPackageCounts(
            allowed = allowedCount,
            disallowed = disallowedCount,
        )
    }

    override fun usePlatformAutoDetectInterfaceControl(): Boolean = true

    override fun autoDetectInterfaceControl(fd: Int) {
        val logCount = protectedSocketLogCount.incrementAndGet()
        if (logCount <= 20 || logCount % 100 == 0) {
            DiagnosticLogger.info(this, "socket.protect.fd", "fd=$fd count=$logCount")
        }
        protect(fd)
    }

    override fun useProcFS(): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q

    override fun findConnectionOwner(
        ipProtocol: Int,
        sourceAddress: String,
        sourcePort: Int,
        destinationAddress: String,
        destinationPort: Int,
    ): ConnectionOwner {
        return ConnectionOwner()
    }

    override fun startDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        networkMonitor.setListener(listener)
    }

    override fun closeDefaultInterfaceMonitor(listener: InterfaceUpdateListener) {
        networkMonitor.setListener(null)
    }

    override fun getInterfaces(): NetworkInterfaceIterator {
        val interfaces = mutableListOf<LibboxNetworkInterface>()
        val metadataByName = networkMonitor.interfaceMetadataByName()
        val javaInterfaces = runCatching { NetworkInterface.getNetworkInterfaces()?.toList().orEmpty() }
            .getOrElse { error ->
                DiagnosticLogger.warn(this, "network.interfaces.read.failed", error = error)
                emptyList()
            }
        var addressCount = 0
        for (networkInterface in javaInterfaces) {
            val boxInterface = LibboxNetworkInterface()
            boxInterface.name = networkInterface.name
            boxInterface.index = networkInterface.index
            boxInterface.mtu = runCatching { networkInterface.mtu }.getOrDefault(1500)
            boxInterface.flags = networkInterface.libboxFlags()
            val metadata = metadataByName[networkInterface.name]
            boxInterface.type = metadata?.type ?: Libbox.InterfaceTypeOther
            boxInterface.dnsServer = StringArray(metadata?.dnsServers.orEmpty().iterator())
            boxInterface.metered = metadata?.isExpensive ?: false
            val addresses = networkInterface.interfaceAddresses.mapNotNull { address ->
                LibboxNetworkAddressFormatter.format(address.address, address.networkPrefixLength)
            }
            addressCount += addresses.size
            boxInterface.addresses = StringArray(addresses.iterator())
            interfaces += boxInterface
        }
        DiagnosticLogger.info(this, "network.interfaces", "count=${interfaces.size} addresses=$addressCount")
        return LibboxNetworkInterfaceArray(interfaces.iterator())
    }

    override fun underNetworkExtension(): Boolean = false

    override fun includeAllNetworks(): Boolean = false

    override fun clearDNSCache() = Unit

    override fun readWIFIState(): WIFIState? = null

    override fun localDNSTransport(): LocalDNSTransport? = null

    override fun systemCertificates(): StringIterator {
        val certificates = mutableListOf<String>()
        val keyStore = KeyStore.getInstance("AndroidCAStore")
        keyStore.load(null, null)
        val aliases = keyStore.aliases()
        while (aliases.hasMoreElements()) {
            val certificate = keyStore.getCertificate(aliases.nextElement())
            certificates += "-----BEGIN CERTIFICATE-----\n" +
                Base64.encodeToString(certificate.encoded, Base64.NO_WRAP) +
                "\n-----END CERTIFICATE-----"
        }
        return StringArray(certificates.iterator())
    }

    override fun serviceStop() {
        DiagnosticLogger.warn(this, "libbox.serviceStop", "state=${state.wireName}")
        when (ServiceStopDecision.forState(state)) {
            ServiceStopAction.StartupFailure -> scope.launch {
                stopAfterFailure(IOException("sing-box stopped during startup"))
            }
            ServiceStopAction.StopVpn -> stopVpn()
            ServiceStopAction.Ignore -> Unit
        }
    }

    override fun serviceReload() = Unit

    override fun getSystemProxyStatus(): SystemProxyStatus {
        return SystemProxyStatus().apply {
            available = false
            enabled = false
        }
    }

    override fun setSystemProxyEnabled(isEnabled: Boolean) = Unit

    override fun sendNotification(notification: Notification) {
        val manager = getSystemService(NotificationManager::class.java)
        val channelId = notification.identifier.ifBlank { CHANNEL_ID }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(
                    channelId,
                    notification.typeName.ifBlank { getString(R.string.notification_channel_vpn) },
                    NotificationManager.IMPORTANCE_HIGH,
                ),
            )
        }
        manager.notify(
            notification.typeID,
            NotificationCompat.Builder(this, channelId)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(notification.title)
                .setContentText(notification.body)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setOnlyAlertOnce(true)
                .build(),
        )
    }

    override fun writeDebugMessage(message: String?) {
        val cleanMessage = message.orEmpty()
        if (!BuildConfig.DEBUG) return
        if (cleanMessage.isRoutineCancellationLog()) return
        if (
            cleanMessage.contains("error", ignoreCase = true) ||
            cleanMessage.contains("fatal", ignoreCase = true) ||
            cleanMessage.contains("panic", ignoreCase = true) ||
            cleanMessage.contains("start", ignoreCase = true) ||
            cleanMessage.contains("stop", ignoreCase = true)
        ) {
            DiagnosticLogger.info(this, "sing-box", cleanMessage.take(1_000))
        }
    }

    private fun String.isRoutineCancellationLog(): Boolean {
        return contains("context canceled", ignoreCase = true) ||
            contains("operation was canceled", ignoreCase = true) ||
            contains("exchange failed", ignoreCase = true) ||
            contains(" NOERROR ", ignoreCase = true) ||
            contains("connection upload handshake: EOF", ignoreCase = true)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_vpn),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    private fun serviceNotification(content: String): android.app.Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(content)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)

        VpnNotificationActionPolicy.actionsFor(state).forEach { action ->
            builder.addAction(
                NotificationCompat.Action.Builder(
                    R.drawable.ic_notification,
                    notificationActionTitle(action),
                    serviceActionPendingIntent(action),
                ).build(),
            )
        }

        return builder.build()
    }

    private fun serviceActionPendingIntent(action: String): PendingIntent {
        val requestCode = when (action) {
            Actions.DISCONNECT -> 1
            Actions.RECONNECT -> 2
            else -> 3
        }
        return PendingIntent.getService(
            this,
            requestCode,
            Intent(this, WhiteDnsVpnService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun notificationActionTitle(action: String): String {
        return when (action) {
            Actions.DISCONNECT -> getString(R.string.notification_action_disconnect)
            Actions.RECONNECT -> getString(R.string.notification_action_reconnect)
            else -> action
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    private fun io.nekohasekai.libbox.RoutePrefix.toIpPrefix(): IpPrefix {
        return IpPrefix(InetAddress.getByName(addressString()), prefixLength())
    }

    private fun NetworkInterface.libboxFlags(): Int {
        var flags = 0
        val isUp = runCatching { isUp }.getOrDefault(false)
        val isLoopback = runCatching { isLoopback }.getOrDefault(false)
        val isPointToPoint = runCatching { isPointToPoint }.getOrDefault(false)
        if (isUp) flags = flags or IFF_UP or IFF_RUNNING
        if (isLoopback) flags = flags or IFF_LOOPBACK
        if (isPointToPoint) flags = flags or IFF_POINTOPOINT
        if (runCatching { supportsMulticast() }.getOrDefault(false)) flags = flags or IFF_MULTICAST
        if (!isLoopback && !isPointToPoint) flags = flags or IFF_BROADCAST
        return flags
    }

    private fun String.addressKind(): String {
        val value = trim().removePrefix("[").removeSuffix("]")
        return if (IPV4_LITERAL.matches(value) || ":" in value) "ip" else "domain"
    }

    private data class AppliedSplitTunnelPackageCounts(
        val allowed: Int,
        val disallowed: Int,
    )

    private companion object {
        const val CHANNEL_ID = "white_dns_vpn"
        const val NOTIFICATION_ID = 1001
        const val MIXED_PROXY_PORT = 2080
        const val RUNTIME_HEALTH_URL = "https://www.gstatic.com/generate_204"
        val RUNTIME_TUN_HEALTH_URLS = listOf(
            "https://www.gstatic.com/generate_204",
            "https://1.1.1.1/cdn-cgi/trace",
            "https://www.speedtest.net/",
        )
        const val DISCONNECT_CLOSE_TIMEOUT_MS = 4_000L
        const val LAST_RUNTIME_CONFIG_FILE = "last-runtime-config.json"
        const val MAX_SPLIT_TUNNEL_LOG_PACKAGES = 12
        const val SCAN_DIAGNOSTICS_ENABLED = false
        const val IFF_UP = 0x1
        const val IFF_BROADCAST = 0x2
        const val IFF_LOOPBACK = 0x8
        const val IFF_POINTOPOINT = 0x10
        const val IFF_RUNNING = 0x40
        const val IFF_MULTICAST = 0x1000
        val IPV4_LITERAL = Regex("""\d{1,3}(\.\d{1,3}){3}""")
    }
}

internal object TunDnsPolicy {
    fun requireAutoRouteDnsServer(value: String?): String {
        return value?.trim()?.takeIf(String::isNotEmpty)
            ?: error("sing-box did not provide a DNS server for the VPN tunnel")
    }
}
