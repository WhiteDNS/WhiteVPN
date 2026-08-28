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
import kotlinx.coroutines.currentCoroutineContext
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

internal data class ConnectionStartupExclusion(
    val endpoint: CleanIpResult? = null,
    val profileFingerprint: String = "",
)

internal enum class AutomaticBridgeFailurePhase {
    ConfigCoreOrController,
    Selector,
    HttpHealth,
}

internal fun isNonRetryableStartupFailure(error: Throwable): Boolean =
    error is CancellationException ||
        error is MihomoCoreBusyException ||
        error is MihomoCoreSetupTimeoutException

internal fun shouldRetryOriginalAfterAutomaticBridgeFailure(
    bridgeApplied: Boolean,
    phase: AutomaticBridgeFailurePhase,
    error: Throwable,
): Boolean = bridgeApplied &&
    phase != AutomaticBridgeFailurePhase.HttpHealth &&
    !isNonRetryableStartupFailure(error)

internal fun shouldPublishStartupError(startupActive: Boolean, state: VpnState): Boolean =
    startupActive && (state == VpnState.Starting || state == VpnState.Started)

internal fun shouldStopServiceAfterConnectionTest(state: VpnState): Boolean =
    state == VpnState.Stopped || state == VpnState.DailyLimitReached || state is VpnState.Error

internal fun disconnectTerminalState(coreStopped: Boolean, errorMessage: String): VpnState =
    if (coreStopped) VpnState.Stopped else VpnState.Error(errorMessage)

internal fun shouldRunPostConnectHealthWatchdog(
    state: VpnState,
    awaitingPreservedRuntimeHealth: Boolean,
): Boolean = state == VpnState.Started || (state == VpnState.Starting && awaitingPreservedRuntimeHealth)

internal fun canStartVpnRefresh(
    state: VpnState,
    automatic: Boolean,
    awaitingPreservedRuntimeHealth: Boolean,
): Boolean = state == VpnState.Started ||
    (automatic && state == VpnState.Starting && awaitingPreservedRuntimeHealth)

internal fun shouldExcludeRecoveryChainPlan(
    excludedFingerprint: String,
    finalFingerprint: String,
    finalHopMode: ConnectionChainHopMode,
): Boolean = excludedFingerprint.isNotBlank() &&
    finalHopMode == ConnectionChainHopMode.Automatic &&
    finalFingerprint == excludedFingerprint

internal fun quickSpeedNetworkUnchanged(startKey: String, currentKey: String): Boolean =
    startKey == currentKey

internal fun defaultNetworkStateChanged(
    force: Boolean,
    networkKey: String,
    dns: String,
    previousNetworkKey: String?,
    previousDns: String,
): Boolean = force || networkKey != previousNetworkKey || dns != previousDns

internal suspend fun <T> connectWithStartupFallback(
    primary: suspend () -> T,
    fallback: suspend (Throwable) -> T,
): T {
    return try {
        primary()
    } catch (error: Throwable) {
        if (isNonRetryableStartupFailure(error)) {
            throw error
        }
        fallback(error)
    }
}

internal object PostConnectHealthPolicy {
    const val INITIAL_CHECK_DELAY_MS = 5_000L
    const val FAILURE_RECHECK_DELAY_MS = 5_000L
    const val CHECK_INTERVAL_MS = 60_000L
    const val FAILURES_BEFORE_RECOVERY = 2
    const val RECOVERY_COOLDOWN_MS = 10 * 60_000L

    fun shouldRecover(consecutiveFailures: Int, nowElapsedMs: Long, lastRecoveryElapsedMs: Long): Boolean {
        return consecutiveFailures >= FAILURES_BEFORE_RECOVERY &&
            (lastRecoveryElapsedMs <= 0L || nowElapsedMs - lastRecoveryElapsedMs >= RECOVERY_COOLDOWN_MS)
    }

    fun isHealthyStatus(statusCode: Int): Boolean = statusCode in 200..399

    fun preservedRuntimeState(hasUsableDefaultNetwork: Boolean, statusCode: Int): VpnState? = when {
        !hasUsableDefaultNetwork -> VpnState.Starting
        isHealthyStatus(statusCode) -> VpnState.Started
        else -> null
    }
}

internal object DefaultNetworkDnsReplayPolicy {
    const val MAX_RETRY_ATTEMPTS = 1
}

internal object VpnTunnelNetwork {
    const val IPV4_ADDRESS = "172.19.0.1"
    const val IPV4_PREFIX_LENGTH = 30
    const val IPV4_DNS_SERVER = "172.19.0.2"
    const val IPV4_DEFAULT_ROUTE = "0.0.0.0"
    const val IPV6_ADDRESS = "fdfe:dcba:9876::1"
    const val IPV6_PREFIX_LENGTH = 126
    const val IPV6_DNS_SERVER = "fdfe:dcba:9876::2"
    const val IPV6_DEFAULT_ROUTE = "::"

    val addresses = listOf(IPV4_ADDRESS to IPV4_PREFIX_LENGTH, IPV6_ADDRESS to IPV6_PREFIX_LENGTH)
    val defaultRoutes = listOf(IPV4_DEFAULT_ROUTE, IPV6_DEFAULT_ROUTE)
    val dnsServers = listOf(IPV4_DNS_SERVER, IPV6_DNS_SERVER)
    val coreAddresses = addresses.joinToString(",") { (address, prefixLength) -> "$address/$prefixLength" }
    val coreDnsServers = dnsServers.joinToString(",")
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
    private lateinit var routingModePreferenceStore: RoutingModePreferenceStore
    private lateinit var dnsPrivacyPreferenceStore: DnsPrivacyPreferenceStore
    private lateinit var tlsIntegrityPreferenceStore: TlsIntegrityPreferenceStore
    private lateinit var connectionOptionsPreferenceStore: MihomoConnectionOptionsPreferenceStore
    private lateinit var connectionModePreferenceStore: ConnectionModePreferenceStore
    private lateinit var lanSharingPreferenceStore: LanSharingPreferenceStore
    private lateinit var connectionSelectionPreferenceStore: ConnectionSelectionPreferenceStore
    private lateinit var connectionTestSettingsPreferenceStore: ConnectionTestSettingsPreferenceStore
    private lateinit var connectionChainPreferenceStore: ConnectionChainPreferenceStore

    private var startupJob: Job? = null
    private var stopJob: Job? = null
    private var subscriptionRefreshJob: Job? = null
    private var encryptedIpScanJob: Job? = null
    private var postConnectHealthJob: Job? = null
    @Volatile
    private var awaitingPreservedRuntimeHealth = false
    private var dpiBypassJob: Job? = null
    private var connectionDelayTestJob: Job? = null
    private var connectionSpeedTestJob: Job? = null
    private var connectionSwitchJob: Job? = null
    private val controllerSelectionMutex = Mutex()
    private val quickSpeedStartedLock = Any()
    @Volatile
    private var activeConnectionDelayTestId: String? = null
    @Volatile
    private var connectionDelayTestPaused = false
    private val uidPackageNameCache = mutableMapOf<Int, String>()
    private var activeConnectionChained = false
    private var activeChainHopCount = 0

    private class DpiBypassStartupException(cause: Throwable) :
        IOException("ByeByeDPI failed to start", cause)

    private class AutomaticRoutingBridgeStartupException(cause: Throwable) :
        IOException("Bridged automatic routing failed during core startup", cause)

    private data class StartedMihomoRuntime(
        val profile: ConnectionProfile,
        val endpoint: CleanIpResult,
        val delayMs: Long,
        val paths: MihomoRuntimePaths,
        val delayProbeName: String,
        val cacheEndpoint: Boolean,
        val selectedCountryCode: String?,
        val availableProfiles: List<ConnectionProfile>,
        val nativeAutomaticStart: Boolean,
        val subscriptionId: String,
        val selectorRootName: String,
        val selectorRoots: List<String>,
        val quickSpeedPin: QuickSpeedPin? = null,
        val chainHopCount: Int = 0,
    )

    private data class QuickSpeedPin(
        val paths: MihomoRuntimePaths,
        val networkFingerprint: String,
        val rootName: String,
        val groupName: String,
        val originalFixed: String,
        val selectedName: String,
        val delayMs: Int,
        val graphReads: AtomicInteger,
    )

    private data class QuickSpeedSelectionResult(
        val response: JSONObject,
        val pin: QuickSpeedPin?,
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
    private var pendingDefaultNetworkDnsReplay: Boolean = false
    @Volatile
    private var activeDpiBypassPort: Int? = null
    @Volatile
    private var alwaysOnActive: Boolean = false
    @Volatile
    private var lockdownActive: Boolean = false
    @Volatile
    private var lastPostConnectRecoveryElapsedMs: Long = 0L
    @Volatile
    private var activeNativeAutomaticStart: Boolean = false
    @Volatile
    private var activeSubscriptionId: String = ""
    @Volatile
    private var activeConnectionTag: String = ""
    @Volatile
    private var activeConnectionFingerprint: String = ""
    @Volatile
    private var activeSelectorReady: Boolean = false
    @Volatile
    private var activeSelectableConnectionFingerprints: Set<String> = emptySet()
    @Volatile
    private var activeSelectorRootName: String = ""
    @Volatile
    private var activeSelectorRoots: List<String> = emptyList()
    @Volatile
    private var activeAvailableProfiles: List<ConnectionProfile> = emptyList()
    @Volatile
    private var activeRuntimeUsesEndpointOverride: Boolean = false
    @Volatile
    private var activeQuickSpeedPin: QuickSpeedPin? = null
    @Volatile
    private var invalidatedQuickSpeedPin: QuickSpeedPin? = null
    private var activeProfileShowsServer: Boolean = false

    override fun onCreate() {
        super.onCreate()
        DiagnosticLogger.beginCapture(this)
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
        routingModePreferenceStore = RoutingModePreferenceStore(this)
        dnsPrivacyPreferenceStore = DnsPrivacyPreferenceStore(this)
        tlsIntegrityPreferenceStore = TlsIntegrityPreferenceStore(this)
        connectionOptionsPreferenceStore = MihomoConnectionOptionsPreferenceStore(this)
        connectionModePreferenceStore = ConnectionModePreferenceStore(this)
        lanSharingPreferenceStore = LanSharingPreferenceStore(this)
        connectionSelectionPreferenceStore = ConnectionSelectionPreferenceStore(this)
        connectionTestSettingsPreferenceStore = ConnectionTestSettingsPreferenceStore(this)
        connectionChainPreferenceStore = ConnectionChainPreferenceStore(this)
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
            Actions.REFRESH -> refreshVpn(quickSpeedRequested = true)
            Actions.SWITCH_CONNECTION -> switchActiveConnection(
                subscriptionId = intent?.getStringExtra(Actions.EXTRA_SUBSCRIPTION_ID).orEmpty(),
                fingerprint = intent?.getStringExtra(Actions.EXTRA_CONNECTION_FINGERPRINT).orEmpty(),
            )
            Actions.TEST_CONNECTION_DELAYS -> startConnectionDelayTest(
                testId = intent?.getStringExtra(Actions.EXTRA_DELAY_TEST_ID).orEmpty(),
                subscriptionId = intent?.getStringExtra(Actions.EXTRA_SUBSCRIPTION_ID).orEmpty(),
                connectionTypes = intent
                    ?.getStringArrayListExtra(Actions.EXTRA_CONNECTION_TYPES)
                    .orEmpty()
                    .toSet(),
                targetFingerprints = intent
                    ?.getStringArrayListExtra(Actions.EXTRA_CONNECTION_FINGERPRINTS)
                    .orEmpty()
                    .toSet(),
            )
            Actions.TEST_CONNECTION_SPEED -> startConnectionSpeedTest(
                testId = intent?.getStringExtra(Actions.EXTRA_SPEED_TEST_ID).orEmpty(),
                subscriptionId = intent?.getStringExtra(Actions.EXTRA_SUBSCRIPTION_ID).orEmpty(),
                fingerprint = intent?.getStringExtra(Actions.EXTRA_CONNECTION_FINGERPRINT).orEmpty(),
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
        connectionSwitchJob?.cancel(CancellationException("Service destroyed"))
        connectionDelayTestJob?.cancel(CancellationException("Service destroyed"))
        connectionSpeedTestJob?.cancel(CancellationException("Service destroyed"))
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
        // VpnService.onRevoke() stops the service; finishStoppedState() must do that after cleanup.
        stopVpn(force = true)
    }

    private fun startVpn() {
        if (state == VpnState.Starting || state == VpnState.Started) {
            DiagnosticLogger.info(this, "connect.ignored", "state=${state.wireName}")
            return
        }
        lastPostConnectRecoveryElapsedMs = 0L
        awaitingPreservedRuntimeHealth = false
        startForeground(NOTIFICATION_ID, serviceNotification(getString(R.string.notification_starting)))
        DiagnosticLogger.info(this, "connect.start")
        publishState(VpnState.Starting)
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
        DiagnosticLogger.info(this, "reconnect.start")
        publishState(VpnState.Starting)
        startForeground(NOTIFICATION_ID, serviceNotification(getString(R.string.notification_starting)))
        launchConnectionStartup("reconnect") { stopActiveCoreForReplacement() }
    }

    private fun switchActiveConnection(subscriptionId: String, fingerprint: String) {
        if (subscriptionId.isBlank() || fingerprint.isBlank()) return
        if (state != VpnState.Started) {
            DiagnosticLogger.info(this, "connection.switch.ignored", "reason=state state=${state.wireName}")
            return
        }
        if (activeConnectionChained) {
            publishState(VpnState.Started, getString(R.string.connection_chain_switch_unavailable))
            return
        }
        if (
            connectionDelayTestJob?.isActive == true ||
            connectionSpeedTestJob?.isActive == true ||
            connectionSwitchJob?.isActive == true
        ) {
            publishState(VpnState.Started, getString(R.string.connection_switch_busy))
            return
        }
        val runtimePaths = activeRuntimePaths ?: return
        val profile = activeAvailableProfiles.firstOrNull { it.fingerprint == fingerprint }
        if (subscriptionId != activeSubscriptionId || profile == null) {
            publishState(VpnState.Started, getString(R.string.connection_switch_unavailable))
            return
        }

        val job = scope.launch(Dispatchers.IO) {
            controllerSelectionMutex.withLock {
                val controller = MihomoControllerClient(runtimePaths.secret, port = runtimePaths.controlPort)
                var rollbackSelections = emptyList<MihomoGroupSelection>()
                try {
                    if (state != VpnState.Started || activeRuntimePaths != runtimePaths) return@withLock
                    val before = controller.getProxies()
                    val path = MihomoControllerProxies.selectorPath(
                        response = before,
                        targetName = profile.tag,
                        preferredRoots = activeSelectorRootName
                            .takeIf(String::isNotBlank)
                            ?.let(::listOf)
                            ?: activeSelectorRoots,
                    )
                    if (path.isEmpty()) {
                        throw IOException("Connection is not reachable from the active Mihomo selector")
                    }
                    rollbackSelections = MihomoControllerProxies.currentSelections(before, path)
                    path.forEach { selection ->
                        controller.selectProxy(selection.selectorGroup, selection.selectedGroup)
                    }

                    val selectedRoot = path.last().selectorGroup
                    val switched = controller.getProxies()
                    val activeName = MihomoControllerProxies.activeProxyName(switched, selectedRoot)
                    if (activeName != profile.tag) {
                        throw IOException("Mihomo selected ${activeName.orEmpty()} instead of ${profile.tag}")
                    }
                    val measuredDelayMs = MihomoRuntimeDefaults.HEALTH_URLS.firstNotNullOfOrNull { url ->
                        MihomoDelayPolicy.acceptedDelayMs(
                            controller.delay(
                                profile.tag,
                                timeoutMs = FOREGROUND_MIHOMO_DELAY_TIMEOUT_MS,
                                url = url,
                            ),
                        )
                    }
                    if (measuredDelayMs == null) verifyRuntimeHealth()
                    if (state != VpnState.Started || activeRuntimePaths != runtimePaths) return@withLock

                    activeSelectorRootName = selectedRoot
                    applyLiveSelectorSnapshot(runtimePaths, controller.getProxies(), publishIfChanged = false)
                    if (activeConnectionFingerprint != profile.fingerprint) {
                        throw IOException("Mihomo did not keep the requested connection active")
                    }
                    activeNativeAutomaticStart = false
                    activeQuickSpeedPin = null
                    invalidatedQuickSpeedPin = null
                    measuredDelayMs?.let { activeDelayMs = it }
                    if (!activeRuntimeUsesEndpointOverride) {
                        activeEndpoint = CleanIpResult(
                            ip = profile.server,
                            port = profile.port,
                            latencyMs = measuredDelayMs ?: activeDelayMs,
                            lossRate = 0.0,
                            checkedAt = System.currentTimeMillis(),
                        )
                    }
                    connectionSelectionPreferenceStore.saveSelectedProfile(subscriptionId, profile)
                    scanStateStore.saveLastSelectedProfile(
                        SelectedConnectionProfile(
                            profile = profile,
                            delayMs = measuredDelayMs?.toInt() ?: Int.MAX_VALUE,
                            selectedAt = System.currentTimeMillis(),
                        ),
                    )
                    publishState(VpnState.Started)
                    DiagnosticLogger.info(
                        this@WhiteDnsVpnService,
                        "connection.switch.ok",
                        "profile=${profile.tag} selectors=${path.size} delayMs=${measuredDelayMs ?: -1L}",
                    )
                } catch (error: Throwable) {
                    if (state == VpnState.Started && activeRuntimePaths == runtimePaths) {
                        rollbackSelections.forEach { selection ->
                            runCatching {
                                controller.selectProxy(selection.selectorGroup, selection.selectedGroup)
                            }.onFailure { rollbackError ->
                                DiagnosticLogger.warn(
                                    this@WhiteDnsVpnService,
                                    "connection.switch.rollback.failed",
                                    "selector=${selection.selectorGroup}",
                                    rollbackError,
                                )
                            }
                        }
                        runCatching { controller.getProxies() }
                            .onSuccess { applyLiveSelectorSnapshot(runtimePaths, it, publishIfChanged = false) }
                    }
                    if (error is CancellationException) throw error
                    DiagnosticLogger.warn(
                        this@WhiteDnsVpnService,
                        "connection.switch.failed",
                        "profile=${profile.tag}",
                        error,
                    )
                    if (state == VpnState.Started && activeRuntimePaths == runtimePaths) {
                        publishState(VpnState.Started, getString(R.string.connection_switch_failed))
                    }
                }
            }
        }
        connectionSwitchJob = job
        job.invokeOnCompletion {
            if (connectionSwitchJob === job) connectionSwitchJob = null
        }
    }

    private fun refreshVpn(
        eventPrefix: String = "refresh",
        automatic: Boolean = false,
        quickSpeedRequested: Boolean = false,
    ) {
        if (!canStartVpnRefresh(state, automatic, awaitingPreservedRuntimeHealth)) {
            DiagnosticLogger.info(this, "$eventPrefix.ignored", "state=${state.wireName}")
            return
        }
        awaitingPreservedRuntimeHealth = false
        if (!automatic) {
            lastPostConnectRecoveryElapsedMs = 0L
        }
        DiagnosticLogger.info(this, "$eventPrefix.start")
        val exclusion = ConnectionStartupExclusion(
            endpoint = activeEndpoint,
            profileFingerprint = activeConnectionFingerprint,
        )
        DiagnosticLogger.info(
            this,
            "$eventPrefix.connection.excluded",
            "endpoint=${exclusion.endpoint != null} profile=${exclusion.profileFingerprint.isNotBlank()}",
        )
        publishState(VpnState.Starting)
        startForeground(NOTIFICATION_ID, serviceNotification(getString(R.string.notification_starting)))
        launchConnectionStartup(
            eventPrefix = eventPrefix,
            exclusion = exclusion,
            preserveRuntimeOnFailure = activeRuntimePaths.takeUnless { automatic },
            quickSpeedRequested = quickSpeedRequested,
        )
    }

    private fun startConnectionDelayTest(
        testId: String,
        subscriptionId: String,
        connectionTypes: Set<String>,
        targetFingerprints: Set<String>,
    ) {
        if (testId.isBlank()) return
        val resolvedSubscriptionId = subscriptionId.ifBlank {
            subscriptionStore.readSelectedSubscriptionId()
        }
        val preparingSession = ConnectionDelayTestState.replace(
            ConnectionDelayTestSession(
                testId = testId,
                subscriptionId = resolvedSubscriptionId,
                connectionTypes = connectionTypes,
                targetFingerprints = targetFingerprints.toList(),
            ),
        )
        val finishingSpeedJob = connectionSpeedTestJob
        if (
            state == VpnState.Starting ||
            state == VpnState.Stopping ||
            ConnectionSpeedTestState.isAnyRunning() ||
            connectionSwitchJob?.isActive == true
        ) {
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
            finishingSpeedJob?.join()
            previousJob?.cancelAndJoin()
            try {
                ensureUnderlyingNetworkAvailable()
                val testSettings = connectionTestSettingsPreferenceStore.read()
                val snapshot = configRepository.readCachedMihomoConfigOrNull(resolvedSubscriptionId)
                    ?: throw IOException(getString(R.string.connection_empty))
                val matchingType = ConnectionTypeSelectionPolicy.filterProfiles(
                    snapshot.catalog.profiles,
                    connectionTypes,
                )
                val profiles = if (targetFingerprints.isEmpty()) {
                    matchingType
                } else {
                    matchingType.filter { it.fingerprint in targetFingerprints }
                }
                if (profiles.isEmpty()) {
                    throw IOException(getString(R.string.connection_empty))
                }
                withConnectionTestController(resolvedSubscriptionId, snapshot, profiles) { controller, _ ->
                    val startedSession = ConnectionDelayTestState.update(testId) {
                        it.copy(
                            targetFingerprints = profiles.map(ConnectionProfile::fingerprint),
                            status = Actions.DELAY_TEST_STARTED,
                            completed = 0,
                            total = profiles.size,
                            available = 0,
                            finishedFingerprints = emptySet(),
                            paused = false,
                            error = "",
                        )
                    } ?: return@withConnectionTestController
                    publishConnectionDelayTest(startedSession)

                    val progressMutex = Mutex()
                    val persistenceMutex = Mutex()
                    val pendingRecords = mutableListOf<ConnectionDelayRecord>()
                    var flushCount = 0
                    suspend fun flushPendingRecords() {
                        persistenceMutex.withLock {
                            if (pendingRecords.isEmpty()) return@withLock
                            subscriptionStore.saveConnectionDelayRecords(pendingRecords)
                            pendingRecords.clear()
                            flushCount += 1
                        }
                    }
                    suspend fun enqueueRecord(record: ConnectionDelayRecord) {
                        persistenceMutex.withLock {
                            pendingRecords += record
                            if (pendingRecords.size >= CONNECTION_DELAY_RECORD_BATCH_SIZE) {
                                subscriptionStore.saveConnectionDelayRecords(pendingRecords)
                                pendingRecords.clear()
                                flushCount += 1
                            }
                        }
                    }
                    val nextDelayProfileIndex = AtomicInteger(0)
                    try {
                        coroutineScope {
                            val delayJobs = List(minOf(testSettings.concurrency, profiles.size)) {
                                launch(Dispatchers.IO) {
                                    while (isActive) {
                                        awaitConnectionDelayTestResumed(testId)
                                        val index = nextDelayProfileIndex.getAndIncrement()
                                        if (index >= profiles.size) break
                                        val profile = profiles[index]
                                        val delayMs = realConnectionDelayMs(
                                            controller = controller,
                                            profile = profile,
                                            timeoutMs = testSettings.timeoutSeconds * 1_000,
                                        )
                                        enqueueRecord(
                                            ConnectionDelayRecord(
                                                subscriptionId = resolvedSubscriptionId,
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
                                                    finishedFingerprints =
                                                        current.finishedFingerprints + profile.fingerprint,
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
                            delayJobs.forEach { it.join() }
                        }
                    } finally {
                        withContext(NonCancellable + Dispatchers.IO) {
                            flushPendingRecords()
                        }
                        DiagnosticLogger.info(
                            this@WhiteDnsVpnService,
                            "connection.delay.cache.flushed",
                            "flushes=$flushCount completed=${nextDelayProfileIndex.get().coerceAtMost(profiles.size)} total=${profiles.size}",
                        )
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
            }
        }
        connectionDelayTestJob = job
        job.invokeOnCompletion {
            if (connectionDelayTestJob === job) {
                connectionDelayTestPaused = false
                activeConnectionDelayTestId = null
                connectionDelayTestJob = null
                if (state == VpnState.Started) {
                    scope.launch(Dispatchers.IO) {
                        runCatching { refreshLiveSelectorState() }
                    }
                } else if (shouldStopServiceAfterConnectionTest(state)) {
                    stopSelf()
                }
            }
        }
    }

    private fun startConnectionSpeedTest(testId: String, subscriptionId: String, fingerprint: String) {
        if (testId.isBlank() || subscriptionId.isBlank() || fingerprint.isBlank()) return
        val finishingDelayJob = connectionDelayTestJob
        val finishingSpeedJob = connectionSpeedTestJob
        if (
            state == VpnState.Starting ||
            state == VpnState.Stopping ||
            ConnectionDelayTestState.isAnyRunning() ||
            ConnectionSpeedTestState.isAnyRunning() ||
            connectionSwitchJob?.isActive == true
        ) {
            publishConnectionSpeedTest(
                ConnectionSpeedTestSession(
                    testId = testId,
                    subscriptionId = subscriptionId,
                    fingerprint = fingerprint,
                    status = Actions.SPEED_TEST_FAILED,
                    error = getString(R.string.connection_test_busy),
                ),
            )
            return
        }

        val preparingSession = ConnectionSpeedTestState.replace(
            ConnectionSpeedTestSession(testId, subscriptionId, fingerprint),
        )
        publishConnectionSpeedTest(preparingSession)
        lateinit var job: Job
        job = scope.launch {
            finishingDelayJob?.join()
            finishingSpeedJob?.join()
            var existingRecord: ConnectionDelayRecord? = null
            try {
                ensureUnderlyingNetworkAvailable()
                val snapshot = configRepository.readCachedMihomoConfigOrNull(subscriptionId)
                    ?: throw IOException(getString(R.string.connection_speed_test_unavailable))
                val profile = snapshot.catalog.profiles.firstOrNull { it.fingerprint == fingerprint }
                    ?: throw IOException(getString(R.string.connection_speed_test_unavailable))
                val record = subscriptionStore
                    .readConnectionDelayRecords(subscriptionId, listOf(profile))
                    .firstOrNull()
                    ?.takeIf { it.status == ConnectionDelayStatus.Success && it.delayMs != null }
                    ?: throw IOException(getString(R.string.connection_speed_test_unavailable))
                val speedTestBytes = connectionTestSettingsPreferenceStore.read().speedTestBytes

                val speedKbps = withConnectionTestController(
                    subscriptionId,
                    snapshot,
                    listOf(profile),
                ) { controller, temporaryCore ->
                    existingRecord = record
                    ConnectionSpeedTestState.update(testId) {
                        it.copy(status = Actions.SPEED_TEST_STARTED, error = "")
                    }?.let(::publishConnectionSpeedTest)
                    val proxies = withContext(Dispatchers.IO) { controller.getProxies() }
                    val preferredRoots = listOfNotNull(
                        MihomoSelectionPolicy.trafficProbeGroup(snapshot.summary)?.name,
                        MihomoSelectionPolicy.mainSelectorGroup(snapshot.summary)?.name,
                    )
                    val path = MihomoControllerProxies.selectorPath(
                        response = proxies,
                        targetName = profile.tag,
                        preferredRoots = preferredRoots,
                    )
                    if (path.isEmpty()) {
                        throw IOException(getString(R.string.connection_speed_test_unavailable))
                    }
                    val restoreSelections = if (temporaryCore) {
                        emptyList()
                    } else {
                        MihomoControllerProxies.currentSelections(proxies, path)
                    }
                    connectionSpeedKbps(
                        controller = controller,
                        profile = profile,
                        path = path,
                        selectionMutex = controllerSelectionMutex,
                        restoreSelections = restoreSelections,
                        downloadBytes = speedTestBytes,
                    ) ?: throw IOException(getString(R.string.connection_speed_test_failed))
                }

                subscriptionStore.saveConnectionDelayRecord(record.copy(speedKbps = speedKbps))
                ConnectionSpeedTestState.update(testId) {
                    it.copy(status = Actions.SPEED_TEST_COMPLETED, error = "")
                }?.let(::publishConnectionSpeedTest)
            } catch (error: CancellationException) {
                existingRecord?.copy(speedKbps = null)?.let(subscriptionStore::saveConnectionDelayRecord)
                ConnectionSpeedTestState.update(testId) {
                    it.copy(status = Actions.SPEED_TEST_CANCELED, error = "")
                }?.let(::publishConnectionSpeedTest)
                throw error
            } catch (error: Throwable) {
                existingRecord?.copy(speedKbps = null)?.let(subscriptionStore::saveConnectionDelayRecord)
                DiagnosticLogger.warn(
                    this@WhiteDnsVpnService,
                    "connection.speed.failed",
                    "fingerprint=$fingerprint",
                    error,
                )
                ConnectionSpeedTestState.update(testId) {
                    it.copy(
                        status = Actions.SPEED_TEST_FAILED,
                        error = error.message?.takeIf(String::isNotBlank)
                            ?: getString(R.string.connection_speed_test_failed),
                    )
                }?.let(::publishConnectionSpeedTest)
            }
        }
        connectionSpeedTestJob = job
        job.invokeOnCompletion {
            if (connectionSpeedTestJob === job) {
                connectionSpeedTestJob = null
                if (state == VpnState.Started) {
                    scope.launch(Dispatchers.IO) {
                        runCatching { refreshLiveSelectorState() }
                    }
                } else if (shouldStopServiceAfterConnectionTest(state)) {
                    stopSelf()
                }
            }
        }
    }

    private suspend fun <T> withConnectionTestController(
        subscriptionId: String,
        snapshot: MihomoSubscriptionSnapshot,
        requiredProfiles: List<ConnectionProfile>,
        block: suspend (MihomoControllerClient, temporaryCore: Boolean) -> T,
    ): T {
        var temporaryCore = false
        try {
            val controller = if (
                state == VpnState.Started &&
                activeRuntimePaths != null &&
                coreLifecycle.isActive() &&
                !activeConnectionChained &&
                subscriptionId == activeSubscriptionId
            ) {
                val paths = activeRuntimePaths ?: throw MihomoCoreBusyException()
                MihomoControllerClient(paths.secret, port = paths.controlPort).also { activeController ->
                    if (requiredProfiles.any { required ->
                            activeAvailableProfiles.none { active ->
                                active.fingerprint == required.fingerprint && active.tag == required.tag
                            }
                        }
                    ) {
                        throw IOException(getString(R.string.connection_test_reconnect_required))
                    }
                    val liveProxies = withContext(Dispatchers.IO) { activeController.getProxies() }
                        .optJSONObject("proxies")
                    if (liveProxies == null || requiredProfiles.any { !liveProxies.has(it.tag) }) {
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
                val paths = MihomoRuntimeConfigBuilder(this@WhiteDnsVpnService).writeProfileTest(
                    ProfileTestRuntimePlan(
                        rawYaml = runtimeYaml,
                        routingMode = routingModePreferenceStore.read(),
                        dns = DnsRuntimeSettings(
                            mode = dnsPrivacyPreferenceStore.readMode(),
                            dohUrl = dnsPrivacyPreferenceStore.readDohUrl(),
                            dotEndpoint = dnsPrivacyPreferenceStore.readDotEndpoint(),
                        ),
                    ),
                )
                temporaryCore = true
                setupCore(paths)
                MihomoControllerClient(paths.secret, port = paths.controlPort).also { probeController ->
                    waitForController(probeController, paths)
                }
            }
            return block(controller, temporaryCore)
        } finally {
            if (temporaryCore) {
                withContext(NonCancellable) { stopCoreService() }
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

    private suspend fun connectionSpeedKbps(
        controller: MihomoControllerClient,
        profile: ConnectionProfile,
        path: List<MihomoGroupSelection>,
        selectionMutex: Mutex,
        restoreSelections: List<MihomoGroupSelection>,
        downloadBytes: Long,
    ): Int? {
        if (path.isEmpty()) return null
        fun restoreActiveSelection() {
            restoreSelections.forEach { selection ->
                runCatching {
                    controller.selectProxy(selection.selectorGroup, selection.selectedGroup)
                }.onFailure { error ->
                    DiagnosticLogger.warn(
                        this@WhiteDnsVpnService,
                        "connection.speed.restore.failed",
                        "selector=${selection.selectorGroup}",
                        error,
                    )
                }
            }
        }
        selectionMutex.lock()
        var selectionLocked = true
        return try {
            withContext(Dispatchers.IO) {
                path.forEach { selection ->
                    controller.selectProxy(selection.selectorGroup, selection.selectedGroup)
                }
            }
            runInterruptible(Dispatchers.IO) {
                MihomoRuntimeHealth.downloadSpeedKbpsThroughMixedProxy(
                    downloadBytes = downloadBytes,
                    onResponseReady = {
                        restoreActiveSelection()
                        selectionMutex.unlock()
                        selectionLocked = false
                    },
                )
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            DiagnosticLogger.warn(
                this,
                "connection.speed.failed",
                "profile=${profile.tag}",
                error,
            )
            null
        } finally {
            if (selectionLocked) {
                withContext(NonCancellable + Dispatchers.IO) { restoreActiveSelection() }
                selectionMutex.unlock()
            }
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

    private suspend fun cancelConnectionTestsAndWait() {
        val delayJob = connectionDelayTestJob
        val speedJob = connectionSpeedTestJob
        connectionDelayTestPaused = false
        delayJob?.cancelAndJoin()
        speedJob?.cancelAndJoin()
        if (connectionDelayTestJob === delayJob) {
            activeConnectionDelayTestId = null
            connectionDelayTestJob = null
        }
        if (connectionSpeedTestJob === speedJob) connectionSpeedTestJob = null
    }

    private suspend fun realConnectionDelayMs(
        controller: MihomoControllerClient,
        profile: ConnectionProfile,
        timeoutMs: Int,
    ): Int? {
        connectionDelayMs(
            controller = controller,
            profile = profile,
            timeoutMs = timeoutMs,
            url = MihomoRuntimeDefaults.DELAY_TEST_URL,
        )?.let { return it }
        DiagnosticLogger.warn(
            this,
            "connection.delay.unavailable",
            "profile=${profile.tag}",
        )
        return null
    }

    private suspend fun connectionDelayMs(
        controller: MihomoControllerClient,
        profile: ConnectionProfile,
        timeoutMs: Int,
        url: String,
    ): Int? {
        return try {
            runInterruptible(Dispatchers.IO) {
                MihomoDelayPolicy.acceptedDelayMs(
                    controller.delay(
                        name = profile.tag,
                        timeoutMs = timeoutMs,
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

    private fun publishConnectionSpeedTest(session: ConnectionSpeedTestSession) {
        sendBroadcast(
            Intent(Actions.CONNECTION_SPEED_TEST_CHANGED)
                .setPackage(packageName)
                .putExtra(Actions.EXTRA_SPEED_TEST_ID, session.testId)
                .putExtra(Actions.EXTRA_SUBSCRIPTION_ID, session.subscriptionId)
                .putExtra(Actions.EXTRA_CONNECTION_FINGERPRINT, session.fingerprint)
                .putExtra(Actions.EXTRA_SPEED_TEST_STATUS, session.status)
                .putExtra(Actions.EXTRA_SPEED_TEST_ERROR, session.error),
        )
    }

    private fun launchConnectionStartup(
        eventPrefix: String,
        exclusion: ConnectionStartupExclusion = ConnectionStartupExclusion(),
        preserveRuntimeOnFailure: MihomoRuntimePaths? = null,
        quickSpeedRequested: Boolean = false,
        beforeStartup: suspend () -> Unit = {},
    ) {
        startupJob?.cancel(CancellationException("Startup superseded"))
        subscriptionRefreshJob?.cancel()
        encryptedIpScanJob?.cancel()
        cancelPostConnectHealthWatchdog()
        val job = scope.launch {
            try {
                connectionSwitchJob?.cancelAndJoin()
                cancelConnectionTestsAndWait()
                beforeStartup()
                runConnectionStartup(eventPrefix, exclusion, quickSpeedRequested)
            } catch (error: CancellationException) {
                DiagnosticLogger.info(this@WhiteDnsVpnService, "$eventPrefix.canceled", error.message.orEmpty())
            } catch (error: Throwable) {
                if (!shouldPublishStartupError(isActive, state)) {
                    DiagnosticLogger.info(
                        this@WhiteDnsVpnService,
                        "$eventPrefix.failure.ignored",
                        "reason=startupCanceled state=${state.wireName}",
                    )
                    return@launch
                }
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
        exclusion: ConnectionStartupExclusion = ConnectionStartupExclusion(),
        quickSpeedRequested: Boolean = false,
    ) {
        ensureStartupActive(eventPrefix)
        ensureUnderlyingNetworkAvailable()
        networkMonitor.start()

        val selectedSubscriptionId = subscriptionStore.readSelectedSubscriptionId()
        val showServer = selectedSubscriptionId != SubscriptionStore.DEFAULT_SUBSCRIPTION_ID
        val chainSettings = connectionChainPreferenceStore.read()
        if (chainSettings.isActive) {
            if (quickSpeedRequested) {
                DiagnosticLogger.info(this, "mihomo.quickSpeed.skipped", "reason=connectionChain")
            }
            val startedRuntime = withContext(Dispatchers.IO) {
                configRepository.refreshAllSubscriptions()
                connectWithConnectionChain(
                    settings = chainSettings,
                    splitTunnelPlan = resolveSplitTunnelRuntimePlan(),
                    preferences = captureSessionPlanPreferences(
                        selectedSubscriptionId = selectedSubscriptionId,
                        explicitProfile = null,
                        selectedAutomaticTypes = emptySet(),
                    ),
                    excludedProfileFingerprint = exclusion.profileFingerprint,
                )
            }
            applyStartedRuntime(
                startedRuntimeCandidate = startedRuntime,
                eventPrefix = eventPrefix,
                showServer = true,
            )
            return
        }
        val snapshot = configRepository.fetchOrCachedMihomoConfig()
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
        val preferences = captureSessionPlanPreferences(
            selectedSubscriptionId = selectedSubscriptionId,
            explicitProfile = explicitProfile,
            selectedAutomaticTypes = selectedAutomaticTypes,
            frontingIps = frontingIps,
        )
        if (quickSpeedRequested && frontingIps.isNotEmpty()) {
            DiagnosticLogger.info(this, "mihomo.quickSpeed.skipped", "reason=fronting")
        }
        val (startedRuntime, startupNotice) = withContext(Dispatchers.IO) {
            if (frontingIps.isNotEmpty()) {
                connectWithFrontingIpsOrOriginal(
                    eventPrefix = eventPrefix,
                    snapshot = snapshot,
                    profiles = profiles,
                    splitTunnelPlan = splitTunnelPlan,
                    selectedCountryCode = selectedCountryCode,
                    exclusion = exclusion,
                    frontingIps = frontingIps,
                    preferences = preferences,
                    availableProfiles = eligibleProfiles,
                )
            } else {
                connectWithOriginal(
                    snapshot = snapshot,
                    splitTunnelPlan = splitTunnelPlan,
                    selectedCountryCode = selectedCountryCode,
                    preferences = preferences,
                    availableProfiles = eligibleProfiles,
                    excludedProfileFingerprint = exclusion.profileFingerprint,
                    quickSpeedRequested = quickSpeedRequested,
                ) to null
            }
        }
        applyStartedRuntime(
            startedRuntimeCandidate = startedRuntime,
            eventPrefix = eventPrefix,
            notice = startupNotice,
            showServer = showServer,
        )
    }

    private fun captureSessionPlanPreferences(
        selectedSubscriptionId: String,
        explicitProfile: ConnectionProfile?,
        selectedAutomaticTypes: Set<String>,
        frontingIps: List<String> = frontingIpPreferenceStore.readFrontingIps(),
    ): SessionPlanPreferences = SessionPlanPreferences(
        frontingIps = frontingIps,
        connectionOptions = connectionOptionsPreferenceStore.read(),
        selectedSubscriptionId = selectedSubscriptionId,
        explicitProfile = explicitProfile,
        selectedAutomaticTypes = selectedAutomaticTypes,
        lanSharing = lanSharingPreferenceStore.read(),
        routingMode = routingModePreferenceStore.read(),
        dns = DnsRuntimeSettings(
            mode = dnsPrivacyPreferenceStore.readMode(),
            dohUrl = dnsPrivacyPreferenceStore.readDohUrl(),
            dotEndpoint = dnsPrivacyPreferenceStore.readDotEndpoint(),
        ),
        connectionMode = connectionModePreferenceStore.read(),
        dpiBypassEnabled = dpiBypassPreferenceStore.isEnabled(),
        alwaysOn = alwaysOnActive,
        lockdown = lockdownActive,
        tlsIntegrityEnabled = tlsIntegrityPreferenceStore.isEnabled(),
    )

    private suspend fun connectWithFrontingIpsOrOriginal(
        eventPrefix: String,
        snapshot: MihomoSubscriptionSnapshot,
        profiles: List<ConnectionProfile>,
        splitTunnelPlan: SplitTunnelRuntimePlan,
        selectedCountryCode: String?,
        exclusion: ConnectionStartupExclusion,
        frontingIps: List<String>,
        preferences: SessionPlanPreferences,
        availableProfiles: List<ConnectionProfile>,
    ): Pair<StartedMihomoRuntime, String?> {
        return try {
            connectWithTopIpCandidates(
                eventPrefix = eventPrefix,
                snapshot = snapshot,
                profiles = profiles,
                splitTunnelPlan = splitTunnelPlan,
                selectedCountryCode = selectedCountryCode,
                bypassConnectionCache = false,
                excludedEndpoint = exclusion.endpoint,
                excludedProfileFingerprint = exclusion.profileFingerprint,
                frontingIps = frontingIps,
                useCachedFirst = false,
                preferences = preferences,
                availableProfiles = availableProfiles,
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
            val startedRuntime = connectWithOriginal(
                snapshot = snapshot,
                splitTunnelPlan = splitTunnelPlan,
                selectedCountryCode = selectedCountryCode,
                preferences = preferences,
                availableProfiles = availableProfiles,
                excludedProfileFingerprint = exclusion.profileFingerprint,
            )
            startedRuntime to FRONTING_FALLBACK_NOTICE
        }
    }

    private suspend fun connectWithOriginal(
        snapshot: MihomoSubscriptionSnapshot,
        splitTunnelPlan: SplitTunnelRuntimePlan,
        selectedCountryCode: String?,
        preferences: SessionPlanPreferences,
        availableProfiles: List<ConnectionProfile>,
        excludedProfileFingerprint: String = "",
        quickSpeedRequested: Boolean = false,
    ): StartedMihomoRuntime {
        return startMihomoRuntimeAttempt(
            snapshot = snapshot,
            splitTunnelPlan = splitTunnelPlan,
            selectedCountryCode = selectedCountryCode,
            topEndpoint = null,
            validateConnectivity = false,
            preferences = preferences,
            availableProfiles = availableProfiles,
            excludedProfileFingerprint = excludedProfileFingerprint,
            quickSpeedRequested = quickSpeedRequested,
        )
    }

    private suspend fun connectWithConnectionChain(
        settings: ConnectionChainSettings,
        splitTunnelPlan: SplitTunnelRuntimePlan,
        preferences: SessionPlanPreferences,
        excludedProfileFingerprint: String = "",
    ): StartedMihomoRuntime {
        val subscriptions = subscriptionStore.readUserSubscriptions()
        val sourceIds = buildList {
            add(SubscriptionStore.DEFAULT_SUBSCRIPTION_ID)
            addAll(subscriptions.map(UserSubscription::id))
        }.distinct()
        val cachedSources = sourceIds.mapNotNull { subscriptionId ->
            val snapshot = configRepository.readCachedMihomoConfigOrNull(subscriptionId)
                ?: return@mapNotNull null
            ConnectionChainSource(
                subscriptionId = subscriptionId,
                subscriptionName = subscriptions.firstOrNull { it.id == subscriptionId }?.name
                    ?: getString(R.string.app_name),
                snapshot = snapshot,
            )
        }
        val allProfiles = cachedSources.flatMap { it.snapshot.catalog.profiles }
        val allowIpv6Literals = networkMonitor.hasUsableIpv6DefaultNetwork() ||
            (allProfiles.isNotEmpty() && allProfiles.all { it.isIpv6Literal })
        val sources = cachedSources.mapNotNull { source ->
            val runtimeProfiles = source.snapshot.catalog.profiles.filter {
                allowIpv6Literals || !it.isIpv6Literal
            }
            source.takeIf { runtimeProfiles.isNotEmpty() }?.copy(
                snapshot = source.snapshot.copy(
                    catalog = source.snapshot.catalog.copy(profiles = runtimeProfiles),
                ),
            )
        }
        val delayRecords = sources.flatMap { source ->
            subscriptionStore.readConnectionDelayRecords(
                subscriptionId = source.subscriptionId,
                profiles = source.snapshot.catalog.profiles,
            )
        }
        val plannedRuntimes = withContext(Dispatchers.Default) {
            ConnectionChainPlanner.plans(
                settings,
                sources,
                delayRecords,
                allowIpv6Literals = allowIpv6Literals,
            )
        }
        val plans = plannedRuntimes.filterNot { plan ->
            shouldExcludeRecoveryChainPlan(
                excludedFingerprint = excludedProfileFingerprint,
                finalFingerprint = plan.finalHop.candidate.profile.fingerprint,
                finalHopMode = settings.hop(plan.finalHop.slot).mode,
            )
        }
        if (plans.isEmpty() && plannedRuntimes.isNotEmpty()) {
            throw IOException("No alternative automatic connection chain is available")
        }
        if (plans.size != plannedRuntimes.size) {
            DiagnosticLogger.info(
                this,
                "mihomo.chain.recovery.excluded",
                "excluded=${plannedRuntimes.size - plans.size} remaining=${plans.size}",
            )
        }
        var lastFailure: Throwable? = null
        plans.forEachIndexed { index, plan ->
            try {
                val runtimeSnapshot = MihomoConfigParser.parse(plan.rawYaml)
                val runtime = startMihomoRuntimeAttempt(
                    snapshot = runtimeSnapshot,
                    splitTunnelPlan = splitTunnelPlan,
                    selectedCountryCode = null,
                    topEndpoint = null,
                    validateConnectivity = true,
                    forcedProxyName = plan.finalRuntimeName,
                    preferences = preferences,
                    availableProfiles = runtimeSnapshot.catalog.profiles,
                )
                val finalCandidate = plan.finalHop.candidate
                DiagnosticLogger.info(
                    this,
                    "mihomo.chain.healthy",
                    "attempt=${index + 1}/${plans.size} hops=${plan.hops.size} chain=${plan.summary}",
                )
                return runtime.copy(
                    profile = MihomoConnectionOptionsPolicy.applyTo(
                        finalCandidate.profile,
                        preferences.connectionOptions,
                    ),
                    endpoint = CleanIpResult(
                        ip = finalCandidate.profile.server,
                        port = finalCandidate.profile.port,
                        latencyMs = runtime.delayMs,
                        lossRate = 0.0,
                        checkedAt = System.currentTimeMillis(),
                    ),
                    availableProfiles = emptyList(),
                    nativeAutomaticStart = false,
                    subscriptionId = finalCandidate.subscriptionId,
                    chainHopCount = plan.hops.size,
                )
            } catch (error: Throwable) {
                if (
                    error is CancellationException ||
                    error is MihomoCoreBusyException ||
                    error is MihomoCoreSetupTimeoutException
                ) {
                    throw error
                }
                lastFailure = error
                DiagnosticLogger.warn(
                    this,
                    "mihomo.chain.rejected",
                    "attempt=${index + 1}/${plans.size} hops=${plan.hops.size} chain=${plan.summary}",
                    error,
                )
            }
        }
        throw IOException("No configured connection chain passed the runtime health check", lastFailure)
    }

    private suspend fun connectWithTopIpCandidates(
        eventPrefix: String,
        snapshot: MihomoSubscriptionSnapshot,
        profiles: List<ConnectionProfile>,
        splitTunnelPlan: SplitTunnelRuntimePlan,
        selectedCountryCode: String?,
        bypassConnectionCache: Boolean,
        excludedEndpoint: CleanIpResult?,
        excludedProfileFingerprint: String = "",
        frontingIps: List<String> = frontingIpPreferenceStore.readFrontingIps(),
        useCachedFirst: Boolean = true,
        preferences: SessionPlanPreferences,
        availableProfiles: List<ConnectionProfile>,
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
                        preferences = preferences,
                        availableProfiles = availableProfiles,
                        excludedProfileFingerprint = excludedProfileFingerprint,
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
            preferences = preferences,
            availableProfiles = availableProfiles,
            excludedProfileFingerprint = excludedProfileFingerprint,
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
            "candidates=${candidates.size} latencyMs=${candidates.first().latencyMs}",
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
        preferences: SessionPlanPreferences,
        availableProfiles: List<ConnectionProfile>,
        excludedProfileFingerprint: String,
    ): StartedMihomoRuntime {
        return connectStartupTopIpCandidates(
            eventPrefix = eventPrefix,
            phase = candidatePlan.primaryPhase,
            candidates = candidatePlan.primaryCandidates + candidatePlan.exhaustiveCandidates,
            snapshot = snapshot,
            splitTunnelPlan = splitTunnelPlan,
            selectedCountryCode = selectedCountryCode,
            preferences = preferences,
            availableProfiles = availableProfiles,
            excludedProfileFingerprint = excludedProfileFingerprint,
        )
    }

    private suspend fun connectStartupTopIpCandidates(
        eventPrefix: String,
        phase: String,
        candidates: List<CleanIpResult>,
        snapshot: MihomoSubscriptionSnapshot,
        splitTunnelPlan: SplitTunnelRuntimePlan,
        selectedCountryCode: String?,
        preferences: SessionPlanPreferences,
        availableProfiles: List<ConnectionProfile>,
        excludedProfileFingerprint: String,
    ): StartedMihomoRuntime {
        if (candidates.isEmpty()) {
            throw IOException("No $phase top-IP candidates are available")
        }
        val quarantineScope = ProfileFingerprint.sha256(snapshot.rawConfig)
        val availableCandidates = if (preferences.tlsIntegrityEnabled) {
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
                    "phase=$phase attempt=$attempt/${availableCandidates.size} latencyMs=${endpoint.latencyMs} lossRate=${endpoint.lossRate}",
                )
                return startMihomoRuntimeAttempt(
                    snapshot = snapshot,
                    splitTunnelPlan = splitTunnelPlan,
                    selectedCountryCode = selectedCountryCode,
                    topEndpoint = endpoint,
                    preferences = preferences,
                    availableProfiles = availableProfiles,
                    excludedProfileFingerprint = excludedProfileFingerprint,
                ).also { startedRuntime ->
                    DiagnosticLogger.info(
                        this,
                        "startup.topIp.connected",
                        "phase=$phase attempt=$attempt/${availableCandidates.size}",
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
                        "phase=$phase",
                        error,
                    )
                }
                DiagnosticLogger.warn(
                    this,
                    "startup.topIp.rejected",
                    "phase=$phase attempt=$attempt/${availableCandidates.size}",
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
        forcedProxyName: String? = null,
        preferences: SessionPlanPreferences,
        availableProfiles: List<ConnectionProfile>,
        excludedProfileFingerprint: String = "",
        quickSpeedRequested: Boolean = false,
    ): StartedMihomoRuntime {
        val dpiBypassEnabled = forcedProxyName == null && preferences.dpiBypassEnabled
        val request = SessionPlanRequest(
            snapshot = snapshot,
            splitTunnelPlan = splitTunnelPlan,
            availableProfiles = availableProfiles,
            selectedCountryCode = selectedCountryCode,
            topEndpoint = topEndpoint,
            validateConnectivity = validateConnectivity,
            dpiBypassEnabled = false,
            dpiBypassPort = null,
            forcedProxyName = forcedProxyName,
            excludedProfileFingerprint = excludedProfileFingerprint,
            quickSpeedRequested = quickSpeedRequested,
        )

        fun plan(
            dpiEnabled: Boolean,
            dpiPort: Int?,
            allowAutomaticBridge: Boolean,
        ): SessionPlan =
            SessionPlanner.resolve(
                request = request.copy(
                    dpiBypassEnabled = dpiEnabled,
                    dpiBypassPort = dpiPort,
                    allowAutomaticBridge = allowAutomaticBridge,
                ),
                preferences = preferences,
            )

        var automaticBridgeAvailable = true
        suspend fun startWithAutomaticBridgeFallback(dpiEnabled: Boolean): StartedMihomoRuntime {
            val dpiPort = if (dpiEnabled) DpiBypassPort.allocate() else null
            if (!automaticBridgeAvailable) {
                return startMihomoRuntimeAttemptOnce(
                    plan(dpiEnabled, dpiPort, allowAutomaticBridge = false),
                )
            }
            return try {
                startMihomoRuntimeAttemptOnce(
                    plan(dpiEnabled, dpiPort, allowAutomaticBridge = true),
                )
            } catch (error: AutomaticRoutingBridgeStartupException) {
                DiagnosticLogger.warn(
                    this,
                    "mihomo.automatic.bridge.startupFallback",
                    "retry=originalRuntime",
                    error,
                )
                automaticBridgeAvailable = false
                stopActiveCoreForReplacement()
                startMihomoRuntimeAttemptOnce(
                    plan(dpiEnabled, dpiPort, allowAutomaticBridge = false),
                )
            }
        }

        return try {
            startWithAutomaticBridgeFallback(dpiBypassEnabled)
        } catch (error: DpiBypassStartupException) {
            DiagnosticLogger.warn(
                this,
                "dpiBypass.fallback.direct",
                "fronting=${topEndpoint != null}",
                error,
            )
            stopActiveCoreForReplacement()
            startWithAutomaticBridgeFallback(false)
        }
    }

    private suspend fun startMihomoRuntimeAttemptOnce(plan: SessionPlan): StartedMihomoRuntime {
        val snapshot = plan.snapshot
        val splitTunnelPlan = plan.splitTunnelPlan
        val availableProfiles = plan.availableProfiles
        val selectedCountryCode = plan.selectedCountryCode
        val topEndpoint = plan.topEndpoint
        val validateConnectivity = plan.validateConnectivity
        val dpiBypassEnabled = plan.dpiBypassEnabled
        val dpiBypassPort = plan.dpiBypassPort
        val forcedProxyName = plan.forcedProxyName
        val excludedProfileFingerprint = plan.excludedProfileFingerprint
        val connectionOptions = plan.connectionOptions
        val selectedSubscriptionId = plan.selectedSubscriptionId
        val explicitProfile = plan.explicitProfile
        val selectedAutomaticTypes = plan.selectedAutomaticTypes
        val automaticSelections = plan.automaticSelections
        val automaticSelectionEligible = plan.automaticSelectionEligible
        val quickSpeedEligible = plan.quickSpeedEligible
        val bridgeEligible = plan.bridgeEligible
        val bridgeResult = plan.bridgeResult
        val selectedMap = plan.selectedMap
        if (activeRuntimePaths != null || !coreLifecycle.isIdle()) {
            stopActiveCoreForReplacement()
        }
        if (plan.quickSpeedRequested && !quickSpeedEligible) {
            DiagnosticLogger.info(
                this,
                "mihomo.quickSpeed.skipped",
                "reason=modeIneligible country=${selectedCountryCode != null} " +
                    "explicit=${explicitProfile != null} filtered=${selectedAutomaticTypes.isNotEmpty()} " +
                    "fronting=${topEndpoint != null} forced=${forcedProxyName != null}",
            )
        } else if (quickSpeedEligible) {
            DiagnosticLogger.info(this, "mihomo.quickSpeed.eligible")
        }
        val paths = try {
            MihomoRuntimeConfigBuilder(this).write(plan)
        } catch (error: Throwable) {
            if (
                shouldRetryOriginalAfterAutomaticBridgeFailure(
                    bridgeApplied = bridgeResult.applied,
                    phase = AutomaticBridgeFailurePhase.ConfigCoreOrController,
                    error = error,
                )
            ) {
                throw AutomaticRoutingBridgeStartupException(error)
            }
            throw error
        }
        DiagnosticLogger.info(
            this,
            "mihomo.automatic.bridge",
            "eligible=$bridgeEligible applied=${bridgeResult.applied} reason=${bridgeResult.reason} " +
                "root=${bridgeResult.rootName.orEmpty()} target=${bridgeResult.targetName.orEmpty()}",
        )
        DiagnosticLogger.info(
            this,
            "mihomo.runtime.files",
            "config=${paths.runtimeConfigYaml.absolutePath} service=${paths.serviceJson.absolutePath} patch=${paths.patchFinalJson.absolutePath} controller=${MihomoRuntimeDefaults.CONTROLLER_HOST}:${paths.controlPort} serverOverride=${plan.serverOverrideIp != null} portOverride=${plan.serverOverridePort != null}",
        )
        DiagnosticLogger.info(
            this,
            "mihomo.startup.flow",
            "frontingScanner=${plan.serverOverrideIp != null} nativeEligible=$automaticSelectionEligible bridge=${bridgeResult.applied} preselected=${selectedMap.size} dpiBypass=$dpiBypassEnabled dpiBypassPort=${dpiBypassPort ?: 0} routing=${plan.routingMode.wireName} dnsPrivacy=${plan.dns.mode.wireName} tlsIntegrity=${plan.tlsIntegrityEnabled} amneziaNoise=${connectionOptions.amneziaNoiseEnabled} randomController=true encryptedSubscription=true nodeBucket=${nodeCountBucket(snapshot.summary.proxies.size)} groups=${snapshot.summary.groups.size}",
        )

        val controller = MihomoControllerClient(paths.secret, port = paths.controlPort)
        try {
            val setupStartedAt = SystemClock.elapsedRealtime()
            setupCoreRuntime(paths, dpiBypassPort)
            activeRuntimePaths = paths
            DiagnosticLogger.info(
                this,
                "mihomo.core.setup.duration",
                "elapsedMs=${SystemClock.elapsedRealtime() - setupStartedAt} nodeBucket=${nodeCountBucket(snapshot.summary.proxies.size)}",
            )
            val controllerStartedAt = SystemClock.elapsedRealtime()
            waitForController(controller, paths)
            DiagnosticLogger.info(
                this,
                "mihomo.controller.ready.duration",
                "elapsedMs=${SystemClock.elapsedRealtime() - controllerStartedAt}",
            )
        } catch (error: Throwable) {
            if (
                error !is DpiBypassStartupException &&
                shouldRetryOriginalAfterAutomaticBridgeFailure(
                    bridgeApplied = bridgeResult.applied,
                    phase = AutomaticBridgeFailurePhase.ConfigCoreOrController,
                    error = error,
                )
            ) {
                throw AutomaticRoutingBridgeStartupException(error)
            }
            throw error
        }
        handleDefaultNetworkChanged(networkMonitor.currentDefaultNetwork(), force = true)
        val selectedName = bridgeResult.rootName
            ?: MihomoSelectionPolicy.trafficProbeGroup(snapshot.summary)?.name
            ?: MihomoSelectionPolicy.mainSelectorGroup(snapshot.summary)?.name
            ?: throw IOException("Mihomo traffic selector is unavailable")
        val preferredSelectorRoots = listOf(selectedName)
        var liveProxies = try {
            withContext(Dispatchers.IO) { controller.getProxies() }
        } catch (error: Throwable) {
            if (
                shouldRetryOriginalAfterAutomaticBridgeFailure(
                    bridgeApplied = bridgeResult.applied,
                    phase = AutomaticBridgeFailurePhase.ConfigCoreOrController,
                    error = error,
                )
            ) {
                throw AutomaticRoutingBridgeStartupException(error)
            }
            throw error
        }
        var selections: List<MihomoGroupSelection> = emptyList()
        var nativeAutomaticStart = false
        var fallbackAttemptCount = 0
        var selectedLeaf: ConnectionProfile? = null
        var quickSpeedPin: QuickSpeedPin? = null
        var quickSpeedHandled = false

        suspend fun applyAndVerify(path: List<MihomoGroupSelection>): JSONObject {
            path.forEach { selection ->
                withContext(Dispatchers.IO) {
                    controller.selectProxy(selection.selectorGroup, selection.selectedGroup)
                }
                DiagnosticLogger.info(
                    this,
                    "mihomo.selection.applied",
                    "selector=${selection.selectorGroup} selected=${selection.selectedGroup}",
                )
            }
            val updated = withContext(Dispatchers.IO) { controller.getProxies() }
            val verified = MihomoControllerProxies.currentSelections(updated, path)
                .associate { it.selectorGroup to it.selectedGroup }
            if (path.any { verified[it.selectorGroup] != it.selectedGroup }) {
                throw IOException("Mihomo selector update was not applied")
            }
            return updated
        }

        if (forcedProxyName != null || explicitProfile != null) {
            val targetName = forcedProxyName ?: explicitProfile!!.tag
            val path = MihomoControllerProxies.selectorPath(
                response = liveProxies,
                targetName = targetName,
                preferredRoots = preferredSelectorRoots,
            )
            if (path.isEmpty()) {
                throw IOException(
                    if (forcedProxyName != null) {
                        "Chained proxy is not selectable"
                    } else {
                        "Explicit connection is not selectable"
                    },
                )
            }
            liveProxies = applyAndVerify(path)
            if (!MihomoControllerProxies.isActiveThrough(liveProxies, selectedName, targetName)) {
                throw IOException("Mihomo traffic selector did not resolve through the requested connection")
            }
            verifyRuntimeHealth(
                timeoutMs = RUNTIME_HEALTH_TIMEOUT_MS,
                event = "mihomo.proxy.health.explicit",
            )
            liveProxies = withContext(Dispatchers.IO) { controller.getProxies() }
            if (!MihomoControllerProxies.isActiveThrough(liveProxies, selectedName, targetName)) {
                throw IOException("Mihomo traffic route changed before startup completed")
            }
            selections = path
        } else {
            val preferredGroups = buildList {
                bridgeResult.targetName?.let(::add)
                addAll(automaticSelections.map(MihomoGroupSelection::selectedGroup))
            }.distinct()
            val rejectedAdaptiveGroups = linkedSetOf<String>()
            val rejectedAdaptiveTypes = linkedSetOf<String>()
            var adaptiveAttempt = 0
            if (automaticSelectionEligible) {
                while (true) {
                    val adaptivePlan = MihomoControllerProxies.rootScopedAdaptivePlan(
                        response = liveProxies,
                        rootName = selectedName,
                        preferredGroupNames = preferredGroups,
                        excludedGroupNames = rejectedAdaptiveGroups,
                        excludedGroupTypes = rejectedAdaptiveTypes,
                    ) ?: break
                    adaptiveAttempt += 1
                    DiagnosticLogger.info(
                        this,
                        "mihomo.selection.adaptive.plan",
                        "root=$selectedName group=${adaptivePlan.groupName} type=${adaptivePlan.groupType} hops=${adaptivePlan.selections.size} attempt=$adaptiveAttempt",
                    )
                    var selectorVerified = false
                    try {
                        liveProxies = applyAndVerify(adaptivePlan.selections)
                        if (!MihomoControllerProxies.isActiveThrough(liveProxies, selectedName, adaptivePlan.groupName)) {
                            throw IOException("Adaptive group is not active on the traffic path")
                        }
                        selectorVerified = true
                        verifyRuntimeHealth(
                            timeoutMs = if (adaptiveAttempt == 1) {
                                RUNTIME_HEALTH_TIMEOUT_MS
                            } else {
                                FALLBACK_RUNTIME_HEALTH_TIMEOUT_MS
                            },
                            event = "mihomo.proxy.health.adaptive",
                        )
                        liveProxies = withContext(Dispatchers.IO) { controller.getProxies() }
                        if (!MihomoControllerProxies.isActiveThrough(liveProxies, selectedName, adaptivePlan.groupName)) {
                            throw IOException("Adaptive traffic route changed before startup completed")
                        }
                        if (
                            quickSpeedEligible &&
                            adaptivePlan.groupType.lowercase().filter(Char::isLetterOrDigit) == "urltest"
                        ) {
                            quickSpeedHandled = true
                            val quickResult = selectQuickFastestReconnect(
                                controller = controller,
                                paths = paths,
                                initialResponse = liveProxies,
                                rootName = selectedName,
                                groupName = adaptivePlan.groupName,
                                availableProfiles = availableProfiles,
                            )
                            liveProxies = quickResult.response
                            quickSpeedPin = quickResult.pin
                        } else if (quickSpeedEligible) {
                            quickSpeedHandled = true
                            DiagnosticLogger.info(
                                this,
                                "mihomo.quickSpeed.skipped",
                                "reason=adaptiveGroupNotUrlTest",
                            )
                        }
                        selections = adaptivePlan.selections
                        nativeAutomaticStart = true
                        DiagnosticLogger.info(
                            this,
                            "mihomo.selection.adaptive.healthy",
                            "root=$selectedName group=${adaptivePlan.groupName} type=${adaptivePlan.groupType} hops=${adaptivePlan.selections.size}",
                        )
                        break
                    } catch (error: Throwable) {
                        if (error is CancellationException) throw error
                        if (
                            !selectorVerified &&
                            adaptivePlan.groupName == bridgeResult.targetName &&
                            shouldRetryOriginalAfterAutomaticBridgeFailure(
                                bridgeApplied = bridgeResult.applied,
                                phase = AutomaticBridgeFailurePhase.Selector,
                                error = error,
                            )
                        ) {
                            throw AutomaticRoutingBridgeStartupException(error)
                        }
                        rejectedAdaptiveGroups += adaptivePlan.groupName
                        rejectedAdaptiveTypes += adaptivePlan.groupType
                        DiagnosticLogger.warn(
                            this,
                            "mihomo.selection.adaptive.rejected",
                            "root=$selectedName group=${adaptivePlan.groupName} type=${adaptivePlan.groupType} attempt=$adaptiveAttempt",
                            error,
                        )
                        liveProxies = withContext(Dispatchers.IO) { controller.getProxies() }
                        if (selectedCountryCode != null) break
                    }
                }
            }
            if (automaticSelectionEligible && adaptiveAttempt == 0) {
                if (bridgeResult.applied) {
                    throw AutomaticRoutingBridgeStartupException(
                        IOException("Bridged automatic selector target is unavailable"),
                    )
                }
                DiagnosticLogger.info(
                    this,
                    "mihomo.selection.adaptive.unavailable",
                    "root=$selectedName",
                )
            }

            if (!nativeAutomaticStart) {
                val candidates = ConnectionLocationPolicy.filterProfiles(
                    profiles = ConnectionTypeSelectionPolicy.filterProfiles(
                        availableProfiles,
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
                val selectableNames = MihomoControllerProxies.selectableTargetNames(
                    response = liveProxies,
                    targetNames = candidates.map(ConnectionProfile::tag),
                    preferredRoots = preferredSelectorRoots,
                )
                val orderedCandidates = AutomaticConnectionCandidatePolicy.order(
                    profiles = candidates.filter { it.tag in selectableNames },
                    records = records,
                    lastSelectedProfile = scanStateStore.readLastSelectedProfile(candidates),
                    excludedFingerprint = excludedProfileFingerprint,
                    limit = CleanIpDefaults.STARTUP_RUNTIME_ATTEMPTS.coerceAtLeast(1),
                )
                var lastFailure: Throwable? = null
                for ((index, candidate) in orderedCandidates.withIndex()) {
                    fallbackAttemptCount += 1
                    val path = MihomoControllerProxies.selectorPath(
                        response = liveProxies,
                        targetName = candidate.tag,
                        preferredRoots = preferredSelectorRoots,
                    )
                    if (path.isEmpty()) continue
                    try {
                        liveProxies = applyAndVerify(path)
                        if (!MihomoControllerProxies.isActiveThrough(liveProxies, selectedName, candidate.tag)) {
                            throw IOException("Fallback connection is not active on the traffic path")
                        }
                        verifyRuntimeHealth(
                            timeoutMs = FALLBACK_RUNTIME_HEALTH_TIMEOUT_MS,
                            event = "mihomo.proxy.health.fallback",
                        )
                        liveProxies = withContext(Dispatchers.IO) { controller.getProxies() }
                        if (!MihomoControllerProxies.isActiveThrough(liveProxies, selectedName, candidate.tag)) {
                            throw IOException("Fallback traffic route changed before startup completed")
                        }
                        selections = path
                        selectedLeaf = candidate
                        DiagnosticLogger.info(
                            this,
                            "mihomo.selection.automatic.healthy",
                            "attempt=${index + 1}/${orderedCandidates.size}",
                        )
                        break
                    } catch (error: Throwable) {
                        if (error is CancellationException) throw error
                        lastFailure = error
                        DiagnosticLogger.warn(
                            this,
                            "mihomo.selection.automatic.rejected",
                            "attempt=${index + 1}/${orderedCandidates.size}",
                            error,
                        )
                        liveProxies = withContext(Dispatchers.IO) { controller.getProxies() }
                    }
                }
                if (selectedLeaf == null) {
                    throw IOException(
                        "No selectable connection passed the runtime health check",
                        lastFailure,
                    )
                }
            }
            if (quickSpeedEligible && !quickSpeedHandled) {
                DiagnosticLogger.info(
                    this,
                    "mihomo.quickSpeed.skipped",
                    "reason=noHealthyUrlTestPath",
                )
            }
        }

        val selection = selections.firstOrNull()
        var activeProxyName = MihomoControllerProxies.activeProxyName(liveProxies, selectedName)
        DiagnosticLogger.info(
            this,
            "mihomo.active.proxy",
            "selected=$selectedName active=${activeProxyName.orEmpty()} nativeAutomatic=$nativeAutomaticStart fallbackAttempts=$fallbackAttemptCount",
        )
        var delayProbeName = activeProxyName?.takeIf(String::isNotBlank) ?: selectedName
        if (delayProbeName.isBlank()) {
            throw IOException("No Mihomo proxy selected for delay test")
        }
        var lastDelayFailure: Throwable? = null
        var delayMs = quickSpeedPin?.delayMs?.toLong() ?: if (validateConnectivity) {
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
        } else {
            DiagnosticLogger.info(this, "mihomo.delay.deferred", "name=$delayProbeName")
        }
        suspend fun restoreQuickSpeedPinIfInvalidated(stage: String) {
            val pin = quickSpeedPin ?: return
            if (
                quickSpeedNetworkUnchanged(
                    pin.networkFingerprint,
                    networkMonitor.currentDefaultNetwork().quickSpeedNetworkFingerprint(),
                ) &&
                activeQuickSpeedPin == pin &&
                invalidatedQuickSpeedPin != pin
            ) {
                return
            }
            liveProxies = controllerSelectionMutex.withLock {
                restoreQuickSpeedSelection(controller, pin.groupName, pin.originalFixed)
            }
            if (activeQuickSpeedPin == pin) activeQuickSpeedPin = null
            if (invalidatedQuickSpeedPin == pin) invalidatedQuickSpeedPin = null
            quickSpeedPin = null
            verifyRuntimeHealth(
                timeoutMs = FALLBACK_RUNTIME_HEALTH_TIMEOUT_MS,
                event = "mihomo.quickSpeed.network.health",
            )
            liveProxies = runInterruptible(Dispatchers.IO) { controller.getProxies() }
            if (!MihomoControllerProxies.isActiveThrough(liveProxies, pin.rootName, pin.groupName)) {
                throw IOException("Automatic route changed after quick speed pin restoration")
            }
            activeProxyName = MihomoControllerProxies.activeProxyName(liveProxies, selectedName)
            delayProbeName = activeProxyName?.takeIf(String::isNotBlank) ?: selectedName
            delayMs = -1L
            DiagnosticLogger.info(
                this,
                "mihomo.quickSpeed.network.invalidated",
                "stage=$stage",
            )
        }

        verifyTlsIntegrity(plan.tlsIntegrityEnabled)
        restoreQuickSpeedPinIfInvalidated("beforeTun")
        if (plan.effectiveDeviceAccess == EffectiveDeviceAccess.TunnelAccess) {
            startCoreTun(paths, splitTunnelPlan)
        } else {
            DiagnosticLogger.info(
                this,
                "mihomo.core.started",
                "config=${paths.runtimeConfigYaml.absolutePath} mode=proxy tun=false endpoint=127.0.0.1:${MihomoRuntimeDefaults.MIXED_PORT}",
            )
        }
        restoreQuickSpeedPinIfInvalidated("beforeStarted")
        DiagnosticLogger.info(
            this,
            "startup.topIp.validated",
            "fronting=${topEndpoint != null} selected=$selectedName active=${activeProxyName.orEmpty()} delayProbe=$delayProbeName delayMs=$delayMs validated=$validateConnectivity",
        )

        val profile = MihomoConnectionOptionsPolicy.applyTo(
            activeProfile(snapshot, selectedCountryCode, selection, activeProxyName),
            connectionOptions,
        )
        val endpoint = if (topEndpoint == null) {
            originalEndpointForSelection(snapshot, selectedCountryCode, activeProxyName, delayMs)
        } else {
            runtimeEndpointForSelection(
                topEndpoint = topEndpoint,
                snapshot = snapshot,
                selectedCountryCode = selectedCountryCode,
                selection = selection,
                activeProxyName = activeProxyName,
                serverOverridePort = plan.serverOverridePort,
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
            availableProfiles = availableProfiles,
            nativeAutomaticStart = nativeAutomaticStart,
            subscriptionId = selectedSubscriptionId,
            selectorRootName = selectedName,
            selectorRoots = preferredSelectorRoots,
            quickSpeedPin = quickSpeedPin,
        )
    }

    private suspend fun selectQuickFastestReconnect(
        controller: MihomoControllerClient,
        paths: MihomoRuntimePaths,
        initialResponse: JSONObject,
        rootName: String,
        groupName: String,
        availableProfiles: List<ConnectionProfile>,
    ): QuickSpeedSelectionResult = controllerSelectionMutex.withLock {
        val startedAt = SystemClock.elapsedRealtime()
        val startingNetworkFingerprint = networkMonitor.currentDefaultNetwork().quickSpeedNetworkFingerprint()
        var response = initialResponse
        var shortlistSnapshots = 1
        var graphReads = 1 // Reused initial controller snapshot.
        val availableProfileNames = availableProfiles.mapTo(hashSetOf(), ConnectionProfile::tag)
        var selectedPlan = MihomoQuickFastestPolicy.plan(
            response = response,
            groupName = groupName,
            availableProfileNames = availableProfileNames,
        )
        var hasRequiredCapabilities = MihomoQuickFastestPolicy.hasRequiredCapabilities(response, groupName)
        if (selectedPlan == null && hasRequiredCapabilities) {
            try {
                val remainingWaitMs = QUICK_SPEED_SHORTLIST_TIMEOUT_MS -
                    (SystemClock.elapsedRealtime() - startedAt)
                if (remainingWaitMs > 0L) delay(remainingWaitMs)
                graphReads += 1
                response = runInterruptible(Dispatchers.IO) { controller.getProxies() }
                shortlistSnapshots += 1
                selectedPlan = MihomoQuickFastestPolicy.plan(
                    response = response,
                    groupName = groupName,
                    availableProfileNames = availableProfileNames,
                )
                hasRequiredCapabilities = MihomoQuickFastestPolicy.hasRequiredCapabilities(response, groupName)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Throwable) {
                DiagnosticLogger.info(
                    this,
                    "mihomo.quickSpeed.shortlist.refresh.failed",
                    "error=${error::class.java.simpleName}",
                )
            }
        }
        if (selectedPlan == null) {
            if (!MihomoControllerProxies.isActiveThrough(response, rootName, groupName)) {
                throw IOException("Automatic route changed while waiting for quick speed candidates")
            }
            DiagnosticLogger.info(
                this,
                "mihomo.quickSpeed.skipped",
                "reason=${if (hasRequiredCapabilities) "insufficientLiveCandidates" else "missingCapabilities"} " +
                    "shortlistSnapshots=$shortlistSnapshots " +
                    "graphReads=$graphReads elapsedMs=${SystemClock.elapsedRealtime() - startedAt}",
            )
            return@withLock QuickSpeedSelectionResult(response, null)
        }

        val groupSize = response.optJSONObject("proxies")
            ?.optJSONObject(groupName)
            ?.optJSONArray("all")
            ?.length()
            ?: 0
        DiagnosticLogger.info(
            this,
            "mihomo.quickSpeed.plan",
            "groupSize=$groupSize measured=${selectedPlan.candidates.size} " +
                "shortlist=${selectedPlan.candidates.size}",
        )

        val measurements = mutableListOf<MihomoQuickFastestMeasurement>()
        var completedBytes = 0L
        var selectionChanged = false
        var restorationVerified = false
        var committedPin: QuickSpeedPin? = null
        var failure: Throwable? = null
        var lastVerifiedCandidateName: String? = null

        fun ensureQuickSpeedNetworkUnchanged() {
            val currentKey = networkMonitor.currentDefaultNetwork().quickSpeedNetworkFingerprint()
            if (!quickSpeedNetworkUnchanged(startingNetworkFingerprint, currentKey)) {
                throw IOException("Default network changed during the quick speed test")
            }
        }

        try {
            selectedPlan.candidates.forEachIndexed { index, candidate ->
                val attemptStartedAt = SystemClock.elapsedRealtime()
                try {
                    selectionChanged = true
                    val speedKbps = withTimeoutOrNull(QUICK_SPEED_DOWNLOAD_TIMEOUT_MS.toLong()) {
                        runInterruptible(Dispatchers.IO) {
                            controller.selectProxy(selectedPlan.groupName, candidate.name)
                        }
                        graphReads += 1
                        response = runInterruptible(Dispatchers.IO) { controller.getProxies() }
                        if (
                            !MihomoQuickFastestPolicy.isPinnedActive(
                                response = response,
                                rootName = rootName,
                                groupName = selectedPlan.groupName,
                                selectedName = candidate.name,
                            )
                        ) {
                            throw IOException("Quick speed candidate did not become active")
                        }
                        lastVerifiedCandidateName = candidate.name
                        ensureQuickSpeedNetworkUnchanged()
                        runInterruptible(Dispatchers.IO) {
                            MihomoRuntimeHealth.downloadSpeedKbpsThroughMixedProxy(
                                downloadBytes = MihomoRuntimeDefaults.SPEED_TEST_BYTES,
                                timeoutMs = QUICK_SPEED_DOWNLOAD_TIMEOUT_MS,
                            )
                        }
                    }
                    ensureQuickSpeedNetworkUnchanged()
                    if (speedKbps != null) {
                        measurements += MihomoQuickFastestMeasurement(candidate, speedKbps)
                        completedBytes += MihomoRuntimeDefaults.SPEED_TEST_BYTES
                    }
                    DiagnosticLogger.info(
                        this,
                        "mihomo.quickSpeed.attempt",
                        "attempt=${index + 1}/${selectedPlan.candidates.size} delayMs=${candidate.delayMs} " +
                            "speedKbps=${speedKbps ?: -1} completedBytes=${if (speedKbps == null) 0 else MihomoRuntimeDefaults.SPEED_TEST_BYTES} " +
                            "elapsedMs=${SystemClock.elapsedRealtime() - attemptStartedAt}",
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    DiagnosticLogger.warn(
                        this,
                        "mihomo.quickSpeed.attempt.failed",
                        "attempt=${index + 1}/${selectedPlan.candidates.size} delayMs=${candidate.delayMs} " +
                            "elapsedMs=${SystemClock.elapsedRealtime() - attemptStartedAt} " +
                            "error=${error::class.java.simpleName}",
                    )
                }
            }

            val winner = MihomoQuickFastestPolicy.winner(measurements)
                ?: throw IOException("Quick speed test produced fewer than two complete measurements")
            ensureQuickSpeedNetworkUnchanged()
            if (lastVerifiedCandidateName != winner.candidate.name) {
                runInterruptible(Dispatchers.IO) {
                    controller.selectProxy(selectedPlan.groupName, winner.candidate.name)
                }
                graphReads += 1
                response = runInterruptible(Dispatchers.IO) { controller.getProxies() }
                if (
                    !MihomoQuickFastestPolicy.isPinnedActive(
                        response = response,
                        rootName = rootName,
                        groupName = selectedPlan.groupName,
                        selectedName = winner.candidate.name,
                    )
                ) {
                    throw IOException("Quick speed winner did not become active")
                }
            }
            verifyRuntimeHealth(
                timeoutMs = FALLBACK_RUNTIME_HEALTH_TIMEOUT_MS,
                event = "mihomo.quickSpeed.health",
            )
            graphReads += 1
            response = runInterruptible(Dispatchers.IO) { controller.getProxies() }
            if (
                !MihomoQuickFastestPolicy.isPinnedActive(
                    response = response,
                    rootName = rootName,
                    groupName = selectedPlan.groupName,
                    selectedName = winner.candidate.name,
                )
            ) {
                throw IOException("Quick speed winner changed before startup completed")
            }
            ensureQuickSpeedNetworkUnchanged()
            committedPin = QuickSpeedPin(
                paths = paths,
                networkFingerprint = startingNetworkFingerprint,
                rootName = rootName,
                groupName = selectedPlan.groupName,
                originalFixed = selectedPlan.originalFixed,
                selectedName = winner.candidate.name,
                delayMs = winner.candidate.delayMs,
                graphReads = AtomicInteger(graphReads),
            )
            activeQuickSpeedPin = committedPin
            invalidatedQuickSpeedPin = null
            DiagnosticLogger.info(
                this,
                "mihomo.quickSpeed.winner",
                "delayMs=${winner.candidate.delayMs} speedKbps=${winner.speedKbps} " +
                    "completed=${measurements.size}/${selectedPlan.candidates.size}",
            )
        } catch (error: Throwable) {
            failure = error
        } finally {
            if (committedPin == null && selectionChanged) {
                try {
                    graphReads += 1
                    response = restoreQuickSpeedSelection(
                        controller,
                        selectedPlan.groupName,
                        selectedPlan.originalFixed,
                    )
                    restorationVerified = true
                    DiagnosticLogger.info(
                        this,
                        "mihomo.quickSpeed.restore.ok",
                        "fixed=${if (selectedPlan.originalFixed.isEmpty()) "automatic" else "preset"}",
                    )
                } catch (restoreError: Throwable) {
                    failure?.addSuppressed(restoreError)
                    if (failure == null) failure = restoreError
                    DiagnosticLogger.warn(
                        this,
                        "mihomo.quickSpeed.restore.failed",
                        "error=${restoreError::class.java.simpleName}",
                    )
                }
            }
        }

        try {
            failure?.let { error ->
                if (error is CancellationException) throw error
                if (selectionChanged && !restorationVerified) {
                    throw IOException("Quick speed selection could not be restored", error)
                }
                DiagnosticLogger.warn(
                    this,
                    "mihomo.quickSpeed.failedOpen",
                    "error=${error::class.java.simpleName}",
                )
                try {
                    verifyRuntimeHealth(
                        timeoutMs = FALLBACK_RUNTIME_HEALTH_TIMEOUT_MS,
                        event = "mihomo.quickSpeed.restore.health",
                    )
                    graphReads += 1
                    response = runInterruptible(Dispatchers.IO) { controller.getProxies() }
                    if (!MihomoControllerProxies.isActiveThrough(response, rootName, selectedPlan.groupName)) {
                        throw IOException("Automatic route changed after the quick speed test")
                    }
                } catch (healthError: Throwable) {
                    if (healthError is CancellationException) throw healthError
                    healthError.addSuppressed(error)
                    throw IOException("Automatic route is unhealthy after the quick speed test", healthError)
                }
            }
        } finally {
            DiagnosticLogger.info(
                this,
                "mihomo.quickSpeed.complete",
                "attempts=${selectedPlan.candidates.size} successes=${measurements.size} " +
                    "bytes=$completedBytes shortlistSnapshots=$shortlistSnapshots graphReads=$graphReads " +
                    "elapsedMs=${SystemClock.elapsedRealtime() - startedAt}",
            )
        }
        QuickSpeedSelectionResult(response, committedPin)
    }

    private suspend fun restoreQuickSpeedSelection(
        controller: MihomoControllerClient,
        groupName: String,
        originalFixed: String,
    ): JSONObject = withContext(NonCancellable + Dispatchers.IO) {
        if (originalFixed.isEmpty()) {
            controller.clearProxySelection(groupName)
        } else {
            controller.selectProxy(groupName, originalFixed)
        }
        controller.getProxies().also { restored ->
            val group = restored.optJSONObject("proxies")?.optJSONObject(groupName)
                ?: throw IOException("Quick speed group is missing after restore")
            val fixed = group.opt("fixed") as? String
                ?: throw IOException("Quick speed fixed selection is missing after restore")
            if (fixed != originalFixed) {
                throw IOException("Quick speed selection was not restored")
            }
        }
    }

    private suspend fun prepareQuickSpeedRuntimeForStarted(
        startedRuntime: StartedMihomoRuntime,
    ): StartedMihomoRuntime {
        val pin = startedRuntime.quickSpeedPin ?: return startedRuntime
        val controller = MihomoControllerClient(pin.paths.secret, port = pin.paths.controlPort)
        pin.graphReads.incrementAndGet()
        var response = runInterruptible(Dispatchers.IO) { controller.getProxies() }
        val stillValid = quickSpeedNetworkUnchanged(
            pin.networkFingerprint,
            networkMonitor.currentDefaultNetwork().quickSpeedNetworkFingerprint(),
        ) && activeQuickSpeedPin == pin && invalidatedQuickSpeedPin != pin &&
            MihomoQuickFastestPolicy.isPinnedActive(
                response = response,
                rootName = pin.rootName,
                groupName = pin.groupName,
                selectedName = pin.selectedName,
            )
        if (stillValid) {
            DiagnosticLogger.info(
                this,
                "mihomo.quickSpeed.beforeStarted.verified",
                "graphReads=${pin.graphReads.get()}",
            )
            return startedRuntime
        }

        pin.graphReads.incrementAndGet()
        response = restoreQuickSpeedSelection(controller, pin.groupName, pin.originalFixed)
        if (activeQuickSpeedPin == pin) activeQuickSpeedPin = null
        if (invalidatedQuickSpeedPin == pin) invalidatedQuickSpeedPin = null
        verifyRuntimeHealth(
            timeoutMs = FALLBACK_RUNTIME_HEALTH_TIMEOUT_MS,
            event = "mihomo.quickSpeed.beforeStarted.health",
        )
        pin.graphReads.incrementAndGet()
        response = runInterruptible(Dispatchers.IO) { controller.getProxies() }
        if (!MihomoControllerProxies.isActiveThrough(response, pin.rootName, pin.groupName)) {
            throw IOException("Automatic route changed before startup publication")
        }
        val activeName = MihomoControllerProxies.activeProxyName(response, pin.rootName)
        val resolvedProfile = startedRuntime.availableProfiles.firstOrNull { it.tag == activeName }
        val profile = resolvedProfile ?: ConnectionProfile(
            tag = pin.groupName,
            type = "mihomo-group",
            server = MihomoRuntimeDefaults.CONTROLLER_HOST,
            port = MihomoRuntimeDefaults.MIXED_PORT,
            transport = "",
            validationHost = MihomoRuntimeDefaults.CONTROLLER_HOST,
        )
        val endpoint = if (resolvedProfile == null) {
            CleanIpResult(
                ip = MihomoRuntimeDefaults.CONTROLLER_HOST,
                port = MihomoRuntimeDefaults.MIXED_PORT,
                latencyMs = -1L,
                lossRate = 0.0,
                checkedAt = System.currentTimeMillis(),
            )
        } else if (profile.fingerprint == startedRuntime.profile.fingerprint) {
            startedRuntime.endpoint
        } else {
            CleanIpResult(
                ip = profile.server,
                port = profile.port,
                latencyMs = -1L,
                lossRate = 0.0,
                checkedAt = System.currentTimeMillis(),
            )
        }
        DiagnosticLogger.info(
            this,
            "mihomo.quickSpeed.beforeStarted.restored",
            "graphReads=${pin.graphReads.get()}",
        )
        return startedRuntime.copy(
            profile = profile,
            endpoint = endpoint,
            delayMs = -1L,
            delayProbeName = resolvedProfile?.tag ?: pin.groupName,
            quickSpeedPin = null,
        )
    }

    private suspend fun applyStartedRuntime(
        startedRuntimeCandidate: StartedMihomoRuntime,
        eventPrefix: String,
        notice: String? = null,
        showServer: Boolean,
    ) = controllerSelectionMutex.withLock {
        var startedRuntime = prepareQuickSpeedRuntimeForStarted(startedRuntimeCandidate)
        while (true) {
            val committed = synchronized(quickSpeedStartedLock) {
                val pendingPin = startedRuntime.quickSpeedPin
                val invalidated = pendingPin != null &&
                    (
                        invalidatedQuickSpeedPin == pendingPin ||
                            !quickSpeedNetworkUnchanged(
                                pendingPin.networkFingerprint,
                                networkMonitor.currentDefaultNetwork().quickSpeedNetworkFingerprint(),
                            )
                        )
                if (invalidated) {
                    false
                } else {
                    commitStartedRuntime(startedRuntime, eventPrefix, notice, showServer)
                    true
                }
            }
            if (committed) return@withLock
            startedRuntime = prepareQuickSpeedRuntimeForStarted(startedRuntime)
        }
    }

    private fun commitStartedRuntime(
        startedRuntime: StartedMihomoRuntime,
        eventPrefix: String,
        notice: String?,
        showServer: Boolean,
    ) {
        activeProfile = startedRuntime.profile
        activeProfileShowsServer = showServer
        activeEndpoint = startedRuntime.endpoint
        activeDelayMs = startedRuntime.delayMs
        activeRuntimePaths = startedRuntime.paths
        activeNativeAutomaticStart = startedRuntime.nativeAutomaticStart
        activeConnectionChained = startedRuntime.chainHopCount > 1
        activeChainHopCount = startedRuntime.chainHopCount
        activeSubscriptionId = startedRuntime.subscriptionId
        activeSelectorRootName = startedRuntime.selectorRootName
        activeSelectorRoots = startedRuntime.selectorRoots
        activeAvailableProfiles = startedRuntime.availableProfiles
        activeRuntimeUsesEndpointOverride = startedRuntime.cacheEndpoint
        activeQuickSpeedPin = startedRuntime.quickSpeedPin?.takeIf { activeQuickSpeedPin == it }
        activeSelectableConnectionFingerprints = emptySet()
        activeSelectorReady = false
        val initialLeaf = startedRuntime.availableProfiles.firstOrNull { candidate ->
            candidate.fingerprint == startedRuntime.profile.fingerprint
        }
        activeConnectionTag = initialLeaf?.tag
            ?: startedRuntime.profile.tag.takeIf { activeConnectionChained }.orEmpty()
        activeConnectionFingerprint = initialLeaf?.fingerprint
            ?: startedRuntime.profile.fingerprint.takeIf { activeConnectionChained }.orEmpty()
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
        if (!activeConnectionChained && !startedRuntime.profile.type.equals("mihomo-group", ignoreCase = true)) {
            scanStateStore.saveLastSelectedProfile(
                SelectedConnectionProfile(
                    profile = startedRuntime.profile,
                    delayMs = startedRuntime.delayMs.takeIf { it > 0 }?.toInt() ?: Int.MAX_VALUE,
                    selectedAt = System.currentTimeMillis(),
                ),
            )
        }
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
            "nativeAutomatic=${startedRuntime.nativeAutomaticStart} chained=${startedRuntime.chainHopCount > 1} delayMs=${startedRuntime.delayMs}",
        )
        startBackgroundSubscriptionRefresh()
        startPostConnectHealthWatchdog()
        refreshDelayInBackground(startedRuntime)
        if (startedRuntime.selectedCountryCode == null) {
            refreshEgressCountryInBackground(startedRuntime)
        }
    }

    private fun refreshDelayInBackground(startedRuntime: StartedMihomoRuntime) {
        scope.launch(Dispatchers.IO) {
            val controller = MihomoControllerClient(
                startedRuntime.paths.secret,
                port = startedRuntime.paths.controlPort,
            )
            val resolvedProfile = if (startedRuntime.chainHopCount > 1) null else runCatching {
                controllerSelectionMutex.withLock {
                    val response = controller.getProxies()
                    applyLiveSelectorSnapshot(startedRuntime.paths, response)
                }
            }.onFailure { error ->
                DiagnosticLogger.warn(
                    this@WhiteDnsVpnService,
                    "mihomo.selector.graph.failed",
                    error = error,
                )
            }.getOrNull()
            val name = if (startedRuntime.chainHopCount > 1) {
                startedRuntime.delayProbeName
            } else {
                activeConnectionTag.takeIf(String::isNotBlank)
                    ?: startedRuntime.delayProbeName.takeIf(String::isNotBlank)
                    ?: return@launch
            }
            val measuredDelayMs = runCatching {
                controller.delay(name, timeoutMs = BACKGROUND_MIHOMO_DELAY_TIMEOUT_MS)
                    ?.toLong()
            }.onFailure { error ->
                DiagnosticLogger.warn(this@WhiteDnsVpnService, "mihomo.delay.background.failed", "name=$name", error)
            }.getOrNull()?.takeIf { it > 0 }
            if (resolvedProfile == null && measuredDelayMs == null) return@launch

            if (state != VpnState.Started || activeRuntimePaths != startedRuntime.paths) return@launch
            if (!activeConnectionChained && activeConnectionTag.isNotBlank() && activeConnectionTag != name) {
                return@launch
            }
            val profile = resolvedProfile ?: startedRuntime.profile
            val checkedAt = System.currentTimeMillis()
            val delayMs = measuredDelayMs ?: startedRuntime.delayMs
            val endpoint = if (resolvedProfile != null && !startedRuntime.cacheEndpoint) {
                CleanIpResult(
                    ip = resolvedProfile.server,
                    port = resolvedProfile.port,
                    latencyMs = delayMs,
                    lossRate = 0.0,
                    checkedAt = checkedAt,
                )
            } else {
                startedRuntime.endpoint.copy(latencyMs = delayMs, checkedAt = checkedAt)
            }
            if (delayMs > 0) {
                activeDelayMs = delayMs
            }
            activeEndpoint = endpoint
            if (!activeConnectionChained && !profile.type.equals("mihomo-group", ignoreCase = true)) {
                scanStateStore.saveLastSelectedProfile(
                    SelectedConnectionProfile(
                        profile,
                        delayMs.takeIf { it > 0 }?.toInt() ?: Int.MAX_VALUE,
                        checkedAt,
                    ),
                )
            }
            if (
                measuredDelayMs != null &&
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
                if (measuredDelayMs != null) {
                    "mihomo.delay.background.ok"
                } else {
                    "mihomo.active.proxy.background.ok"
                },
                "resolved=${resolvedProfile != null} delayMs=${measuredDelayMs ?: -1L} fronting=${startedRuntime.cacheEndpoint}",
            )
        }
    }

    private suspend fun refreshLiveSelectorState() {
        if (activeConnectionChained) return
        if (connectionDelayTestJob?.isActive == true || connectionSpeedTestJob?.isActive == true) return
        val paths = activeRuntimePaths ?: return
        controllerSelectionMutex.withLock {
            if (state != VpnState.Started || activeRuntimePaths != paths) return@withLock
            val controller = MihomoControllerClient(paths.secret, port = paths.controlPort)
            applyLiveSelectorSnapshot(paths, controller.getProxies())
        }
    }

    private fun applyLiveSelectorSnapshot(
        paths: MihomoRuntimePaths,
        response: JSONObject,
        publishIfChanged: Boolean = true,
    ): ConnectionProfile? {
        if (activeConnectionChained) return null
        if (state != VpnState.Started || activeRuntimePaths != paths) return null
        val selectableNames = MihomoControllerProxies.selectableTargetNames(
            response = response,
            targetNames = activeAvailableProfiles.map(ConnectionProfile::tag),
            preferredRoots = activeSelectorRootName
                .takeIf(String::isNotBlank)
                ?.let(::listOf)
                ?: activeSelectorRoots,
        )
        val selectableFingerprints = activeAvailableProfiles.asSequence()
            .filter { it.tag in selectableNames }
            .map(ConnectionProfile::fingerprint)
            .toSet()
        val activeName = MihomoControllerProxies.activeProxyName(response, activeSelectorRootName)
            .orEmpty()
        val resolvedProfile = activeAvailableProfiles.firstOrNull { it.tag == activeName }
        val previousReady = activeSelectorReady
        val previousSelectable = activeSelectableConnectionFingerprints
        val previousTag = activeConnectionTag
        val previousFingerprint = activeConnectionFingerprint

        activeSelectorReady = true
        activeSelectableConnectionFingerprints = selectableFingerprints
        activeConnectionTag = activeName
        activeConnectionFingerprint = resolvedProfile?.fingerprint.orEmpty()
        if (resolvedProfile != null) {
            activeProfile = MihomoConnectionOptionsPolicy.applyTo(
                resolvedProfile,
                connectionOptionsPreferenceStore.read(),
            )
            if (!activeRuntimeUsesEndpointOverride) {
                activeEndpoint = CleanIpResult(
                    ip = resolvedProfile.server,
                    port = resolvedProfile.port,
                    latencyMs = activeDelayMs,
                    lossRate = 0.0,
                    checkedAt = System.currentTimeMillis(),
                )
            }
        }
        val changed = !previousReady ||
            previousSelectable != selectableFingerprints ||
            previousTag != activeName ||
            previousFingerprint != activeConnectionFingerprint
        if (changed && publishIfChanged) publishState(VpnState.Started)
        if (changed) {
            DiagnosticLogger.info(
                this,
                "mihomo.selector.graph.updated",
                "root=$activeSelectorRootName active=$activeName selectable=${selectableFingerprints.size}",
            )
        }
        return resolvedProfile
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

    private fun nodeCountBucket(count: Int): String = when {
        count < 50 -> "0-49"
        count < 200 -> "50-199"
        count < 500 -> "200-499"
        count < 1_000 -> "500-999"
        else -> "1000+"
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
                    "candidates=${connectableFresh.size} exhaustiveFallback=${untriedExhaustive.size} latencyMs=${connectableFresh.first().latencyMs} lossRate=${connectableFresh.first().lossRate} cacheAfterRuntimeValidation=${!bypassConnectionCache}",
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
                "latencyMs=${cached.latencyMs} lossRate=${cached.lossRate} speedBps=${cached.downloadBytesPerSecond} cacheAfterRuntimeValidation=true",
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
        serverOverridePort: Int?,
        delayMs: Long,
    ): CleanIpResult {
        val port = serverOverridePort
            ?: runtimePortForSelection(snapshot, selectedCountryCode, selection, activeProxyName)
            ?: topEndpoint.port
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
            "port=$port health=$healthStatus",
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

    private suspend fun setupCoreRuntime(
        paths: MihomoRuntimePaths,
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
    }

    private suspend fun startCoreTun(
        paths: MihomoRuntimePaths,
        splitTunnelPlan: SplitTunnelRuntimePlan,
    ) {
        val tunFd = withContext(Dispatchers.Main) {
            establishTun(splitTunnelPlan)
        }
        withContext(Dispatchers.IO) {
            Core.startTun(
                fd = tunFd,
                protect = this@WhiteDnsVpnService::protect,
                resolverProcess = this@WhiteDnsVpnService::resolveProcess,
                stack = MIHOMO_TUN_STACK,
                address = VpnTunnelNetwork.coreAddresses,
                dns = VpnTunnelNetwork.coreDnsServers,
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
            val installed = MihomoGeoDataInstaller.install(paths.baseDir) { assetPath ->
                assets.open(assetPath)
            }
            if (installed.isNotEmpty()) {
                DiagnosticLogger.info(
                    this@WhiteDnsVpnService,
                    "mihomo.geodata.installed",
                    "files=${installed.joinToString(",")}",
                )
            }
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
                .setMtu(MIHOMO_TUN_MTU)
                .setSession(getString(R.string.app_name))
                .setBlocking(false)
            VpnTunnelNetwork.addresses.forEach { (address, prefixLength) ->
                builder.addAddress(address, prefixLength)
            }
            VpnTunnelNetwork.defaultRoutes.forEach { route -> builder.addRoute(route, 0) }
            VpnTunnelNetwork.dnsServers.forEach(builder::addDnsServer)

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

    private fun runtimeHealthStatus(deadlineMs: Long): Int {
        val urls = MihomoRuntimeDefaults.HEALTH_URLS
        for ((index, url) in urls.withIndex()) {
            val timeoutMs = MihomoRuntimeHealthDeadlinePolicy.probeTimeoutMs(
                deadlineMs = deadlineMs,
                nowMs = SystemClock.elapsedRealtime(),
                remainingUrlCount = urls.size - index,
            ) ?: break
            val code = runCatching {
                MihomoRuntimeHealth.httpStatusThroughMixedProxy(url, timeoutMs)
            }.getOrNull()
            if (code != null && (code == 204 || code in 200..399)) return code
        }
        return -1
    }

    private suspend fun verifyRuntimeHealth(
        timeoutMs: Long = RUNTIME_HEALTH_TIMEOUT_MS,
        event: String = "mihomo.proxy.health",
    ) {
        val startedAt = SystemClock.elapsedRealtime()
        val deadlineMs = MihomoRuntimeHealthDeadlinePolicy.deadlineMs(startedAt, timeoutMs)
        val proxyCode = waitForHealthyStatus(event, deadlineMs) {
            runtimeHealthStatus(deadlineMs)
        } ?: run {
            DiagnosticLogger.warn(
                this,
                "$event.timeout",
                "elapsedMs=${SystemClock.elapsedRealtime() - startedAt} timeoutMs=$timeoutMs",
            )
            throw IOException(
                "Local Mihomo proxy did not pass health check at 127.0.0.1:2080 " +
                    "within ${timeoutMs}ms",
            )
        }
        DiagnosticLogger.info(
            this,
            "$event.ok",
            "code=$proxyCode elapsedMs=${SystemClock.elapsedRealtime() - startedAt} timeoutMs=$timeoutMs",
        )
    }

    private suspend fun verifyTlsIntegrity(enabled: Boolean) {
        if (!enabled) return
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
        deadlineMs: Long,
        check: () -> Int,
    ): Int? {
        while (SystemClock.elapsedRealtime() < deadlineMs) {
            val code = withContext(Dispatchers.IO) {
                runCatching { check() }.getOrElse {
                    DiagnosticLogger.warn(this@WhiteDnsVpnService, "$event.failed", error = it)
                    -1
                }
            }
            if (code == 204 || code in 200..399) return code
            val pollDelayMs = MihomoRuntimeHealthDeadlinePolicy.pollDelayMs(
                deadlineMs = deadlineMs,
                nowMs = SystemClock.elapsedRealtime(),
                requestedMs = RUNTIME_HEALTH_POLL_INTERVAL_MS,
            )
            if (pollDelayMs <= 0L) break
            delay(pollDelayMs)
        }
        return null
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
            if (connectionDelayTestJob != null || connectionSpeedTestJob != null) {
                cancelConnectionDelayTest()
                connectionSpeedTestJob?.cancel(CancellationException("Connection speed test canceled"))
                return
            }
            publishState(VpnState.Stopped)
            stopSelf()
            return
        }
        if (state == VpnState.Stopping) return
        DiagnosticLogger.info(this, "disconnect.start", "state=${state.wireName}")
        awaitingPreservedRuntimeHealth = false
        startupJob?.cancel(CancellationException("Disconnect requested"))
        subscriptionRefreshJob?.cancel()
        encryptedIpScanJob?.cancel()
        cancelPostConnectHealthWatchdog()
        publishState(VpnState.Stopping)
        connectionSwitchJob?.cancel(CancellationException("Disconnect requested"))
        cancelConnectionDelayTest()
        connectionSpeedTestJob?.cancel(CancellationException("Disconnect requested"))
        stopJob?.cancel()
        stopJob = scope.launch {
            val terminalState = disconnectTerminalState(
                stopCoreService(),
                getString(R.string.state_connection_error),
            )
            if (terminalState == VpnState.Stopped) {
                finishStoppedState("disconnect.stopped")
            } else {
                DiagnosticLogger.warn(
                    this@WhiteDnsVpnService,
                    "disconnect.failed",
                    "reason=coreShutdownTimeout state=${coreLifecycle.currentState()}",
                )
                publishState(terminalState)
                stopForegroundCompat()
                stopSelf()
            }
        }
    }

    private suspend fun stopAfterFailure(error: Throwable) {
        awaitingPreservedRuntimeHealth = false
        subscriptionRefreshJob?.cancel()
        encryptedIpScanJob?.cancel()
        cancelPostConnectHealthWatchdog()
        stopCoreService()
        sessionStartedAtElapsedMs = 0L
        activeProfile = null
        activeProfileShowsServer = false
        activeDelayMs = -1L
        activeRuntimePaths = null
        activeNativeAutomaticStart = false
        activeConnectionChained = false
        activeChainHopCount = 0
        clearActiveSelectorState()
        activeEndpoint = null
        activeConnectionCountryFlag = ""
        activeFrontingIp = ""
        pendingDefaultNetworkDnsReplay = false
        runCatching { networkMonitor.stop() }
        stopForegroundCompat()
        if (!shouldPublishStartupError(currentCoroutineContext().isActive, state)) {
            DiagnosticLogger.info(
                this,
                "startup.failure.ignored",
                "reason=shutdownWon state=${state.wireName}",
            )
            return
        }
        publishState(VpnState.Error(error.message ?: "Unable to start VPN"))
        stopSelf()
    }

    private suspend fun keepActiveRuntimeAfterStartupFailure(eventPrefix: String, error: Throwable) {
        val hasUsableDefaultNetwork = networkMonitor.hasUsableDefaultNetwork()
        val healthStatus = if (hasUsableDefaultNetwork) {
            val healthStartedAt = SystemClock.elapsedRealtime()
            withContext(Dispatchers.IO) {
                runtimeHealthStatus(
                    MihomoRuntimeHealthDeadlinePolicy.deadlineMs(
                        healthStartedAt,
                        FALLBACK_RUNTIME_HEALTH_TIMEOUT_MS,
                    ),
                )
            }
        } else {
            -1
        }
        val preservedState = PostConnectHealthPolicy.preservedRuntimeState(
            hasUsableDefaultNetwork,
            healthStatus,
        )
        if (preservedState == null) {
            DiagnosticLogger.warn(
                this,
                "$eventPrefix.failed.oldRuntimeUnhealthy",
                "code=$healthStatus",
                error,
            )
            stopAfterFailure(error)
            return
        }
        awaitingPreservedRuntimeHealth = preservedState == VpnState.Starting
        DiagnosticLogger.warn(
            this,
            "$eventPrefix.failed.keptActive",
            "state=${preservedState.wireName}",
            error,
        )
        publishState(preservedState)
        getSystemService(NotificationManager::class.java)
            .notify(
                NOTIFICATION_ID,
                serviceNotification(
                    getString(
                        if (preservedState == VpnState.Started) {
                            R.string.notification_connected
                        } else {
                            R.string.notification_starting
                        },
                    ),
                ),
            )
        if (preservedState == VpnState.Started) startBackgroundSubscriptionRefresh()
        startPostConnectHealthWatchdog()
    }

    private suspend fun stopActiveCoreForReplacement() {
        if (!stopCoreService()) {
            throw MihomoCoreBusyException()
        }
        activeRuntimePaths = null
        activeNativeAutomaticStart = false
        clearActiveSelectorState()
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
        awaitingPreservedRuntimeHealth = false
        sessionStartedAtElapsedMs = 0L
        activeProfile = null
        activeProfileShowsServer = false
        activeDelayMs = -1L
        activeRuntimePaths = null
        activeNativeAutomaticStart = false
        activeConnectionChained = false
        activeChainHopCount = 0
        clearActiveSelectorState()
        activeEndpoint = null
        activeConnectionCountryFlag = ""
        activeFrontingIp = ""
        lastPostConnectRecoveryElapsedMs = 0L
        lastDefaultNetworkKey = null
        lastDefaultDns = ""
        pendingDefaultNetworkDnsReplay = false
        runCatching { networkMonitor.stop() }
        stopForegroundCompat()
        publishState(VpnState.Stopped)
        DiagnosticLogger.info(this, event)
        stopSelf()
    }

    private fun clearActiveSelectorState() {
        activeSubscriptionId = ""
        activeConnectionTag = ""
        activeConnectionFingerprint = ""
        activeSelectorReady = false
        activeSelectableConnectionFingerprints = emptySet()
        activeSelectorRootName = ""
        activeSelectorRoots = emptyList()
        activeAvailableProfiles = emptyList()
        activeRuntimeUsesEndpointOverride = false
        activeQuickSpeedPin = null
        invalidatedQuickSpeedPin = null
    }

    private fun handleDefaultNetworkChanged(candidate: DefaultNetworkCandidate?, force: Boolean = false) {
        val networkChanged = defaultNetworkStateChanged(
            force = force,
            networkKey = candidate.defaultNetworkKey(),
            dns = candidate?.dnsServers.orEmpty().distinct().joinToString(","),
            previousNetworkKey = lastDefaultNetworkKey,
            previousDns = lastDefaultDns,
        )
        pendingDefaultNetworkDnsReplay = !applyDefaultNetworkDns(candidate, force)
        if (networkChanged) {
            if (candidate == null && state == VpnState.Started && activeRuntimePaths != null) {
                awaitingPreservedRuntimeHealth = true
                publishState(VpnState.Starting)
                getSystemService(NotificationManager::class.java).notify(
                    NOTIFICATION_ID,
                    serviceNotification(getString(R.string.notification_starting)),
                )
            }
            synchronized(quickSpeedStartedLock) {
                activeQuickSpeedPin?.let { invalidatedQuickSpeedPin = it }
            }
            restoreActiveQuickSpeedPinAfterNetworkChange()
            if (shouldRunPostConnectHealthWatchdog(state, awaitingPreservedRuntimeHealth)) {
                startPostConnectHealthWatchdog()
            }
        }
    }

    private fun restoreActiveQuickSpeedPinAfterNetworkChange() {
        val pin = activeQuickSpeedPin ?: return
        val activePaths = activeRuntimePaths
        if (activePaths != null && activePaths != pin.paths) {
            activeQuickSpeedPin = null
            if (invalidatedQuickSpeedPin == pin) invalidatedQuickSpeedPin = null
            return
        }
        scope.launch(Dispatchers.IO) {
            var rebuildRequired = false
            controllerSelectionMutex.withLock {
                if (
                    (state != VpnState.Started && state != VpnState.Starting) ||
                    (activeRuntimePaths != null && activeRuntimePaths != pin.paths) ||
                    activeQuickSpeedPin != pin
                ) {
                    return@withLock
                }
                val controller = MihomoControllerClient(pin.paths.secret, port = pin.paths.controlPort)
                repeat(QUICK_SPEED_PIN_RESTORE_ATTEMPTS) { index ->
                    try {
                        if (
                            (activeRuntimePaths != null && activeRuntimePaths != pin.paths) ||
                            activeQuickSpeedPin != pin
                        ) {
                            return@withLock
                        }
                        val before = controller.getProxies()
                        val group = before.optJSONObject("proxies")?.optJSONObject(pin.groupName)
                            ?: throw IOException("Quick speed group is missing")
                        val fixed = group.opt("fixed") as? String
                            ?: throw IOException("Quick speed fixed selection is missing")
                        if (fixed != pin.selectedName) {
                            activeQuickSpeedPin = null
                            if (invalidatedQuickSpeedPin == pin) invalidatedQuickSpeedPin = null
                            applyLiveSelectorSnapshot(
                                pin.paths,
                                before,
                                publishIfChanged = state == VpnState.Started,
                            )
                            DiagnosticLogger.info(
                                this@WhiteDnsVpnService,
                                "mihomo.quickSpeed.network.restore.skipped",
                                "reason=ownershipChanged",
                            )
                            return@withLock
                        }
                        val restored = restoreQuickSpeedSelection(
                            controller,
                            pin.groupName,
                            pin.originalFixed,
                        )
                        applyLiveSelectorSnapshot(
                            pin.paths,
                            restored,
                            publishIfChanged = state == VpnState.Started,
                        )
                        activeQuickSpeedPin = null
                        if (invalidatedQuickSpeedPin == pin) invalidatedQuickSpeedPin = null
                        DiagnosticLogger.info(
                            this@WhiteDnsVpnService,
                            "mihomo.quickSpeed.network.restore.ok",
                            "attempt=${index + 1}/$QUICK_SPEED_PIN_RESTORE_ATTEMPTS " +
                                "fixed=${if (pin.originalFixed.isEmpty()) "automatic" else "preset"}",
                        )
                        return@withLock
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        DiagnosticLogger.warn(
                            this@WhiteDnsVpnService,
                            "mihomo.quickSpeed.network.restore.failed",
                            "attempt=${index + 1}/$QUICK_SPEED_PIN_RESTORE_ATTEMPTS " +
                                "error=${error::class.java.simpleName}",
                        )
                        if (index + 1 < QUICK_SPEED_PIN_RESTORE_ATTEMPTS) {
                            delay(CONTROLLER_POLL_INTERVAL_MS)
                        }
                    }
                }
                rebuildRequired = state == VpnState.Started && activeQuickSpeedPin == pin
            }
            if (rebuildRequired) {
                DiagnosticLogger.warn(
                    this@WhiteDnsVpnService,
                    "mihomo.quickSpeed.network.restore.exhausted",
                    "attempts=$QUICK_SPEED_PIN_RESTORE_ATTEMPTS recovery=rebuild",
                )
                withContext(Dispatchers.Main.immediate) {
                    refreshVpn(eventPrefix = "quickSpeedNetworkRecovery", automatic = true)
                }
            }
        }
    }

    private fun applyDefaultNetworkDns(candidate: DefaultNetworkCandidate?, force: Boolean): Boolean {
        val networkKey = candidate.defaultNetworkKey()
        val dns = candidate?.dnsServers.orEmpty().distinct().joinToString(",")
        if (!force && networkKey == lastDefaultNetworkKey && dns == lastDefaultDns) return true
        DiagnosticLogger.info(this, "network.default.changed", "network=$networkKey dns=${candidate?.dnsServers?.size ?: 0} state=${state.wireName}")
        if (!coreLifecycle.isActive()) {
            DiagnosticLogger.info(
                this,
                "network.dns.update.skipped",
                "reason=coreNotActive coreState=${coreLifecycle.currentState()}",
            )
            return false
        }
        val applied = if (dns.isBlank()) {
            true
        } else {
            runCatching { Core.updateDNS(dns) }
                .onFailure {
                    DiagnosticLogger.warn(
                        this,
                        "network.dns.update.failed",
                        "dnsCount=${candidate?.dnsServers?.size ?: 0}",
                        it,
                    )
                }
                .onSuccess {
                    DiagnosticLogger.info(
                        this,
                        "network.dns.update.ok",
                        "dnsCount=${candidate?.dnsServers?.size ?: 0}",
                    )
                }
                .isSuccess
        }
        if (applied) {
            lastDefaultNetworkKey = networkKey
            lastDefaultDns = dns
        }
        return applied
    }

    private fun retryPendingDefaultNetworkDnsReplay() {
        if (!pendingDefaultNetworkDnsReplay || state != VpnState.Started) return
        pendingDefaultNetworkDnsReplay = false
        DiagnosticLogger.info(
            this,
            "network.dns.update.retry",
            "attempt=1/${DefaultNetworkDnsReplayPolicy.MAX_RETRY_ATTEMPTS}",
        )
        if (!applyDefaultNetworkDns(networkMonitor.currentDefaultNetwork(), force = true)) {
            DiagnosticLogger.warn(
                this,
                "network.dns.update.retry.exhausted",
                "attempts=${DefaultNetworkDnsReplayPolicy.MAX_RETRY_ATTEMPTS}",
            )
        }
    }

    private fun DefaultNetworkCandidate?.defaultNetworkKey(): String {
        if (this == null) return "none"
        return listOf(name, index.toString(), isWifi.toString(), isCellular.toString(), isEthernet.toString())
            .joinToString("|")
    }

    private fun DefaultNetworkCandidate?.quickSpeedNetworkFingerprint(): String =
        "${defaultNetworkKey()}#${this?.dnsServers.orEmpty().distinct().joinToString(",")}"

    private fun cancelPostConnectHealthWatchdog() {
        val job = postConnectHealthJob
        postConnectHealthJob = null
        job?.cancel()
    }

    private fun startPostConnectHealthWatchdog() {
        cancelPostConnectHealthWatchdog()
        val job = scope.launch(Dispatchers.IO) {
            var consecutiveFailures = 0
            var nextCheckDelayMs = PostConnectHealthPolicy.INITIAL_CHECK_DELAY_MS
            while (isActive && shouldRunPostConnectHealthWatchdog(state, awaitingPreservedRuntimeHealth)) {
                delay(nextCheckDelayMs)
                if (!isActive || !shouldRunPostConnectHealthWatchdog(state, awaitingPreservedRuntimeHealth)) break
                retryPendingDefaultNetworkDnsReplay()
                if (!networkMonitor.hasUsableDefaultNetwork()) {
                    if (state == VpnState.Started) {
                        awaitingPreservedRuntimeHealth = true
                        publishState(VpnState.Starting)
                        getSystemService(NotificationManager::class.java).notify(
                            NOTIFICATION_ID,
                            serviceNotification(getString(R.string.notification_starting)),
                        )
                    }
                    consecutiveFailures = 0
                    nextCheckDelayMs = PostConnectHealthPolicy.CHECK_INTERVAL_MS
                    continue
                }

                runCatching { refreshLiveSelectorState() }
                    .onFailure { error ->
                        DiagnosticLogger.warn(
                            this@WhiteDnsVpnService,
                            "mihomo.selector.graph.refresh.failed",
                            error = error,
                        )
                    }

                val healthStartedAt = SystemClock.elapsedRealtime()
                val code = runtimeHealthStatus(
                    MihomoRuntimeHealthDeadlinePolicy.deadlineMs(
                        healthStartedAt,
                        FALLBACK_RUNTIME_HEALTH_TIMEOUT_MS,
                    ),
                )
                if (PostConnectHealthPolicy.isHealthyStatus(code)) {
                    if (state == VpnState.Starting) {
                        awaitingPreservedRuntimeHealth = false
                        publishState(VpnState.Started)
                        getSystemService(NotificationManager::class.java).notify(
                            NOTIFICATION_ID,
                            serviceNotification(getString(R.string.notification_connected)),
                        )
                        startBackgroundSubscriptionRefresh()
                    }
                    if (consecutiveFailures > 0) {
                        DiagnosticLogger.info(
                            this@WhiteDnsVpnService,
                            "mihomo.postConnect.health.recovered",
                            "code=$code",
                        )
                    }
                    consecutiveFailures = 0
                    nextCheckDelayMs = PostConnectHealthPolicy.CHECK_INTERVAL_MS
                    continue
                }

                consecutiveFailures += 1
                nextCheckDelayMs = PostConnectHealthPolicy.FAILURE_RECHECK_DELAY_MS
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
                        nextCheckDelayMs = PostConnectHealthPolicy.CHECK_INTERVAL_MS
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
                    if (canStartVpnRefresh(state, automatic = true, awaitingPreservedRuntimeHealth)) {
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
                configRepository.refreshAllSubscriptions()
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
        val activeSubscription = activeSubscriptionId.takeIf { newState == VpnState.Started }.orEmpty()
        val activeTag = activeConnectionTag.takeIf { newState == VpnState.Started }.orEmpty()
        val activeFingerprint = activeConnectionFingerprint.takeIf { newState == VpnState.Started }.orEmpty()
        val selectorReady = newState == VpnState.Started && activeSelectorReady
        val selectableFingerprints = if (newState == VpnState.Started) {
            activeSelectableConnectionFingerprints
        } else {
            emptySet()
        }
        VpnRuntimeStateStore.save(
            context = this,
            state = newState,
            sessionStartedAtElapsedMs = sessionStartedAtElapsedMs,
            connectionCountryFlag = countryFlag,
            debugFrontingIp = debugFrontingIp,
            connectionDetails = connectionDetails,
            activeSubscriptionId = activeSubscription,
            activeConnectionTag = activeTag,
            activeConnectionFingerprint = activeFingerprint,
            chainHopCount = if (newState == VpnState.Started) activeChainHopCount else 0,
            liveSelectorReady = selectorReady,
            selectableConnectionFingerprints = selectableFingerprints,
            alwaysOn = alwaysOnActive,
            lockdown = lockdownActive,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            runCatching { WhiteDnsTileService.requestTileRefresh(this) }
                .onFailure { DiagnosticLogger.warn(this, "tile.refresh.failed", error = it) }
        }
        DiagnosticLogger.info(this, "state.publish", "state=${newState.wireName} debugFrontingIp=${debugFrontingIp}")
        val intent = Intent(Actions.STATE_CHANGED)
            .setPackage(packageName)
            .putExtra(Actions.EXTRA_STATE, newState.wireName)
            .putExtra(Actions.EXTRA_SESSION_STARTED_AT_ELAPSED_MS, sessionStartedAtElapsedMs)
            .putExtra(Actions.EXTRA_CONNECTION_COUNTRY_FLAG, countryFlag)
            .putExtra(Actions.EXTRA_DEBUG_FRONTING_IP, debugFrontingIp)
            .putExtra(Actions.EXTRA_CONNECTION_DETAILS, connectionDetails)
            .putExtra(Actions.EXTRA_ACTIVE_SUBSCRIPTION_ID, activeSubscription)
            .putExtra(Actions.EXTRA_ACTIVE_CONNECTION_TAG, activeTag)
            .putExtra(Actions.EXTRA_ACTIVE_CONNECTION_FINGERPRINT, activeFingerprint)
            .putExtra(
                Actions.EXTRA_CHAIN_HOP_COUNT,
                if (newState == VpnState.Started) activeChainHopCount else 0,
            )
            .putExtra(Actions.EXTRA_LIVE_SELECTOR_READY, selectorReady)
            .putStringArrayListExtra(
                Actions.EXTRA_SELECTABLE_CONNECTION_FINGERPRINTS,
                ArrayList(selectableFingerprints),
            )
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
        const val CONTROLLER_READY_TIMEOUT_MS = 20_000L
        const val CONTROLLER_POLL_INTERVAL_MS = 300L
        const val CONTROLLER_LOG_TAIL_CHARS = 4_000
        const val RUNTIME_HEALTH_TIMEOUT_MS = 20_000L
        const val FALLBACK_RUNTIME_HEALTH_TIMEOUT_MS = 5_000L
        const val RUNTIME_HEALTH_POLL_INTERVAL_MS = 500L
        const val QUICK_SPEED_SHORTLIST_TIMEOUT_MS = 2_000L
        const val QUICK_SPEED_DOWNLOAD_TIMEOUT_MS = 10_000
        const val QUICK_SPEED_PIN_RESTORE_ATTEMPTS = 2
        const val FOREGROUND_MIHOMO_DELAY_TIMEOUT_MS = 1_500
        const val BACKGROUND_MIHOMO_DELAY_TIMEOUT_MS = 3_000
        const val CONNECTION_DELAY_PAUSE_POLL_INTERVAL_MS = 100L
        const val CONNECTION_DELAY_RECORD_BATCH_SIZE = 25
        const val BACKGROUND_ENCRYPTED_IP_SCAN_MS = 12_000L
        const val BACKGROUND_ENCRYPTED_IP_SCAN_INTERVAL_MS = 10 * 60 * 1_000L
        const val TUN_ESTABLISH_ATTEMPTS = 3
        const val TUN_ESTABLISH_RETRY_DELAY_MS = 250L
        const val FRONTING_FALLBACK_NOTICE = "None of the Fronting IPs were reachable. Used original connection."
        const val SCAN_DIAGNOSTICS_ENABLED = true
        const val MIHOMO_TUN_STACK = "gvisor"
        const val MIHOMO_TUN_MTU = 9000
    }
}
