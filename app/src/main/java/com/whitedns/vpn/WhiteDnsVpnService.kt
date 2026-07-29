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
import com.follow.clash.core.TunInterface
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger

internal class MihomoCoreBusyException(cause: Throwable? = null) :
    IOException("Mihomo core is still finishing startup. Please try again.", cause)

internal class MihomoCoreSetupTimeoutException :
    IOException("Mihomo core setup did not finish within 60 seconds")

internal suspend fun <T> connectWithStartupFallback(
    primary: suspend () -> T,
    fallback: suspend (Throwable) -> T,
): T {
    return try {
        primary()
    } catch (error: Throwable) {
        if (
            error is CancellationException ||
            error is MihomoCoreBusyException ||
            error is MihomoCoreSetupTimeoutException
        ) {
            throw error
        }
        fallback(error)
    }
}

internal object PostConnectHealthPolicy {
    const val CHECK_INTERVAL_MS = 60_000L
    const val FAILURES_BEFORE_RECOVERY = 2
    const val RECOVERY_COOLDOWN_MS = 10 * 60_000L

    fun shouldRecover(consecutiveFailures: Int, nowElapsedMs: Long, lastRecoveryElapsedMs: Long): Boolean {
        return consecutiveFailures >= FAILURES_BEFORE_RECOVERY &&
            (lastRecoveryElapsedMs <= 0L || nowElapsedMs - lastRecoveryElapsedMs >= RECOVERY_COOLDOWN_MS)
    }
}

class WhiteDnsVpnService : VpnService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var configRepository: ConfigRepository
    private lateinit var subscriptionStore: SubscriptionStore
    private lateinit var scanStateStore: WhiteDnsScanStateStore
    private lateinit var locationPreferenceStore: ConnectionLocationPreferenceStore
    private lateinit var networkMonitor: DefaultNetworkMonitor
    private lateinit var splitTunnelPreferenceStore: SplitTunnelPreferenceStore
    private lateinit var installedAppRepository: InstalledAppRepository
    private lateinit var cleanIpCache: CleanIpCache
    private lateinit var encryptedIpListRepository: EncryptedIpListRepository
    private lateinit var frontingIpPreferenceStore: FrontingIpPreferenceStore
    private lateinit var dpiBypassPreferenceStore: DpiBypassPreferenceStore
    private lateinit var dnsPrivacyPreferenceStore: DnsPrivacyPreferenceStore
    private lateinit var tlsIntegrityPreferenceStore: TlsIntegrityPreferenceStore
    private lateinit var connectionOptionsPreferenceStore: MihomoConnectionOptionsPreferenceStore
    private lateinit var connectionSelectionPreferenceStore: ConnectionSelectionPreferenceStore

    private var startupJob: Job? = null
    private var stopJob: Job? = null
    private var subscriptionRefreshJob: Job? = null
    private var encryptedIpScanJob: Job? = null
    private var postConnectHealthJob: Job? = null
    private var dpiBypassJob: Job? = null
    private var connectionDelayTestJob: Job? = null
    @Volatile
    private var activeConnectionDelayTestId: String? = null
    @Volatile
    private var connectionDelayTestPaused = false
    private val uidPackageNameCache = mutableMapOf<Int, String>()

    private class DpiBypassStartupException(cause: Throwable) :
        IOException("ByeByeDPI failed to start", cause)

    private data class StartedMihomoRuntime(
        val profile: ConnectionProfile,
        val endpoint: CleanIpResult,
        val delayMs: Long,
        val paths: MihomoRuntimePaths,
        val delayProbeName: String,
        val cacheEndpoint: Boolean,
        val selectedCountryCode: String?,
    )

    private data class StartupTopIpCandidatePlan(
        val primaryPhase: String,
        val primaryCandidates: List<CleanIpResult>,
        val exhaustiveCandidates: List<CleanIpResult>,
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
    private var activeFrontingIp: String = ""
    @Volatile
    private var lastDefaultNetworkKey: String? = null
    @Volatile
    private var lastDefaultDns: String = ""
    @Volatile
    private var activeDpiBypassPort: Int? = null
    @Volatile
    private var alwaysOnActive: Boolean = false
    @Volatile
    private var lockdownActive: Boolean = false
    @Volatile
    private var lastPostConnectRecoveryElapsedMs: Long = 0L
    private var activeProfileShowsServer: Boolean = false

    override fun onCreate() {
        super.onCreate()
        configRepository = ConfigRepository(this)
        subscriptionStore = SubscriptionStore(this)
        scanStateStore = WhiteDnsScanStateStore(this)
        locationPreferenceStore = ConnectionLocationPreferenceStore(this)
        networkMonitor = DefaultNetworkMonitor(this)
        splitTunnelPreferenceStore = SplitTunnelPreferenceStore(this)
        installedAppRepository = InstalledAppRepository(this)
        cleanIpCache = CleanIpCache(this)
        encryptedIpListRepository = EncryptedIpListRepository(this)
        frontingIpPreferenceStore = FrontingIpPreferenceStore(this)
        dpiBypassPreferenceStore = DpiBypassPreferenceStore(this)
        dnsPrivacyPreferenceStore = DnsPrivacyPreferenceStore(this)
        tlsIntegrityPreferenceStore = TlsIntegrityPreferenceStore(this)
        connectionOptionsPreferenceStore = MihomoConnectionOptionsPreferenceStore(this)
        connectionSelectionPreferenceStore = ConnectionSelectionPreferenceStore(this)
        networkMonitor.setDefaultNetworkChangeListener { candidate ->
            handleDefaultNetworkChanged(candidate)
        }
        createNotificationChannel()
        DiagnosticLogger.info(this, "service.onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val appInitiated = intent?.getBooleanExtra(Actions.EXTRA_APP_INITIATED, false) == true
        updateAlwaysOnMode(appInitiated)
        DiagnosticLogger.info(
            this,
            "service.onStartCommand",
            "action=${intent?.action} appInitiated=$appInitiated alwaysOn=$alwaysOnActive lockdown=$lockdownActive " +
                "startId=$startId flags=$flags state=${state.wireName}",
        )
        when (Actions.resolveServiceAction(intent?.action, appInitiated)) {
            Actions.CONNECT -> startVpn()
            Actions.DISCONNECT -> stopVpn()
            Actions.RECONNECT -> reconnectVpn()
            Actions.REFRESH -> refreshVpn()
            Actions.TEST_CONNECTION_DELAYS -> startConnectionDelayTest(
                testId = intent?.getStringExtra(Actions.EXTRA_DELAY_TEST_ID).orEmpty(),
                connectionTypes = intent
                    ?.getStringArrayListExtra(Actions.EXTRA_CONNECTION_TYPES)
                    .orEmpty()
                    .toSet(),
            )
            Actions.PAUSE_CONNECTION_DELAY_TEST -> setConnectionDelayTestPaused(
                testId = intent?.getStringExtra(Actions.EXTRA_DELAY_TEST_ID).orEmpty(),
                paused = true,
            )
            Actions.RESUME_CONNECTION_DELAY_TEST -> setConnectionDelayTestPaused(
                testId = intent?.getStringExtra(Actions.EXTRA_DELAY_TEST_ID).orEmpty(),
                paused = false,
            )
            Actions.CANCEL_CONNECTION_DELAY_TEST -> cancelConnectionDelayTest(
                intent?.getStringExtra(Actions.EXTRA_DELAY_TEST_ID),
            )
        }
        return START_NOT_STICKY
    }

    private fun updateAlwaysOnMode(appInitiated: Boolean) {
        alwaysOnActive = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            isAlwaysOn()
        } else {
            alwaysOnActive || !appInitiated
        }
        lockdownActive = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && isLockdownEnabled()
    }

    override fun onDestroy() {
        DiagnosticLogger.info(this, "service.onDestroy", "state=${state.wireName}")
        startupJob?.cancel(CancellationException("Service destroyed"))
        connectionDelayTestJob?.cancel(CancellationException("Service destroyed"))
        stopJob?.cancel()
        subscriptionRefreshJob?.cancel()
        encryptedIpScanJob?.cancel()
        cancelPostConnectHealthWatchdog()
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
        alwaysOnActive = false
        lockdownActive = false
        stopVpn(force = true)
        super.onRevoke()
    }

    private fun startVpn() {
        if (state == VpnState.Starting || state == VpnState.Started) {
            DiagnosticLogger.info(this, "connect.ignored", "state=${state.wireName}")
            return
        }
        lastPostConnectRecoveryElapsedMs = 0L
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
        lastPostConnectRecoveryElapsedMs = 0L
        DiagnosticLogger.clear(this)
        DiagnosticLogger.info(this, "reconnect.start")
        publishState(VpnState.Starting)
        startForeground(NOTIFICATION_ID, serviceNotification(getString(R.string.notification_starting)))
        launchConnectionStartup("reconnect") { stopActiveCoreForReplacement() }
    }

    private fun refreshVpn(eventPrefix: String = "refresh", automatic: Boolean = false) {
        if (state != VpnState.Started) {
            DiagnosticLogger.info(this, "$eventPrefix.ignored", "state=${state.wireName}")
            return
        }
        if (!automatic) {
            lastPostConnectRecoveryElapsedMs = 0L
            DiagnosticLogger.clear(this)
        }
        DiagnosticLogger.info(this, "$eventPrefix.start")
        val excludedEndpoint = activeEndpoint
        DiagnosticLogger.info(
            this,
            "$eventPrefix.endpoint.excluded",
            "endpoint=${excludedEndpoint?.let { "${it.ip}:${it.port}" }.orEmpty()}",
        )
        publishState(VpnState.Starting)
        startForeground(NOTIFICATION_ID, serviceNotification(getString(R.string.notification_starting)))
        launchConnectionStartup(
            eventPrefix = eventPrefix,
            excludedEndpoint = excludedEndpoint,
            preserveRuntimeOnFailure = activeRuntimePaths,
        )
    }

    private fun startConnectionDelayTest(testId: String, connectionTypes: Set<String>) {
        if (testId.isBlank()) return
        val subscriptionId = subscriptionStore.readSelectedSubscriptionId()
        val preparingSession = ConnectionDelayTestState.replace(
            ConnectionDelayTestSession(
                testId = testId,
                subscriptionId = subscriptionId,
                connectionTypes = connectionTypes,
            ),
        )
        if (state == VpnState.Starting || state == VpnState.Stopping) {
            val failedSession = ConnectionDelayTestState.replace(
                preparingSession.copy(
                    status = Actions.DELAY_TEST_FAILED,
                    error = getString(R.string.connection_test_busy),
                ),
            )
            publishConnectionDelayTest(
                failedSession,
            )
            return
        }

        val previousJob = connectionDelayTestJob
        activeConnectionDelayTestId = testId
        connectionDelayTestPaused = false
        lateinit var job: Job
        job = scope.launch {
            previousJob?.cancelAndJoin()
            var temporaryCore = false
            try {
                ensureUnderlyingNetworkAvailable()
                val snapshot = configRepository.readCachedMihomoConfigOrNull()
                    ?: configRepository.fetchOrCachedMihomoConfig()
                val profiles = ConnectionTypeSelectionPolicy.filterProfiles(
                    snapshot.catalog.profiles,
                    connectionTypes,
                )
                if (profiles.isEmpty()) {
                    throw IOException(getString(R.string.connection_empty))
                }

                val controller = if (
                    state == VpnState.Started &&
                    activeRuntimePaths != null &&
                    coreLifecycle.isActive()
                ) {
                    val paths = activeRuntimePaths ?: throw MihomoCoreBusyException()
                    MihomoControllerClient(paths.secret, port = paths.controlPort).also { activeController ->
                        val liveProxies = withContext(Dispatchers.IO) { activeController.getProxies() }
                            .optJSONObject("proxies")
                        if (liveProxies == null || profiles.any { !liveProxies.has(it.tag) }) {
                            throw IOException(getString(R.string.connection_test_reconnect_required))
                        }
                    }
                } else {
                    if (state == VpnState.Started) {
                        throw IOException(getString(R.string.connection_test_reconnect_required))
                    }
                    if (!coreLifecycle.isIdle() && !stopCoreService()) {
                        throw MihomoCoreBusyException()
                    }
                    val runtimeYaml = MihomoConnectionOptionsPatcher.patch(
                        snapshot.rawConfig,
                        connectionOptionsPreferenceStore.read(),
                    )
                    val paths = MihomoRuntimeConfigBuilder(this@WhiteDnsVpnService).write(
                        rawYaml = runtimeYaml,
                        splitTunnelPlan = SplitTunnelRuntimePlan.off(),
                        dnsPrivacyMode = dnsPrivacyPreferenceStore.readMode(),
                        dohUrl = dnsPrivacyPreferenceStore.readDohUrl(),
                        dotEndpoint = dnsPrivacyPreferenceStore.readDotEndpoint(),
                    )
                    temporaryCore = true
                    setupCore(paths)
                    MihomoControllerClient(paths.secret, port = paths.controlPort).also { probeController ->
                        waitForController(probeController, paths)
                    }
                }

                val startedSession = ConnectionDelayTestState.update(testId) {
                    it.copy(
                        targetFingerprints = profiles.map(ConnectionProfile::fingerprint),
                        status = Actions.DELAY_TEST_STARTED,
                        completed = 0,
                        total = profiles.size,
                        available = 0,
                        paused = false,
                        error = "",
                    )
                } ?: return@launch
                publishConnectionDelayTest(startedSession)

                val nextProfileIndex = AtomicInteger(0)
                val progressMutex = Mutex()
                coroutineScope {
                    repeat(minOf(CONNECTION_DELAY_WORKER_COUNT, profiles.size)) {
                        launch(Dispatchers.IO) {
                            while (isActive) {
                                awaitConnectionDelayTestResumed(testId)
                                val index = nextProfileIndex.getAndIncrement()
                                if (index >= profiles.size) break
                                val profile = profiles[index]
                                val delayMs = realConnectionDelayMs(controller, profile)
                                subscriptionStore.saveConnectionDelayRecord(
                                    ConnectionDelayRecord(
                                        subscriptionId = subscriptionId,
                                        fingerprint = profile.fingerprint,
                                        delayMs = delayMs,
                                        status = if (delayMs != null) {
                                            ConnectionDelayStatus.Success
                                        } else {
                                            ConnectionDelayStatus.Failure
                                        },
                                        testedAt = System.currentTimeMillis(),
                                    ),
                                )
                                val progressSession = progressMutex.withLock {
                                    ConnectionDelayTestState.update(testId) { current ->
                                        current.copy(
                                            status = Actions.DELAY_TEST_PROGRESS,
                                            completed = current.completed + 1,
                                            available = current.available + if (delayMs != null) 1 else 0,
                                            finishedFingerprints = current.finishedFingerprints + profile.fingerprint,
                                        )
                                    }
                                } ?: break
                                publishConnectionDelayTest(
                                    progressSession,
                                    finishedFingerprints = listOf(profile.fingerprint),
                                )
                            }
                        }
                    }
                }
                val completedSession = ConnectionDelayTestState.update(testId) {
                    it.copy(
                        status = Actions.DELAY_TEST_COMPLETED,
                        paused = false,
                    )
                } ?: return@launch
                publishConnectionDelayTest(completedSession)
            } catch (error: CancellationException) {
                ConnectionDelayTestState.update(testId) {
                    it.copy(
                        status = Actions.DELAY_TEST_CANCELED,
                        paused = false,
                    )
                }?.let(::publishConnectionDelayTest)
                throw error
            } catch (error: Throwable) {
                DiagnosticLogger.warn(
                    this@WhiteDnsVpnService,
                    "connection.delay.failed",
                    "types=${connectionTypes.sorted().joinToString(",")}",
                    error,
                )
                val failedSession = ConnectionDelayTestState.update(testId) {
                    it.copy(
                        status = Actions.DELAY_TEST_FAILED,
                        paused = false,
                        error = error.message?.takeIf(String::isNotBlank)
                            ?: getString(R.string.connection_test_failed),
                    )
                } ?: preparingSession.copy(
                    status = Actions.DELAY_TEST_FAILED,
                    error = getString(R.string.connection_test_failed),
                )
                publishConnectionDelayTest(failedSession)
            } finally {
                if (temporaryCore) {
                    withContext(NonCancellable) {
                        stopCoreService()
                    }
                }
            }
        }
        connectionDelayTestJob = job
        job.invokeOnCompletion {
            if (connectionDelayTestJob === job) {
                connectionDelayTestPaused = false
                activeConnectionDelayTestId = null
                connectionDelayTestJob = null
                if (state != VpnState.Started) stopSelf()
            }
        }
    }

    private suspend fun awaitConnectionDelayTestResumed(testId: String) {
        while (
            activeConnectionDelayTestId == testId &&
            connectionDelayTestPaused
        ) {
            delay(CONNECTION_DELAY_PAUSE_POLL_INTERVAL_MS)
        }
    }

    private fun setConnectionDelayTestPaused(testId: String, paused: Boolean) {
        if (
            testId.isBlank() ||
            activeConnectionDelayTestId != testId ||
            connectionDelayTestJob?.isActive != true
        ) {
            return
        }
        connectionDelayTestPaused = paused
        ConnectionDelayTestState.update(testId) {
            it.copy(paused = paused)
        }?.let(::publishConnectionDelayTest)
    }

    private fun cancelConnectionDelayTest(testId: String? = null) {
        if (!testId.isNullOrBlank() && activeConnectionDelayTestId != testId) return
        connectionDelayTestPaused = false
        connectionDelayTestJob?.cancel(CancellationException("Connection delay test canceled"))
    }

    private suspend fun cancelConnectionDelayTestAndWait() {
        val job = connectionDelayTestJob ?: return
        connectionDelayTestPaused = false
        job.cancelAndJoin()
        if (connectionDelayTestJob === job) {
            activeConnectionDelayTestId = null
            connectionDelayTestJob = null
        }
    }

    private suspend fun realConnectionDelayMs(
        controller: MihomoControllerClient,
        profile: ConnectionProfile,
    ): Int? {
        connectionDelayMs(controller, profile, MihomoRuntimeDefaults.HEALTH_URL)?.let { return it }
        val fallbackReachable = MihomoRuntimeDefaults.HEALTH_URLS
            .drop(1)
            .any { connectionDelayMs(controller, profile, it) != null }
        DiagnosticLogger.warn(
            this,
            "connection.delay.canonical.unavailable",
            "profile=${profile.tag} fallbackReachable=$fallbackReachable",
        )
        return null
    }

    private suspend fun connectionDelayMs(
        controller: MihomoControllerClient,
        profile: ConnectionProfile,
        url: String,
    ): Int? {
        return try {
            runInterruptible(Dispatchers.IO) {
                MihomoDelayPolicy.acceptedDelayMs(
                    controller.delay(
                        name = profile.tag,
                        timeoutMs = CONNECTION_DELAY_TIMEOUT_MS,
                        url = url,
                    ),
                )?.toInt()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            null
        }
    }

    private fun publishConnectionDelayTest(
        session: ConnectionDelayTestSession,
        finishedFingerprints: List<String> = emptyList(),
    ) {
        sendBroadcast(
            Intent(Actions.CONNECTION_DELAY_TEST_CHANGED)
                .setPackage(packageName)
                .putExtra(Actions.EXTRA_DELAY_TEST_ID, session.testId)
                .putExtra(Actions.EXTRA_DELAY_TEST_STATUS, session.status)
                .putExtra(Actions.EXTRA_DELAY_TEST_COMPLETED, session.completed)
                .putExtra(Actions.EXTRA_DELAY_TEST_TOTAL, session.total)
                .putExtra(Actions.EXTRA_DELAY_TEST_AVAILABLE, session.available)
                .putExtra(Actions.EXTRA_DELAY_TEST_PAUSED, session.paused)
                .putStringArrayListExtra(
                    Actions.EXTRA_DELAY_TEST_FINISHED,
                    ArrayList(finishedFingerprints),
                )
                .putExtra(Actions.EXTRA_DELAY_TEST_ERROR, session.error),
        )
    }

    private fun launchConnectionStartup(
        eventPrefix: String,
        bypassConnectionCache: Boolean = false,
        excludedEndpoint: CleanIpResult? = null,
        preserveRuntimeOnFailure: MihomoRuntimePaths? = null,
        beforeStartup: suspend () -> Unit = {},
    ) {
        startupJob?.cancel(CancellationException("Startup superseded"))
        subscriptionRefreshJob?.cancel()
        encryptedIpScanJob?.cancel()
        cancelPostConnectHealthWatchdog()
        val job = scope.launch {
            try {
                cancelConnectionDelayTestAndWait()
                beforeStartup()
                runConnectionStartup(eventPrefix, bypassConnectionCache, excludedEndpoint)
            } catch (error: CancellationException) {
                DiagnosticLogger.info(this@WhiteDnsVpnService, "$eventPrefix.canceled", error.message.orEmpty())
            } catch (error: Throwable) {
                if (preserveRuntimeOnFailure != null && activeRuntimePaths == preserveRuntimeOnFailure) {
                    keepActiveRuntimeAfterStartupFailure(eventPrefix, error)
                } else {
                    DiagnosticLogger.error(this@WhiteDnsVpnService, "$eventPrefix.failed", error = error)
                    stopAfterFailure(error)
                }
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

        val selectedSubscriptionId = subscriptionStore.readSelectedSubscriptionId()
        val showServer = selectedSubscriptionId != SubscriptionStore.DEFAULT_SUBSCRIPTION_ID
        val snapshot = configRepository.readCachedMihomoConfigOrNull()
            ?: configRepository.fetchOrCachedMihomoConfig()
        ensureStartupActive(eventPrefix)
        val eligibleProfiles = eligibleProfilesForRuntime(snapshot.catalog)
        val requestedExplicitProfile = connectionSelectionPreferenceStore.readSelectedProfile(
            selectedSubscriptionId,
            snapshot.catalog.profiles,
        )
        val selectedAutomaticTypes = connectionSelectionPreferenceStore.readAutomaticTypes(
            selectedSubscriptionId,
            snapshot.catalog.profiles,
        )
        val explicitProfile = requestedExplicitProfile?.let { requested ->
            eligibleProfiles.firstOrNull { it.fingerprint == requested.fingerprint }
        }
        if (requestedExplicitProfile != null && explicitProfile == null) {
            connectionSelectionPreferenceStore.saveSelectedProfile(selectedSubscriptionId, null)
            DiagnosticLogger.warn(
                this,
                "connection.selection.ineligible",
                "profile=${requestedExplicitProfile.tag} fallback=automatic",
            )
        }
        val selectedCountryCode = locationPreferenceStore.readSelectedCountryCode()
            .takeIf { explicitProfile == null }
        val automaticProfiles = ConnectionTypeSelectionPolicy.filterProfiles(
            eligibleProfiles,
            selectedAutomaticTypes,
        )
        if (explicitProfile == null && automaticProfiles.isEmpty()) {
            throw IOException("No eligible connections match the selected types")
        }
        val profiles = explicitProfile?.let(::listOf) ?: profilesForSelectedLocation(
            profiles = automaticProfiles,
            selectedCountryCode = selectedCountryCode,
        )
        DiagnosticLogger.info(
            this,
            "connection.selection",
            "mode=${if (explicitProfile == null) "automatic" else "explicit"} " +
                "profile=${explicitProfile?.tag.orEmpty()} types=${selectedAutomaticTypes.sorted().joinToString(",")}",
        )
        val splitTunnelPlan = resolveSplitTunnelRuntimePlan()
        val frontingIps = frontingIpPreferenceStore.readFrontingIps()
        var startupNotice: String? = null
        val startedRuntime = if (frontingIps.isNotEmpty()) {
            connectWithFrontingIpsOrOriginal(
                eventPrefix = eventPrefix,
                snapshot = snapshot,
                profiles = profiles,
                splitTunnelPlan = splitTunnelPlan,
                selectedCountryCode = selectedCountryCode,
                excludedEndpoint = excludedEndpoint,
                frontingIps = frontingIps,
            ).also { result ->
                startupNotice = result.second
            }.first
        } else if (bypassConnectionCache) {
            connectWithTopIpCandidates(
                eventPrefix = eventPrefix,
                snapshot = snapshot,
                profiles = profiles,
                splitTunnelPlan = splitTunnelPlan,
                selectedCountryCode = selectedCountryCode,
                bypassConnectionCache = bypassConnectionCache,
                excludedEndpoint = excludedEndpoint,
                frontingIps = frontingIps,
            )
        } else {
            connectWithOriginalOrTopIpCandidates(
                eventPrefix = eventPrefix,
                snapshot = snapshot,
                profiles = profiles,
                splitTunnelPlan = splitTunnelPlan,
                selectedCountryCode = selectedCountryCode,
                excludedEndpoint = excludedEndpoint,
            )
        }
        applyStartedRuntime(
            startedRuntime = startedRuntime,
            eventPrefix = eventPrefix,
            notice = startupNotice,
            showServer = showServer,
        )
        startBackgroundEncryptedIpScan(profiles)
    }

    private suspend fun connectWithFrontingIpsOrOriginal(
        eventPrefix: String,
        snapshot: MihomoSubscriptionSnapshot,
        profiles: List<ConnectionProfile>,
        splitTunnelPlan: SplitTunnelRuntimePlan,
        selectedCountryCode: String?,
        excludedEndpoint: CleanIpResult?,
        frontingIps: List<String>,
    ): Pair<StartedMihomoRuntime, String?> {
        return try {
            connectWithTopIpCandidates(
                eventPrefix = eventPrefix,
                snapshot = snapshot,
                profiles = profiles,
                splitTunnelPlan = splitTunnelPlan,
                selectedCountryCode = selectedCountryCode,
                bypassConnectionCache = false,
                excludedEndpoint = excludedEndpoint,
                frontingIps = frontingIps,
                useCachedFirst = false,
            ) to null
        } catch (error: Throwable) {
            if (
                error is CancellationException ||
                error is MihomoCoreBusyException ||
                error is MihomoCoreSetupTimeoutException
            ) {
                throw error
            }
            DiagnosticLogger.warn(this, "frontingIp.fallback.original", "ips=${frontingIps.size}", error)
            val startedRuntime = connectWithOriginalOrTopIpCandidates(
                eventPrefix = eventPrefix,
                snapshot = snapshot,
                profiles = profiles,
                splitTunnelPlan = splitTunnelPlan,
                selectedCountryCode = selectedCountryCode,
                excludedEndpoint = excludedEndpoint,
            )
            val notice = if (startedRuntime.cacheEndpoint) {
                FRONTING_AUTOMATIC_FALLBACK_NOTICE
            } else {
                FRONTING_FALLBACK_NOTICE
            }
            startedRuntime to notice
        }
    }

    private suspend fun connectWithOriginalOrTopIpCandidates(
        eventPrefix: String,
        snapshot: MihomoSubscriptionSnapshot,
        profiles: List<ConnectionProfile>,
        splitTunnelPlan: SplitTunnelRuntimePlan,
        selectedCountryCode: String?,
        excludedEndpoint: CleanIpResult?,
    ): StartedMihomoRuntime {
        return connectWithStartupFallback(
            primary = {
                startMihomoRuntimeAttempt(
                    snapshot = snapshot,
                    splitTunnelPlan = splitTunnelPlan,
                    selectedCountryCode = selectedCountryCode,
                    topEndpoint = null,
                )
            },
            fallback = { error ->
                DiagnosticLogger.warn(
                    this,
                    "startup.original.fallback.topIp",
                    "source=$eventPrefix",
                    error,
                )
                stopActiveCoreForReplacement()
                connectWithTopIpCandidates(
                    eventPrefix = eventPrefix,
                    snapshot = snapshot,
                    profiles = profiles,
                    splitTunnelPlan = splitTunnelPlan,
                    selectedCountryCode = selectedCountryCode,
                    bypassConnectionCache = false,
                    excludedEndpoint = excludedEndpoint,
                    frontingIps = emptyList(),
                )
            },
        )
    }

    private suspend fun connectWithTopIpCandidates(
        eventPrefix: String,
        snapshot: MihomoSubscriptionSnapshot,
        profiles: List<ConnectionProfile>,
        splitTunnelPlan: SplitTunnelRuntimePlan,
        selectedCountryCode: String?,
        bypassConnectionCache: Boolean,
        excludedEndpoint: CleanIpResult?,
        frontingIps: List<String> = frontingIpPreferenceStore.readFrontingIps(),
        useCachedFirst: Boolean = true,
    ): StartedMihomoRuntime {
        if (frontingIps.isEmpty() && useCachedFirst) {
            cachedTopIpCandidatePlan(profiles, excludedEndpoint)?.let { cachedPlan ->
                try {
                    return connectStartupTopIpCandidates(
                        eventPrefix = eventPrefix,
                        candidatePlan = cachedPlan,
                        snapshot = snapshot,
                        splitTunnelPlan = splitTunnelPlan,
                        selectedCountryCode = selectedCountryCode,
                    )
                } catch (error: Throwable) {
                    if (
                        error is CancellationException ||
                        error is MihomoCoreBusyException ||
                        error is MihomoCoreSetupTimeoutException
                    ) {
                        throw error
                    }
                    DiagnosticLogger.warn(this, "startup.cachedTopIp.failed", "source=$eventPrefix", error)
                }
            }
        }
        val candidatePlan = findStartupTopIpCandidates(
            profiles = profiles,
            bypassConnectionCache = if (frontingIps.isEmpty()) true else bypassConnectionCache,
            excludedEndpoint = excludedEndpoint,
            frontingIps = frontingIps,
        )
        return connectStartupTopIpCandidates(
            eventPrefix = eventPrefix,
            candidatePlan = candidatePlan,
            snapshot = snapshot,
            splitTunnelPlan = splitTunnelPlan,
            selectedCountryCode = selectedCountryCode,
        )
    }

    private fun cachedTopIpCandidatePlan(
        profiles: List<ConnectionProfile>,
        excludedEndpoint: CleanIpResult?,
    ): StartupTopIpCandidatePlan? {
        val candidates = StartupScanPolicy.cachedEncryptedCandidates(
            subscriptionPorts = profiles.map { it.port },
            lastEndpoint = scanStateStore.readLastEndpoint(),
            cachedResults = cleanIpCache.readResults(),
            excludedEndpoint = excludedEndpoint,
        )
        if (candidates.isEmpty()) return null
        DiagnosticLogger.info(
            this,
            "startup.cachedTopIp.ready",
            "candidates=${candidates.size} best=${candidates.first().ip}:${candidates.first().port} latencyMs=${candidates.first().latencyMs}",
        )
        return StartupTopIpCandidatePlan(
            primaryPhase = "cached",
            primaryCandidates = candidates,
            exhaustiveCandidates = emptyList(),
        )
    }

    private suspend fun connectStartupTopIpCandidates(
        eventPrefix: String,
        candidatePlan: StartupTopIpCandidatePlan,
        snapshot: MihomoSubscriptionSnapshot,
        splitTunnelPlan: SplitTunnelRuntimePlan,
        selectedCountryCode: String?,
    ): StartedMihomoRuntime {
        return connectStartupTopIpCandidates(
            eventPrefix = eventPrefix,
            phase = candidatePlan.primaryPhase,
            candidates = candidatePlan.primaryCandidates + candidatePlan.exhaustiveCandidates,
            snapshot = snapshot,
            splitTunnelPlan = splitTunnelPlan,
            selectedCountryCode = selectedCountryCode,
        )
    }

    private suspend fun connectStartupTopIpCandidates(
        eventPrefix: String,
        phase: String,
        candidates: List<CleanIpResult>,
        snapshot: MihomoSubscriptionSnapshot,
        splitTunnelPlan: SplitTunnelRuntimePlan,
        selectedCountryCode: String?,
    ): StartedMihomoRuntime {
        if (candidates.isEmpty()) {
            throw IOException("No $phase top-IP candidates are available")
        }
        val quarantineScope = ProfileFingerprint.sha256(snapshot.rawConfig)
        val availableCandidates = if (tlsIntegrityPreferenceStore.isEnabled()) {
            candidates.filterNot { scanStateStore.isTlsEndpointQuarantined(quarantineScope, it) }
        } else {
            candidates
        }.take(CleanIpDefaults.STARTUP_RUNTIME_ATTEMPTS.coerceAtLeast(1))
        if (availableCandidates.isEmpty()) {
            throw IOException("All $phase top-IP candidates are quarantined by the TLS integrity check")
        }

        var lastFailure: Throwable? = null
        for ((index, endpoint) in availableCandidates.withIndex()) {
            ensureStartupActive(eventPrefix)
            val attempt = index + 1
            try {
                DiagnosticLogger.info(
                    this,
                    "startup.topIp.try",
                    "phase=$phase attempt=$attempt/${availableCandidates.size} endpoint=${endpoint.ip}:${endpoint.port} latencyMs=${endpoint.latencyMs} lossRate=${endpoint.lossRate}",
                )
                return startMihomoRuntimeAttempt(
                    snapshot = snapshot,
                    splitTunnelPlan = splitTunnelPlan,
                    selectedCountryCode = selectedCountryCode,
                    topEndpoint = endpoint,
                ).also { startedRuntime ->
                    DiagnosticLogger.info(
                        this,
                        "startup.topIp.connected",
                        "phase=$phase attempt=$attempt/${availableCandidates.size} endpoint=${startedRuntime.endpoint.ip}:${startedRuntime.endpoint.port}",
                    )
                }
            } catch (error: Throwable) {
                if (error is CancellationException) throw error
                lastFailure = error
                if (error is TlsIntegrityException) {
                    scanStateStore.quarantineTlsEndpoint(quarantineScope, endpoint)
                    DiagnosticLogger.warn(
                        this,
                        "tlsIntegrity.quarantined",
                        "phase=$phase endpoint=${endpoint.ip}:${endpoint.port}",
                        error,
                    )
                }
                DiagnosticLogger.warn(
                    this,
                    "startup.topIp.rejected",
                    "phase=$phase attempt=$attempt/${availableCandidates.size} endpoint=${endpoint.ip}:${endpoint.port}",
                    error,
                )
                if (!stopCoreService()) {
                    throw MihomoCoreBusyException(error)
                }
                if (error is MihomoCoreSetupTimeoutException) {
                    throw error
                }
            }
        }
        throw IOException("No $phase top-IP candidate passed Mihomo delay and runtime health check", lastFailure)
    }

    private suspend fun startMihomoRuntimeAttempt(
        snapshot: MihomoSubscriptionSnapshot,
        splitTunnelPlan: SplitTunnelRuntimePlan,
        selectedCountryCode: String?,
        topEndpoint: CleanIpResult?,
        validateConnectivity: Boolean = true,
    ): StartedMihomoRuntime {
        val dpiBypassEnabled = dpiBypassPreferenceStore.isEnabled()
        return try {
            startMihomoRuntimeAttemptOnce(
                snapshot = snapshot,
                splitTunnelPlan = splitTunnelPlan,
                selectedCountryCode = selectedCountryCode,
                topEndpoint = topEndpoint,
                validateConnectivity = validateConnectivity,
                dpiBypassEnabled = dpiBypassEnabled,
            )
        } catch (error: DpiBypassStartupException) {
            DiagnosticLogger.warn(
                this,
                "dpiBypass.fallback.direct",
                "endpoint=${topEndpoint?.let { "${it.ip}:${it.port}" } ?: "original"}",
                error,
            )
            stopActiveCoreForReplacement()
            startMihomoRuntimeAttemptOnce(
                snapshot = snapshot,
                splitTunnelPlan = splitTunnelPlan,
                selectedCountryCode = selectedCountryCode,
                topEndpoint = topEndpoint,
                validateConnectivity = validateConnectivity,
                dpiBypassEnabled = false,
            )
        }
    }

    private suspend fun startMihomoRuntimeAttemptOnce(
        snapshot: MihomoSubscriptionSnapshot,
        splitTunnelPlan: SplitTunnelRuntimePlan,
        selectedCountryCode: String?,
        topEndpoint: CleanIpResult?,
        validateConnectivity: Boolean,
        dpiBypassEnabled: Boolean,
    ): StartedMihomoRuntime {
        if (activeRuntimePaths != null || !coreLifecycle.isIdle()) {
            stopActiveCoreForReplacement()
        }
        val serverOverrideIp = topEndpoint?.ip
        val serverOverridePort = topEndpoint?.let { endpoint ->
            FrontingIpPolicy.explicitPortFor(
                frontingIpPreferenceStore.readFrontingIps(),
                endpoint.ip,
                endpoint.port,
            )
        }
        val frontedYaml = MihomoFrontingPatcher.patchProxyServers(
            rawYaml = snapshot.rawConfig,
            serverOverrideIp = serverOverrideIp,
            serverOverridePort = serverOverridePort,
        )
        val connectionOptions = connectionOptionsPreferenceStore.read()
        val optionsYaml = MihomoConnectionOptionsPatcher.patch(frontedYaml, connectionOptions)
        val dnsPrivacyMode = dnsPrivacyPreferenceStore.readMode()
        val dpiBypassPort = if (dpiBypassEnabled) DpiBypassPort.allocate() else null
        val runtimeYaml = MihomoDpiBypassPatcher.patch(
            rawYaml = optionsYaml,
            enabled = dpiBypassEnabled,
            proxyPort = dpiBypassPort ?: DpiBypassDefaults.FALLBACK_PROXY_PORT,
        )
        val paths = MihomoRuntimeConfigBuilder(this).write(
            rawYaml = runtimeYaml,
            splitTunnelPlan = splitTunnelPlan,
            dnsPrivacyMode = dnsPrivacyMode,
            dohUrl = dnsPrivacyPreferenceStore.readDohUrl(),
            dotEndpoint = dnsPrivacyPreferenceStore.readDotEndpoint(),
        )
        DiagnosticLogger.info(
            this,
            "mihomo.runtime.files",
            "config=${paths.runtimeConfigYaml.absolutePath} service=${paths.serviceJson.absolutePath} patch=${paths.patchFinalJson.absolutePath} controller=${MihomoRuntimeDefaults.CONTROLLER_HOST}:${paths.controlPort} serverOverride=${serverOverrideIp.orEmpty()} portOverride=${serverOverridePort ?: 0}",
        )
        DiagnosticLogger.info(
            this,
            "mihomo.startup.flow",
            "frontingScanner=${serverOverrideIp != null} dpiBypass=$dpiBypassEnabled dpiBypassPort=${dpiBypassPort ?: 0} dnsPrivacy=${dnsPrivacyMode.wireName} tlsIntegrity=${tlsIntegrityPreferenceStore.isEnabled()} amneziaNoise=${connectionOptions.amneziaNoiseEnabled} randomController=true encryptedSubscription=true profiles=${snapshot.summary.proxies.size} groups=${snapshot.summary.groups.size}",
        )

        startCoreServiceAndWait(paths, splitTunnelPlan, dpiBypassPort)
        activeRuntimePaths = paths
        val controller = MihomoControllerClient(paths.secret, port = paths.controlPort)
        waitForController(controller, paths)
        val selectedSubscriptionId = subscriptionStore.readSelectedSubscriptionId()
        val explicitProfile = connectionSelectionPreferenceStore.readSelectedProfile(
            selectedSubscriptionId,
            snapshot.catalog.profiles,
        )
        val selectedAutomaticTypes = connectionSelectionPreferenceStore.readAutomaticTypes(
            selectedSubscriptionId,
            snapshot.catalog.profiles,
        )
        val preferredSelectorRoots = listOfNotNull(
            MihomoSelectionPolicy.trafficProbeGroup(snapshot.summary)?.name,
            MihomoSelectionPolicy.mainSelectorGroup(snapshot.summary)?.name,
        )
        var scopedAutomaticHealthVerified = false
        val directSelections = if (explicitProfile != null) {
            val liveProxies = withContext(Dispatchers.IO) { controller.getProxies() }
            MihomoControllerProxies.selectorPath(
                response = liveProxies,
                targetName = explicitProfile.tag,
                preferredRoots = preferredSelectorRoots,
            )
        } else {
            val candidates = ConnectionLocationPolicy.filterProfiles(
                profiles = ConnectionTypeSelectionPolicy.filterProfiles(
                    eligibleProfilesForRuntime(snapshot.catalog),
                    selectedAutomaticTypes,
                ),
                selectedCountryCode = selectedCountryCode,
            ).profiles
            if (candidates.isEmpty()) {
                throw IOException("Selected connection types do not contain an eligible connection")
            }
            val records = withContext(Dispatchers.IO) {
                subscriptionStore.readConnectionDelayRecords(
                    subscriptionId = selectedSubscriptionId,
                    profiles = candidates,
                )
            }
            val orderedCandidates = AutomaticConnectionCandidatePolicy.order(
                profiles = candidates,
                records = records,
                lastSelectedProfile = scanStateStore.readLastSelectedProfile(candidates),
            )
            val liveProxies = withContext(Dispatchers.IO) { controller.getProxies() }
            var selectedPath: List<MihomoGroupSelection> = emptyList()
            var lastFailure: Throwable? = null
            for ((index, candidate) in orderedCandidates.withIndex()) {
                val path = MihomoControllerProxies.selectorPath(
                    response = liveProxies,
                    targetName = candidate.tag,
                    preferredRoots = preferredSelectorRoots,
                )
                if (path.isEmpty()) continue
                try {
                    path.forEach { selection ->
                        withContext(Dispatchers.IO) {
                            controller.selectProxy(selection.selectorGroup, selection.selectedGroup)
                        }
                    }
                    verifyRuntimeHealth()
                    scopedAutomaticHealthVerified = true
                    selectedPath = path
                    DiagnosticLogger.info(
                        this,
                        "mihomo.selection.automatic.healthy",
                        "profile=${candidate.tag} attempt=${index + 1}/${orderedCandidates.size}",
                    )
                    break
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    lastFailure = error
                    DiagnosticLogger.warn(
                        this,
                        "mihomo.selection.automatic.rejected",
                        "profile=${candidate.tag} attempt=${index + 1}/${orderedCandidates.size}",
                        error,
                    )
                }
            }
            if (selectedPath.isEmpty()) {
                if (selectedAutomaticTypes.isNotEmpty()) {
                    throw IOException(
                        "No connection from the selected types passed the runtime health check",
                        lastFailure,
                    )
                }
                DiagnosticLogger.warn(
                    this,
                    "mihomo.selection.automatic.fallback",
                    "reason=no healthy selectable leaf; using subscription automatic group",
                    lastFailure,
                )
            }
            selectedPath
        }
        val automaticSelections = MihomoSelectionPolicy.desiredSelections(snapshot.summary, selectedCountryCode)
        if (explicitProfile != null && directSelections.isEmpty()) {
            connectionSelectionPreferenceStore.saveSelectedProfile(selectedSubscriptionId, null)
            DiagnosticLogger.warn(
                this,
                "mihomo.selection.explicit.unsupported",
                "profile=${explicitProfile.tag} fallback=automatic",
            )
        }
        val selections = directSelections.ifEmpty { automaticSelections }
        if (!scopedAutomaticHealthVerified) {
            selections.forEach { selection ->
                withContext(Dispatchers.IO) {
                    controller.selectProxy(selection.selectorGroup, selection.selectedGroup)
                }
                DiagnosticLogger.info(
                    this,
                    "mihomo.selection.applied",
                    "selector=${selection.selectorGroup} selected=${selection.selectedGroup}",
                )
            }
        }
        if (selections.isEmpty()) {
            DiagnosticLogger.info(
                this,
                "mihomo.selection.skipped",
                "country=${selectedCountryCode ?: "auto"} groups=${snapshot.summary.groups.size}",
            )
        }

        val selection = selections.firstOrNull()
        val selectedName = directSelections.lastOrNull()?.selectorGroup
            ?: MihomoSelectionPolicy.trafficProbeGroup(snapshot.summary)?.name
            ?: selection?.selectedGroup
            ?: snapshot.summary.proxies.firstOrNull()?.name.orEmpty()
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
        val delayProbeName = activeProxyName?.takeIf(String::isNotBlank) ?: selectedName
        if (delayProbeName.isBlank()) {
            throw IOException("No Mihomo proxy selected for delay test")
        }
        var lastDelayFailure: Throwable? = null
        val delayMs = if (validateConnectivity) {
            MihomoRuntimeDefaults.HEALTH_URLS.firstNotNullOfOrNull { url ->
                runCatching {
                    withContext(Dispatchers.IO) {
                        MihomoDelayPolicy.acceptedDelayMs(
                            controller.delay(
                                delayProbeName,
                                timeoutMs = FOREGROUND_MIHOMO_DELAY_TIMEOUT_MS,
                                url = url,
                            ),
                        )
                    }
                }.onFailure { lastDelayFailure = it }.getOrNull()
            } ?: -1L
        } else {
            -1L
        }
        if (validateConnectivity) {
            if (delayMs > 0) {
                DiagnosticLogger.info(
                    this,
                    "mihomo.delay.ok",
                    "name=$delayProbeName delayMs=$delayMs",
                )
            } else {
                DiagnosticLogger.warn(
                    this,
                    "mihomo.delay.unavailable",
                    "name=$delayProbeName continuingWithHttpHealth=true",
                    lastDelayFailure,
                )
            }
            if (!scopedAutomaticHealthVerified) {
                verifyRuntimeHealth()
            }
        } else {
            DiagnosticLogger.info(this, "mihomo.delay.deferred", "name=$delayProbeName")
        }
        verifyTlsIntegrity()
        DiagnosticLogger.info(
            this,
            "startup.topIp.validated",
            "endpoint=${topEndpoint?.let { "${it.ip}:${it.port}" } ?: "original"} selected=$selectedName active=${activeProxyName.orEmpty()} delayProbe=$delayProbeName delayMs=$delayMs validated=$validateConnectivity",
        )

        val profile = activeProfile(snapshot, selectedCountryCode, selection, activeProxyName)
        val endpoint = if (topEndpoint == null) {
            originalEndpointForSelection(snapshot, selectedCountryCode, activeProxyName, delayMs)
        } else {
            runtimeEndpointForSelection(
                topEndpoint = topEndpoint,
                snapshot = snapshot,
                selectedCountryCode = selectedCountryCode,
                selection = selection,
                activeProxyName = activeProxyName,
                delayMs = delayMs,
            )
        }
        return StartedMihomoRuntime(
            profile = profile,
            endpoint = endpoint,
            delayMs = delayMs,
            paths = paths,
            delayProbeName = delayProbeName,
            cacheEndpoint = topEndpoint != null,
            selectedCountryCode = ConnectionLocationPolicy.normalizeCountryCode(selectedCountryCode),
        )
    }

    private fun applyStartedRuntime(
        startedRuntime: StartedMihomoRuntime,
        eventPrefix: String,
        notice: String? = null,
        showServer: Boolean,
    ) {
        activeProfile = MihomoConnectionOptionsPolicy.applyTo(
            startedRuntime.profile,
            connectionOptionsPreferenceStore.read(),
        )
        activeProfileShowsServer = showServer
        activeEndpoint = startedRuntime.endpoint
        activeDelayMs = startedRuntime.delayMs
        activeRuntimePaths = startedRuntime.paths
        activeConnectionCountryFlag = startedRuntime.selectedCountryCode
            ?.let(ConnectionLocationPolicy::countryFromCode)
            ?.flag
            ?: startedRuntime.profile.let(ConnectionLocationPolicy::countryForProfile)?.flag.orEmpty()
        val configuredFrontingIps = frontingIpPreferenceStore.readFrontingIps()
        activeFrontingIp = FrontingIpPolicy.matchingValue(
            configuredFrontingIps,
            startedRuntime.endpoint.ip,
            startedRuntime.endpoint.port,
        ).orEmpty()
        scanStateStore.saveLastSelectedProfile(
            SelectedConnectionProfile(
                profile = startedRuntime.profile,
                delayMs = startedRuntime.delayMs.takeIf { it > 0 }?.toInt() ?: Int.MAX_VALUE,
                selectedAt = System.currentTimeMillis(),
            ),
        )
        if (
            startedRuntime.cacheEndpoint &&
            FrontingIpPolicy.matchingValue(
                configuredFrontingIps,
                startedRuntime.endpoint.ip,
                startedRuntime.endpoint.port,
            ) == null
        ) {
            cleanIpCache.saveResult(startedRuntime.endpoint)
            scanStateStore.saveLastEndpoint(startedRuntime.endpoint)
        }
        sessionStartedAtElapsedMs = SystemClock.elapsedRealtime()
        publishState(VpnState.Started, notice)
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, serviceNotification(getString(R.string.notification_connected)))
        DiagnosticLogger.info(
            this,
            "$eventPrefix.started",
            "profile=${startedRuntime.profile.tag} endpoint=${startedRuntime.endpoint.ip}:${startedRuntime.endpoint.port} delayMs=${startedRuntime.delayMs}",
        )
        startBackgroundSubscriptionRefresh()
        startPostConnectHealthWatchdog()
        refreshDelayInBackground(startedRuntime)
        if (startedRuntime.selectedCountryCode == null) {
            refreshEgressCountryInBackground(startedRuntime)
        }
    }

    private fun refreshDelayInBackground(startedRuntime: StartedMihomoRuntime) {
        val name = startedRuntime.delayProbeName.takeIf(String::isNotBlank) ?: return
        scope.launch(Dispatchers.IO) {
            val delayMs = runCatching {
                MihomoControllerClient(startedRuntime.paths.secret, port = startedRuntime.paths.controlPort)
                    .delay(name, timeoutMs = BACKGROUND_MIHOMO_DELAY_TIMEOUT_MS)
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
            if (
                startedRuntime.cacheEndpoint &&
                FrontingIpPolicy.matchingValue(
                    frontingIpPreferenceStore.readFrontingIps(),
                    endpoint.ip,
                    endpoint.port,
                ) == null
            ) {
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
        frontingIps: List<String> = frontingIpPreferenceStore.readFrontingIps(),
    ): StartupTopIpCandidatePlan {
        val subscriptionPorts = profiles
            .map { it.port }
            .filter { it > 0 }
        if (subscriptionPorts.isEmpty()) throw IOException("Subscription has no usable proxy ports")

        val tcpProbePorts = StartupScanPolicy.tcpProbePorts(profiles)
        val priorityPorts = StartupScanPolicy.priorityPorts(tcpProbePorts)
        val fallbackPorts = StartupScanPolicy.fallbackPorts(tcpProbePorts)
        val connectionPorts = StartupScanPolicy.orderedConnectionPorts(subscriptionPorts)
        logScanInfo(
            "scanner.startup.ports",
            "priority=$priorityPorts fallback=$fallbackPorts connection=$connectionPorts subscription=${subscriptionPorts.distinct().sorted()} tcpProbe=$tcpProbePorts",
        )

        frontingIps.takeIf { it.isNotEmpty() }?.let { frontingIps ->
            val candidates = StartupScanPolicy.frontingCandidates(
                frontingIps = frontingIps,
                subscriptionPorts = subscriptionPorts,
                checkedAt = System.currentTimeMillis(),
                excludedEndpoint = null,
            )
            if (candidates.isEmpty()) {
                throw IOException("No alternate fronting IP is available")
            }
            DiagnosticLogger.info(
                this,
                "frontingIp.override",
                "enabled=true ips=${frontingIps.size} ports=$connectionPorts candidates=${candidates.size}",
            )
            return StartupTopIpCandidatePlan(
                primaryPhase = "fronting",
                primaryCandidates = candidates,
                exhaustiveCandidates = emptyList(),
            )
        }

        val decryptedIps = runCatching {
            encryptedIpListRepository.fetchIps()
        }.onFailure { error ->
            DiagnosticLogger.warn(this, "encryptedIpList.fetchOrDecrypt.failed", error = error)
        }.getOrDefault(emptyList())
        val exhaustiveCandidates = StartupScanPolicy.exhaustiveEncryptedCandidates(
            candidateIps = decryptedIps,
            subscriptionPorts = subscriptionPorts,
            checkedAt = System.currentTimeMillis(),
            excludedEndpoint = excludedEndpoint,
        )

        val freshCandidates = mutableListOf<CleanIpResult>()
        if (decryptedIps.isNotEmpty()) {
            if (tcpProbePorts.isEmpty()) {
                logScanInfo(
                    "scanner.encryptedTop.tcp.skipped",
                    "reason=wireguardRequiresRuntimeValidation candidates=${exhaustiveCandidates.size}",
                )
                return StartupTopIpCandidatePlan(
                    primaryPhase = "wireguard-runtime",
                    primaryCandidates = exhaustiveCandidates,
                    exhaustiveCandidates = emptyList(),
                )
            }
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
                val untriedExhaustive = StartupScanPolicy.untriedFallbackCandidates(
                    primaryCandidates = connectableFresh,
                    fallbackCandidates = exhaustiveCandidates,
                )
                logScanInfo(
                    "scanner.encryptedTop.quick",
                    "candidates=${connectableFresh.size} exhaustiveFallback=${untriedExhaustive.size} best=${connectableFresh.first().ip}:${connectableFresh.first().port} latencyMs=${connectableFresh.first().latencyMs} lossRate=${connectableFresh.first().lossRate} cacheAfterRuntimeValidation=${!bypassConnectionCache}",
                )
                return StartupTopIpCandidatePlan(
                    primaryPhase = "quick",
                    primaryCandidates = connectableFresh,
                    exhaustiveCandidates = untriedExhaustive,
                )
            }
            logScanWarn(
                "scanner.encryptedTop.quick.empty",
                "ips=${decryptedIps.size} priority=$priorityPorts fallback=$fallbackPorts",
            )
            if (exhaustiveCandidates.isNotEmpty()) {
                logScanInfo(
                    "scanner.encryptedTop.exhaustive.ready",
                    "candidates=${exhaustiveCandidates.size} ips=${decryptedIps.size} ports=$connectionPorts",
                )
                return StartupTopIpCandidatePlan(
                    primaryPhase = "quick",
                    primaryCandidates = emptyList(),
                    exhaustiveCandidates = exhaustiveCandidates,
                )
            }
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
            return StartupTopIpCandidatePlan(
                primaryPhase = "cached",
                primaryCandidates = listOf(cached),
                exhaustiveCandidates = emptyList(),
            )
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

    private fun originalEndpointForSelection(
        snapshot: MihomoSubscriptionSnapshot,
        selectedCountryCode: String?,
        activeProxyName: String?,
        delayMs: Long,
    ): CleanIpResult {
        val countryCode = ConnectionLocationPolicy.normalizeCountryCode(selectedCountryCode)
        val countryProxy = countryCode?.let { code ->
            snapshot.summary.proxies.firstOrNull { proxy ->
                ConnectionLocationPolicy.countryFromText(proxy.name, proxy.server)?.code == code
            }
        }
        val proxy = countryProxy ?: activeProxyName
            ?.let { name -> snapshot.summary.proxies.firstOrNull { proxy -> proxy.name == name } }
            ?: snapshot.summary.proxies.firstOrNull()
        return CleanIpResult(
            ip = proxy?.server ?: MihomoRuntimeDefaults.CONTROLLER_HOST,
            port = proxy?.port?.takeIf { it > 0 } ?: MihomoRuntimeDefaults.MIXED_PORT,
            latencyMs = delayMs,
            lossRate = 0.0,
            checkedAt = System.currentTimeMillis(),
        )
    }

    private fun runtimePortForSelection(
        snapshot: MihomoSubscriptionSnapshot,
        selectedCountryCode: String?,
        selection: MihomoGroupSelection?,
        activeProxyName: String?,
    ): Int? {
        val countryCode = ConnectionLocationPolicy.normalizeCountryCode(selectedCountryCode)
        val countryProfile = countryCode?.let { code ->
            snapshot.catalog.profiles.firstOrNull { profile ->
                ConnectionLocationPolicy.countryForProfile(profile)?.code == code
            }
        }
        if (countryProfile?.port?.takeIf { it > 0 } != null) return countryProfile.port
        val activeProxy = activeProxyName?.let { name ->
            snapshot.summary.proxies.firstOrNull { proxy -> proxy.name == name }
        }
        if (activeProxy?.port?.takeIf { it > 0 } != null) return activeProxy.port
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
        if (state == VpnState.Starting || state == VpnState.Started) return
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

    private suspend fun startDpiBypassProxy(port: Int): Int {
        stopDpiBypassProxy()

        val protectCallback = object : TunInterface {
            override fun protect(fd: Int) {
                check(this@WhiteDnsVpnService.protect(fd)) {
                    "Android refused to protect ByeByeDPI socket $fd"
                }
            }

            override fun resolverProcess(protocol: Int, source: String, target: String, uid: Int): String = ""
        }
        val job = scope.launch(Dispatchers.IO) {
            val result = runCatching {
                ByeDpiProxy.start(port, protectCallback)
            }
            result
                .onSuccess { exitCode ->
                    if (exitCode != 0 && activeDpiBypassPort == port) {
                        DiagnosticLogger.warn(
                            this@WhiteDnsVpnService,
                            "dpiBypass.proxy.exited",
                            "port=$port code=$exitCode",
                        )
                    }
                }
                .onFailure { error ->
                    if (activeDpiBypassPort == port) {
                        DiagnosticLogger.warn(
                            this@WhiteDnsVpnService,
                            "dpiBypass.proxy.failed",
                            "port=$port",
                            error,
                        )
                    }
                }
        }
        dpiBypassJob = job
        activeDpiBypassPort = port

        if (!waitForDpiBypassPort(port, job)) {
            stopDpiBypassProxy()
            throw IOException("ByeByeDPI proxy did not start on ${DpiBypassDefaults.PROXY_HOST}:$port")
        }

        val healthStatus = withContext(Dispatchers.IO) {
            MihomoRuntimeDefaults.HEALTH_URLS.firstNotNullOfOrNull { url ->
                runCatching {
                    MihomoRuntimeHealth.httpStatusThroughSocksProxy(
                        port = port,
                        url = url,
                        timeoutMs = TlsIntegrityPolicy.PROBE_TIMEOUT_MS,
                    )
                }.getOrNull()?.takeIf { code -> code == 204 || code in 200..399 }
            }
        } ?: throw IOException(
            "ByeByeDPI proxy could not reach any connectivity-check endpoint on ${DpiBypassDefaults.PROXY_HOST}:$port",
        )

        DiagnosticLogger.info(
            this,
            "dpiBypass.proxy.started",
            "endpoint=${DpiBypassDefaults.PROXY_HOST}:$port health=$healthStatus",
        )
        return port
    }

    private suspend fun waitForDpiBypassPort(port: Int, job: Job): Boolean {
        return withTimeoutOrNull(DPI_BYPASS_START_TIMEOUT_MS) {
            while (job.isActive) {
                if (!DpiBypassPort.canBind(port)) return@withTimeoutOrNull true
                delay(DPI_BYPASS_PORT_POLL_INTERVAL_MS)
            }
            false
        } == true
    }

    private suspend fun stopDpiBypassProxy() {
        val job = dpiBypassJob ?: return
        val port = activeDpiBypassPort
        dpiBypassJob = null
        activeDpiBypassPort = null

        withContext(Dispatchers.IO) {
            runCatching { ByeDpiProxy.stop() }
                .onFailure { DiagnosticLogger.warn(this@WhiteDnsVpnService, "dpiBypass.proxy.stop.failed", error = it) }
        }
        val stopped = withTimeoutOrNull(DPI_BYPASS_STOP_TIMEOUT_MS) {
            job.join()
            true
        } == true
        if (!stopped) {
            job.cancel(CancellationException("ByeByeDPI proxy stop timed out"))
            DiagnosticLogger.warn(this, "dpiBypass.proxy.stop.timeout", "port=${port ?: 0}")
            return
        }
        DiagnosticLogger.info(this, "dpiBypass.proxy.stopped", "port=${port ?: 0}")
    }

    private suspend fun startCoreServiceAndWait(
        paths: MihomoRuntimePaths,
        splitTunnelPlan: SplitTunnelRuntimePlan,
        dpiBypassPort: Int? = null,
    ) {
        setupCore(paths)
        if (dpiBypassPort != null) {
            try {
                startDpiBypassProxy(dpiBypassPort)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                throw DpiBypassStartupException(error)
            }
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
                dns = MIHOMO_TUN_DNS_SERVER,
            )
        }
        DiagnosticLogger.info(this, "mihomo.core.started", "config=${paths.runtimeConfigYaml.absolutePath}")
    }

    private suspend fun setupCore(paths: MihomoRuntimePaths) {
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
    }

    private suspend fun quickSetupCore(
        initParams: String,
        setupParams: String,
    ): String {
        if (!coreLifecycle.beginSetup()) {
            throw MihomoCoreBusyException()
        }
        val deferred = CompletableDeferred<String?>()
        try {
            withContext(NonCancellable + Dispatchers.IO) {
                Core.quickSetup(initParams, setupParams) { result ->
                    val completion = coreLifecycle.finishSetup()
                    if (!deferred.isCompleted) {
                        deferred.complete(result)
                    }
                    if (completion == MihomoCoreSetupCompletion.CleanupClaimed) {
                        DiagnosticLogger.info(
                            this@WhiteDnsVpnService,
                            "mihomo.cleanup.deferred.start",
                            "reason=setupCompletedAfterStopRequest",
                        )
                        coreCleanupScope.launch {
                            cleanupClaimedCore("setupCompletedAfterStopRequest")
                        }
                    }
                }
            }
        } catch (error: Throwable) {
            if (error !is CancellationException) {
                DiagnosticLogger.warn(this, "mihomo.core.setup.dispatch.failed", error = error)
            }
            throw error
        }

        val initialResult = withTimeoutOrNull(CORE_SETUP_SLOW_WARNING_MS) {
            deferred.await()
        }
        if (initialResult != null || deferred.isCompleted) {
            if (!coreLifecycle.isActive()) throw MihomoCoreBusyException()
            return initialResult.orEmpty()
        }

        DiagnosticLogger.warn(
            this,
            "mihomo.core.setup.slow",
            "elapsedMs=$CORE_SETUP_SLOW_WARNING_MS continuing=true",
        )
        val result = withTimeoutOrNull(CORE_SETUP_HARD_TIMEOUT_MS - CORE_SETUP_SLOW_WARNING_MS) {
            deferred.await()
        }
        if (result == null && !deferred.isCompleted) {
            throw MihomoCoreSetupTimeoutException()
        }
        if (!coreLifecycle.isActive()) throw MihomoCoreBusyException()
        return result.orEmpty()
    }

    private suspend fun establishTun(splitTunnelPlan: SplitTunnelRuntimePlan): Int {
        for (attempt in 1..TUN_ESTABLISH_ATTEMPTS) {
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
            builder.establish()?.detachFd()?.let { return it }
            if (attempt < TUN_ESTABLISH_ATTEMPTS) {
                DiagnosticLogger.warn(this, "mihomo.tun.establish.retry", "attempt=$attempt/$TUN_ESTABLISH_ATTEMPTS")
                delay(TUN_ESTABLISH_RETRY_DELAY_MS)
            }
        }
        throw IOException("Android rejected the VPN tunnel")
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
        timeoutMs: Long = CONTROLLER_READY_TIMEOUT_MS,
    ) {
        DiagnosticLogger.info(
            this,
            "mihomo.controller.wait.start",
            "endpoint=${controller.endpoint} timeoutMs=$timeoutMs runtimeConfigBytes=${paths.runtimeConfigYaml.length()} profileBytes=${paths.profileYaml.length()} serviceBytes=${paths.serviceJson.length()} patchBytes=${paths.patchFinalJson.length()}",
        )
        val ready = withTimeoutOrNull(timeoutMs) {
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

    private fun runtimeHealthStatus(): Int {
        return MihomoRuntimeDefaults.HEALTH_URLS.firstNotNullOfOrNull { url ->
            runCatching { MihomoRuntimeHealth.httpStatusThroughMixedProxy(url) }
                .getOrNull()
                ?.takeIf { code -> code == 204 || code in 200..399 }
        } ?: -1
    }

    private suspend fun verifyRuntimeHealth() {
        val proxyCode = waitForHealthyStatus("mihomo.proxy.health") {
            runtimeHealthStatus()
        }
            ?: throw IOException("Local Mihomo proxy did not pass health check at 127.0.0.1:2080")
        DiagnosticLogger.info(this, "mihomo.proxy.health.ok", "code=$proxyCode")
    }

    private suspend fun verifyTlsIntegrity() {
        if (!tlsIntegrityPreferenceStore.isEnabled()) return
        val completed = withTimeoutOrNull(TlsIntegrityPolicy.TOTAL_TIMEOUT_MS) {
            var lastFailure: Throwable? = null
            for (url in TlsIntegrityPolicy.TEST_URLS) {
                try {
                    val code = withContext(Dispatchers.IO) {
                        MihomoRuntimeHealth.httpStatusThroughMixedProxy(
                            url = url,
                            timeoutMs = TlsIntegrityPolicy.PROBE_TIMEOUT_MS,
                        )
                    }
                    DiagnosticLogger.info(
                        this@WhiteDnsVpnService,
                        "tlsIntegrity.ok",
                        "url=$url code=$code",
                    )
                    return@withTimeoutOrNull
                } catch (error: Throwable) {
                    if (error is CancellationException) throw error
                    if (TlsIntegrityPolicy.isCertificateFailure(error)) {
                        DiagnosticLogger.warn(
                            this@WhiteDnsVpnService,
                            "tlsIntegrity.failed",
                            "url=$url",
                            error,
                        )
                        throw TlsIntegrityException(error)
                    }
                    lastFailure = error
                    DiagnosticLogger.warn(
                        this@WhiteDnsVpnService,
                        "tlsIntegrity.probe.unreachable",
                        "url=$url",
                        error,
                    )
                }
            }
            DiagnosticLogger.warn(
                this@WhiteDnsVpnService,
                "tlsIntegrity.inconclusive",
                "all probes unreachable; allowing connection",
                lastFailure,
            )
        }
        if (completed == null) {
            DiagnosticLogger.warn(
                this,
                "tlsIntegrity.inconclusive",
                "probe deadline reached; allowing connection",
            )
        }
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
        val countryCode = ConnectionLocationPolicy.normalizeCountryCode(selectedCountryCode)
        val countryProfile = countryCode?.let { code ->
            snapshot.catalog.profiles.firstOrNull { profile ->
                ConnectionLocationPolicy.countryForProfile(profile)?.code == code
            }
        }
        if (countryProfile != null) return countryProfile

        activeProxyName?.let { name ->
            snapshot.catalog.profiles.firstOrNull { profile -> profile.tag == name }?.let { return it }
        }

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

    private fun stopVpn(force: Boolean = false) {
        if (!force && alwaysOnActive) {
            DiagnosticLogger.info(this, "disconnect.ignored", "reason=alwaysOn lockdown=$lockdownActive")
            publishState(state)
            return
        }
        if (state == VpnState.Stopped) {
            if (connectionDelayTestJob != null) {
                cancelConnectionDelayTest()
                return
            }
            publishState(VpnState.Stopped)
            stopSelf()
            return
        }
        if (state == VpnState.Stopping) return
        DiagnosticLogger.info(this, "disconnect.start", "state=${state.wireName}")
        startupJob?.cancel(CancellationException("Disconnect requested"))
        subscriptionRefreshJob?.cancel()
        encryptedIpScanJob?.cancel()
        cancelPostConnectHealthWatchdog()
        publishState(VpnState.Stopping)
        stopJob?.cancel()
        stopJob = scope.launch {
            cancelConnectionDelayTestAndWait()
            stopCoreService()
            finishStoppedState("disconnect.stopped")
        }
    }

    private suspend fun stopAfterFailure(error: Throwable) {
        subscriptionRefreshJob?.cancel()
        encryptedIpScanJob?.cancel()
        cancelPostConnectHealthWatchdog()
        stopCoreService()
        sessionStartedAtElapsedMs = 0L
        activeProfile = null
        activeProfileShowsServer = false
        activeDelayMs = -1L
        activeRuntimePaths = null
        activeEndpoint = null
        activeConnectionCountryFlag = ""
        activeFrontingIp = ""
        runCatching { networkMonitor.stop() }
        stopForegroundCompat()
        publishState(VpnState.Error(error.message ?: "Unable to start VPN"))
        stopSelf()
    }

    private fun keepActiveRuntimeAfterStartupFailure(eventPrefix: String, error: Throwable) {
        DiagnosticLogger.warn(this, "$eventPrefix.failed.keptActive", error = error)
        publishState(VpnState.Started)
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, serviceNotification(getString(R.string.notification_connected)))
        startBackgroundSubscriptionRefresh()
        startPostConnectHealthWatchdog()
    }

    private suspend fun stopActiveCoreForReplacement() {
        if (!stopCoreService()) {
            throw MihomoCoreBusyException()
        }
        activeRuntimePaths = null
    }

    private suspend fun stopCoreService(): Boolean {
        val coreStopped = when (coreLifecycle.requestCleanup()) {
            MihomoCoreCleanupRequest.Claimed -> {
                coreCleanupScope.launch {
                    cleanupClaimedCore("serviceRequest")
                }
                awaitCoreIdle(CORE_SHUTDOWN_WAIT_TIMEOUT_MS)
            }
            MihomoCoreCleanupRequest.PendingSetup -> {
                DiagnosticLogger.warn(this, "mihomo.cleanup.deferred", "reason=setupRace")
                awaitCoreIdle(CORE_SETUP_CLEANUP_GRACE_MS)
            }
            MihomoCoreCleanupRequest.AlreadyStopping -> awaitCoreIdle(CORE_SHUTDOWN_WAIT_TIMEOUT_MS)
            MihomoCoreCleanupRequest.AlreadyIdle -> true
        }
        stopDpiBypassProxy()
        return coreStopped
    }

    private suspend fun awaitCoreIdle(timeoutMs: Long): Boolean {
        return withTimeoutOrNull(timeoutMs) {
            while (!coreLifecycle.isIdle()) {
                delay(CORE_LIFECYCLE_POLL_INTERVAL_MS)
            }
            true
        } == true
    }

    private suspend fun cleanupClaimedCore(reason: String): Boolean {
        DiagnosticLogger.info(this, "mihomo.cleanup.start", "reason=$reason")
        withContext(Dispatchers.IO) {
            runCatching { Core.stopTun() }
                .onFailure { DiagnosticLogger.warn(this@WhiteDnsVpnService, "mihomo.tun.stop.failed", error = it) }
        }
        val completion = withContext(Dispatchers.IO) {
            beginCoreShutdown(reason)
        } ?: return false

        val completedQuickly = withTimeoutOrNull(CORE_SHUTDOWN_SLOW_WARNING_MS) {
            completion.await()
            true
        } == true
        if (completedQuickly) return true

        DiagnosticLogger.warn(
            this,
            "mihomo.shutdown.slow",
            "reason=$reason elapsedMs=$CORE_SHUTDOWN_SLOW_WARNING_MS continuing=true",
        )
        val completed = withTimeoutOrNull(CORE_SHUTDOWN_HARD_TIMEOUT_MS - CORE_SHUTDOWN_SLOW_WARNING_MS) {
            completion.await()
            true
        } == true
        if (!completed) {
            DiagnosticLogger.warn(
                this,
                "mihomo.shutdown.deferred",
                "reason=$reason elapsedMs=$CORE_SHUTDOWN_HARD_TIMEOUT_MS state=${coreLifecycle.currentState()}",
            )
        }
        return completed
    }

    private fun beginCoreShutdown(reason: String): CompletableDeferred<Unit>? {
        val completion = CompletableDeferred<Unit>()
        val action = JSONObject()
            .put("id", "shutdown-${SystemClock.elapsedRealtimeNanos()}")
            .put("method", "shutdown")
        return runCatching {
            Core.invokeAction(action.toString()) {
                val transitioned = coreLifecycle.finishCleanup()
                completion.complete(Unit)
                if (transitioned) {
                    DiagnosticLogger.info(
                        this@WhiteDnsVpnService,
                        "mihomo.cleanup.completed",
                        "reason=$reason",
                    )
                }
            }
            completion
        }.onFailure { error ->
            DiagnosticLogger.warn(this, "mihomo.shutdown.failed", "reason=$reason", error)
        }.getOrNull()
    }

    private fun stopCoreImmediately() {
        when (coreLifecycle.requestCleanup()) {
            MihomoCoreCleanupRequest.Claimed -> {
                coreCleanupScope.launch {
                    cleanupClaimedCore("serviceDestroyed")
                }
            }

            MihomoCoreCleanupRequest.PendingSetup -> DiagnosticLogger.warn(
                this,
                "mihomo.cleanup.skipped",
                "reason=setupInFlight serviceDestroyed=true deferred=true",
            )

            MihomoCoreCleanupRequest.AlreadyStopping,
            MihomoCoreCleanupRequest.AlreadyIdle -> Unit
        }
        runCatching { ByeDpiProxy.stop() }
        dpiBypassJob?.cancel(CancellationException("Service destroyed"))
        dpiBypassJob = null
        activeDpiBypassPort = null
    }

    private fun finishStoppedState(event: String) {
        sessionStartedAtElapsedMs = 0L
        activeProfile = null
        activeProfileShowsServer = false
        activeDelayMs = -1L
        activeRuntimePaths = null
        activeEndpoint = null
        activeConnectionCountryFlag = ""
        activeFrontingIp = ""
        lastPostConnectRecoveryElapsedMs = 0L
        lastDefaultNetworkKey = null
        lastDefaultDns = ""
        runCatching { networkMonitor.stop() }
        stopForegroundCompat()
        publishState(VpnState.Stopped)
        DiagnosticLogger.info(this, event)
        stopSelf()
    }

    private fun handleDefaultNetworkChanged(candidate: DefaultNetworkCandidate?) {
        val networkKey = candidate.defaultNetworkKey()
        val dns = candidate?.dnsServers.orEmpty().distinct().joinToString(",")
        if (networkKey == lastDefaultNetworkKey && dns == lastDefaultDns) return
        lastDefaultNetworkKey = networkKey
        lastDefaultDns = dns
        DiagnosticLogger.info(this, "network.default.changed", "network=$networkKey dns=${candidate?.dnsServers?.size ?: 0} state=${state.wireName}")
        if (dns.isBlank()) return
        if (!coreLifecycle.isActive()) {
            DiagnosticLogger.info(
                this,
                "network.dns.update.skipped",
                "reason=coreNotActive coreState=${coreLifecycle.currentState()}",
            )
            return
        }
        runCatching { Core.updateDNS(dns) }
            .onFailure { DiagnosticLogger.warn(this, "network.dns.update.failed", "dnsCount=${candidate?.dnsServers?.size ?: 0}", it) }
            .onSuccess {
                DiagnosticLogger.info(this, "network.dns.update.ok", "dnsCount=${candidate?.dnsServers?.size ?: 0}")
            }
    }

    private fun DefaultNetworkCandidate?.defaultNetworkKey(): String {
        if (this == null) return "none"
        return listOf(name, index.toString(), isWifi.toString(), isCellular.toString(), isEthernet.toString())
            .joinToString("|")
    }

    private fun cancelPostConnectHealthWatchdog() {
        val job = postConnectHealthJob
        postConnectHealthJob = null
        job?.cancel()
    }

    private fun startPostConnectHealthWatchdog() {
        cancelPostConnectHealthWatchdog()
        val job = scope.launch(Dispatchers.IO) {
            var consecutiveFailures = 0
            while (isActive && state == VpnState.Started) {
                delay(PostConnectHealthPolicy.CHECK_INTERVAL_MS)
                if (!isActive || state != VpnState.Started) break
                if (!networkMonitor.hasUsableDefaultNetwork()) {
                    consecutiveFailures = 0
                    continue
                }

                val code = runtimeHealthStatus()
                if (code == 204 || code in 200..399) {
                    if (consecutiveFailures > 0) {
                        DiagnosticLogger.info(
                            this@WhiteDnsVpnService,
                            "mihomo.postConnect.health.recovered",
                            "code=$code",
                        )
                    }
                    consecutiveFailures = 0
                    continue
                }

                consecutiveFailures += 1
                DiagnosticLogger.warn(
                    this@WhiteDnsVpnService,
                    "mihomo.postConnect.health.failed",
                    "failures=$consecutiveFailures code=$code",
                )
                val nowElapsedMs = SystemClock.elapsedRealtime()
                if (
                    !PostConnectHealthPolicy.shouldRecover(
                        consecutiveFailures,
                        nowElapsedMs,
                        lastPostConnectRecoveryElapsedMs,
                    )
                ) {
                    if (consecutiveFailures >= PostConnectHealthPolicy.FAILURES_BEFORE_RECOVERY) {
                        DiagnosticLogger.info(
                            this@WhiteDnsVpnService,
                            "mihomo.postConnect.recovery.skipped",
                            "reason=cooldown",
                        )
                        consecutiveFailures = 0
                    }
                    continue
                }

                lastPostConnectRecoveryElapsedMs = nowElapsedMs
                DiagnosticLogger.warn(
                    this@WhiteDnsVpnService,
                    "mihomo.postConnect.recovery.start",
                    "reason=consecutiveHealthFailures failures=$consecutiveFailures",
                )
                withContext(Dispatchers.Main) {
                    if (state == VpnState.Started) {
                        refreshVpn(eventPrefix = "selfHeal", automatic = true)
                    }
                }
                break
            }
        }
        postConnectHealthJob = job
        job.invokeOnCompletion {
            if (postConnectHealthJob === job) {
                postConnectHealthJob = null
            }
        }
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

    private fun startBackgroundEncryptedIpScan(profiles: List<ConnectionProfile>) {
        encryptedIpScanJob?.cancel()
        if (frontingIpPreferenceStore.readFrontingIps().isNotEmpty()) return
        val ports = StartupScanPolicy.orderedConnectionPorts(StartupScanPolicy.tcpProbePorts(profiles))
        if (ports.isEmpty()) {
            DiagnosticLogger.info(
                this,
                "scanner.encryptedTop.background.skipped",
                "reason=noTcpProfiles",
            )
            return
        }
        encryptedIpScanJob = scope.launch(Dispatchers.IO) {
            while (isActive && state == VpnState.Started) {
                runCatching {
                    val candidateIps = encryptedIpListRepository.fetchIps()
                    if (candidateIps.isEmpty()) return@runCatching 0
                    scanQuickEncryptedIps(
                        phase = "background",
                        candidateIps = candidateIps,
                        ports = ports,
                        maxScanDurationMs = BACKGROUND_ENCRYPTED_IP_SCAN_MS,
                    ).onEach(cleanIpCache::saveResult).size
                }.onSuccess { count ->
                    DiagnosticLogger.info(
                        this@WhiteDnsVpnService,
                        "scanner.encryptedTop.background.done",
                        "saved=$count ports=$ports",
                    )
                }.onFailure { error ->
                    DiagnosticLogger.warn(this@WhiteDnsVpnService, "scanner.encryptedTop.background.failed", error = error)
                }
                delay(BACKGROUND_ENCRYPTED_IP_SCAN_INTERVAL_MS)
            }
        }
    }

    private fun publishState(newState: VpnState, notice: String? = null) {
        state = newState
        val countryFlag = if (newState == VpnState.Started) {
            activeConnectionCountryFlag
        } else {
            ""
        }
        val debugFrontingIp = if (newState == VpnState.Started) {
            activeFrontingIp
        } else {
            ""
        }
        val connectionDetails = if (newState == VpnState.Started) {
            activeProfile?.let {
                ConnectionDetailsPresenter.forProfile(
                    it,
                    showServer = activeProfileShowsServer,
                    stringFor = { id -> getString(id) },
                )
            }.orEmpty()
        } else {
            ""
        }
        VpnRuntimeStateStore.save(
            this,
            newState,
            sessionStartedAtElapsedMs,
            countryFlag,
            debugFrontingIp,
            connectionDetails,
            alwaysOnActive,
            lockdownActive,
        )
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
            .putExtra(Actions.EXTRA_CONNECTION_DETAILS, connectionDetails)
            .putExtra(Actions.EXTRA_ALWAYS_ON, alwaysOnActive)
            .putExtra(Actions.EXTRA_LOCKDOWN, lockdownActive)
        if (newState is VpnState.Error) {
            intent.putExtra(Actions.EXTRA_ERROR, newState.message)
        }
        if (!notice.isNullOrBlank()) {
            intent.putExtra(Actions.EXTRA_NOTICE, notice)
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

        VpnNotificationActionPolicy.actionsFor(state, disconnectAllowed = !alwaysOnActive).forEach { action ->
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
            Intent(this, WhiteDnsVpnService::class.java)
                .setAction(action)
                .putExtra(Actions.EXTRA_APP_INITIATED, true),
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
        val coreLifecycle = MihomoCoreLifecycle()
        val coreCleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        const val CHANNEL_ID = "white_dns_vpn"
        const val NOTIFICATION_ID = 1001
        const val CORE_SETUP_SLOW_WARNING_MS = 15_000L
        const val CORE_SETUP_HARD_TIMEOUT_MS = 60_000L
        const val CORE_SETUP_CLEANUP_GRACE_MS = 5_000L
        const val CORE_LIFECYCLE_POLL_INTERVAL_MS = 50L
        const val CORE_SHUTDOWN_SLOW_WARNING_MS = 3_000L
        const val CORE_SHUTDOWN_HARD_TIMEOUT_MS = 30_000L
        const val CORE_SHUTDOWN_WAIT_TIMEOUT_MS = 30_000L
        const val DPI_BYPASS_START_TIMEOUT_MS = 3_000L
        const val DPI_BYPASS_STOP_TIMEOUT_MS = 2_000L
        const val DPI_BYPASS_PORT_POLL_INTERVAL_MS = 50L
        const val CONTROLLER_READY_TIMEOUT_MS = 12_000L
        const val CONTROLLER_POLL_INTERVAL_MS = 300L
        const val CONTROLLER_LOG_TAIL_CHARS = 4_000
        const val RUNTIME_HEALTH_TIMEOUT_MS = 12_000L
        const val RUNTIME_HEALTH_POLL_INTERVAL_MS = 500L
        const val FOREGROUND_MIHOMO_DELAY_TIMEOUT_MS = 1_500
        const val BACKGROUND_MIHOMO_DELAY_TIMEOUT_MS = 3_000
        const val CONNECTION_DELAY_TIMEOUT_MS = 4_000
        const val CONNECTION_DELAY_WORKER_COUNT = 8
        const val CONNECTION_DELAY_PAUSE_POLL_INTERVAL_MS = 100L
        const val BACKGROUND_ENCRYPTED_IP_SCAN_MS = 12_000L
        const val BACKGROUND_ENCRYPTED_IP_SCAN_INTERVAL_MS = 10 * 60 * 1_000L
        const val TUN_ESTABLISH_ATTEMPTS = 3
        const val TUN_ESTABLISH_RETRY_DELAY_MS = 250L
        const val FRONTING_FALLBACK_NOTICE = "None of the Fronting IPs were reachable. Used original connection."
        const val FRONTING_AUTOMATIC_FALLBACK_NOTICE =
            "Configured Fronting IPs and original connection were unreachable. Used an automatic fallback IP."
        const val SCAN_DIAGNOSTICS_ENABLED = true
        const val MIHOMO_TUN_STACK = "gvisor"
        const val MIHOMO_TUN_IPV4_ADDRESS = "172.19.0.1"
        const val MIHOMO_TUN_IPV4_PREFIX_LENGTH = 30
        const val MIHOMO_TUN_ADDRESS = "172.19.0.1/30"
        const val MIHOMO_TUN_DNS_SERVER = "172.19.0.2"
        const val MIHOMO_TUN_ROUTE_ANY = "0.0.0.0"
        const val MIHOMO_TUN_MTU = 9000
    }
}
