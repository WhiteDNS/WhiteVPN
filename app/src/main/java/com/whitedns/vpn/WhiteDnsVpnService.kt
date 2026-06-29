package com.whitedns.vpn

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.VpnService
import android.os.Build
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.follow.clash.core.Core
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress

class WhiteDnsVpnService : VpnService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var configRepository: ConfigRepository
    private lateinit var scanStateStore: WhiteDnsScanStateStore
    private lateinit var locationPreferenceStore: ConnectionLocationPreferenceStore
    private lateinit var networkMonitor: DefaultNetworkMonitor
    private lateinit var splitTunnelPreferenceStore: SplitTunnelPreferenceStore
    private lateinit var installedAppRepository: InstalledAppRepository
    private lateinit var cleanIpCache: CleanIpCache
    private lateinit var encryptedIpListRepository: EncryptedIpListRepository
    private lateinit var frontingIpPreferenceStore: FrontingIpPreferenceStore

    private var startupJob: Job? = null
    private var stopJob: Job? = null
    private var subscriptionRefreshJob: Job? = null
    private var networkRecoveryJob: Job? = null
    private val uidPackageNameCache = mutableMapOf<Int, String>()

    private data class StartedMihomoRuntime(
        val profile: ConnectionProfile,
        val endpoint: CleanIpResult,
        val delayMs: Long,
        val paths: MihomoRuntimePaths,
        val delayProbeName: String,
    )

    @Volatile
    private var state: VpnState = VpnState.Stopped
    @Volatile
    private var sessionStartedAtElapsedMs: Long = 0L
    @Volatile
    private var activeProfile: ConnectionProfile? = null
    @Volatile
    private var activeDelayMs: Long = -1L
    @Volatile
    private var activeRuntimePaths: MihomoRuntimePaths? = null
    @Volatile
    private var activeEndpoint: CleanIpResult? = null
    @Volatile
    private var activeConnectionCountryFlag: String = ""
    @Volatile
    private var lastNetworkRecoveryAtMs: Long = 0L
    @Volatile
    private var isNetworkRecoveryActive: Boolean = false
    @Volatile
    private var lastDefaultNetworkKey: String? = null

    override fun onCreate() {
        super.onCreate()
        configRepository = ConfigRepository(this)
        scanStateStore = WhiteDnsScanStateStore(this)
        locationPreferenceStore = ConnectionLocationPreferenceStore(this)
        networkMonitor = DefaultNetworkMonitor(this)
        splitTunnelPreferenceStore = SplitTunnelPreferenceStore(this)
        installedAppRepository = InstalledAppRepository(this)
        cleanIpCache = CleanIpCache(this)
        encryptedIpListRepository = EncryptedIpListRepository(this)
        frontingIpPreferenceStore = FrontingIpPreferenceStore(this)
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
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        DiagnosticLogger.info(this, "service.onDestroy", "state=${state.wireName}")
        startupJob?.cancel(CancellationException("Service destroyed"))
        stopJob?.cancel()
        subscriptionRefreshJob?.cancel()
        networkRecoveryJob?.cancel()
        stopCoreImmediately()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? {
        if (intent?.action == SERVICE_INTERFACE) {
            return super.onBind(intent)
        }
        return null
    }

    override fun onLowMemory() {
        runCatching { Core.forceGC() }
        super.onLowMemory()
    }

    override fun onRevoke() {
        stopVpn()
        super.onRevoke()
    }

    private fun startVpn() {
        if (state == VpnState.Starting || state == VpnState.Started) {
            DiagnosticLogger.info(this, "connect.ignored", "state=${state.wireName}")
            return
        }
        DiagnosticLogger.clear(this)
        DiagnosticLogger.info(this, "connect.start")
        publishState(VpnState.Starting)
        startForeground(NOTIFICATION_ID, serviceNotification(getString(R.string.notification_starting)))
        launchConnectionStartup("connect")
    }

    private fun reconnectVpn() {
        if (state == VpnState.Starting || state == VpnState.Stopping) {
            DiagnosticLogger.info(this, "reconnect.ignored", "state=${state.wireName}")
            return
        }
        if (state == VpnState.Stopped || state is VpnState.Error) {
            startVpn()
            return
        }
        DiagnosticLogger.clear(this)
        DiagnosticLogger.info(this, "reconnect.start")
        publishState(VpnState.Starting)
        startForeground(NOTIFICATION_ID, serviceNotification(getString(R.string.notification_starting)))
        launchConnectionStartup("reconnect") { stopCoreService() }
    }

    private fun refreshVpn() {
        if (state != VpnState.Started) {
            DiagnosticLogger.info(this, "refresh.ignored", "state=${state.wireName}")
            return
        }
        DiagnosticLogger.clear(this)
        DiagnosticLogger.info(this, "refresh.start")
        val excludedEndpoint = activeEndpoint
        DiagnosticLogger.info(
            this,
            "refresh.endpoint.excluded",
            "endpoint=${excludedEndpoint?.let { "${it.ip}:${it.port}" }.orEmpty()}",
        )
        publishState(VpnState.Starting)
        startForeground(NOTIFICATION_ID, serviceNotification(getString(R.string.notification_starting)))
        launchConnectionStartup(
            eventPrefix = "refresh",
            bypassConnectionCache = true,
            excludedEndpoint = excludedEndpoint,
        ) {
            stopCoreService()
        }
    }

    private fun launchConnectionStartup(
        eventPrefix: String,
        bypassConnectionCache: Boolean = false,
        excludedEndpoint: CleanIpResult? = null,
        beforeStartup: suspend () -> Unit = {},
    ) {
        startupJob?.cancel(CancellationException("Startup superseded"))
        subscriptionRefreshJob?.cancel()
        networkRecoveryJob?.cancel()
        val job = scope.launch {
            try {
                beforeStartup()
                runConnectionStartup(eventPrefix, bypassConnectionCache, excludedEndpoint)
            } catch (error: CancellationException) {
                DiagnosticLogger.info(this@WhiteDnsVpnService, "$eventPrefix.canceled", error.message.orEmpty())
            } catch (error: Throwable) {
                DiagnosticLogger.error(this@WhiteDnsVpnService, "$eventPrefix.failed", error = error)
                stopAfterFailure(error)
            }
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
        ensureStartupActive(eventPrefix)
        ensureUnderlyingNetworkAvailable()
        networkMonitor.start()

        val selectedCountryCode = locationPreferenceStore.readSelectedCountryCode()
        if (bypassConnectionCache) {
            DiagnosticLogger.info(this, "startup.cache.skipped", "source=$eventPrefix reason=bypassConnectionCache")
        } else {
            startCachedMihomoRuntimeIfAvailable(eventPrefix, selectedCountryCode, excludedEndpoint)?.let { startedRuntime ->
                ensureStartupActive(eventPrefix)
                applyStartedRuntime(startedRuntime, eventPrefix)
                return
            }
        }

        val snapshot = configRepository.fetchOrCachedMihomoConfig()
        ensureStartupActive(eventPrefix)
        val profiles = profilesForSelectedLocation(
            profiles = eligibleProfilesForRuntime(snapshot.catalog),
            selectedCountryCode = selectedCountryCode,
        )
        val topIpCandidates = findStartupTopIpCandidates(
            profiles = profiles,
            bypassConnectionCache = bypassConnectionCache,
            excludedEndpoint = excludedEndpoint,
        )
        val splitTunnelPlan = resolveSplitTunnelRuntimePlan()

        var lastFailure: Throwable? = null
        val runtimeCandidates = topIpCandidates.take(CleanIpDefaults.STARTUP_RUNTIME_ATTEMPTS.coerceAtLeast(1))
        for ((index, endpoint) in runtimeCandidates.withIndex()) {
            ensureStartupActive(eventPrefix)
            val attempt = index + 1
            try {
                DiagnosticLogger.info(
                    this,
                    "startup.topIp.try",
                    "attempt=$attempt/${runtimeCandidates.size} endpoint=${endpoint.ip}:${endpoint.port} latencyMs=${endpoint.latencyMs} lossRate=${endpoint.lossRate}",
                )
                if (index > 0) {
                    stopCoreService()
                }
                val startedRuntime = startMihomoRuntimeAttempt(
                    snapshot = snapshot,
                    splitTunnelPlan = splitTunnelPlan,
                    selectedCountryCode = selectedCountryCode,
                    topEndpoint = endpoint,
                )
                applyStartedRuntime(
                    startedRuntime = startedRuntime,
                    eventPrefix = eventPrefix,
                )
                DiagnosticLogger.info(
                    this,
                    "startup.topIp.connected",
                    "attempt=$attempt/${runtimeCandidates.size} endpoint=${startedRuntime.endpoint.ip}:${startedRuntime.endpoint.port}",
                )
                return
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                lastFailure = error
                DiagnosticLogger.warn(
                    this,
                    "startup.topIp.rejected",
                    "attempt=$attempt/${runtimeCandidates.size} endpoint=${endpoint.ip}:${endpoint.port}",
                    error,
                )
                stopCoreService()
            }
        }
        throw IOException("No patched top-IP profile passed runtime health check", lastFailure)
    }

    private suspend fun startCachedMihomoRuntimeIfAvailable(
        eventPrefix: String,
        selectedCountryCode: String?,
        excludedEndpoint: CleanIpResult?,
    ): StartedMihomoRuntime? {
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

            val snapshot = configRepository.readCachedMihomoConfigOrNull()
            if (snapshot == null) {
                DiagnosticLogger.info(this, "startup.cache.miss", "source=$eventPrefix reason=noCachedMihomoConfig")
                return null
            }
            val profiles = profilesForSelectedLocation(
                profiles = eligibleProfilesForRuntime(snapshot.catalog),
                selectedCountryCode = selectedCountryCode,
            )
            val selection = scanStateStore.readLastSelectedProfileSelection(profiles)
            val candidates = StartupScanPolicy.cachedRuntimeCandidates(
                selection = selection,
                lastEndpoint = scanStateStore.readLastEndpoint(),
                cachedResults = cleanIpCache.readResults(),
                frontingIpOverrideEnabled = false,
                excludedEndpoint = excludedEndpoint,
            )
            if (candidates.isEmpty()) {
                DiagnosticLogger.info(
                    this,
                    "startup.cache.miss",
                    "source=$eventPrefix reason=noEndpoint profile=${selection?.profile?.tag.orEmpty()}",
                )
                return null
            }

            val cached = cleanIpScanner(
                ports = candidates.map { it.port },
                candidateIps = emptyList(),
            ).findFirstCachedWorking(candidates)
            if (cached == null) {
                DiagnosticLogger.info(this, "startup.cache.fallback", "source=$eventPrefix candidates=${candidates.size}")
                return null
            }

            DiagnosticLogger.info(
                this,
                "startup.cache.try",
                "source=$eventPrefix endpoint=${cached.ip}:${cached.port} latencyMs=${cached.latencyMs}",
            )
            startMihomoRuntimeAttempt(
                snapshot = snapshot,
                splitTunnelPlan = resolveSplitTunnelRuntimePlan(),
                selectedCountryCode = selectedCountryCode,
                topEndpoint = cached,
            ).also { startedRuntime ->
                DiagnosticLogger.info(
                    this,
                    "startup.cache.connected",
                    "source=$eventPrefix endpoint=${startedRuntime.endpoint.ip}:${startedRuntime.endpoint.port}",
                )
            }
        } catch (error: Throwable) {
            if (error is CancellationException) throw error
            stopCoreService()
            DiagnosticLogger.warn(this, "startup.cache.failed", "source=$eventPrefix", error)
            null
        }
    }

    private suspend fun startMihomoRuntimeAttempt(
        snapshot: MihomoSubscriptionSnapshot,
        splitTunnelPlan: SplitTunnelRuntimePlan,
        selectedCountryCode: String?,
        topEndpoint: CleanIpResult,
    ): StartedMihomoRuntime {
        val frontedYaml = MihomoFrontingPatcher.patchProxyServers(
            rawYaml = snapshot.rawConfig,
            serverOverrideIp = topEndpoint.ip,
        )
        val paths = MihomoRuntimeConfigBuilder(this).write(
            rawYaml = frontedYaml,
            splitTunnelPlan = splitTunnelPlan,
        )
        activeRuntimePaths = paths
        DiagnosticLogger.info(
            this,
            "mihomo.runtime.files",
            "config=${paths.runtimeConfigYaml.absolutePath} service=${paths.serviceJson.absolutePath} patch=${paths.patchFinalJson.absolutePath} controller=${MihomoRuntimeDefaults.CONTROLLER_HOST}:${paths.controlPort} topIp=${topEndpoint.ip}",
        )
        DiagnosticLogger.info(
            this,
            "mihomo.startup.flow",
            "frontingScanner=true randomController=true encryptedSubscription=true profiles=${snapshot.summary.proxies.size} groups=${snapshot.summary.groups.size}",
        )

        startCoreServiceAndWait(paths, splitTunnelPlan)
        val controller = MihomoControllerClient(paths.secret, port = paths.controlPort)
        waitForController(controller, paths)
        val selection = MihomoSelectionPolicy.desiredSelection(snapshot.summary, selectedCountryCode)
        if (selection != null) {
            withContext(Dispatchers.IO) {
                controller.selectProxy(selection.selectorGroup, selection.selectedGroup)
            }
            DiagnosticLogger.info(
                this,
                "mihomo.selection.applied",
                "selector=${selection.selectorGroup} selected=${selection.selectedGroup}",
            )
        } else {
            DiagnosticLogger.info(
                this,
                "mihomo.selection.skipped",
                "country=${selectedCountryCode ?: "auto"} groups=${snapshot.summary.groups.size}",
            )
        }

        val selectedName = selection?.selectedGroup ?: snapshot.summary.proxies.firstOrNull()?.name.orEmpty()
        val delayMs = topEndpoint.latencyMs
        DiagnosticLogger.info(this, "mihomo.delay.skipped", "name=$selectedName fallbackLatencyMs=$delayMs")
        val activeProxyName = runCatching {
            withContext(Dispatchers.IO) {
                controller.activeProxyName(selectedName)
            }
        }
            .onFailure { DiagnosticLogger.warn(this, "mihomo.active.proxy.failed", "selected=$selectedName", it) }
            .getOrNull()
        DiagnosticLogger.info(
            this,
            "mihomo.active.proxy",
            "selected=$selectedName active=${activeProxyName.orEmpty()}",
        )
        verifyRuntimeHealth()
        DiagnosticLogger.info(
            this,
            "startup.topIp.validated",
            "endpoint=${topEndpoint.ip}:${topEndpoint.port} selected=$selectedName active=${activeProxyName.orEmpty()}",
        )

        val profile = activeProfile(snapshot, selectedCountryCode, selection, activeProxyName)
        val endpoint = runtimeEndpointForSelection(
            topEndpoint = topEndpoint,
            snapshot = snapshot,
            selectedCountryCode = selectedCountryCode,
            selection = selection,
            activeProxyName = activeProxyName,
            delayMs = delayMs,
        )
        return StartedMihomoRuntime(
            profile = profile,
            endpoint = endpoint,
            delayMs = delayMs,
            paths = paths,
            delayProbeName = selectedName,
        )
    }

    private fun applyStartedRuntime(
        startedRuntime: StartedMihomoRuntime,
        eventPrefix: String,
    ) {
        activeProfile = startedRuntime.profile
        activeEndpoint = startedRuntime.endpoint
        activeDelayMs = startedRuntime.delayMs
        activeRuntimePaths = startedRuntime.paths
        activeConnectionCountryFlag = startedRuntime.profile.let(ConnectionLocationPolicy::countryForProfile)?.flag.orEmpty()
        scanStateStore.saveLastSelectedProfile(
            SelectedConnectionProfile(
                profile = startedRuntime.profile,
                delayMs = startedRuntime.delayMs.takeIf { it > 0 }?.toInt() ?: Int.MAX_VALUE,
                selectedAt = System.currentTimeMillis(),
            ),
        )
        if (startedRuntime.endpoint.ip !in frontingIpPreferenceStore.readFrontingIps()) {
            cleanIpCache.saveResult(startedRuntime.endpoint)
            scanStateStore.saveLastEndpoint(startedRuntime.endpoint)
        }
        sessionStartedAtElapsedMs = SystemClock.elapsedRealtime()
        publishState(VpnState.Started)
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, serviceNotification(getString(R.string.notification_connected)))
        DiagnosticLogger.info(
            this,
            "$eventPrefix.started",
            "profile=${startedRuntime.profile.tag} endpoint=${startedRuntime.endpoint.ip}:${startedRuntime.endpoint.port} delayMs=${startedRuntime.delayMs}",
        )
        startBackgroundSubscriptionRefresh()
        refreshDelayInBackground(startedRuntime)
        refreshEgressCountryInBackground(startedRuntime)
    }

    private fun refreshDelayInBackground(startedRuntime: StartedMihomoRuntime) {
        val name = startedRuntime.delayProbeName.takeIf(String::isNotBlank) ?: return
        scope.launch(Dispatchers.IO) {
            val delayMs = runCatching {
                MihomoControllerClient(startedRuntime.paths.secret, port = startedRuntime.paths.controlPort)
                    .delay(name, timeoutMs = BACKGROUND_DELAY_TIMEOUT_MS)
                    ?.toLong()
            }.onFailure { error ->
                DiagnosticLogger.warn(this@WhiteDnsVpnService, "mihomo.delay.background.failed", "name=$name", error)
            }.getOrNull()?.takeIf { it > 0 } ?: return@launch

            if (state != VpnState.Started || activeRuntimePaths != startedRuntime.paths) return@launch
            val endpoint = startedRuntime.endpoint.copy(
                latencyMs = delayMs,
                checkedAt = System.currentTimeMillis(),
            )
            activeDelayMs = delayMs
            activeEndpoint = endpoint
            scanStateStore.saveLastSelectedProfile(
                SelectedConnectionProfile(startedRuntime.profile, delayMs.toInt(), System.currentTimeMillis()),
            )
            if (endpoint.ip !in frontingIpPreferenceStore.readFrontingIps()) {
                cleanIpCache.saveResult(endpoint)
                scanStateStore.saveLastEndpoint(endpoint)
            }
            DiagnosticLogger.info(
                this@WhiteDnsVpnService,
                "mihomo.delay.background.ok",
                "name=$name delayMs=$delayMs endpoint=${endpoint.ip}:${endpoint.port}",
            )
        }
    }

    private fun refreshEgressCountryInBackground(startedRuntime: StartedMihomoRuntime) {
        scope.launch(Dispatchers.IO) {
            val country = runCatching {
                MihomoRuntimeHealth.egressCountryCodeThroughMixedProxy()
                    ?.let(ConnectionLocationPolicy::countryFromCode)
            }.onFailure { error ->
                DiagnosticLogger.warn(this@WhiteDnsVpnService, "mihomo.egress.country.failed", error = error)
            }.getOrNull() ?: return@launch

            if (state != VpnState.Started || activeRuntimePaths != startedRuntime.paths) return@launch
            val previousFlag = activeConnectionCountryFlag
            activeConnectionCountryFlag = country.flag
            if (previousFlag != country.flag) {
                publishState(VpnState.Started)
            }
            DiagnosticLogger.info(
                this@WhiteDnsVpnService,
                "mihomo.egress.country.ok",
                "code=${country.code} flag=${country.flag} previousFlag=$previousFlag",
            )
        }
    }

    private fun eligibleProfilesForRuntime(catalog: SubscriptionCatalog): List<ConnectionProfile> {
        val profiles = catalog.profiles
        DiagnosticLogger.info(
            this,
            "config.profiles",
            "count=${profiles.size} types=${profiles.groupingBy { it.type }.eachCount()}",
        )
        if (profiles.isEmpty()) {
            throw IOException("Subscription has no supported Mihomo proxies")
        }

        val defaultNetworkHasIpv6 = networkMonitor.hasUsableIpv6DefaultNetwork()
        val runtimeProfiles = profiles
            .filterNot { it.isIpv6Literal && !defaultNetworkHasIpv6 }
            .ifEmpty { profiles }
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

    private fun profilesForSelectedLocation(
        profiles: List<ConnectionProfile>,
        selectedCountryCode: String?,
    ): List<ConnectionProfile> {
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

    private suspend fun findStartupTopIpCandidates(
        profiles: List<ConnectionProfile>,
        bypassConnectionCache: Boolean = false,
        excludedEndpoint: CleanIpResult? = null,
    ): List<CleanIpResult> {
        val subscriptionPorts = profiles
            .map { it.port }
            .filter { it > 0 }
        if (subscriptionPorts.isEmpty()) throw IOException("Subscription has no usable proxy ports")

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
            return StartupScanPolicy.excludeEndpoint(
                candidates = frontingIps.map { frontingIp ->
                    CleanIpResult(frontingIp, port, 1L, 0.0, checkedAt)
                },
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
                logScanInfo(
                    "scanner.encryptedTop.quick",
                    "candidates=${connectableFresh.size} best=${connectableFresh.first().ip}:${connectableFresh.first().port} latencyMs=${connectableFresh.first().latencyMs} lossRate=${connectableFresh.first().lossRate} cacheAfterRuntimeValidation=${!bypassConnectionCache}",
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
        val cached = cleanIpScanner(
            ports = fallbackPorts,
            candidateIps = emptyList(),
        ).findFirstCachedWorking(cachedCandidates)
        if (cached != null) {
            logScanInfo(
                "scanner.encryptedTop.cached",
                "endpoint=${cached.ip}:${cached.port} latencyMs=${cached.latencyMs} lossRate=${cached.lossRate} speedBps=${cached.downloadBytesPerSecond} cacheAfterRuntimeValidation=true",
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

    private fun runtimeEndpointForSelection(
        topEndpoint: CleanIpResult,
        snapshot: MihomoSubscriptionSnapshot,
        selectedCountryCode: String?,
        selection: MihomoGroupSelection?,
        activeProxyName: String?,
        delayMs: Long,
    ): CleanIpResult {
        val port = runtimePortForSelection(snapshot, selectedCountryCode, selection, activeProxyName) ?: topEndpoint.port
        return topEndpoint.copy(
            port = port,
            latencyMs = delayMs.takeIf { it > 0 } ?: topEndpoint.latencyMs,
            checkedAt = System.currentTimeMillis(),
        )
    }

    private fun runtimePortForSelection(
        snapshot: MihomoSubscriptionSnapshot,
        selectedCountryCode: String?,
        selection: MihomoGroupSelection?,
        activeProxyName: String?,
    ): Int? {
        val activeProxy = activeProxyName?.let { name ->
            snapshot.summary.proxies.firstOrNull { proxy -> proxy.name == name }
        }
        if (activeProxy?.port?.takeIf { it > 0 } != null) return activeProxy.port
        val countryCode = ConnectionLocationPolicy.normalizeCountryCode(selectedCountryCode)
        val countryProfile = countryCode?.let { code ->
            snapshot.catalog.profiles.firstOrNull { profile ->
                ConnectionLocationPolicy.countryForProfile(profile)?.code == code
            }
        }
        if (countryProfile?.port?.takeIf { it > 0 } != null) return countryProfile.port
        val selectedProxy = selection?.selectedGroup?.let { selected ->
            snapshot.summary.proxies.firstOrNull { proxy -> proxy.name == selected }
        }
        return selectedProxy?.port?.takeIf { it > 0 }
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

    private fun ensureStartupActive(eventPrefix: String) {
        if (state == VpnState.Starting) return
        throw CancellationException("$eventPrefix canceled while state=${state.wireName}")
    }

    private fun ensureUnderlyingNetworkAvailable() {
        if (networkMonitor.hasUsableDefaultNetwork()) return
        throw IOException("No internet connection. Connect to Wi-Fi or cellular and try again.")
    }

    private suspend fun resolveSplitTunnelRuntimePlan(): SplitTunnelRuntimePlan {
        val settings = splitTunnelPreferenceStore.readSettings()
        val apps = withContext(Dispatchers.IO) {
            installedAppRepository.loadLaunchableApps().map { it.packageName }
        }
        return SplitTunnelPolicy.runtimePlan(
            settings = settings,
            launchablePackages = apps,
            selfPackageName = packageName,
        ).also { plan ->
            DiagnosticLogger.info(
                this,
                "splitTunnel.runtime",
                "mode=${plan.mode.wireName} allowed=${plan.allowedPackages.size} disallowed=${plan.disallowedPackages.size} skipped=${plan.skippedPackages.size}",
            )
        }
    }

    private suspend fun startCoreServiceAndWait(
        paths: MihomoRuntimePaths,
        splitTunnelPlan: SplitTunnelRuntimePlan,
    ) {
        val initParams = MihomoRuntimeConfigBuilder.initParamsJson(
            baseDir = paths.baseDir.absolutePath,
            sdkInt = Build.VERSION.SDK_INT,
        ).toString()
        val setupParams = withContext(Dispatchers.IO) {
            paths.setupParamsJson.readText()
        }
        val setupMessage = quickSetupCore(initParams, setupParams)
        if (setupMessage.isNotBlank() && !setupMessage.endsWith("is empty")) {
            throw IOException(setupMessage)
        }

        val tunFd = withContext(Dispatchers.Main) {
            establishTun(splitTunnelPlan)
        }
        withContext(Dispatchers.IO) {
            Core.startTun(
                fd = tunFd,
                protect = this@WhiteDnsVpnService::protect,
                resolverProcess = this@WhiteDnsVpnService::resolveProcess,
                stack = MIHOMO_TUN_STACK,
                address = MIHOMO_TUN_ADDRESS,
                dns = MIHOMO_TUN_DNS_HIJACK,
            )
        }
        DiagnosticLogger.info(this, "mihomo.core.started", "config=${paths.runtimeConfigYaml.absolutePath}")
    }

    private suspend fun quickSetupCore(
        initParams: String,
        setupParams: String,
    ): String {
        val deferred = CompletableDeferred<String?>()
        withContext(Dispatchers.IO) {
            Core.quickSetup(initParams, setupParams) { result ->
                if (!deferred.isCompleted) {
                    deferred.complete(result)
                }
            }
        }
        val result = withTimeoutOrNull(CORE_SETUP_TIMEOUT_MS) {
            deferred.await()
        }
        if (result == null && !deferred.isCompleted) {
            throw IOException("Mihomo core setup timed out")
        }
        return result.orEmpty()
    }

    private fun establishTun(splitTunnelPlan: SplitTunnelRuntimePlan): Int {
        val builder = Builder()
            .addAddress(MIHOMO_TUN_IPV4_ADDRESS, MIHOMO_TUN_IPV4_PREFIX_LENGTH)
            .addRoute(MIHOMO_TUN_ROUTE_ANY, 0)
            .addDnsServer(MIHOMO_TUN_DNS_SERVER)
            .setMtu(MIHOMO_TUN_MTU)
            .setSession(getString(R.string.app_name))
            .setBlocking(false)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            builder.setMetered(false)
        }

        applySplitTunnel(builder, splitTunnelPlan)
        return builder.establish()?.detachFd()
            ?: throw IOException("Android rejected the VPN tunnel")
    }

    private fun applySplitTunnel(
        builder: Builder,
        splitTunnelPlan: SplitTunnelRuntimePlan,
    ) {
        when (splitTunnelPlan.mode) {
            SplitTunnelMode.Off -> Unit
            SplitTunnelMode.VpnOnlySelected -> {
                (splitTunnelPlan.allowedPackages + packageName).distinct().forEach { packageName ->
                    addAllowedApplication(builder, packageName)
                }
            }
            SplitTunnelMode.BypassSelected -> {
                splitTunnelPlan.disallowedPackages
                    .filterNot { it == packageName }
                    .forEach { packageName -> addDisallowedApplication(builder, packageName) }
            }
        }
    }

    private fun addAllowedApplication(builder: Builder, packageName: String) {
        runCatching { builder.addAllowedApplication(packageName) }
            .onFailure { error ->
                if (error !is PackageManager.NameNotFoundException) {
                    throw error
                }
                DiagnosticLogger.warn(this, "splitTunnel.allowed.missing", "package=$packageName", error)
            }
    }

    private fun addDisallowedApplication(builder: Builder, packageName: String) {
        runCatching { builder.addDisallowedApplication(packageName) }
            .onFailure { error ->
                if (error !is PackageManager.NameNotFoundException) {
                    throw error
                }
                DiagnosticLogger.warn(this, "splitTunnel.disallowed.missing", "package=$packageName", error)
            }
    }

    private fun resolveProcess(
        protocol: Int,
        source: InetSocketAddress,
        target: InetSocketAddress,
        uid: Int,
    ): String {
        val resolvedUid = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            runCatching {
                getSystemService(ConnectivityManager::class.java).getConnectionOwnerUid(protocol, source, target)
            }.getOrDefault(-1)
        } else {
            uid
        }
        if (resolvedUid == -1) return ""
        return uidPackageNameCache.getOrPut(resolvedUid) {
            packageManager.getPackagesForUid(resolvedUid)?.firstOrNull().orEmpty()
        }
    }

    private suspend fun waitForController(
        controller: MihomoControllerClient,
        paths: MihomoRuntimePaths,
    ) {
        DiagnosticLogger.info(
            this,
            "mihomo.controller.wait.start",
            "endpoint=${controller.endpoint} timeoutMs=$CONTROLLER_READY_TIMEOUT_MS runtimeConfigBytes=${paths.runtimeConfigYaml.length()} profileBytes=${paths.profileYaml.length()} serviceBytes=${paths.serviceJson.length()} patchBytes=${paths.patchFinalJson.length()}",
        )
        val ready = withTimeoutOrNull(CONTROLLER_READY_TIMEOUT_MS) {
            while (isActive) {
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        controller.getProxies()
                    }.isSuccess
                }
                if (ok) return@withTimeoutOrNull true
                delay(CONTROLLER_POLL_INTERVAL_MS)
            }
            false
        } == true
        if (!ready) {
            logControllerStartupFailure(controller, paths)
            throw IOException("Mihomo core actions did not become ready")
        }
        DiagnosticLogger.info(this, "mihomo.controller.wait.ok", "endpoint=${controller.endpoint}")
    }

    private suspend fun logControllerStartupFailure(
        controller: MihomoControllerClient,
        paths: MihomoRuntimePaths,
    ) = withContext(Dispatchers.IO) {
        DiagnosticLogger.warn(
            this@WhiteDnsVpnService,
            "mihomo.controller.wait.timeout",
            "endpoint=${controller.endpoint} portBindable=${MihomoControllerPort.canBind(paths.controlPort)} runtimeConfigBytes=${paths.runtimeConfigYaml.length()} profileBytes=${paths.profileYaml.length()} serviceBytes=${paths.serviceJson.length()} patchBytes=${paths.patchFinalJson.length()} coreLogBytes=${paths.logFile.length()} stderrBytes=${paths.errorFile.length()}",
        )
        logFileTail("mihomo.core.log.tail", paths.logFile, paths.secret)
        logFileTail("mihomo.stderr.tail", paths.errorFile, paths.secret)
    }

    private fun logFileTail(
        event: String,
        file: File,
        secret: String,
    ) {
        if (!file.exists()) {
            DiagnosticLogger.warn(this, event, "path=${file.absolutePath} status=missing")
            return
        }
        if (file.length() == 0L) {
            DiagnosticLogger.warn(this, event, "path=${file.absolutePath} status=empty")
            return
        }
        val tail = runCatching {
            file.readText()
                .takeLast(CONTROLLER_LOG_TAIL_CHARS)
                .redactRuntimeSecret(secret)
                .replace("\n", "\\n")
        }.getOrElse { error ->
            DiagnosticLogger.warn(this, event, "path=${file.absolutePath} status=readFailed", error)
            return
        }
        DiagnosticLogger.warn(this, event, "path=${file.absolutePath} bytes=${file.length()} tail=$tail")
    }

    private fun String.redactRuntimeSecret(secret: String): String {
        return if (secret.isBlank()) this else replace(secret, "<mihomo-secret>")
    }

    private suspend fun verifyRuntimeHealth() {
        val proxyCode = waitForHealthyStatus("mihomo.proxy.health") {
            MihomoRuntimeHealth.httpStatusThroughMixedProxy()
        }
            ?: throw IOException("Local Mihomo proxy did not pass health check at 127.0.0.1:2080")
        DiagnosticLogger.info(this, "mihomo.proxy.health.ok", "code=$proxyCode")
    }

    private suspend fun waitForHealthyStatus(
        event: String,
        check: () -> Int,
    ): Int? {
        return withTimeoutOrNull(RUNTIME_HEALTH_TIMEOUT_MS) {
            while (isActive) {
                val code = withContext(Dispatchers.IO) {
                    runCatching { check() }.getOrElse {
                        DiagnosticLogger.warn(this@WhiteDnsVpnService, "$event.failed", error = it)
                        -1
                    }
                }
                if (code == 204 || code in 200..399) return@withTimeoutOrNull code
                delay(RUNTIME_HEALTH_POLL_INTERVAL_MS)
            }
            null
        }
    }

    private fun activeProfile(
        snapshot: MihomoSubscriptionSnapshot,
        selectedCountryCode: String?,
        selection: MihomoGroupSelection?,
        activeProxyName: String?,
    ): ConnectionProfile {
        activeProxyName?.let { name ->
            snapshot.catalog.profiles.firstOrNull { profile -> profile.tag == name }?.let { return it }
        }

        val countryCode = ConnectionLocationPolicy.normalizeCountryCode(selectedCountryCode)
        val countryProfile = countryCode?.let { code ->
            snapshot.catalog.profiles.firstOrNull { profile ->
                ConnectionLocationPolicy.countryForProfile(profile)?.code == code
            }
        }
        if (countryProfile != null) return countryProfile

        val tag = selection?.selectedGroup
            ?: MihomoSelectionPolicy.autoGroup(snapshot.summary)?.name
            ?: snapshot.summary.proxies.firstOrNull()?.name
            ?: "Mihomo"
        return ConnectionProfile(
            tag = tag,
            type = "mihomo-group",
            server = MihomoRuntimeDefaults.CONTROLLER_HOST,
            port = MihomoRuntimeDefaults.MIXED_PORT,
            transport = "",
            validationHost = MihomoRuntimeDefaults.CONTROLLER_HOST,
        )
    }

    private fun stopVpn() {
        if (state == VpnState.Stopped) {
            publishState(VpnState.Stopped)
            stopSelf()
            return
        }
        if (state == VpnState.Stopping) return
        DiagnosticLogger.info(this, "disconnect.start", "state=${state.wireName}")
        startupJob?.cancel(CancellationException("Disconnect requested"))
        subscriptionRefreshJob?.cancel()
        networkRecoveryJob?.cancel()
        publishState(VpnState.Stopping)
        stopJob?.cancel()
        stopJob = scope.launch {
            stopCoreService()
            finishStoppedState("disconnect.stopped")
        }
    }

    private suspend fun stopAfterFailure(error: Throwable) {
        subscriptionRefreshJob?.cancel()
        networkRecoveryJob?.cancel()
        stopCoreService()
        sessionStartedAtElapsedMs = 0L
        activeProfile = null
        activeDelayMs = -1L
        activeRuntimePaths = null
        activeEndpoint = null
        activeConnectionCountryFlag = ""
        runCatching { networkMonitor.stop() }
        stopForegroundCompat()
        publishState(VpnState.Error(error.message ?: "Unable to start VPN"))
        stopSelf()
    }

    private suspend fun stopCoreService() {
        withContext(Dispatchers.IO) {
            runCatching { Core.stopTun() }
                .onFailure { DiagnosticLogger.warn(this@WhiteDnsVpnService, "mihomo.tun.stop.failed", error = it) }
            runCatching { invokeCoreAction("stopListener") }
                .onFailure { DiagnosticLogger.warn(this@WhiteDnsVpnService, "mihomo.listener.stop.failed", error = it) }
            runCatching { invokeCoreAction("shutdown") }
                .onFailure { DiagnosticLogger.warn(this@WhiteDnsVpnService, "mihomo.shutdown.failed", error = it) }
        }
    }

    private suspend fun invokeCoreAction(method: String): String? {
        val deferred = CompletableDeferred<String?>()
        val action = JSONObject()
            .put("id", "$method-${SystemClock.elapsedRealtimeNanos()}")
            .put("method", method)
        Core.invokeAction(action.toString()) { result ->
            if (!deferred.isCompleted) {
                deferred.complete(result)
            }
        }
        return withTimeoutOrNull(CORE_ACTION_TIMEOUT_MS) {
            deferred.await()
        }
    }

    private fun stopCoreImmediately() {
        runCatching { Core.stopTun() }
        runCatching { fireAndForgetCoreAction("stopListener") }
        runCatching { fireAndForgetCoreAction("shutdown") }
    }

    private fun fireAndForgetCoreAction(method: String) {
        val action = JSONObject()
            .put("id", "$method-${SystemClock.elapsedRealtimeNanos()}")
            .put("method", method)
        Core.invokeAction(action.toString()) {}
    }

    private fun finishStoppedState(event: String) {
        sessionStartedAtElapsedMs = 0L
        activeProfile = null
        activeDelayMs = -1L
        activeRuntimePaths = null
        activeEndpoint = null
        activeConnectionCountryFlag = ""
        lastNetworkRecoveryAtMs = 0L
        isNetworkRecoveryActive = false
        lastDefaultNetworkKey = null
        runCatching { networkMonitor.stop() }
        stopForegroundCompat()
        publishState(VpnState.Stopped)
        DiagnosticLogger.info(this, event)
        stopSelf()
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
                return@launch
            }
            isNetworkRecoveryActive = true
            lastNetworkRecoveryAtMs = nowMs
            try {
                reconnectVpn()
            } finally {
                isNetworkRecoveryActive = false
            }
        }
    }

    private fun DefaultNetworkCandidate?.networkRecoveryKey(): String {
        if (this == null) return "none"
        return listOf(name, index.toString(), isValidated.toString(), isWifi.toString(), isCellular.toString())
            .joinToString("|")
    }

    private fun startBackgroundSubscriptionRefresh() {
        subscriptionRefreshJob?.cancel()
        subscriptionRefreshJob = scope.launch(Dispatchers.IO) {
            while (isActive && state == VpnState.Started) {
                delay(WhiteDnsConfig.SUBSCRIPTION_REFRESH_INTERVAL_MS)
                if (!isActive || state != VpnState.Started) break
                runCatching { configRepository.fetchOrCachedMihomoConfig() }
                    .onSuccess { snapshot ->
                        DiagnosticLogger.info(
                            this@WhiteDnsVpnService,
                            "subscription.background.refresh.done",
                            "profiles=${snapshot.catalog.profiles.size} groups=${snapshot.summary.groups.size}",
                        )
                    }
                    .onFailure { error ->
                        DiagnosticLogger.warn(
                            this@WhiteDnsVpnService,
                            "subscription.background.refresh.failed",
                            error = error,
                        )
                    }
            }
        }
    }

    private fun publishState(newState: VpnState) {
        state = newState
        val countryFlag = if (newState == VpnState.Started) {
            activeConnectionCountryFlag
        } else {
            ""
        }
        val debugFrontingIp = if (newState == VpnState.Started) {
            activeEndpoint?.ip.orEmpty()
        } else {
            ""
        }
        VpnRuntimeStateStore.save(this, newState, sessionStartedAtElapsedMs, countryFlag, debugFrontingIp)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            WhiteDnsTileService.requestTileRefresh(this)
        }
        DiagnosticLogger.info(this, "state.publish", "state=${newState.wireName} debugFrontingIp=${debugFrontingIp}")
        val intent = Intent(Actions.STATE_CHANGED)
            .setPackage(packageName)
            .putExtra(Actions.EXTRA_STATE, newState.wireName)
            .putExtra(Actions.EXTRA_SESSION_STARTED_AT_ELAPSED_MS, sessionStartedAtElapsedMs)
            .putExtra(Actions.EXTRA_CONNECTION_COUNTRY_FLAG, countryFlag)
            .putExtra(Actions.EXTRA_DEBUG_FRONTING_IP, debugFrontingIp)
        if (newState is VpnState.Error) {
            intent.putExtra(Actions.EXTRA_ERROR, newState.message)
        }
        sendBroadcast(intent)
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

    private companion object {
        const val CHANNEL_ID = "white_dns_vpn"
        const val NOTIFICATION_ID = 1001
        const val CORE_SETUP_TIMEOUT_MS = 15_000L
        const val CORE_ACTION_TIMEOUT_MS = 3_000L
        const val CONTROLLER_READY_TIMEOUT_MS = 12_000L
        const val CONTROLLER_POLL_INTERVAL_MS = 300L
        const val CONTROLLER_LOG_TAIL_CHARS = 4_000
        const val RUNTIME_HEALTH_TIMEOUT_MS = 12_000L
        const val RUNTIME_HEALTH_POLL_INTERVAL_MS = 500L
        const val BACKGROUND_DELAY_TIMEOUT_MS = 3_000
        const val SCAN_DIAGNOSTICS_ENABLED = true
        const val MIHOMO_TUN_STACK = "gvisor"
        const val MIHOMO_TUN_IPV4_ADDRESS = "172.19.0.1"
        const val MIHOMO_TUN_IPV4_PREFIX_LENGTH = 30
        const val MIHOMO_TUN_ADDRESS = "172.19.0.1/30"
        const val MIHOMO_TUN_DNS_SERVER = "172.19.0.2"
        const val MIHOMO_TUN_DNS_HIJACK = "0.0.0.0"
        const val MIHOMO_TUN_ROUTE_ANY = "0.0.0.0"
        const val MIHOMO_TUN_MTU = 9000
    }
}
