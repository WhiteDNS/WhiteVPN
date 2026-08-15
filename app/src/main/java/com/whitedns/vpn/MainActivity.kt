package com.whitedns.vpn

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.net.TrafficStats
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PersistableBundle
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import android.text.Editable
import android.text.InputType
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.PopupMenu
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.button.MaterialButtonToggleGroup
import com.google.android.material.checkbox.MaterialCheckBox
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import java.text.DateFormat

/* Hallmark · genre: modern-minimal · macrostructure: Workbench · design-system: design.md · designed-as-app · tone: utilitarian · anchor hue: green */
/* Hallmark · pre-emit critique: P5 H5 E5 S5 R5 V4 · contrast: pass (40–41) · slop: pass */
class MainActivity : Activity() {
    private val palette: WhiteDnsPalette by lazy { WhiteDnsDesignTokens.forContext(this) }
    private val buttonModel = ConnectButtonModel()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var privacyPolicyStore: PrivacyPolicyAcceptanceStore
    private lateinit var appLanguagePreferenceStore: AppLanguagePreferenceStore
    private lateinit var appThemePreferenceStore: AppThemePreferenceStore
    private lateinit var locationPreferenceStore: ConnectionLocationPreferenceStore
    private lateinit var splitTunnelPreferenceStore: SplitTunnelPreferenceStore
    private lateinit var frontingIpPreferenceStore: FrontingIpPreferenceStore
    private lateinit var routingModePreferenceStore: RoutingModePreferenceStore
    private lateinit var dnsPrivacyPreferenceStore: DnsPrivacyPreferenceStore
    private lateinit var tlsIntegrityPreferenceStore: TlsIntegrityPreferenceStore
    private lateinit var connectionOptionsPreferenceStore: MihomoConnectionOptionsPreferenceStore
    private lateinit var connectionModePreferenceStore: ConnectionModePreferenceStore
    private lateinit var lanSharingPreferenceStore: LanSharingPreferenceStore
    private lateinit var connectionSelectionPreferenceStore: ConnectionSelectionPreferenceStore
    private lateinit var installedAppRepository: InstalledAppRepository
    private lateinit var userSubscriptionManager: UserSubscriptionManager
    private var privacyPolicyDialog: AlertDialog? = null
    private var connectionSelectorDialog: AlertDialog? = null
    private var connectionDelayTestListener: ((Intent) -> Unit)? = null
    private var sessionStartedAtElapsedMs: Long = 0L
    private var connectFlowPending: Boolean = false
    private var connectFlowAction: String = Actions.CONNECT
    private var disconnectAnalyticsPending: Boolean = false
    private var locationOptions: List<LocationSelectorOption> = emptyList()
    private var connectionProfiles: List<ConnectionProfile> = emptyList()
    private var connectionDelayRecords: Map<String, ConnectionDelayRecord> = emptyMap()
    private var activeRuntimeSubscriptionId: String = ""
    private var activeConnectionTag: String = ""
    private var activeConnectionFingerprint: String = ""
    private var liveSelectorReady: Boolean = false
    private var liveSelectableConnectionFingerprints: Set<String> = emptySet()

    private lateinit var connectionOrb: ConnectionOrbView
    private lateinit var statusDot: View
    private lateinit var statusText: TextView
    private lateinit var connectionDetailsText: TextView
    private lateinit var timerText: TextView
    private lateinit var downloadSpeedText: TextView
    private lateinit var uploadSpeedText: TextView
    private lateinit var downloadArrowIcon: AnimatedArrowIcon
    private lateinit var uploadArrowIcon: AnimatedArrowIcon
    private lateinit var connectionCountryText: TextView
    private lateinit var locationSelectorRow: DashboardDataRowView
    private lateinit var connectionSelectorRow: DashboardDataRowView
    private lateinit var splitTunnelRow: DashboardDataRowView
    private lateinit var connectionModeGroup: MaterialButtonToggleGroup
    private lateinit var vpnModeButton: MaterialButton
    private lateinit var proxyModeButton: MaterialButton
    private lateinit var dashboardLocalEndpointText: TextView
    private lateinit var tlsIntegrityCheckbox: MaterialSwitch
    private lateinit var alwaysOnStatusText: TextView
    private lateinit var amneziaNoiseCheckbox: MaterialSwitch
    private lateinit var amneziaNoiseFields: LinearLayout
    private lateinit var amneziaNoiseCountInput: EditText
    private lateinit var amneziaNoiseMinSizeInput: EditText
    private lateinit var amneziaNoiseMaxSizeInput: EditText
    private lateinit var amneziaNoiseApplyButton: MaterialButton
    private lateinit var amneziaNoiseErrorText: TextView
    private lateinit var lanSharingCheckbox: MaterialSwitch
    private lateinit var lanSharingPasswordCheckbox: MaterialSwitch
    private lateinit var lanSharingDetailsText: TextView
    private lateinit var lanSharingRegenerateButton: MaterialButton
    private lateinit var routingModeRow: LinearLayout
    private lateinit var routingModeValueText: TextView
    private lateinit var routingModeDetailText: TextView
    private lateinit var dnsPrivacyRow: LinearLayout
    private lateinit var dnsPrivacyValueText: TextView
    private lateinit var dnsPrivacyDetailText: TextView
    private lateinit var dnsPrivacyEndpointInput: EditText
    private lateinit var dnsPrivacyEndpointLayout: TextInputLayout
    private lateinit var dnsPrivacyErrorText: TextView
    private lateinit var frontingIpChipGroup: ChipGroup
    private lateinit var frontingIpInput: EditText
    private lateinit var frontingIpInputLayout: TextInputLayout
    private lateinit var frontingIpErrorText: TextView
    private lateinit var refreshActionButton: MaterialButton
    private lateinit var subscriptionsList: LinearLayout
    private lateinit var vpnTabContent: View
    private lateinit var subscriptionsTabContent: View
    private lateinit var advancedTabContent: View
    private var connectionCountryFlag: String = ""
    private var debugFrontingIp: String = ""
    private var connectionDetails: String = ""
    private var alwaysOnMode: Boolean = false
    private var lockdownMode: Boolean = false
    private var frontingIps: List<String> = emptyList()
    private var frontingIpInputUpdating: Boolean = false
    private var dnsPrivacyInputUpdating: Boolean = false
    private var lastTransferRxBytes: Long = TrafficStats.UNSUPPORTED.toLong()
    private var lastTransferTxBytes: Long = TrafficStats.UNSUPPORTED.toLong()
    private var lastTransferSampleElapsedMs: Long = 0L

    private val BACKGROUND: Int get() = palette.background
    private val SURFACE: Int get() = palette.surface
    private val OUTLINE: Int get() = palette.outline
    private val TEXT_PRIMARY: Int get() = palette.textPrimary
    private val TEXT_SECONDARY: Int get() = palette.textSecondary
    private val TIMER_MUTED: Int get() = palette.textSecondary
    private val TEAL: Int get() = palette.teal
    private val AMBER: Int get() = palette.amber
    private val ERROR: Int get() = palette.red

    private val timerRunnable = object : Runnable {
        override fun run() {
            val startedAt = sessionStartedAtElapsedMs
            if (buttonModel.state == VpnState.Started && startedAt > 0L) {
                setTimerText(SystemClock.elapsedRealtime() - startedAt)
                updateTransferSpeeds()
                mainHandler.postDelayed(this, TIMER_TICK_MS)
            }
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocale.wrap(AppTheme.wrap(newBase)))
    }

    private val disconnectTimeoutRunnable = Runnable {
        if (buttonModel.state == VpnState.Stopping) {
            DiagnosticLogger.warn(
                this,
                "activity.disconnect.timeout",
                "No stopped broadcast received after ${DISCONNECT_UI_TIMEOUT_MS}ms; resetting UI",
            )
            sessionStartedAtElapsedMs = 0L
            mainHandler.removeCallbacks(timerRunnable)
            resetTransferSpeeds()
            buttonModel.onStateChanged(VpnState.Stopped)
            renderState(VpnState.Stopped)
        }
    }

    private val stateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Actions.STATE_CHANGED -> handleStateChanged(context, intent)
                Actions.CONNECTION_DELAY_TEST_CHANGED -> connectionDelayTestListener?.invoke(intent)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        appLanguagePreferenceStore = AppLanguagePreferenceStore(this)
        appThemePreferenceStore = AppThemePreferenceStore(this)
        if (savedInstanceState == null) {
            AnalyticsEvents.appOpened(this)
        }
        privacyPolicyStore = PrivacyPolicyAcceptanceStore(this)
        locationPreferenceStore = ConnectionLocationPreferenceStore(this)
        splitTunnelPreferenceStore = SplitTunnelPreferenceStore(this)
        frontingIpPreferenceStore = FrontingIpPreferenceStore(this)
        routingModePreferenceStore = RoutingModePreferenceStore(this)
        dnsPrivacyPreferenceStore = DnsPrivacyPreferenceStore(this)
        tlsIntegrityPreferenceStore = TlsIntegrityPreferenceStore(this)
        connectionOptionsPreferenceStore = MihomoConnectionOptionsPreferenceStore(this)
        connectionModePreferenceStore = ConnectionModePreferenceStore(this)
        lanSharingPreferenceStore = LanSharingPreferenceStore(this)
        connectionSelectionPreferenceStore = ConnectionSelectionPreferenceStore(this)
        installedAppRepository = InstalledAppRepository(this)
        userSubscriptionManager = UserSubscriptionManager(this)
        DiagnosticLogger.info(this, "activity.onCreate")
        configureSystemBars()
        setContentView(buildAppShell())
        renderState(VpnState.Stopped)
        refreshLocationOptions()
        mainHandler.post {
            val checkUpdatesAfterStartup = { if (savedInstanceState == null) checkForUpdates() }
            if (!showPrivacyPolicyIfNeeded(checkUpdatesAfterStartup)) checkUpdatesAfterStartup()
        }
    }

    private fun buildAppShell(): View {
        val root = FrameLayout(this).apply {
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            setBackgroundColor(BACKGROUND)
        }

        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        vpnTabContent = buildDashboard()
        subscriptionsTabContent = buildSubscriptionsScreen().apply { visibility = View.GONE }
        advancedTabContent = buildAdvancedScreen().apply { visibility = View.GONE }
        val content = FrameLayout(this).apply {
            addView(vpnTabContent, FrameLayout.LayoutParams(-1, -1))
            addView(subscriptionsTabContent, FrameLayout.LayoutParams(-1, -1))
            addView(advancedTabContent, FrameLayout.LayoutParams(-1, -1))
        }
        shell.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))

        // New bottom navigation with pill-shaped container
        val tabs = TabLayout(this).apply {
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            minimumHeight = dp(72)
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(24).toFloat()
                setColor(withAlpha(SURFACE, 220))
            }
            elevation = dp(8).toFloat()
            setSelectedTabIndicatorHeight(dp(3))
            setSelectedTabIndicatorColor(TEAL)
            setSelectedTabIndicatorGravity(TabLayout.INDICATOR_GRAVITY_BOTTOM)
            setTabIndicatorFullWidth(false)
            setTabTextColors(TEXT_SECONDARY, TEAL)
            tabRippleColor = ColorStateList.valueOf(withAlpha(TEAL, 24))
            tabIconTint = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_selected), intArrayOf()),
                intArrayOf(TEAL, TEXT_SECONDARY),
            )
            tabMode = TabLayout.MODE_FIXED
            tabGravity = TabLayout.GRAVITY_FILL
            // Reorder tabs: Settings, VPN (center/active), Subscription
            addTab(newTab().setText(R.string.tab_settings).setIcon(R.drawable.ic_advanced_tab))
            addTab(newTab().setText(R.string.tab_vpn).setIcon(R.drawable.ic_vpn_tab), true)
            addTab(newTab().setText(R.string.tab_subscriptions).setIcon(R.drawable.ic_subscriptions_tab))
            post {
                val tabStrip = getChildAt(0) as? ViewGroup ?: return@post
                for (index in 0 until tabStrip.childCount) {
                    val tabView = tabStrip.getChildAt(index) as? ViewGroup ?: continue
                    val icon = tabView.getChildAt(0) as? ImageView ?: continue
                    val params = icon.layoutParams as? ViewGroup.MarginLayoutParams ?: continue
                    params.bottomMargin = dp(5)
                    icon.layoutParams = params
                }
            }
            addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) {
                    // Map new tab order: 0=Settings, 1=VPN, 2=Subscriptions
                    val mappedPosition = when (tab.position) {
                        0 -> 2  // Settings -> position 2
                        1 -> 1  // VPN -> position 1
                        2 -> 0  // Subscriptions -> position 0
                        else -> tab.position
                    }
                    showAppTab(mappedPosition)
                }

                override fun onTabUnselected(tab: TabLayout.Tab) = Unit

                override fun onTabReselected(tab: TabLayout.Tab) = Unit
            })
        }
        val tabsHost = FrameLayout(this).apply {
            setBackgroundColor(Color.TRANSPARENT)
            setPadding(dp(18), 0, dp(18), dp(18))
            addView(tabs, FrameLayout.LayoutParams(-1, dp(72)))
        }
        ViewCompat.setOnApplyWindowInsetsListener(tabsHost) { view, insets ->
            val navigationBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            view.setPadding(dp(18), 0, dp(18), navigationBottom + dp(18))
            insets
        }
        shell.addView(tabsHost, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(shell, FrameLayout.LayoutParams(-1, -1))
        return root
    }

    private fun showAppTab(position: Int) {
        vpnTabContent.visibility = if (position == 1) View.VISIBLE else View.GONE
        subscriptionsTabContent.visibility = if (position == 0) View.VISIBLE else View.GONE
        advancedTabContent.visibility = if (position == 2) View.VISIBLE else View.GONE
        if (position == 0) renderSubscriptions()
        if (position == 2) renderAdvancedControls()
    }

    private fun buildSubscriptionsScreen(): View {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setBackgroundColor(withAlpha(BACKGROUND, 0))
        }
        ViewCompat.setOnApplyWindowInsetsListener(scroll) { view, insets ->
            val topInset = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout(),
            ).top
            view.setPadding(view.paddingLeft, topInset, view.paddingRight, view.paddingBottom)
            insets
        }
        val content = MaxWidthLinearLayout(this).apply {
            maxWidthPx = dp(520)
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(34), dp(24), dp(40))
        }
        val subscriptionsHeaderCopy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            addView(TextView(this@MainActivity).apply {
                setText(R.string.subscriptions_title)
                textSize = 28f
                typeface = WhiteDnsDisplayTypeface
                setTextColor(TEXT_PRIMARY)
                includeFontPadding = false
                gravity = Gravity.START
            })
            addView(
                TextView(this@MainActivity).apply {
                    setText(R.string.subscriptions_description)
                    textSize = 14f
                    typeface = WhiteDnsBodyTypeface
                    setTextColor(TEXT_SECONDARY)
                    includeFontPadding = false
                    gravity = Gravity.START
                },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) },
            )
        }
        val addSubscriptionButton = MaterialButton(this).apply {
            setText(R.string.subscription_add)
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            textDirection = View.TEXT_DIRECTION_LOCALE
            setAllCaps(false)
            textSize = 12f
            typeface = WhiteDnsBodyBoldTypeface
            isSingleLine = true
            setPadding(dp(8), 0, dp(8), 0)
            minWidth = 0
            insetTop = 0
            insetBottom = 0
            cornerRadius = dp(8)
            backgroundTintList = ColorStateList.valueOf(SURFACE)
            strokeWidth = dp(1)
            strokeColor = ColorStateList.valueOf(TEAL)
            rippleColor = ColorStateList.valueOf(withAlpha(TEAL, 24))
            setTextColor(TEAL)
            setOnClickListener { showAddSubscriptionDialog() }
        }
        val compactHeader = resources.configuration.screenWidthDp < 360
        content.addView(LinearLayout(this).apply {
            orientation = if (compactHeader) LinearLayout.VERTICAL else LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            gravity = if (compactHeader) Gravity.START else Gravity.CENTER_VERTICAL
            if (compactHeader) {
                addView(
                    subscriptionsHeaderCopy,
                    LinearLayout.LayoutParams(-1, -2),
                )
                addView(
                    addSubscriptionButton,
                    LinearLayout.LayoutParams(-2, dp(44)).apply { topMargin = dp(16) },
                )
            } else {
                addView(
                    subscriptionsHeaderCopy,
                    LinearLayout.LayoutParams(0, -2, 1f),
                )
                addView(
                    addSubscriptionButton,
                    LinearLayout.LayoutParams(dp(84), dp(44)).apply { marginStart = dp(16) },
                )
            }
        })
        subscriptionsList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        content.addView(subscriptionsList, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) })
        scroll.addView(content, ViewGroup.LayoutParams(-1, -2))
        renderSubscriptions()
        return scroll
    }

    private fun renderSubscriptions() {
        if (!::subscriptionsList.isInitialized) return
        subscriptionsList.removeAllViews()
        val store = SubscriptionStore(this)
        val selectedId = store.readSelectedSubscriptionId()
        val whiteDnsCount = store.readCatalog()?.profiles?.size ?: 0
        subscriptionsList.addView(
            subscriptionCard(
                title = "WhiteVPN",
                detail = getString(R.string.subscription_builtin_detail, connectionCountLabel(whiteDnsCount)),
                selected = selectedId == SubscriptionStore.DEFAULT_SUBSCRIPTION_ID,
                error = "",
                actions = listOf(
                    R.string.subscription_action_select to {
                        userSubscriptionManager.select(SubscriptionStore.DEFAULT_SUBSCRIPTION_ID)
                        onSubscriptionSelected()
                    },
                    R.string.subscription_action_refresh to { refreshDefaultSubscription() },
                ),
            ),
        )
        userSubscriptionManager.list().forEach { item ->
            val updated = item.updatedAt.takeIf { it > 0 }?.let {
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(it)
            } ?: getString(R.string.subscription_never_updated)
            subscriptionsList.addView(subscriptionDivider())
            subscriptionsList.addView(
                subscriptionCard(
                    title = item.name,
                    detail = getString(
                        R.string.subscription_detail,
                        item.format.label,
                        connectionCountLabel(item.connectionCount),
                        updated,
                    ),
                    selected = selectedId == item.id,
                    error = localizedSubscriptionError(item.lastError),
                    actions = listOf(
                        R.string.subscription_action_select to {
                            userSubscriptionManager.select(item.id)
                            onSubscriptionSelected()
                        },
                        R.string.subscription_action_edit to { showEditSubscriptionDialog(item) },
                        R.string.subscription_action_test to { testSubscription(item) },
                        R.string.subscription_action_refresh to { refreshSubscription(item) },
                        R.string.subscription_action_delete to { confirmDeleteSubscription(item) },
                    ),
                ),
                LinearLayout.LayoutParams(-1, -2),
            )
        }
    }

    private fun subscriptionDivider(): View = View(this).apply {
        setBackgroundColor(OUTLINE)
        layoutParams = LinearLayout.LayoutParams(-1, dp(1)).apply {
            marginStart = dp(16)
            marginEnd = dp(16)
        }
    }

    private fun subscriptionCard(
        title: String,
        detail: String,
        selected: Boolean,
        error: String,
        actions: List<Pair<Int, () -> Unit>>,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(88)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        elevation = 0f
        val selectAction = actions.firstOrNull { it.first == R.string.subscription_action_select }
        val overflowActions = actions.filterNot { it.first == R.string.subscription_action_select }
        val canSelect = selectAction != null && !selected
        isClickable = canSelect
        isFocusable = canSelect
        contentDescription = "$title, ${getString(
            if (selected) R.string.subscription_active else R.string.subscription_action_select,
        )}"
        background = if (selected) {
            glassSurfaceDrawable(radiusDp = 12, highlighted = true)
        } else {
            RippleDrawable(
                ColorStateList.valueOf(withAlpha(TEAL, 28)),
                glassSurfaceDrawable(radiusDp = 12),
                null,
            )
        }
        clipToOutline = true
        if (canSelect) {
            setOnClickListener { selectAction?.second?.invoke() }
        }

        addView(
            LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.VERTICAL
                layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                addView(
                    LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                        gravity = Gravity.CENTER_VERTICAL
                        addView(
                            TextView(this@MainActivity).apply {
                                text = title
                                textSize = 16f
                                typeface = WhiteDnsBodyBoldTypeface
                                setTextColor(TEXT_PRIMARY)
                                includeFontPadding = false
                                layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                                textDirection = View.TEXT_DIRECTION_FIRST_STRONG
                                gravity = Gravity.START
                            },
                            LinearLayout.LayoutParams(0, -2, 1f),
                        )
                        if (selected) addView(
                            LinearLayout(this@MainActivity).apply {
                                orientation = LinearLayout.HORIZONTAL
                                layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                                gravity = Gravity.CENTER_VERTICAL
                                addView(
                                    View(this@MainActivity).apply {
                                        background = GradientDrawable().apply {
                                            shape = GradientDrawable.OVAL
                                            setColor(TEAL)
                                        }
                                    },
                                    LinearLayout.LayoutParams(dp(7), dp(7)).apply { marginEnd = dp(8) },
                                )
                                addView(TextView(this@MainActivity).apply {
                                    setText(R.string.subscription_active)
                                    textSize = 12f
                                    typeface = WhiteDnsBodyBoldTypeface
                                    setTextColor(TEAL)
                                    includeFontPadding = false
                                    layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                                    textDirection = View.TEXT_DIRECTION_LOCALE
                                })
                            },
                            LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(12) },
                        )
                    },
                    LinearLayout.LayoutParams(-1, -2),
                )
                addView(TextView(this@MainActivity).apply {
                    text = detail
                    textSize = 12f
                    typeface = WhiteDnsBodyTypeface
                    setTextColor(TEXT_SECONDARY)
                    includeFontPadding = false
                    maxLines = 2
                    layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                    textDirection = View.TEXT_DIRECTION_FIRST_STRONG
                    gravity = Gravity.START
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
                if (error.isNotBlank()) addView(TextView(this@MainActivity).apply {
                    text = getString(R.string.subscription_error, error)
                    textSize = 12f
                    typeface = WhiteDnsBodyBoldTypeface
                    setTextColor(ERROR)
                    layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                    textDirection = View.TEXT_DIRECTION_FIRST_STRONG
                    gravity = Gravity.START
                }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
            },
            LinearLayout.LayoutParams(0, -2, 1f),
        )
        if (overflowActions.isNotEmpty()) {
            addView(
                subscriptionManageButton { view ->
                    whiteDnsPopupMenu(view).apply {
                        overflowActions.forEachIndexed { index, (labelRes, _) ->
                            menu.add(0, index, index, labelRes)
                        }
                        setOnMenuItemClickListener { item ->
                            overflowActions[item.itemId].second()
                            true
                        }
                    }.show()
                },
                LinearLayout.LayoutParams(-2, dp(44)).apply { marginStart = dp(12) },
            )
        }
    }

    private fun subscriptionManageButton(action: (View) -> Unit): ImageButton =
        ImageButton(this).apply {
            setImageResource(R.drawable.ic_more_vert)
            imageTintList = ColorStateList.valueOf(TEXT_PRIMARY)
            scaleType = ImageView.ScaleType.CENTER
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setSelectableBackground()
            contentDescription = getString(R.string.subscription_action_manage)
            setOnClickListener(action)
        }

    private fun showAddSubscriptionDialog() = showSubscriptionDialog()

    private fun showEditSubscriptionDialog(item: UserSubscription) = showSubscriptionDialog(item)

    private fun showSubscriptionDialog(existing: UserSubscription? = null) {
        val nameInput = TextInputEditText(this).apply {
            setSingleLine(true)
            background = null
            setPaddingRelative(dp(16), dp(12), dp(16), dp(12))
            setTextColor(TEXT_PRIMARY)
            setHintTextColor(TEXT_SECONDARY)
            setText(existing?.name.orEmpty())
        }
        val nameLayout = TextInputLayout(this).apply {
            hint = getString(R.string.subscription_name_hint)
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            boxBackgroundColor = withAlpha(SURFACE, if (palette.isDark) 232 else 246)
            boxStrokeColor = TEAL
            defaultHintTextColor = ColorStateList.valueOf(TEXT_SECONDARY)
            setBoxCornerRadii(dp(8).toFloat(), dp(8).toFloat(), dp(8).toFloat(), dp(8).toFloat())
            addView(nameInput)
        }
        val sourceInput = TextInputEditText(this).apply {
            minLines = 4
            maxLines = 9
            gravity = Gravity.TOP or Gravity.START
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            textDirection = View.TEXT_DIRECTION_LTR
            background = null
            setPaddingRelative(dp(16), dp(12), dp(16), dp(12))
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            setTextColor(TEXT_PRIMARY)
            setHintTextColor(TEXT_SECONDARY)
            setText(existing?.input.orEmpty())
        }
        val sourceLayout = TextInputLayout(this).apply {
            hint = getString(R.string.subscription_source_hint)
            helperText = getString(R.string.subscription_source_helper)
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            boxBackgroundColor = withAlpha(SURFACE, if (palette.isDark) 232 else 246)
            boxStrokeColor = TEAL
            defaultHintTextColor = ColorStateList.valueOf(TEXT_SECONDARY)
            setHelperTextColor(ColorStateList.valueOf(TEXT_SECONDARY))
            setBoxCornerRadii(dp(8).toFloat(), dp(8).toFloat(), dp(8).toFloat(), dp(8).toFloat())
            addView(sourceInput)
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(4))
            addView(nameLayout, LinearLayout.LayoutParams(-1, -2))
            addView(sourceLayout, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) })
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(
                if (existing == null) {
                    R.string.subscription_add_title
                } else {
                    R.string.subscription_edit_title
                },
            )
            .setView(body)
            .setNegativeButton(R.string.split_tunnel_cancel, null)
            .setPositiveButton(
                if (existing == null) R.string.subscription_add else R.string.split_tunnel_save,
                null,
            )
            .create()
        dialog.showWhiteDnsDialog {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameInput.text.toString()
                val source = sourceInput.text.toString()
                if (name.isBlank() || source.isBlank()) {
                    Toast.makeText(
                        this@MainActivity,
                        R.string.subscription_fields_required,
                        Toast.LENGTH_SHORT,
                    ).show()
                    return@setOnClickListener
                }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                activityScope.launch {
                    val result = runCatching {
                        withContext(Dispatchers.IO) {
                            if (existing == null) {
                                userSubscriptionManager.add(name, source)
                            } else {
                                userSubscriptionManager.update(existing.id, name, source)
                            }
                        }
                    }
                    result.onSuccess { item ->
                        dialog.dismiss()
                        renderSubscriptions()
                        renderConnectionDetails(buttonModel.state)
                        if (existing?.id == userSubscriptionManager.selectedId()) {
                            refreshLocationOptions()
                        }
                        Toast.makeText(
                            this@MainActivity,
                            getString(
                                if (existing == null) {
                                    R.string.subscription_added
                                } else {
                                    R.string.subscription_updated
                                },
                                connectionCountLabel(item.connectionCount),
                            ),
                            Toast.LENGTH_SHORT,
                        ).show()
                    }.onFailure { error ->
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        Toast.makeText(
                            this@MainActivity,
                            localizedError(
                                error,
                                if (existing == null) {
                                    R.string.subscription_add_failed
                                } else {
                                    R.string.subscription_update_failed
                                },
                            ),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }
            }
        }
    }

    private fun testSubscription(item: UserSubscription) {
        runSubscriptionAction(getString(R.string.subscription_testing, item.name)) {
            val imported = userSubscriptionManager.test(item.input)
            getString(R.string.subscription_found, connectionCountLabel(imported.connectionCount))
        }
    }

    private fun refreshSubscription(item: UserSubscription) {
        runSubscriptionAction(
            startMessage = getString(R.string.subscription_refreshing, item.name),
            refreshProfiles = item.id == userSubscriptionManager.selectedId(),
        ) {
            val refreshed = userSubscriptionManager.refresh(item.id)
            getString(R.string.subscription_refreshed, connectionCountLabel(refreshed.connectionCount))
        }
    }

    private fun refreshDefaultSubscription() {
        runSubscriptionAction(
            startMessage = getString(R.string.subscription_refreshing, "WhiteVPN"),
            refreshProfiles = userSubscriptionManager.selectedId() == SubscriptionStore.DEFAULT_SUBSCRIPTION_ID,
        ) {
            val refreshed = ConfigRepository(this@MainActivity).refreshDefaultMihomoConfig()
            getString(R.string.subscription_refreshed, connectionCountLabel(refreshed.catalog.profiles.size))
        }
    }

    private fun runSubscriptionAction(
        startMessage: String,
        refreshProfiles: Boolean = false,
        action: suspend () -> String,
    ) {
        Toast.makeText(this, startMessage, Toast.LENGTH_SHORT).show()
        activityScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { action() } }
            renderSubscriptions()
            if (result.isSuccess && refreshProfiles) refreshLocationOptions()
            result.onSuccess { message -> Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show() }
                .onFailure { error ->
                    Toast.makeText(
                        this@MainActivity,
                        localizedError(error, R.string.subscription_operation_failed),
                        Toast.LENGTH_LONG,
                    ).show()
                }
        }
    }

    private fun confirmDeleteSubscription(item: UserSubscription) {
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.subscription_delete_title, item.name))
            .setMessage(R.string.subscription_delete_message)
            .setNegativeButton(R.string.split_tunnel_cancel, null)
            .setPositiveButton(R.string.subscription_action_delete) { _, _ ->
                userSubscriptionManager.delete(item.id)
                renderSubscriptions()
                refreshLocationOptions()
            }
            .create()
        dialog.showWhiteDnsDialog(positiveColor = ERROR)
    }

    private fun onSubscriptionSelected() {
        locationPreferenceStore.clearSelectedCountry()
        renderSubscriptions()
        renderConnectionDetails(buttonModel.state)
        renderConnectionSelection()
        refreshLocationOptions()
        if (buttonModel.state == VpnState.Started) {
            Toast.makeText(this, R.string.subscription_selected_reconnect, Toast.LENGTH_LONG).show()
        }
    }

    private fun connectionCountLabel(count: Int): String =
        resources.getQuantityString(R.plurals.connection_count, count, count)

    private fun localizedSubscriptionError(error: String): String = when {
        error.isBlank() -> ""
        else -> getString(R.string.subscription_operation_failed)
    }

    private fun selectedSubscriptionName(): String {
        val store = SubscriptionStore(this)
        return store.readUserSubscription(store.readSelectedSubscriptionId())?.name ?: "WhiteVPN"
    }

    private fun renderConnectionDetails(state: VpnState) {
        if (!::connectionDetailsText.isInitialized) return
        connectionDetailsText.text = ConnectionDetailsPresenter.forDashboard(
            selectedSource = selectedSubscriptionName(),
            runtimeDetails = connectionDetails.takeIf { state == VpnState.Started }.orEmpty(),
            stringFor = { getString(it) },
        )
        connectionDetailsText.visibility = View.VISIBLE
    }

    private fun renderDashboardLocalEndpoint(mode: ConnectionMode) {
        if (!::dashboardLocalEndpointText.isInitialized) return
        dashboardLocalEndpointText.text = if (mode == ConnectionMode.Proxy) {
            getString(
                R.string.connection_mode_local_endpoint,
                "127.0.0.1",
                MihomoRuntimeDefaults.MIXED_PORT.toString(),
            )
        } else {
            ""
        }
        dashboardLocalEndpointText.visibility =
            if (dashboardLocalEndpointText.text.isNotEmpty()) View.VISIBLE else View.GONE
    }

    private fun localizedError(@Suppress("UNUSED_PARAMETER") error: Throwable, @StringRes fallbackRes: Int): String =
        getString(fallbackRes)

    override fun onStart() {
        super.onStart()
        DiagnosticLogger.info(this, "activity.onStart")
        // Resume orb animation when app comes to foreground
        if (::connectionOrb.isInitialized) connectionOrb.resumeAnimation()
        val filter = IntentFilter().apply {
            addAction(Actions.STATE_CHANGED)
            addAction(Actions.CONNECTION_DELAY_TEST_CHANGED)
        }
        // Below API 33 a bare registerReceiver is implicitly exported, which would let any other
        // app broadcast STATE_CHANGED and paint a false "connected" state over the UI.
        // ContextCompat guards the pre-33 path with a signature-level permission.
        ContextCompat.registerReceiver(this, stateReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        applyRuntimeState(
            VpnRuntimeStateStore.read(this),
            VpnRuntimeStateStore.readSessionStartedAtElapsedMs(this),
            VpnRuntimeStateStore.readConnectionCountryFlag(this),
            VpnRuntimeStateStore.readDebugFrontingIp(this)
                .takeIf { it in frontingIpPreferenceStore.readFrontingIps() }
                .orEmpty(),
            VpnRuntimeStateStore.readConnectionDetails(this),
            VpnRuntimeStateStore.readActiveSubscriptionId(this),
            VpnRuntimeStateStore.readActiveConnectionTag(this),
            VpnRuntimeStateStore.readActiveConnectionFingerprint(this),
            VpnRuntimeStateStore.readLiveSelectorReady(this),
            VpnRuntimeStateStore.readSelectableConnectionFingerprints(this),
            VpnRuntimeStateStore.readAlwaysOn(this),
            VpnRuntimeStateStore.readLockdown(this),
        )
    }

    override fun onStop() {
        DiagnosticLogger.info(this, "activity.onStop")
        // Pause orb animation when app goes to background to save battery
        if (::connectionOrb.isInitialized) connectionOrb.pauseAnimation()
        mainHandler.removeCallbacks(timerRunnable)
        mainHandler.removeCallbacks(disconnectTimeoutRunnable)
        privacyPolicyDialog?.dismiss()
        privacyPolicyDialog = null
        connectionSelectorDialog?.dismiss()
        connectionSelectorDialog = null
        unregisterReceiver(stateReceiver)
        super.onStop()
    }

    override fun onDestroy() {
        activityScope.cancel()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Android API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_VPN_PERMISSION) return
        if (!connectFlowPending) {
            DiagnosticLogger.info(this, "permission.vpn.ignored", "resultCode=$resultCode reason=connect-canceled")
            return
        }
        val pendingAction = connectFlowAction
        connectFlowPending = false
        connectFlowAction = Actions.CONNECT
        if (resultCode == RESULT_OK) {
            DiagnosticLogger.info(this, "permission.vpn", "granted")
            startVpnService(pendingAction)
        } else {
            DiagnosticLogger.warn(this, "permission.vpn", "denied resultCode=$resultCode")
            AnalyticsEvents.connectionTryFailed(this)
            if (pendingAction == Actions.RECONNECT) {
                connectionModePreferenceStore.save(ConnectionMode.Proxy)
                buttonModel.onStateChanged(VpnState.Started)
                renderState(VpnState.Started)
            } else {
                buttonModel.onStateChanged(VpnState.Stopped)
                renderState(VpnState.Stopped)
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATION_PERMISSION) {
            DiagnosticLogger.info(
                this,
                "permission.notification",
                "result=${grantResults.joinToString()}",
            )
            if (!connectFlowPending) {
                DiagnosticLogger.info(this, "permission.notification.ignored", "reason=connect-canceled")
                return
            }
            requestVpnPermissionThenConnect()
        }
    }

    private fun buildDashboard(): View {
        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(withAlpha(BACKGROUND, 0))
            clipToPadding = false
            clipChildren = false  // Allow particles to extend beyond bounds
        }
        ViewCompat.setOnApplyWindowInsetsListener(scrollView) { view, insets ->
            val topInset = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout(),
            ).top
            val bottomInset = insets.getInsets(
                WindowInsetsCompat.Type.ime() or WindowInsetsCompat.Type.navigationBars(),
            ).bottom
            view.setPadding(view.paddingLeft, topInset, view.paddingRight, bottomInset)
            view.post {
                view.findFocus()?.let { focusedView ->
                    scrollFieldIntoView(scrollView, focusedView, delayMs = 0L)
                }
            }
            insets
        }
        val viewport = FrameLayout(this).apply {
            setPadding(0, 0, 0, dp(24))
            // Allow particles to extend beyond this layout's bounds
            clipChildren = false
            clipToPadding = false
        }
        scrollView.addView(
            viewport,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        val dashboardContent = MaxWidthLinearLayout(this).apply {
            maxWidthPx = dp(520)
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, dp(24))
            // Allow particles to extend beyond this layout's bounds
            clipChildren = false
            clipToPadding = false
        }

        fun contentParams(topMargin: Int = 0): LinearLayout.LayoutParams {
            return LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                leftMargin = dp(24)
                rightMargin = dp(24)
                this.topMargin = topMargin
            }
        }

        // New header with hamburger menu, centered title, and theme toggle
        val headerBlock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            gravity = Gravity.CENTER_VERTICAL
            // Hamburger menu button on left
            addView(
                ImageButton(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_menu)
                    imageTintList = ColorStateList.valueOf(TEXT_PRIMARY)
                    scaleType = ImageView.ScaleType.CENTER
                    setPadding(dp(10), dp(10), dp(10), dp(10))
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(Color.TRANSPARENT)
                    }
                    setSelectableBackground()
                    contentDescription = getString(R.string.home_menu_content_description)
                    setOnClickListener { showHomeMenu(this) }
                },
                LinearLayout.LayoutParams(dp(40), dp(40)),
            )
            // Centered title with version
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutDirection = View.LAYOUT_DIRECTION_LTR
                    gravity = Gravity.CENTER
                    setPadding(dp(9), dp(4), dp(9), dp(4))
                    addView(TextView(this@MainActivity).apply {
                        text = "WhiteVPN"
                        textSize = 14.5f
                        typeface = WhiteDnsDisplayTypeface
                        setTextColor(TEXT_PRIMARY)
                        includeFontPadding = false
                        letterSpacing = -0.01f
                    })
                    addView(TextView(this@MainActivity).apply {
                        text = BuildConfig.VERSION_NAME
                        textSize = 11.5f
                        typeface = WhiteDnsBodyTypeface
                        setTextColor(palette.textTertiary)
                        includeFontPadding = false
                    }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(9) })
                },
                LinearLayout.LayoutParams(0, -2, 1f).apply {
                    gravity = Gravity.CENTER
                },
            )
            // Empty spacer on right for balance (theme toggle removed)
            addView(
                View(this@MainActivity),
                LinearLayout.LayoutParams(dp(40), dp(40)),
            )
        }

        // New segmented tab switcher with rounded corners
        val connectionModeButtonColors = arrayOf(
            intArrayOf(android.R.attr.state_enabled, android.R.attr.state_checked),
            intArrayOf(android.R.attr.state_enabled, -android.R.attr.state_checked),
            intArrayOf(-android.R.attr.state_enabled, android.R.attr.state_checked),
            intArrayOf(-android.R.attr.state_enabled, -android.R.attr.state_checked),
        )
        fun connectionModeButton(@StringRes textRes: Int) = MaterialButton(
            this,
            null,
            com.google.android.material.R.attr.materialButtonOutlinedStyle,
        ).apply {
            id = View.generateViewId()
            setText(textRes)
            setAllCaps(false)
            textSize = 14f
            typeface = WhiteDnsBodyBoldTypeface
            isCheckable = true
            minWidth = 0
            minimumWidth = 0
            minHeight = dp(44)
            minimumHeight = dp(44)
            insetTop = 0
            insetBottom = 0
            setPadding(0, 0, 0, 0)
            cornerRadius = dp(12)
            strokeWidth = 0
            elevation = 0f
            stateListAnimator = null
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            backgroundTintList = ColorStateList(
                connectionModeButtonColors,
                intArrayOf(TEAL, Color.TRANSPARENT, withAlpha(TEAL, 80), Color.TRANSPARENT),
            )
            setTextColor(
                ColorStateList(
                    connectionModeButtonColors,
                    intArrayOf(
                        palette.onAccent,
                        TEXT_PRIMARY,
                        withAlpha(palette.onAccent, 140),
                        withAlpha(TEXT_PRIMARY, 110),
                    ),
                ),
            )
            rippleColor = ColorStateList.valueOf(withAlpha(TEAL, 28))
        }
        vpnModeButton = connectionModeButton(R.string.connection_mode_vpn)
        proxyModeButton = connectionModeButton(R.string.connection_mode_proxy)
        connectionModeGroup = MaterialButtonToggleGroup(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            isSingleSelection = true
            isSelectionRequired = true
            // Container with rounded background
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(withAlpha(SURFACE, 200))
            }
            setPadding(dp(5), dp(5), dp(5), dp(5))
            addView(vpnModeButton, LinearLayout.LayoutParams(0, -1, 1f))
            addView(proxyModeButton, LinearLayout.LayoutParams(0, -1, 1f))
            check(
                if (connectionModePreferenceStore.read() == ConnectionMode.Proxy) {
                    proxyModeButton.id
                } else {
                    vpnModeButton.id
                },
            )
            addOnButtonCheckedListener { _, checkedId, isChecked ->
                if (isChecked && isEnabled) {
                    saveConnectionMode(
                        if (checkedId == proxyModeButton.id) ConnectionMode.Proxy else ConnectionMode.Vpn,
                    )
                }
            }
        }
        connectionOrb = ConnectionOrbView(this).apply {
            isClickable = true
            isFocusable = true
            setOnClickListener { handleButtonClick() }
            setOnLongClickListener {
                copyDiagnosticsToClipboard()
                true
            }
        }
        statusDot = View(this).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(palette.neutral)
            }
        }
        statusText = TextView(this).apply {
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            textDirection = View.TEXT_DIRECTION_LOCALE
            textSize = 13f
            typeface = WhiteDnsBodyTypeface
            setTextColor(TEXT_SECONDARY)
            includeFontPadding = false
        }
        timerText = TextView(this).apply {
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            textDirection = View.TEXT_DIRECTION_LTR
            text = "00:00:00"
            textSize = 15f
            letterSpacing = 0.02f
            typeface = WhiteDnsBodyBoldTypeface // Same as stats
            setTextColor(TEXT_PRIMARY)
            includeFontPadding = false
            alpha = 0.25f
        }
        downloadSpeedText = TextView(this).apply {
            text = "0 B/s"
            gravity = Gravity.END
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            textDirection = View.TEXT_DIRECTION_LTR
            textSize = 15f
            typeface = WhiteDnsBodyBoldTypeface
            setTextColor(TEXT_PRIMARY)
            includeFontPadding = false
        }
        uploadSpeedText = TextView(this).apply {
            text = "0 B/s"
            gravity = Gravity.END
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            textDirection = View.TEXT_DIRECTION_LTR
            textSize = 15f
            typeface = WhiteDnsBodyBoldTypeface
            setTextColor(TEXT_PRIMARY)
            includeFontPadding = false
        }
        connectionCountryText = TextView(this).apply {
            setText(R.string.output_automatic)
            gravity = Gravity.START
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            textSize = 12f
            typeface = WhiteDnsBodyBoldTypeface
            setTextColor(TEXT_SECONDARY)
            includeFontPadding = false
        }
        connectionDetailsText = TextView(this).apply {
            gravity = Gravity.START
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            textSize = 12f
            typeface = WhiteDnsDataTypeface
            setTextColor(TEXT_SECONDARY)
            includeFontPadding = false
            minWidth = 0
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            visibility = View.GONE
        }
        dashboardLocalEndpointText = TextView(this).apply {
            gravity = Gravity.START
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            textDirection = View.TEXT_DIRECTION_LTR
            textSize = 12f
            typeface = WhiteDnsDataTypeface
            setTextColor(TEXT_SECONDARY)
            includeFontPadding = false
            visibility = View.GONE
        }

        // Animated arrow icons for download/upload
        downloadArrowIcon = AnimatedArrowIcon(this, isDownload = true)
        uploadArrowIcon = AnimatedArrowIcon(this, isDownload = false)

        // Speed stats row with animated arrow icons - matching the design
        fun speedStatRow(label: String, value: TextView, isDownload: Boolean, arrowIcon: AnimatedArrowIcon): View = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(6), dp(6), dp(6), dp(6))
            // Arrow icon + label on left
            addView(LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                // Animated arrow icon
                addView(arrowIcon, LinearLayout.LayoutParams(dp(13), dp(13)))
                addView(TextView(this@MainActivity).apply {
                    text = label
                    textSize = 11.5f
                    typeface = WhiteDnsBodyTypeface
                    setTextColor(TEXT_SECONDARY)
                    includeFontPadding = false
                }, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(6) })
            }, LinearLayout.LayoutParams(0, -2, 1f))
            // Value on right
            addView(value, LinearLayout.LayoutParams(-2, -2))
        }
        val speedStatsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            gravity = Gravity.CENTER_VERTICAL
            addView(
                speedStatRow(getString(R.string.metric_download), downloadSpeedText, true, downloadArrowIcon),
                LinearLayout.LayoutParams(0, -2, 1f),
            )
            addView(
                speedStatRow(getString(R.string.metric_upload), uploadSpeedText, false, uploadArrowIcon),
                LinearLayout.LayoutParams(0, -2, 1f),
            )
        }

        // Reconnect button - initialize before signalSection
        refreshActionButton = MaterialButton(this).apply {
            setText(R.string.action_reconnect)
            textSize = 11f
            typeface = WhiteDnsBodyBoldTypeface
            minHeight = dp(32)
            minimumHeight = dp(32)
            minWidth = 0
            minimumWidth = 0
            insetTop = 0
            insetBottom = 0
            setPadding(dp(12), dp(6), dp(12), dp(6))
            cornerRadius = dp(16)
            strokeWidth = 0
            elevation = 0f
            stateListAnimator = null
            setAllCaps(false)
            setIconResource(R.drawable.ic_refresh)
            iconSize = dp(14)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            iconPadding = dp(4)
            visibility = View.INVISIBLE  // Use INVISIBLE to preserve space and prevent UI jump
            setOnClickListener { handleRefreshClick() }
        }

        // Row with reconnect button on left and timer on right
        val timerRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            // Reconnect button on left
            addView(
                refreshActionButton,
                LinearLayout.LayoutParams(-2, -2),
            )
            // Spacer
            addView(
                View(this@MainActivity),
                LinearLayout.LayoutParams(0, 0, 1f),
            )
            // Timer on right
            addView(
                timerText,
                LinearLayout.LayoutParams(-2, -2),
            )
        }

        val signalSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            // Allow particles to extend beyond this layout's bounds
            clipChildren = false
            clipToPadding = false
            // Connection orb button - centered at top
            addView(
                connectionOrb,
                LinearLayout.LayoutParams(-2, -2),
            )
            // Timer and reconnect button row
            addView(
                timerRow,
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) },
            )
            // Speed stats row
            addView(
                speedStatsRow,
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) },
            )
        }

        locationSelectorRow = DashboardDataRowView(this).apply {
            setRow(getString(R.string.location_label), getString(R.string.option_automatic))
            setOnRowClickListener { showLocationSelector() }
        }
        connectionSelectorRow = DashboardDataRowView(this).apply {
            setRow(getString(R.string.connection_label), getString(R.string.option_automatic))
            setOnRowClickListener { showConnectionSelector() }
        }
        splitTunnelRow = DashboardDataRowView(this).apply {
            setRow(getString(R.string.split_tunnel_label), getString(R.string.value_inactive))
            setOnRowClickListener { showSplitTunnelSelector() }
        }
        // Settings card with rounded corners and shadow
        val dataRowsList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(22).toFloat()
                setColor(withAlpha(SURFACE, 200))
            }
            elevation = dp(8).toFloat()
            clipToOutline = true
            addView(locationSelectorRow, LinearLayout.LayoutParams(-1, -2))
            addView(View(this@MainActivity).apply { setBackgroundColor(withAlpha(OUTLINE, 150)) },
                LinearLayout.LayoutParams(-1, dp(1)).apply {
                    marginStart = dp(18)
                    marginEnd = dp(18)
                })
            addView(connectionSelectorRow, LinearLayout.LayoutParams(-1, -2))
            addView(View(this@MainActivity).apply { setBackgroundColor(withAlpha(OUTLINE, 150)) },
                LinearLayout.LayoutParams(-1, dp(1)).apply {
                    marginStart = dp(18)
                    marginEnd = dp(18)
                })
            addView(splitTunnelRow, LinearLayout.LayoutParams(-1, -2))
        }
        val dataRows = dataRowsList

        val footerCopyrightText = TextView(this).apply {
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            text = getString(R.string.footer_copyright)
            textSize = 12f
            typeface = WhiteDnsBodyTypeface
            setTextColor(TEXT_SECONDARY)
            includeFontPadding = false
        }
        val footerTelegramLink = TextView(this).apply {
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            textDirection = View.TEXT_DIRECTION_LTR
            text = getString(R.string.footer_url)
            textSize = 12f
            typeface = WhiteDnsBodyTypeface
            setTextColor(TEXT_SECONDARY)
            includeFontPadding = false
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            minHeight = dp(44)
            setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ic_telegram, 0, 0, 0)
            compoundDrawablePadding = dp(6)
            compoundDrawableTintList = ColorStateList.valueOf(TEXT_SECONDARY)
            setSelectableBackground()
            isClickable = true
            isFocusable = true
            contentDescription = getString(R.string.footer_telegram_content_description)
            setOnClickListener { openFooterLink() }
        }
        val footer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(footerCopyrightText, LinearLayout.LayoutParams(-1, -2))
            addView(footerTelegramLink, LinearLayout.LayoutParams(-2, dp(44)))
        }

        dashboardContent.apply {
            addView(
                headerBlock,
                contentParams(dp(34)),
            )
            addView(
                signalSection,
                contentParams(dp(24)),
            )
            // VPN/Proxy tab switcher
            addView(
                connectionModeGroup,
                contentParams(dp(16)),
            )
            addView(
                dataRows,
                contentParams(dp(16)),
            )
            // Connection details row
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                    gravity = Gravity.CENTER_VERTICAL
                    addView(connectionDetailsText, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(dashboardLocalEndpointText, LinearLayout.LayoutParams(-2, -2))
                },
                contentParams(dp(4)),
            )
            addView(
                footer,
                contentParams(dp(16)),
            )
        }
        viewport.addView(
            dashboardContent,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            ),
        )

        return scrollView
    }

    private fun buildAdvancedScreen(): View {
        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setBackgroundColor(withAlpha(BACKGROUND, 0))
        }
        ViewCompat.setOnApplyWindowInsetsListener(scrollView) { view, insets ->
            val topInset = insets.getInsets(
                WindowInsetsCompat.Type.statusBars() or WindowInsetsCompat.Type.displayCutout(),
            ).top
            val bottomInset = insets.getInsets(
                WindowInsetsCompat.Type.ime() or WindowInsetsCompat.Type.navigationBars(),
            ).bottom
            view.setPadding(view.paddingLeft, topInset, view.paddingRight, bottomInset)
            view.post {
                view.findFocus()?.let { focusedView ->
                    scrollFieldIntoView(scrollView, focusedView, delayMs = 0L)
                }
            }
            insets
        }
        val advancedBody = MaxWidthLinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            maxWidthPx = dp(520)
            setPadding(dp(24), dp(34), dp(24), dp(40))
            addView(
                TextView(this@MainActivity).apply {
                    setText(R.string.settings_title)
                    textSize = 28f
                    typeface = WhiteDnsDisplayTypeface
                    setTextColor(TEXT_PRIMARY)
                    includeFontPadding = false
                },
            )
            addView(
                TextView(this@MainActivity).apply {
                    setText(R.string.settings_description)
                    textSize = 14f
                    typeface = WhiteDnsBodyTypeface
                    setTextColor(TEXT_SECONDARY)
                    includeFontPadding = false
                    setLineSpacing(dp(2).toFloat(), 1f)
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        advancedBody.addView(
            advancedSectionLabel(getString(R.string.tls_integrity_section)),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(32) },
        )
        val tlsIntegrityPanel = advancedSettingsPanel()
        tlsIntegrityCheckbox = MaterialSwitch(this).apply {
            isChecked = tlsIntegrityPreferenceStore.isEnabled()
            contentDescription = getString(R.string.tls_integrity_title)
            setOnClickListener { saveTlsIntegrityEnabled(isChecked) }
        }
        tlsIntegrityPanel.addView(
            advancedToggleRow(
                title = getString(R.string.tls_integrity_title),
                detail = getString(R.string.tls_integrity_description),
                toggle = tlsIntegrityCheckbox,
            ),
            LinearLayout.LayoutParams(-1, -2),
        )
        advancedBody.addView(
            tlsIntegrityPanel,
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) },
        )
        advancedBody.addView(
            advancedSectionLabel(getString(R.string.settings_warp_section)),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(24) },
        )
        val amneziaPanel = advancedSettingsPanel()
        advancedBody.addView(
            amneziaPanel,
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) },
        )
        amneziaNoiseCheckbox = MaterialSwitch(this).apply {
            isChecked = connectionOptionsPreferenceStore.read().amneziaNoiseEnabled
            contentDescription = getString(R.string.amnezia_noise_enable)
            setOnClickListener { saveAmneziaNoiseEnabled(isChecked) }
        }
        amneziaPanel.addView(
            advancedToggleRow(
                title = getString(R.string.amnezia_noise_title),
                detail = getString(R.string.amnezia_noise_description),
                toggle = amneziaNoiseCheckbox,
            ),
            LinearLayout.LayoutParams(-1, -2),
        )

        fun noiseNumberField(hint: String): Pair<EditText, TextInputLayout> {
            val input = TextInputEditText(this).apply {
                setSingleLine(true)
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                textDirection = View.TEXT_DIRECTION_LTR
                gravity = Gravity.CENTER
                background = null
                textSize = 14f
                inputType = InputType.TYPE_CLASS_NUMBER
                imeOptions = EditorInfo.IME_ACTION_NEXT
                setTextColor(TEXT_PRIMARY)
            }
            val layout = TextInputLayout(this).apply {
                this.hint = hint
                boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
                boxBackgroundColor = withAlpha(SURFACE, if (palette.isDark) 232 else 246)
                boxStrokeColor = TEAL
                boxStrokeWidth = dp(1)
                boxStrokeWidthFocused = dp(1)
                defaultHintTextColor = ColorStateList.valueOf(TEXT_SECONDARY)
                setBoxCornerRadii(dp(8).toFloat(), dp(8).toFloat(), dp(8).toFloat(), dp(8).toFloat())
                addView(input)
            }
            return input to layout
        }

        val (countInput, countLayout) = noiseNumberField(getString(R.string.amnezia_noise_count))
        val (minSizeInput, minSizeLayout) = noiseNumberField(getString(R.string.amnezia_noise_min_size))
        val (maxSizeInput, maxSizeLayout) = noiseNumberField(getString(R.string.amnezia_noise_max_size))
        amneziaNoiseCountInput = countInput
        amneziaNoiseMinSizeInput = minSizeInput
        amneziaNoiseMaxSizeInput = maxSizeInput.apply { imeOptions = EditorInfo.IME_ACTION_DONE }
        amneziaNoiseFields = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            gravity = Gravity.CENTER_VERTICAL
            addView(countLayout, LinearLayout.LayoutParams(0, -2, 1f))
            addView(minSizeLayout, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
            addView(maxSizeLayout, LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(8) })
        }
        amneziaPanel.addView(
            amneziaNoiseFields,
            LinearLayout.LayoutParams(-1, -2).apply {
                marginStart = dp(8)
                marginEnd = dp(8)
                topMargin = dp(8)
            },
        )
        amneziaNoiseApplyButton = MaterialButton(this).apply {
            setText(R.string.amnezia_noise_apply)
            textSize = 12f
            typeface = WhiteDnsBodyBoldTypeface
            setAllCaps(false)
            minWidth = 0
            minHeight = dp(44)
            insetTop = 0
            insetBottom = 0
            cornerRadius = dp(8)
            backgroundTintList = ColorStateList.valueOf(SURFACE)
            strokeWidth = dp(1)
            strokeColor = ColorStateList.valueOf(OUTLINE)
            rippleColor = ColorStateList.valueOf(withAlpha(TEAL, 28))
            setTextColor(TEAL)
            elevation = 0f
            stateListAnimator = null
            setOnClickListener { applyAmneziaNoiseSettings() }
        }
        amneziaPanel.addView(
            amneziaNoiseApplyButton,
            LinearLayout.LayoutParams(-2, dp(44)).apply {
                gravity = Gravity.END
                marginStart = dp(8)
                marginEnd = dp(8)
                topMargin = dp(12)
            },
        )
        amneziaNoiseErrorText = TextView(this).apply {
            textSize = 12f
            setTextColor(ERROR)
            visibility = View.GONE
        }
        amneziaPanel.addView(
            amneziaNoiseErrorText,
            LinearLayout.LayoutParams(-1, -2).apply {
                marginStart = dp(8)
                marginEnd = dp(8)
            },
        )
        advancedBody.addView(
            advancedSectionLabel(getString(R.string.fronting_section)),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(24)
            },
        )
        val frontingPanel = advancedSettingsPanel()
        advancedBody.addView(
            frontingPanel,
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) },
        )
        frontingPanel.addView(
            advancedSectionDetail(getString(R.string.fronting_description)),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginStart = dp(8)
                marginEnd = dp(8)
                topMargin = dp(8)
            },
        )
        frontingIps = frontingIpPreferenceStore.readFrontingIps()
        frontingIpChipGroup = ChipGroup(this).apply {
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            isSingleLine = false
            chipSpacingHorizontal = dp(8)
            chipSpacingVertical = dp(4)
        }
        frontingPanel.addView(
            frontingIpChipGroup,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginStart = dp(8)
                marginEnd = dp(8)
                topMargin = dp(8)
            },
        )
        renderFrontingIpChips()
        frontingIpInput = TextInputEditText(this).apply {
            setSingleLine(true)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            textDirection = View.TEXT_DIRECTION_LTR
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            background = null
            setPaddingRelative(dp(16), dp(12), dp(16), dp(12))
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_DONE
            setTextColor(TEXT_PRIMARY)
            setHintTextColor(TEXT_SECONDARY)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId != EditorInfo.IME_ACTION_DONE) return@setOnEditorActionListener false
                commitFrontingIpInput(reconnectIfChanged = true)
                clearFocus()
                true
            }
            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    scrollFieldIntoView(scrollView, frontingIpInputLayout)
                } else {
                    commitFrontingIpInput(reconnectIfChanged = true)
                }
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    if (frontingIpInputUpdating) return
                    frontingIpErrorText.visibility = View.GONE
                    val value = s?.toString().orEmpty()
                    if (value.contains(",")) {
                        val parts = value.split(",")
                        val completeParts = parts.dropLast(1)
                        val tail = if (value.endsWith(",")) "" else parts.last()
                        if (addFrontingIpTokens(completeParts, focusOnError = false)) {
                            setFrontingIpInputText(tail)
                        }
                    }
                }
            })
        }
        frontingIpInputLayout = TextInputLayout(this).apply {
            hint = getString(R.string.fronting_hint)
            placeholderText = "104.16.0.1:443"
            helperText = frontingIpInputHint()
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            boxBackgroundColor = withAlpha(SURFACE, if (palette.isDark) 232 else 246)
            boxStrokeColor = TEAL
            boxStrokeWidth = dp(1)
            boxStrokeWidthFocused = dp(1)
            defaultHintTextColor = ColorStateList.valueOf(TEXT_SECONDARY)
            setHelperTextColor(ColorStateList.valueOf(TEXT_SECONDARY))
            setBoxCornerRadii(dp(8).toFloat(), dp(8).toFloat(), dp(8).toFloat(), dp(8).toFloat())
            addView(frontingIpInput)
        }
        frontingPanel.addView(
            frontingIpInputLayout,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginStart = dp(8)
                marginEnd = dp(8)
                topMargin = dp(12)
            },
        )
        frontingIpErrorText = TextView(this).apply {
            textSize = 12f
            setTextColor(ERROR)
            includeFontPadding = true
            visibility = View.GONE
        }
        frontingPanel.addView(
            frontingIpErrorText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginStart = dp(8)
                marginEnd = dp(8)
            },
        )
        advancedBody.addView(
            advancedSectionLabel(getString(R.string.routing_section)),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(24)
            },
        )
        val routingPanel = advancedSettingsPanel()
        advancedBody.addView(
            routingPanel,
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) },
        )
        routingModeDetailText = TextView(this).apply {
            textSize = 12f
            typeface = WhiteDnsBodyTypeface
            setTextColor(TEXT_SECONDARY)
            includeFontPadding = false
            maxLines = 2
        }
        val routingText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            addView(
                TextView(this@MainActivity).apply {
                    setText(R.string.routing_rules_title)
                    textSize = 14f
                    typeface = WhiteDnsBodyBoldTypeface
                    setTextColor(TEXT_PRIMARY)
                    includeFontPadding = false
                },
            )
            addView(
                routingModeDetailText,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { topMargin = dp(4) },
            )
        }
        routingModeValueText = TextView(this).apply {
            textSize = 14f
            typeface = WhiteDnsBodyBoldTypeface
            setTextColor(TEAL)
            includeFontPadding = false
            gravity = Gravity.START
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            textDirection = View.TEXT_DIRECTION_LOCALE
        }
        routingModeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setSelectableBackground()
            isClickable = true
            isFocusable = true
            setOnClickListener { showRoutingModeSelector() }
            addView(
                routingText,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                routingModeValueText,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = dp(16) },
            )
            addView(
                TextView(this@MainActivity).apply {
                    setText(R.string.chevron_forward)
                    textSize = 22f
                    layoutDirection = View.LAYOUT_DIRECTION_LTR
                    textDirection = View.TEXT_DIRECTION_LTR
                    typeface = WhiteDnsBodyBoldTypeface
                    setTextColor(TEAL)
                    includeFontPadding = false
                    gravity = Gravity.CENTER
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = dp(8) },
            )
        }
        routingPanel.addView(
            routingModeRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        advancedBody.addView(
            advancedSectionLabel(getString(R.string.dns_privacy_section)),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(24)
            },
        )
        val dnsPanel = advancedSettingsPanel()
        advancedBody.addView(
            dnsPanel,
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) },
        )
        dnsPrivacyDetailText = TextView(this).apply {
            textSize = 12f
            typeface = WhiteDnsBodyTypeface
            setTextColor(TEXT_SECONDARY)
            includeFontPadding = false
        }
        val dnsText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            addView(
                TextView(this@MainActivity).apply {
                    setText(R.string.dns_encrypted_title)
                    textSize = 14f
                    typeface = WhiteDnsBodyBoldTypeface
                    setTextColor(TEXT_PRIMARY)
                    includeFontPadding = false
                },
            )
            addView(
                dnsPrivacyDetailText,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = dp(4)
                },
            )
        }
        dnsPrivacyValueText = TextView(this).apply {
            textSize = 14f
            typeface = WhiteDnsBodyBoldTypeface
            setTextColor(TEAL)
            includeFontPadding = false
            gravity = Gravity.START
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            textDirection = View.TEXT_DIRECTION_LOCALE
        }
        dnsPrivacyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setSelectableBackground()
            isClickable = true
            isFocusable = true
            setOnClickListener { showDnsPrivacySelector() }
            addView(
                dnsText,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                dnsPrivacyValueText,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = dp(16) },
            )
            addView(
                TextView(this@MainActivity).apply {
                    setText(R.string.chevron_forward)
                    textSize = 22f
                    layoutDirection = View.LAYOUT_DIRECTION_LTR
                    textDirection = View.TEXT_DIRECTION_LTR
                    typeface = WhiteDnsBodyBoldTypeface
                    setTextColor(TEAL)
                    includeFontPadding = false
                    gravity = Gravity.CENTER
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = dp(8) },
            )
        }
        dnsPanel.addView(
            dnsPrivacyRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        dnsPrivacyEndpointInput = TextInputEditText(this).apply {
            setSingleLine(true)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            textDirection = View.TEXT_DIRECTION_LTR
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            background = null
            setPaddingRelative(dp(16), dp(12), dp(16), dp(12))
            textSize = 14f
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
            imeOptions = EditorInfo.IME_ACTION_DONE
            setTextColor(TEXT_PRIMARY)
            setHintTextColor(TEXT_SECONDARY)
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId != EditorInfo.IME_ACTION_DONE) return@setOnEditorActionListener false
                if (commitDnsPrivacyEndpoint(reconnectIfChanged = true, focusOnError = true)) {
                    clearFocus()
                }
                true
            }
            setOnFocusChangeListener { _, hasFocus ->
                if (hasFocus) {
                    scrollFieldIntoView(scrollView, dnsPrivacyEndpointLayout)
                } else {
                    commitDnsPrivacyEndpoint(reconnectIfChanged = true)
                }
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
                override fun afterTextChanged(s: Editable?) {
                    if (!dnsPrivacyInputUpdating && ::dnsPrivacyErrorText.isInitialized) {
                        dnsPrivacyErrorText.visibility = View.GONE
                    }
                }
            })
        }
        dnsPrivacyEndpointLayout = TextInputLayout(this).apply {
            hint = getString(R.string.dns_server_hint)
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            boxBackgroundColor = withAlpha(SURFACE, if (palette.isDark) 232 else 246)
            boxStrokeColor = TEAL
            boxStrokeWidth = dp(1)
            boxStrokeWidthFocused = dp(1)
            defaultHintTextColor = ColorStateList.valueOf(TEXT_SECONDARY)
            setHelperTextColor(ColorStateList.valueOf(TEXT_SECONDARY))
            setBoxCornerRadii(dp(8).toFloat(), dp(8).toFloat(), dp(8).toFloat(), dp(8).toFloat())
            addView(dnsPrivacyEndpointInput)
        }
        dnsPanel.addView(
            dnsPrivacyEndpointLayout,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginStart = dp(8)
                marginEnd = dp(8)
                topMargin = dp(12)
            },
        )
        dnsPrivacyErrorText = TextView(this).apply {
            textSize = 12f
            setTextColor(ERROR)
            includeFontPadding = true
            visibility = View.GONE
        }
        dnsPanel.addView(
            dnsPrivacyErrorText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                marginStart = dp(8)
                marginEnd = dp(8)
            },
        )
        advancedBody.addView(
            advancedSectionLabel(getString(R.string.lan_sharing_section)),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) },
        )
        val lanSharingPanel = advancedSettingsPanel()
        lanSharingCheckbox = MaterialSwitch(this).apply {
            contentDescription = getString(R.string.lan_sharing_title)
            setOnClickListener { saveLanSharingEnabled(isChecked) }
        }
        lanSharingPanel.addView(
            advancedToggleRow(
                title = getString(R.string.lan_sharing_title),
                detail = getString(R.string.lan_sharing_description),
                toggle = lanSharingCheckbox,
            ),
            LinearLayout.LayoutParams(-1, -2),
        )
        lanSharingPasswordCheckbox = MaterialSwitch(this).apply {
            contentDescription = getString(R.string.lan_sharing_require_password)
            setOnClickListener { saveLanSharingPasswordRequired(isChecked) }
        }
        lanSharingPanel.addView(
            advancedToggleRow(
                title = getString(R.string.lan_sharing_require_password),
                detail = getString(R.string.lan_sharing_require_password_description),
                toggle = lanSharingPasswordCheckbox,
            ),
            LinearLayout.LayoutParams(-1, -2),
        )
        lanSharingPanel.addView(
            advancedSectionDetail(getString(R.string.lan_sharing_manual_setup)),
            LinearLayout.LayoutParams(-1, -2).apply {
                marginStart = dp(16)
                marginEnd = dp(16)
                topMargin = dp(8)
            },
        )
        lanSharingDetailsText = TextView(this).apply {
            textSize = 13f
            typeface = WhiteDnsDataTypeface
            setTextColor(TEXT_PRIMARY)
            setTextIsSelectable(true)
            includeFontPadding = false
            setLineSpacing(dp(5).toFloat(), 1f)
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = RippleDrawable(
                ColorStateList.valueOf(withAlpha(TEAL, 28)),
                GradientDrawable().apply {
                    cornerRadius = dp(8).toFloat()
                    setColor(withAlpha(SURFACE, if (palette.isDark) 232 else 246))
                    setStroke(dp(1), withAlpha(OUTLINE, 170), dp(5).toFloat(), dp(4).toFloat())
                },
                null,
            )
            isClickable = true
            isFocusable = true
            setOnClickListener { copyLanSharingSettings() }
        }
        lanSharingPanel.addView(
            lanSharingDetailsText,
            LinearLayout.LayoutParams(-1, -2).apply {
                marginStart = dp(16)
                marginEnd = dp(16)
                topMargin = dp(12)
            },
        )
        lanSharingRegenerateButton = MaterialButton(this).apply {
            setText(R.string.lan_sharing_regenerate)
            setAllCaps(false)
            textSize = 12f
            typeface = WhiteDnsBodyBoldTypeface
            minWidth = 0
            minHeight = dp(44)
            insetTop = 0
            insetBottom = 0
            cornerRadius = dp(8)
            backgroundTintList = ColorStateList.valueOf(SURFACE)
            strokeWidth = dp(1)
            strokeColor = ColorStateList.valueOf(OUTLINE)
            setTextColor(TEAL)
            setOnClickListener { regenerateLanSharingPassword() }
        }
        lanSharingPanel.addView(
            lanSharingRegenerateButton,
            LinearLayout.LayoutParams(-1, dp(44)).apply {
                marginStart = dp(16)
                marginEnd = dp(16)
                topMargin = dp(12)
                bottomMargin = dp(16)
            },
        )
        advancedBody.addView(
            lanSharingPanel,
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) },
        )
        advancedBody.addView(
            advancedSectionLabel(getString(R.string.always_on_section)),
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(24) },
        )
        val alwaysOnPanel = advancedSettingsPanel()
        alwaysOnStatusText = TextView(this).apply {
            textSize = 14f
            typeface = WhiteDnsBodyBoldTypeface
            includeFontPadding = false
        }
        alwaysOnPanel.addView(
            alwaysOnStatusText,
            LinearLayout.LayoutParams(-1, -2).apply {
                marginStart = dp(16)
                marginEnd = dp(16)
                topMargin = dp(16)
            },
        )
        alwaysOnPanel.addView(
            advancedSectionDetail(getString(R.string.always_on_description)),
            LinearLayout.LayoutParams(-1, -2).apply {
                marginStart = dp(16)
                marginEnd = dp(16)
                topMargin = dp(6)
            },
        )
        alwaysOnPanel.addView(
            MaterialButton(this).apply {
                setText(R.string.always_on_open_settings)
                setAllCaps(false)
                textSize = 12f
                typeface = WhiteDnsBodyBoldTypeface
                minHeight = dp(44)
                insetTop = 0
                insetBottom = 0
                cornerRadius = dp(8)
                backgroundTintList = ColorStateList.valueOf(SURFACE)
                strokeWidth = dp(1)
                strokeColor = ColorStateList.valueOf(TEAL)
                rippleColor = ColorStateList.valueOf(withAlpha(TEAL, 24))
                setTextColor(TEAL)
                setOnClickListener {
                    startActivity(Intent(Settings.ACTION_VPN_SETTINGS))
                }
            },
            LinearLayout.LayoutParams(-2, dp(44)).apply {
                gravity = Gravity.END
                marginStart = dp(16)
                marginEnd = dp(16)
                topMargin = dp(14)
                bottomMargin = dp(16)
            },
        )
        advancedBody.addView(
            alwaysOnPanel,
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) },
        )
        renderAlwaysOnStatus()

        scrollView.addView(
            advancedBody,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        renderAdvancedControls()
        return scrollView
    }

    private fun scrollFieldIntoView(
        scrollView: ScrollView,
        field: View,
        delayMs: Long = KEYBOARD_SCROLL_DELAY_MS,
    ) {
        scrollView.postDelayed(
            {
                val scrollLocation = IntArray(2)
                val fieldLocation = IntArray(2)
                scrollView.getLocationInWindow(scrollLocation)
                field.getLocationInWindow(fieldLocation)
                val visibleBottom = scrollLocation[1] + scrollView.height - scrollView.paddingBottom - dp(16)
                val overlap = fieldLocation[1] + field.height - visibleBottom
                if (overlap > 0) scrollView.smoothScrollBy(0, overlap)
            },
            delayMs,
        )
    }

    private fun advancedSectionLabel(value: String): TextView {
        return TextView(this).apply {
            text = value
            textSize = 12f
            letterSpacing = 0f
            typeface = WhiteDnsBodyBoldTypeface
            setTextColor(TEXT_SECONDARY)
            includeFontPadding = false
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            gravity = Gravity.START
        }
    }

    private fun advancedSectionDetail(value: String): TextView {
        return TextView(this).apply {
            text = value
            textSize = 12f
            typeface = WhiteDnsBodyTypeface
            setTextColor(TEXT_SECONDARY)
            includeFontPadding = false
            setLineSpacing(dp(2).toFloat(), 1f)
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            gravity = Gravity.START
        }
    }

    private fun advancedSettingsPanel(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            background = glassSurfaceDrawable(radiusDp = 12)
            clipToOutline = true
            elevation = 0f
            setPadding(dp(4), dp(4), dp(4), dp(12))
        }
    }

    private fun advancedToggleRow(
        title: String,
        detail: String,
        toggle: MaterialSwitch,
    ): View {
        val toggleStates = arrayOf(
            intArrayOf(android.R.attr.state_enabled, android.R.attr.state_checked),
            intArrayOf(android.R.attr.state_enabled, -android.R.attr.state_checked),
            intArrayOf(-android.R.attr.state_enabled, android.R.attr.state_checked),
            intArrayOf(-android.R.attr.state_enabled, -android.R.attr.state_checked),
        )
        toggle.thumbTintList = ColorStateList(
            toggleStates,
            intArrayOf(SURFACE, SURFACE, withAlpha(SURFACE, 140), withAlpha(SURFACE, 140)),
        )
        toggle.trackTintList = ColorStateList(
            toggleStates,
            intArrayOf(
                TEAL,
                withAlpha(TEXT_SECONDARY, 190),
                withAlpha(TEAL, 80),
                withAlpha(TEXT_SECONDARY, 80),
            ),
        )
        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            addView(
                TextView(this@MainActivity).apply {
                    text = title
                    textSize = 14f
                    typeface = WhiteDnsBodyBoldTypeface
                    setTextColor(TEXT_PRIMARY)
                    includeFontPadding = false
                },
            )
            addView(
                TextView(this@MainActivity).apply {
                    text = detail
                    textSize = 12f
                    typeface = WhiteDnsBodyTypeface
                    setTextColor(TEXT_SECONDARY)
                    includeFontPadding = false
                    maxLines = 2
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = dp(4)
                },
            )
        }
        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(64)
            setPadding(dp(12), dp(12), dp(16), dp(12))
            setSelectableBackground()
            isClickable = true
            isFocusable = true
            contentDescription = "$title. $detail"
            setOnClickListener {
                if (toggle.isEnabled) toggle.performClick()
            }
            addView(
                textColumn,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
            )
            addView(
                toggle,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = dp(12) },
            )
        }
    }


    private fun showPrivacyPolicyIfNeeded(onAccepted: (() -> Unit)? = null): Boolean {
        if (privacyPolicyStore.isAccepted()) return false
        showPrivacyPolicyDialog(onAccepted)
        return true
    }

    private fun showPrivacyPolicyDialog(onAccepted: (() -> Unit)? = null) {
        if (privacyPolicyDialog?.isShowing == true) return

        val messageText = TextView(this).apply {
            text = getString(R.string.privacy_policy_message)
            textSize = 14f
            setTextColor(TEXT_PRIMARY)
            includeFontPadding = true
            setLineSpacing(dp(2).toFloat(), 1.0f)
        }
        val checkbox = CheckBox(this).apply {
            text = getString(R.string.privacy_policy_checkbox)
            textSize = 14f
            setTextColor(TEXT_PRIMARY)
        }
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), 0)
            addView(
                messageText,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                checkbox,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = dp(12)
                },
            )
        }
        val scrollView = ScrollView(this).apply {
            addView(
                content,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.privacy_policy_title)
            .setView(scrollView)
            .setNegativeButton(R.string.privacy_policy_not_now, null)
            .setPositiveButton(R.string.privacy_policy_accept, null)
            .create()

        dialog.setOnDismissListener {
            if (privacyPolicyDialog === dialog) {
                privacyPolicyDialog = null
            }
        }
        privacyPolicyDialog = dialog
        dialog.showWhiteDnsDialog {
            val acceptButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            acceptButton.isEnabled = false
            checkbox.setOnCheckedChangeListener { _, isChecked ->
                acceptButton.isEnabled = isChecked
            }
            acceptButton.setOnClickListener {
                privacyPolicyStore.acceptCurrentVersion()
                DiagnosticLogger.info(
                    this@MainActivity,
                    "privacy.accepted",
                    "version=${PrivacyPolicyAcceptancePolicy.CURRENT_VERSION}",
                )
                dialog.dismiss()
                onAccepted?.invoke()
            }
        }
    }

    private fun handleStateChanged(context: Context, intent: Intent) {
        val previousState = buttonModel.state
        val state = VpnState.fromWireName(
            intent.getStringExtra(Actions.EXTRA_STATE),
            intent.getStringExtra(Actions.EXTRA_ERROR),
        )
        VpnAnalyticsEventPolicy.forStatePublished(previousState, state)?.let {
            AnalyticsEvents.log(this, it)
        }
        if (previousState == VpnState.Started && state == VpnState.Stopping) {
            disconnectAnalyticsPending = true
        }
        if (state == VpnState.Stopped) {
            VpnAnalyticsEventPolicy.forDisconnectFinished(disconnectAnalyticsPending)?.let {
                AnalyticsEvents.log(this, it)
            }
            disconnectAnalyticsPending = false
        } else if (state == VpnState.Started || state is VpnState.Error) {
            disconnectAnalyticsPending = false
        }
        DiagnosticLogger.info(
            context,
            "activity.stateChanged",
            "state=${state.wireName}" + if (state is VpnState.Error) " message=${state.message}" else "",
        )
        if (state != VpnState.Starting) {
            connectFlowPending = false
        }
        if (state == VpnState.Started && previousState != VpnState.Started) {
            refreshLocationOptions()
        }
        if (state == VpnState.Starting || state == VpnState.Stopping) {
            connectionSelectorDialog?.dismiss()
        }
        applyRuntimeState(
            state,
            intent.getLongExtra(Actions.EXTRA_SESSION_STARTED_AT_ELAPSED_MS, 0L),
            intent.getStringExtra(Actions.EXTRA_CONNECTION_COUNTRY_FLAG).orEmpty(),
            intent.getStringExtra(Actions.EXTRA_DEBUG_FRONTING_IP).orEmpty(),
            intent.getStringExtra(Actions.EXTRA_CONNECTION_DETAILS).orEmpty(),
            intent.getStringExtra(Actions.EXTRA_ACTIVE_SUBSCRIPTION_ID).orEmpty(),
            intent.getStringExtra(Actions.EXTRA_ACTIVE_CONNECTION_TAG).orEmpty(),
            intent.getStringExtra(Actions.EXTRA_ACTIVE_CONNECTION_FINGERPRINT).orEmpty(),
            intent.getBooleanExtra(Actions.EXTRA_LIVE_SELECTOR_READY, false),
            intent.getStringArrayListExtra(Actions.EXTRA_SELECTABLE_CONNECTION_FINGERPRINTS)
                .orEmpty()
                .toSet(),
            intent.getBooleanExtra(Actions.EXTRA_ALWAYS_ON, false),
            intent.getBooleanExtra(Actions.EXTRA_LOCKDOWN, false),
        )
        intent.getStringExtra(Actions.EXTRA_NOTICE)
            ?.takeIf(String::isNotBlank)
            ?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
    }

    private fun applyRuntimeState(
        state: VpnState,
        startedAt: Long,
        countryFlag: String = "",
        frontingIp: String = "",
        details: String = "",
        runtimeSubscriptionId: String = "",
        runtimeConnectionTag: String = "",
        runtimeConnectionFingerprint: String = "",
        selectorReady: Boolean = false,
        selectableConnectionFingerprints: Set<String> = emptySet(),
        alwaysOn: Boolean = false,
        lockdown: Boolean = false,
    ) {
        alwaysOnMode = alwaysOn
        lockdownMode = lockdown
        buttonModel.onStateChanged(state, alwaysOn)
        if (state == VpnState.Started) {
            activeRuntimeSubscriptionId = runtimeSubscriptionId
            activeConnectionTag = runtimeConnectionTag
            activeConnectionFingerprint = runtimeConnectionFingerprint
            liveSelectorReady = selectorReady
            liveSelectableConnectionFingerprints = selectableConnectionFingerprints
            connectionCountryFlag = countryFlag
            debugFrontingIp = frontingIp
            connectionDetails = details
            sessionStartedAtElapsedMs = if (startedAt > 0L) startedAt else SystemClock.elapsedRealtime()
            startTimerUpdates()
            // Enable arrow animations when connected
            if (::downloadArrowIcon.isInitialized) downloadArrowIcon.isAnimating = true
            if (::uploadArrowIcon.isInitialized) uploadArrowIcon.isAnimating = true
        } else if (state == VpnState.Stopped || state == VpnState.DailyLimitReached || state is VpnState.Error) {
            activeRuntimeSubscriptionId = ""
            activeConnectionTag = ""
            activeConnectionFingerprint = ""
            liveSelectorReady = false
            liveSelectableConnectionFingerprints = emptySet()
            connectionCountryFlag = ""
            debugFrontingIp = ""
            connectionDetails = ""
            sessionStartedAtElapsedMs = 0L
            mainHandler.removeCallbacks(timerRunnable)
            resetTransferSpeeds()
            // Disable arrow animations when disconnected
            if (::downloadArrowIcon.isInitialized) downloadArrowIcon.isAnimating = false
            if (::uploadArrowIcon.isInitialized) uploadArrowIcon.isAnimating = false
        }
        renderAlwaysOnStatus()
        renderState(state)
        renderConnectionSelection()
    }

    private fun handleButtonClick() {
        val nextAction = buttonModel.nextAction() ?: return
        DiagnosticLogger.info(
            this,
            "button.click",
            "currentState=${buttonModel.state.wireName} nextAction=$nextAction",
        )
        when (nextAction) {
            Actions.CONNECT -> {
                if (!commitFrontingIpInput(reconnectIfChanged = false, focusOnError = true)) return
                if (!commitDnsPrivacyEndpoint(reconnectIfChanged = false, focusOnError = true)) return
                if (showPrivacyPolicyIfNeeded { beginConnectFlow() }) return
                beginConnectFlow()
            }
            Actions.DISCONNECT -> {
                connectFlowPending = false
                buttonModel.onStateChanged(VpnState.Stopping)
                renderState(VpnState.Stopping)
                startVpnService(Actions.DISCONNECT)
            }
        }
    }

    private fun handleRefreshClick() {
        if (buttonModel.state != VpnState.Started) return
        if (!commitFrontingIpInput(reconnectIfChanged = false, focusOnError = true)) return
        if (!commitDnsPrivacyEndpoint(reconnectIfChanged = false, focusOnError = true)) return
        DiagnosticLogger.info(this, "button.refresh", "currentState=${buttonModel.state.wireName}")
        connectFlowPending = false
        buttonModel.onStateChanged(VpnState.Starting)
        renderState(VpnState.Starting)
        startVpnService(Actions.REFRESH)
    }

    private fun refreshLocationOptions() {
        renderLocationSelection()
        renderConnectionSelection()
        activityScope.launch {
            val cachedCatalog = withContext(Dispatchers.IO) {
                val store = SubscriptionStore(this@MainActivity)
                val selectedId = store.readSelectedSubscriptionId()
                if (selectedId == SubscriptionStore.DEFAULT_SUBSCRIPTION_ID) {
                    store.readCatalog()
                } else {
                    userSubscriptionManager.cachedSnapshot(selectedId)?.catalog
                }
            }
            if (cachedCatalog != null) {
                updateLocationOptions(cachedCatalog.profiles, resetMissingSelection = true)
            }

            val fetchedCatalog = runCatching {
                ConfigRepository(this@MainActivity).fetchOrCachedCatalog()
            }.onFailure { error ->
                DiagnosticLogger.warn(this@MainActivity, "activity.location.fetch.failed", error = error)
            }.getOrNull()

            if (fetchedCatalog != null) {
                updateLocationOptions(fetchedCatalog.profiles, resetMissingSelection = true)
            }
        }
    }

    private fun updateLocationOptions(
        profiles: List<ConnectionProfile>,
        resetMissingSelection: Boolean,
    ) {
        connectionProfiles = profiles
        val subscriptionStore = SubscriptionStore(this)
        connectionDelayRecords = subscriptionStore
            .readConnectionDelayRecords(
                subscriptionId = subscriptionStore.readSelectedSubscriptionId(),
                profiles = profiles,
            )
            .associateBy(ConnectionDelayRecord::fingerprint)
        val options = ConnectionLocationPolicy.selectorOptions(
            profiles = profiles,
            automaticLabel = getString(R.string.option_automatic),
            displayLocale = resources.configuration.locales[0],
        )
        val selectedCountryCode = locationPreferenceStore.readSelectedCountryCode()
        if (
            resetMissingSelection &&
            selectedCountryCode != null &&
            options.none { it.countryCode == selectedCountryCode }
        ) {
            locationPreferenceStore.clearSelectedCountry()
            DiagnosticLogger.info(
                this,
                "activity.location.reset",
                "missingCountry=$selectedCountryCode profiles=${profiles.size}",
            )
        }
        locationOptions = options
        DiagnosticLogger.info(
            this,
            "activity.location.options",
            "countries=${(options.size - 1).coerceAtLeast(0)} profiles=${profiles.size}",
        )
        renderLocationSelection()
        renderConnectionSelection()
    }

    private fun showConnectionSelector() {
        if (buttonModel.state == VpnState.Starting || buttonModel.state == VpnState.Stopping) return
        val subscriptionStore = SubscriptionStore(this)
        val selectedSubscriptionId = subscriptionStore.readSelectedSubscriptionId()
        val usesActiveRuntime = buttonModel.state == VpnState.Started &&
            activeRuntimeSubscriptionId == selectedSubscriptionId
        if (usesActiveRuntime && !liveSelectorReady) {
            Toast.makeText(this, R.string.connection_selector_loading, Toast.LENGTH_SHORT).show()
            return
        }
        val selectorProfiles = if (usesActiveRuntime) {
            connectionProfiles.filter { it.fingerprint in liveSelectableConnectionFingerprints }
        } else {
            connectionProfiles
        }
        connectionDelayRecords = subscriptionStore
            .readConnectionDelayRecords(selectedSubscriptionId, selectorProfiles)
            .associateBy(ConnectionDelayRecord::fingerprint)
        val selectedProfile = connectionSelectionPreferenceStore.readSelectedProfile(
            selectedSubscriptionId,
            selectorProfiles,
        )
        val displayLocale = resources.configuration.locales[0]
        val allCountriesLabel = getString(R.string.connection_filter_all_countries)
        val countryOptions = ConnectionLocationPolicy.selectorOptions(
            profiles = selectorProfiles,
            automaticLabel = allCountriesLabel,
            displayLocale = displayLocale,
        )
        val availableTypes = ConnectionTypeSelectionPolicy.availableTypes(selectorProfiles)
        val restoredSession = ConnectionDelayTestState.snapshot(selectedSubscriptionId)
        val profilesByFingerprint = selectorProfiles.associateBy(ConnectionProfile::fingerprint)
        var selectedCountryCode = restoredSession
            ?.takeIf(ConnectionDelayTestSession::isRunning)
            ?.targetFingerprints
            ?.map { fingerprint ->
                profilesByFingerprint[fingerprint]
                    ?.let { ConnectionLocationPolicy.countryForProfile(it, displayLocale) }
                    ?.code
            }
            ?.distinct()
            ?.singleOrNull()
        val selectedTypes = restoredSession
            ?.takeIf(ConnectionDelayTestSession::isRunning)
            ?.connectionTypes
            .orEmpty()
            .ifEmpty {
                connectionSelectionPreferenceStore
                    .readAutomaticTypes(selectedSubscriptionId, selectorProfiles)
                    .ifEmpty { availableTypes.toSet() }
            }
            .toMutableSet()
        val testingFingerprints = if (restoredSession?.isRunning == true) {
            (restoredSession.targetFingerprints - restoredSession.finishedFingerprints).toMutableSet()
        } else {
            mutableSetOf<String>()
        }
        val speedTestingFingerprints = restoredSession
            ?.takeIf { it.isRunning && it.speedTestEnabled }
            ?.let { session ->
                session.finishedFingerprints.filterTo(mutableSetOf()) { fingerprint ->
                    fingerprint !in session.speedFinishedFingerprints &&
                        connectionDelayRecords[fingerprint]?.status == ConnectionDelayStatus.Success
                }
            }
            ?: mutableSetOf()
        var speedTestEnabled = restoredSession?.speedTestEnabled
            ?: connectionDelayRecords.values.any { it.speedKbps != null }
        fun selectedTypesLabel(): String = when {
            selectedTypes.size == availableTypes.size -> getString(R.string.connection_filter_all_types)
            selectedTypes.size == 1 -> selectedTypes.first().uppercase(Locale.US)
            else -> getString(R.string.connection_filter_types_count, selectedTypes.size)
        }
        fun selectedCountryLabel(): String = countryOptions
            .firstOrNull { it.countryCode == selectedCountryCode }
            ?.label
            ?: allCountriesLabel
        fun visibleProfiles(): List<ConnectionProfile> {
            val matchingCountry = ConnectionLocationPolicy.filterProfiles(
                profiles = selectorProfiles,
                selectedCountryCode = selectedCountryCode,
                automaticLabel = allCountriesLabel,
                displayLocale = displayLocale,
            ).profiles
            val matchingType = ConnectionTypeSelectionPolicy.filterProfiles(
                matchingCountry,
                selectedTypes,
            )
            return ConnectionTestResultOrder.order(
                profiles = matchingType,
                records = connectionDelayRecords,
                speedTestEnabled = speedTestEnabled,
                pendingFingerprints = testingFingerprints + speedTestingFingerprints,
            )
        }
        var filteredProfiles = visibleProfiles()
        var testRunning = restoredSession?.isRunning == true
        var testPaused = restoredSession?.paused == true
        var lastTestCompleted = restoredSession?.completed ?: 0
        var lastTestTotal = restoredSession?.total ?: 0
        var lastTestAvailable = restoredSession?.available ?: 0
        var lastSpeedCompleted = restoredSession?.speedCompleted ?: 0
        var lastSpeedTotal = restoredSession?.speedTotal ?: 0
        var lastTestStatus = restoredSession?.status
        var lastTestError = restoredSession?.error.orEmpty()
        var delayTestId: String? = restoredSession?.testId
        lateinit var dialog: AlertDialog

        data class ConnectionRowHolder(
            val container: LinearLayout,
            val title: TextView,
            val detail: TextView,
            val delayBadge: TextView,
            val checkIcon: View,
        )

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(8), dp(16), 0)
        }

        // Filter row - horizontal with chips
        val filterRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        fun filterChip(text: String) = TextView(this).apply {
            this.text = text
            textSize = 13f
            typeface = WhiteDnsBodyTypeface
            setTextColor(TEXT_PRIMARY)
            includeFontPadding = false
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat()
                setStroke(dp(1), OUTLINE)
                setColor(SURFACE)
            }
        }

        val countryFilterButton = filterChip(allCountriesLabel)
        val typeFilterButton = filterChip(getString(R.string.connection_filter_all_types))

        filterRow.addView(countryFilterButton, LinearLayout.LayoutParams(-2, -2))
        filterRow.addView(typeFilterButton, LinearLayout.LayoutParams(-2, -2).apply { marginStart = dp(8) })

        // Progress section
        val progressSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            isIndeterminate = false
            max = 100
            progress = 0
            progressTintList = ColorStateList.valueOf(TEAL)
            progressBackgroundTintList = ColorStateList.valueOf(OUTLINE)
            minimumHeight = dp(4)
        }
        val testStatus = TextView(this).apply {
            textSize = 13f
            typeface = WhiteDnsBodyTypeface
            setTextColor(TEXT_SECONDARY)
            includeFontPadding = false
            gravity = Gravity.CENTER
        }
        progressSection.addView(progressBar, LinearLayout.LayoutParams(-1, dp(4)))
        progressSection.addView(testStatus, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(6) })

        // Control buttons
        val testButton = MaterialButton(this).apply {
            setText(R.string.connection_test_visible)
            setIconResource(R.drawable.ic_connection_test)
            iconTint = ColorStateList.valueOf(BACKGROUND)
            iconSize = dp(18)
            iconPadding = dp(8)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            textSize = 14f
            typeface = WhiteDnsBodyBoldTypeface
            minWidth = 0
            minimumWidth = 0
            minHeight = dp(44)
            minimumHeight = dp(44)
            insetTop = 0
            insetBottom = 0
            cornerRadius = dp(22)
            backgroundTintList = ColorStateList.valueOf(TEAL)
            setTextColor(BACKGROUND)
            setAllCaps(false)
        }

        val speedTestToggle = MaterialSwitch(this).apply {
            setText(R.string.connection_speed_test_toggle)
            textSize = 12f
            typeface = WhiteDnsBodyTypeface
            setTextColor(TEXT_SECONDARY)
            minHeight = dp(40)
            gravity = Gravity.CENTER_VERTICAL
            isChecked = speedTestEnabled
        }

        val controlRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val pauseButton = MaterialButton(this).apply {
            setIconResource(R.drawable.ic_pause)
            iconTint = ColorStateList.valueOf(TEAL)
            iconSize = dp(20)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            iconPadding = 0
            text = ""
            minWidth = dp(44)
            minimumWidth = dp(44)
            minHeight = dp(44)
            minimumHeight = dp(44)
            insetTop = 0
            insetBottom = 0
            cornerRadius = dp(22)
            strokeWidth = dp(1)
            strokeColor = ColorStateList.valueOf(OUTLINE)
            backgroundTintList = ColorStateList.valueOf(SURFACE)
            visibility = View.GONE
            setPadding(0, 0, 0, 0)
        }

        val stopButton = MaterialButton(this).apply {
            setText(R.string.connection_test_stop)
            textSize = 13f
            typeface = WhiteDnsBodyBoldTypeface
            minWidth = 0
            minimumWidth = 0
            minHeight = dp(44)
            minimumHeight = dp(44)
            insetTop = 0
            insetBottom = 0
            cornerRadius = dp(22)
            strokeWidth = dp(1)
            strokeColor = ColorStateList.valueOf(ERROR)
            backgroundTintList = ColorStateList.valueOf(SURFACE)
            setTextColor(ERROR)
            setAllCaps(false)
            visibility = View.GONE
        }

        stopButton.setPadding(dp(20), 0, dp(20), 0)
        controlRow.addView(testButton, LinearLayout.LayoutParams(0, dp(44), 1f))
        controlRow.addView(pauseButton, LinearLayout.LayoutParams(dp(44), dp(44)).apply { marginStart = dp(8) })
        controlRow.addView(stopButton, LinearLayout.LayoutParams(-2, dp(44)).apply { marginStart = dp(8) })

        // Server list with better styling
        val list = ListView(this).apply {
            divider = ColorDrawable(Color.TRANSPARENT)
            dividerHeight = dp(8)
            isVerticalScrollBarEnabled = true
            clipToPadding = false
            setPadding(0, dp(4), 0, dp(4))
        }

        val adapter = object : BaseAdapter() {
            override fun getCount(): Int = filteredProfiles.size + 1

            override fun getItem(position: Int): Any? =
                if (position == 0) null else filteredProfiles[position - 1]

            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val row: LinearLayout
                val holder: ConnectionRowHolder
                if (convertView == null) {
                    val container = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                        gravity = Gravity.CENTER_VERTICAL
                    }
                    val title = TextView(this@MainActivity).apply {
                        textSize = 14f
                        typeface = WhiteDnsBodyBoldTypeface
                        setTextColor(TEXT_PRIMARY)
                        includeFontPadding = false
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    }
                    val detail = TextView(this@MainActivity).apply {
                        textSize = 12f
                        typeface = WhiteDnsDataTypeface
                        setTextColor(TEXT_SECONDARY)
                        includeFontPadding = false
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                    }
                    val delayBadge = TextView(this@MainActivity).apply {
                        textSize = 11f
                        typeface = WhiteDnsDataTypeface
                        includeFontPadding = false
                        gravity = Gravity.CENTER
                        setPadding(dp(10), dp(6), dp(10), dp(6))
                        minWidth = dp(70)
                    }
                    // Selection indicator (small dot)
                    val checkIcon = View(this@MainActivity).apply {
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.OVAL
                            setColor(TEAL)
                        }
                        visibility = View.INVISIBLE
                    }
                    val textColumn = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                        addView(title, LinearLayout.LayoutParams(-1, -2))
                        addView(detail, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(3) })
                    }
                    container.addView(checkIcon, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(10) })
                    container.addView(textColumn, LinearLayout.LayoutParams(0, -2, 1f))
                    container.addView(delayBadge, LinearLayout.LayoutParams(dp(108), -2).apply { marginStart = dp(8) })

                    row = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(12), dp(10), dp(12), dp(10))
                        addView(container, LinearLayout.LayoutParams(-1, -2))
                    }
                    // Card-like background
                    row.background = GradientDrawable().apply {
                        cornerRadius = dp(12).toFloat()
                        setColor(SURFACE)
                    }
                    holder = ConnectionRowHolder(container, title, detail, delayBadge, checkIcon)
                    row.tag = holder
                } else {
                    row = convertView as LinearLayout
                    holder = row.tag as ConnectionRowHolder
                }

                val profile = getItem(position) as? ConnectionProfile
                val isSelected = if (profile == null) {
                    selectedProfile == null
                } else {
                    selectedProfile?.fingerprint == profile.fingerprint
                }

                // Update selection state
                holder.checkIcon.visibility = if (isSelected) View.VISIBLE else View.INVISIBLE
                (row.background as? GradientDrawable)?.setColor(
                    if (isSelected) ((TEAL and 0x00FFFFFF) or (0x18 shl 24)) else SURFACE
                )
                (row.background as? GradientDrawable)?.setStroke(
                    dp(1),
                    if (isSelected) TEAL else OUTLINE
                )

                if (profile == null) {
                    holder.title.setText(R.string.option_automatic)
                    val automaticDetail = if (selectedTypes.size == availableTypes.size) {
                        getString(R.string.connection_automatic_detail)
                    } else {
                        getString(R.string.connection_automatic_types_detail, selectedTypesLabel())
                    }
                    holder.detail.text = activeConnectionTag
                        .takeIf { usesActiveRuntime && it.isNotBlank() }
                        ?.let { getString(R.string.connection_automatic_active_detail, automaticDetail, it) }
                        ?: automaticDetail
                    holder.delayBadge.text = ""
                    holder.delayBadge.background = null
                } else {
                    holder.title.text = profile.tag
                    holder.detail.text = if (
                        selectedSubscriptionId == SubscriptionStore.DEFAULT_SUBSCRIPTION_ID
                    ) {
                        profile.type.uppercase(Locale.US)
                    } else {
                        getString(
                            R.string.connection_detail,
                            profile.type.uppercase(Locale.US),
                            profile.server,
                            profile.port,
                        )
                    }
                    val delayRecord = connectionDelayRecords[profile.fingerprint]
                    val delayMs: Int? = if (delayRecord?.status == ConnectionDelayStatus.Success) {
                        delayRecord.delayMs
                    } else {
                        null
                    }
                    val speedKbps = delayRecord?.speedKbps
                    val isTesting = profile.fingerprint in testingFingerprints
                    val isSpeedTesting = profile.fingerprint in speedTestingFingerprints

                    holder.delayBadge.text = when {
                        isTesting -> getString(R.string.connection_delay_testing)
                        isSpeedTesting && delayMs != null -> getString(R.string.connection_speed_testing, delayMs)
                        speedTestEnabled && speedKbps != null && delayMs != null -> getString(
                            R.string.connection_speed_delay_value,
                            speedKbps / 1_000.0,
                            delayMs,
                        )
                        delayMs != null -> getString(R.string.connection_delay_value, delayMs)
                        delayRecord?.status == ConnectionDelayStatus.Failure ->
                            getString(R.string.connection_delay_unavailable)
                        else -> ""
                    }

                    // Badge background based on state
                    val badgeColor = when {
                        isTesting || isSpeedTesting -> AMBER
                        delayMs != null -> TEAL
                        delayRecord?.status == ConnectionDelayStatus.Failure -> ERROR
                        else -> TEXT_SECONDARY
                    }
                    if (holder.delayBadge.text.isNotEmpty()) {
                        holder.delayBadge.setTextColor(badgeColor)
                        holder.delayBadge.background = GradientDrawable().apply {
                            cornerRadius = dp(12).toFloat()
                            setColor((badgeColor and 0x00FFFFFF) or (0x20 shl 24))
                        }
                    } else {
                        holder.delayBadge.background = null
                    }
                }

                row.setOnClickListener {
                    handleConnectionSelected(profile, selectedTypes)
                    dialog.dismiss()
                }
                return row
            }
        }
        list.adapter = adapter

        fun updateTestControls() {
            // Test button state
            testButton.isEnabled = filteredProfiles.isNotEmpty() && (!testRunning || testPaused)
            testButton.alpha = if (testButton.isEnabled) 1f else 0.5f
            val hasResults = filteredProfiles.any { it.fingerprint in connectionDelayRecords }
            testButton.setText(
                if (testPaused || hasResults) R.string.connection_test_again else R.string.connection_test_visible
            )
            // Show/hide test button based on state
            testButton.visibility = if (testRunning && !testPaused) View.GONE else View.VISIBLE

            // Pause button
            pauseButton.visibility = if (testRunning) View.VISIBLE else View.GONE
            pauseButton.setIconResource(if (testPaused) R.drawable.ic_play else R.drawable.ic_pause)

            // Stop button
            stopButton.visibility = if (testRunning) View.VISIBLE else View.GONE

            // Filter chips
            typeFilterButton.isEnabled = !testRunning && availableTypes.isNotEmpty()
            typeFilterButton.alpha = if (typeFilterButton.isEnabled) 1f else 0.5f
            typeFilterButton.text = selectedTypesLabel()
            (typeFilterButton.background as? GradientDrawable)?.setStroke(
                dp(1), if (selectedTypes.size < availableTypes.size) TEAL else OUTLINE
            )

            countryFilterButton.isEnabled = !testRunning && countryOptions.size > 1
            countryFilterButton.alpha = if (countryFilterButton.isEnabled) 1f else 0.5f
            countryFilterButton.text = selectedCountryLabel()
            (countryFilterButton.background as? GradientDrawable)?.setStroke(
                dp(1), if (selectedCountryCode != null) TEAL else OUTLINE
            )

            // Speed test toggle
            speedTestToggle.isEnabled = !testRunning
            speedTestToggle.alpha = if (speedTestToggle.isEnabled) 1f else 0.5f

            // Progress section
            val failed = lastTestStatus == Actions.DELAY_TEST_FAILED
            val speedPhase = testRunning && lastSpeedTotal > 0 && lastTestCompleted >= lastTestTotal

            progressSection.visibility = if (testRunning || lastTestStatus != null) View.VISIBLE else View.GONE

            // Update progress bar
            val progressPercent = when {
                speedPhase && lastSpeedTotal > 0 -> (lastSpeedCompleted * 100) / lastSpeedTotal
                lastTestTotal > 0 -> (lastTestCompleted * 100) / lastTestTotal
                else -> 0
            }
            progressBar.progress = progressPercent
            progressBar.progressTintList = ColorStateList.valueOf(
                when {
                    failed -> ERROR
                    testPaused -> AMBER
                    else -> TEAL
                }
            )

            // Status text
            testStatus.setTextColor(if (failed) ERROR else TEXT_SECONDARY)
            testStatus.text = when {
                selectorProfiles.isEmpty() -> getString(R.string.connection_empty)
                testRunning && lastTestTotal == 0 -> getString(R.string.connection_test_preparing)
                speedPhase && testPaused ->
                    getString(R.string.connection_speed_test_paused, lastSpeedCompleted, lastSpeedTotal)
                speedPhase ->
                    getString(R.string.connection_speed_test_progress, lastSpeedCompleted, lastSpeedTotal)
                testRunning && testPaused ->
                    getString(R.string.connection_test_paused, lastTestCompleted, lastTestTotal)
                testRunning ->
                    getString(R.string.connection_test_progress, lastTestCompleted, lastTestTotal)
                lastTestStatus == Actions.DELAY_TEST_COMPLETED ->
                    getString(R.string.connection_test_complete, lastTestAvailable, lastTestTotal)
                failed -> lastTestError.ifBlank { getString(R.string.connection_test_failed) }
                lastTestStatus == Actions.DELAY_TEST_CANCELED ->
                    getString(R.string.connection_test_canceled)
                else -> ""
            }
        }

        typeFilterButton.setOnClickListener {
            val draft = selectedTypes.toMutableSet()
            val choices = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(4), 0, dp(4), 0)
            }
            availableTypes.forEach { type ->
                choices.addView(
                    MaterialCheckBox(this).apply {
                        text = type.uppercase(Locale.US)
                        textSize = 14f
                        typeface = WhiteDnsBodyTypeface
                        setTextColor(TEXT_PRIMARY)
                        minHeight = dp(48)
                        gravity = Gravity.CENTER_VERTICAL
                        isUseMaterialThemeColors = false
                        buttonTintList = ColorStateList(
                            arrayOf(
                                intArrayOf(android.R.attr.state_checked),
                                intArrayOf(),
                            ),
                            intArrayOf(TEAL, TEXT_SECONDARY),
                        )
                        isChecked = type in draft
                        setOnCheckedChangeListener { button, isChecked ->
                            if (!isChecked && draft.size == 1) {
                                button.isChecked = true
                            } else if (isChecked) {
                                draft += type
                            } else {
                                draft -= type
                            }
                        }
                    },
                    LinearLayout.LayoutParams(-1, dp(48)),
                )
            }
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.connection_filter_types_title)
                .setView(ScrollView(this).apply { addView(choices) })
                .setNegativeButton(R.string.split_tunnel_cancel, null)
                .setPositiveButton(R.string.split_tunnel_save) { _, _ ->
                    selectedTypes.clear()
                    selectedTypes += draft
                    filteredProfiles = visibleProfiles()
                    adapter.notifyDataSetChanged()
                    updateTestControls()
                }
                .create()
                .showWhiteDnsDialog()
        }

        countryFilterButton.setOnClickListener {
            val labels = countryOptions.map(LocationSelectorOption::label).toTypedArray()
            val selectedIndex = countryOptions.indexOfFirst { it.countryCode == selectedCountryCode }
            MaterialAlertDialogBuilder(this)
                .setTitle(R.string.connection_filter_country_title)
                .setSingleChoiceItems(labels, selectedIndex) { choiceDialog, which ->
                    selectedCountryCode = countryOptions[which].countryCode
                    filteredProfiles = visibleProfiles()
                    adapter.notifyDataSetChanged()
                    updateTestControls()
                    choiceDialog.dismiss()
                }
                .setNegativeButton(R.string.split_tunnel_cancel, null)
                .create()
                .showWhiteDnsDialog()
        }

        speedTestToggle.setOnCheckedChangeListener { _, isChecked ->
            speedTestEnabled = isChecked
            filteredProfiles = visibleProfiles()
            adapter.notifyDataSetChanged()
            updateTestControls()
        }

        testButton.setOnClickListener {
            if (testRunning && !testPaused) return@setOnClickListener
            val targets = filteredProfiles.toList()
            if (targets.isEmpty()) return@setOnClickListener
            testRunning = true
            testPaused = false
            lastTestCompleted = 0
            lastTestTotal = targets.size
            lastTestAvailable = 0
            lastSpeedCompleted = 0
            lastSpeedTotal = 0
            lastTestStatus = Actions.DELAY_TEST_PREPARING
            lastTestError = ""
            delayTestId = SystemClock.elapsedRealtimeNanos().toString()
            val targetFingerprints = targets.map { it.fingerprint }.toSet()
            connectionDelayRecords = connectionDelayRecords.filterKeys { it !in targetFingerprints }
            testingFingerprints.clear()
            testingFingerprints += targetFingerprints
            speedTestingFingerprints.clear()
            ConnectionDelayTestState.replace(
                ConnectionDelayTestSession(
                    testId = delayTestId.orEmpty(),
                    subscriptionId = selectedSubscriptionId,
                    connectionTypes = selectedTypes,
                    targetFingerprints = targets.map(ConnectionProfile::fingerprint),
                    status = Actions.DELAY_TEST_PREPARING,
                    total = targets.size,
                    speedTestEnabled = speedTestEnabled,
                ),
            )
            filteredProfiles = visibleProfiles()
            adapter.notifyDataSetChanged()
            updateTestControls()
            startService(
                Intent(this, WhiteDnsVpnService::class.java)
                    .setAction(Actions.TEST_CONNECTION_DELAYS)
                    .putExtra(Actions.EXTRA_APP_INITIATED, true)
                    .putExtra(Actions.EXTRA_DELAY_TEST_ID, delayTestId)
                    .putExtra(Actions.EXTRA_SPEED_TEST_ENABLED, speedTestEnabled)
                    .putStringArrayListExtra(
                        Actions.EXTRA_CONNECTION_TYPES,
                        ArrayList(selectedTypes.sorted()),
                    )
                    .putStringArrayListExtra(
                        Actions.EXTRA_CONNECTION_FINGERPRINTS,
                        ArrayList(targets.map(ConnectionProfile::fingerprint)),
                    ),
            )
        }

        pauseButton.setOnClickListener {
            val currentTestId = delayTestId ?: return@setOnClickListener
            if (!testRunning) return@setOnClickListener
            testPaused = !testPaused
            updateTestControls()
            startService(
                Intent(this, WhiteDnsVpnService::class.java)
                    .setAction(
                        if (testPaused) {
                            Actions.PAUSE_CONNECTION_DELAY_TEST
                        } else {
                            Actions.RESUME_CONNECTION_DELAY_TEST
                        },
                    )
                    .putExtra(Actions.EXTRA_APP_INITIATED, true)
                    .putExtra(Actions.EXTRA_DELAY_TEST_ID, currentTestId),
            )
        }

        stopButton.setOnClickListener {
            if (!testRunning) return@setOnClickListener
            startService(
                Intent(this, WhiteDnsVpnService::class.java)
                    .setAction(Actions.CANCEL_CONNECTION_DELAY_TEST)
                    .putExtra(Actions.EXTRA_APP_INITIATED, true)
                    .putExtra(Actions.EXTRA_DELAY_TEST_ID, delayTestId),
            )
        }

        connectionDelayTestListener = listener@{ intent ->
            val broadcastTestId = intent.getStringExtra(Actions.EXTRA_DELAY_TEST_ID)
            if (broadcastTestId != delayTestId) return@listener
            val session = ConnectionDelayTestState.snapshot(selectedSubscriptionId)
                ?.takeIf { it.testId == broadcastTestId }
                ?: return@listener
            val status = session.status
            lastTestCompleted = session.completed
            lastTestTotal = session.total
            lastTestAvailable = session.available
            lastSpeedCompleted = session.speedCompleted
            lastSpeedTotal = session.speedTotal
            lastTestStatus = status
            lastTestError = session.error
            testPaused = session.paused
            speedTestEnabled = session.speedTestEnabled
            speedTestToggle.isChecked = speedTestEnabled
            if (status == Actions.DELAY_TEST_PROGRESS || status == Actions.DELAY_TEST_COMPLETED) {
                connectionDelayRecords = subscriptionStore
                    .readConnectionDelayRecords(
                        subscriptionId = selectedSubscriptionId,
                        profiles = selectorProfiles,
                    )
                    .associateBy(ConnectionDelayRecord::fingerprint)
            }
            testingFingerprints.clear()
            speedTestingFingerprints.clear()
            if (session.isRunning) {
                testingFingerprints += session.targetFingerprints - session.finishedFingerprints
                if (session.speedTestEnabled) {
                    speedTestingFingerprints += session.finishedFingerprints.filter { fingerprint ->
                        fingerprint !in session.speedFinishedFingerprints &&
                            connectionDelayRecords[fingerprint]?.status == ConnectionDelayStatus.Success
                    }
                }
            }
            if (status == Actions.DELAY_TEST_PROGRESS || status == Actions.DELAY_TEST_COMPLETED) {
                filteredProfiles = visibleProfiles()
            }
            when (status) {
                Actions.DELAY_TEST_STARTED,
                Actions.DELAY_TEST_PROGRESS,
                -> {
                    testRunning = true
                    adapter.notifyDataSetChanged()
                    updateTestControls()
                }

                Actions.DELAY_TEST_COMPLETED -> {
                    testRunning = false
                    testPaused = false
                    testingFingerprints.clear()
                    speedTestingFingerprints.clear()
                    filteredProfiles = visibleProfiles()
                    adapter.notifyDataSetChanged()
                    updateTestControls()
                }

                Actions.DELAY_TEST_FAILED -> {
                    testRunning = false
                    testPaused = false
                    testingFingerprints.clear()
                    speedTestingFingerprints.clear()
                    adapter.notifyDataSetChanged()
                    updateTestControls()
                }

                Actions.DELAY_TEST_CANCELED -> {
                    testRunning = false
                    testPaused = false
                    testingFingerprints.clear()
                    speedTestingFingerprints.clear()
                    adapter.notifyDataSetChanged()
                    updateTestControls()
                }
            }
        }

        // Assemble layout
        content.addView(filterRow, LinearLayout.LayoutParams(-1, -2))
        content.addView(
            progressSection,
            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) },
        )
        content.addView(
            speedTestToggle,
            LinearLayout.LayoutParams(-1, dp(40)).apply { topMargin = dp(8) },
        )
        content.addView(
            controlRow,
            LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(12) },
        )
        content.addView(
            list,
            LinearLayout.LayoutParams(-1, (resources.displayMetrics.heightPixels * 0.45f).toInt()).apply {
                topMargin = dp(12)
            },
        )

        dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.connection_selector_title)
            .setView(content)
            .setNegativeButton(R.string.split_tunnel_cancel, null)
            .create()
        dialog.setOnDismissListener {
            connectionDelayTestListener = null
            if (connectionSelectorDialog === dialog) connectionSelectorDialog = null
        }
        connectionSelectorDialog = dialog
        updateTestControls()
        dialog.showWhiteDnsDialog()
    }

    private fun handleConnectionSelected(
        profile: ConnectionProfile?,
        selectedTypes: Set<String>,
    ) {
        val selectedSubscriptionId = SubscriptionStore(this).readSelectedSubscriptionId()
        val previous = connectionSelectionPreferenceStore.readSelectedProfile(
            selectedSubscriptionId,
            connectionProfiles,
        )
        val previousAutomaticTypes = connectionSelectionPreferenceStore.readAutomaticTypes(
            selectedSubscriptionId,
            connectionProfiles,
        )
        val automaticTypes = ConnectionTypeSelectionPolicy.restrictedTypes(
            selectedTypes,
            connectionProfiles,
        )
        val selectionChanged = previous?.fingerprint != profile?.fingerprint ||
            (profile == null && previousAutomaticTypes != automaticTypes)
        connectionSelectionPreferenceStore.saveAutomaticTypes(
            selectedSubscriptionId,
            selectedTypes,
            connectionProfiles,
        )
        val usesActiveRuntime = buttonModel.state == VpnState.Started &&
            activeRuntimeSubscriptionId == selectedSubscriptionId
        if (
            profile != null &&
            usesActiveRuntime &&
            (!liveSelectorReady || profile.fingerprint !in liveSelectableConnectionFingerprints)
        ) {
            Toast.makeText(this, R.string.connection_switch_unavailable, Toast.LENGTH_LONG).show()
            return
        }
        val activeSelectionChanged = profile != null &&
            activeConnectionFingerprint != profile.fingerprint
        if (profile != null && usesActiveRuntime && (selectionChanged || activeSelectionChanged)) {
            DiagnosticLogger.info(
                this,
                "activity.connection.switch.requested",
                "profile=${profile.tag}",
            )
            requestActiveConnectionSwitch(selectedSubscriptionId, profile.fingerprint)
            Toast.makeText(this, R.string.connection_switching, Toast.LENGTH_SHORT).show()
            return
        }
        if (!selectionChanged) return
        connectionSelectionPreferenceStore.saveSelectedProfile(selectedSubscriptionId, profile)
        DiagnosticLogger.info(
            this,
            "activity.connection.selected",
            "mode=${if (profile == null) "automatic" else "explicit"} " +
                "profile=${profile?.tag.orEmpty()} types=${automaticTypes.sorted().joinToString(",")}",
        )
        renderConnectionSelection()
        renderLocationSelection()
        if (buttonModel.state == VpnState.Started) {
            buttonModel.onStateChanged(VpnState.Starting)
            renderState(VpnState.Starting)
            startVpnService(Actions.RECONNECT)
        }
    }

    private fun renderConnectionSelection() {
        if (!::connectionSelectorRow.isInitialized) return
        val selectedSubscriptionId = SubscriptionStore(this).readSelectedSubscriptionId()
        val profile = connectionSelectionPreferenceStore.readSelectedProfile(
            selectedSubscriptionId,
            connectionProfiles,
        )
        val automaticTypes = connectionSelectionPreferenceStore.readAutomaticTypes(
            selectedSubscriptionId,
            connectionProfiles,
        )
        val configuredValue = profile?.tag ?: if (automaticTypes.isEmpty()) {
            getString(R.string.option_automatic)
        } else {
            getString(
                R.string.connection_automatic_types_value,
                if (automaticTypes.size == 1) {
                    automaticTypes.first().uppercase(Locale.US)
                } else {
                    getString(R.string.connection_filter_types_count, automaticTypes.size)
                },
            )
        }
        val value = activeConnectionTag.takeIf {
            buttonModel.state == VpnState.Started && it.isNotBlank()
        }?.let { activeTag ->
            when {
                activeRuntimeSubscriptionId != selectedSubscriptionId ->
                    getString(R.string.connection_active_value, activeTag)
                profile == null -> getString(R.string.connection_automatic_active_value, activeTag)
                else -> activeTag
            }
        } ?: configuredValue
        connectionSelectorRow.setValue(value)
        connectionSelectorRow.contentDescription = getString(R.string.connection_content_description, value)
    }

    private fun showLocationSelector() {
        if (buttonModel.state == VpnState.Starting || buttonModel.state == VpnState.Stopping) return
        val options = dialogLocationOptions()
        val selectedCountryCode = locationPreferenceStore.readSelectedCountryCode()
        val checkedIndex = options.indexOfFirst { it.countryCode == selectedCountryCode }.takeIf { it >= 0 } ?: 0
        val labels = options.map { option ->
            if (resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
                "\u200F${option.label}"
            } else {
                option.label
            }
        }
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.location_selector_title)
            .setSingleChoiceItems(labels.toTypedArray(), checkedIndex) { dialog, which ->
                handleLocationSelected(options[which])
                dialog.dismiss()
            }
            .create()
        dialog.showWhiteDnsDialog()
    }

    private fun dialogLocationOptions(): List<LocationSelectorOption> {
        if (locationOptions.isEmpty()) return listOf(automaticLocationOption())
        val selectedCountryCode = locationPreferenceStore.readSelectedCountryCode()
        val selectedOption = if (
            selectedCountryCode != null &&
            locationOptions.none { it.countryCode == selectedCountryCode }
        ) {
            ConnectionLocationPolicy.optionForCode(
                selectedCountryCode,
                resources.configuration.locales[0],
            )
        } else {
            null
        }
        if (selectedOption == null) return locationOptions
        return listOf(automaticLocationOption(), selectedOption) +
            locationOptions.filter { it.countryCode != null && it.countryCode != selectedCountryCode }
    }

    private fun handleLocationSelected(option: LocationSelectorOption) {
        val previousCountryCode = locationPreferenceStore.readSelectedCountryCode()
        val selectedSubscriptionId = SubscriptionStore(this).readSelectedSubscriptionId()
        val hadExplicitConnection = connectionSelectionPreferenceStore.readSelectedProfile(
            selectedSubscriptionId,
            connectionProfiles,
        ) != null
        if (previousCountryCode == option.countryCode && !hadExplicitConnection) return
        connectionSelectionPreferenceStore.saveSelectedProfile(selectedSubscriptionId, null)
        locationPreferenceStore.saveSelectedCountryCode(option.countryCode)
        DiagnosticLogger.info(
            this,
            "activity.location.selected",
            "code=${option.countryCode ?: "auto"} label=${option.label}",
        )
        renderLocationSelection()
        renderConnectionSelection()
        if (buttonModel.state == VpnState.Started) {
            buttonModel.onStateChanged(VpnState.Starting)
            renderState(VpnState.Starting)
            startVpnService(Actions.RECONNECT)
        }
    }

    private fun renderLocationSelection() {
        if (!::locationSelectorRow.isInitialized) return
        val selectedSubscriptionId = SubscriptionStore(this).readSelectedSubscriptionId()
        val selectedCountryCode = connectionSelectionPreferenceStore.readSelectedProfile(
            selectedSubscriptionId,
            connectionProfiles,
        )?.let(ConnectionLocationPolicy::countryForProfile)?.code
            ?: locationPreferenceStore.readSelectedCountryCode()
        val option = locationOptions.firstOrNull { it.countryCode == selectedCountryCode }
            ?: ConnectionLocationPolicy.optionForCode(
                selectedCountryCode,
                resources.configuration.locales[0],
            )
            ?: automaticLocationOption()
        locationSelectorRow.setValue(option.label)
        locationSelectorRow.contentDescription = getString(R.string.location_content_description, option.label)
    }

    private fun automaticLocationOption(): LocationSelectorOption =
        LocationSelectorOption(countryCode = null, label = getString(R.string.option_automatic))

    private fun showHomeMenu(anchor: View) {
        val themeMode = appThemePreferenceStore.read()
        val language = appLanguagePreferenceStore.read()
        whiteDnsPopupMenu(anchor).apply {
            menu.add(
                0,
                HOME_MENU_THEME_ID,
                0,
                getString(R.string.home_menu_theme, getString(themeMode.labelRes)),
            )
            menu.add(
                0,
                HOME_MENU_LANGUAGE_ID,
                1,
                getString(R.string.home_menu_language, getString(language.labelRes)),
            )
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    HOME_MENU_THEME_ID -> showThemeSelector()
                    HOME_MENU_LANGUAGE_ID -> showLanguageSelector()
                }
                true
            }
        }.show()
    }

    private fun showThemeSelector() {
        val modes = AppThemeMode.entries
        val selected = appThemePreferenceStore.read()
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.theme_dialog_title)
            .setSingleChoiceItems(
                modes.map { getString(it.labelRes) }.toTypedArray(),
                modes.indexOf(selected),
            ) { dialog, which ->
                val mode = modes[which]
                dialog.dismiss()
                if (mode == selected) return@setSingleChoiceItems
                appThemePreferenceStore.save(mode)
                recreate()
            }
            .setNegativeButton(R.string.split_tunnel_cancel, null)
            .create()
        dialog.showWhiteDnsDialog()
    }

    private fun showLanguageSelector() {
        val languages = AppLanguage.entries
        val selected = appLanguagePreferenceStore.read()
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.language_dialog_title)
            .setSingleChoiceItems(
                languages.map { getString(it.labelRes) }.toTypedArray(),
                languages.indexOf(selected),
            ) { dialog, which ->
                val language = languages[which]
                dialog.dismiss()
                if (language == selected) return@setSingleChoiceItems
                AppLocale.apply(applicationContext, language)
                WhiteDnsTileService.requestTileRefresh(this)
                recreate()
            }
            .setNegativeButton(R.string.split_tunnel_cancel, null)
            .create()
        dialog.showWhiteDnsDialog()
    }

    private fun showDnsPrivacySelector() {
        if (buttonModel.state == VpnState.Starting || buttonModel.state == VpnState.Stopping) return
        val modes = DnsPrivacyMode.values()
        val selectedMode = dnsPrivacyPreferenceStore.readMode()
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.dns_encrypted_title)
            .setSingleChoiceItems(
                modes.map { getString(it.labelRes) }.toTypedArray(),
                modes.indexOf(selectedMode),
            ) { dialog, which ->
                handleDnsPrivacySelected(modes[which])
                dialog.dismiss()
            }
            .create()
        dialog.showWhiteDnsDialog()
    }

    private fun showRoutingModeSelector() {
        if (buttonModel.state == VpnState.Starting || buttonModel.state == VpnState.Stopping) return
        val modes = RoutingMode.values()
        val selectedMode = routingModePreferenceStore.read()
        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.routing_rules_title)
            .setSingleChoiceItems(
                modes.map { getString(it.labelRes) }.toTypedArray(),
                modes.indexOf(selectedMode),
            ) { dialog, which ->
                val mode = modes[which]
                dialog.dismiss()
                if (mode == selectedMode) return@setSingleChoiceItems
                routingModePreferenceStore.save(mode)
                DiagnosticLogger.info(this, "activity.routing.saved", "mode=${mode.wireName}")
                renderRoutingModeSelection()
                reconnectForConnectionOptionChange()
            }
            .create()
        dialog.showWhiteDnsDialog()
    }

    private fun renderRoutingModeSelection() {
        if (!::routingModeRow.isInitialized) return
        val mode = routingModePreferenceStore.read()
        val modeLabel = getString(mode.labelRes)
        routingModeValueText.text = modeLabel
        routingModeDetailText.setText(mode.detailRes)
        routingModeRow.contentDescription = getString(R.string.routing_content_description, modeLabel)
    }

    private fun handleDnsPrivacySelected(mode: DnsPrivacyMode) {
        val previousMode = dnsPrivacyPreferenceStore.readMode()
        if (previousMode == mode) return
        dnsPrivacyPreferenceStore.saveMode(mode)
        DiagnosticLogger.info(this, "activity.dnsPrivacy.saved", "mode=${mode.wireName}")
        renderDnsPrivacySelection()
        if (buttonModel.state == VpnState.Started) {
            buttonModel.onStateChanged(VpnState.Starting)
            renderState(VpnState.Starting)
            startVpnService(Actions.RECONNECT)
        }
    }

    private fun commitDnsPrivacyEndpoint(
        reconnectIfChanged: Boolean,
        focusOnError: Boolean = false,
    ): Boolean {
        if (!::dnsPrivacyEndpointInput.isInitialized) return true
        val mode = dnsPrivacyPreferenceStore.readMode()
        if (mode == DnsPrivacyMode.Automatic) return true
        val previousValue = when (mode) {
            DnsPrivacyMode.DoH -> dnsPrivacyPreferenceStore.readDohUrl()
            DnsPrivacyMode.DoT -> dnsPrivacyPreferenceStore.readDotEndpoint()
            DnsPrivacyMode.Automatic -> return true
        }
        val nextValue = runCatching {
            when (mode) {
                DnsPrivacyMode.DoH -> DnsPrivacyPolicy.normalizeDohUrl(dnsPrivacyEndpointInput.text.toString())
                DnsPrivacyMode.DoT -> DnsPrivacyPolicy.normalizeDotEndpoint(dnsPrivacyEndpointInput.text.toString())
                DnsPrivacyMode.Automatic -> return true
            }
        }.getOrElse { error ->
            showDnsPrivacyError(localizedError(error, R.string.dns_invalid), focusOnError)
            return false
        }
        when (mode) {
            DnsPrivacyMode.DoH -> dnsPrivacyPreferenceStore.saveDohUrl(nextValue)
            DnsPrivacyMode.DoT -> dnsPrivacyPreferenceStore.saveDotEndpoint(nextValue)
            DnsPrivacyMode.Automatic -> Unit
        }
        setDnsPrivacyEndpointInputText(displayDnsPrivacyEndpoint(mode, nextValue))
        dnsPrivacyErrorText.visibility = View.GONE
        if (previousValue != nextValue && reconnectIfChanged && buttonModel.state == VpnState.Started) {
            buttonModel.onStateChanged(VpnState.Starting)
            renderState(VpnState.Starting)
            startVpnService(Actions.RECONNECT)
        }
        return true
    }

    private fun renderDnsPrivacySelection() {
        if (!::dnsPrivacyRow.isInitialized) return
        val mode = dnsPrivacyPreferenceStore.readMode()
        val modeLabel = getString(mode.labelRes)
        dnsPrivacyValueText.text = modeLabel
        dnsPrivacyDetailText.text = when (mode) {
            DnsPrivacyMode.Automatic -> getString(R.string.dns_automatic_detail)
            DnsPrivacyMode.DoH -> getString(R.string.dns_doh_detail)
            DnsPrivacyMode.DoT -> getString(R.string.dns_dot_detail)
        }
        dnsPrivacyRow.contentDescription = getString(R.string.dns_content_description, modeLabel)
        if (
            !::dnsPrivacyEndpointInput.isInitialized ||
            !::dnsPrivacyEndpointLayout.isInitialized ||
            !::dnsPrivacyErrorText.isInitialized
        ) return
        val endpointVisible = mode != DnsPrivacyMode.Automatic
        dnsPrivacyEndpointLayout.visibility = if (endpointVisible) View.VISIBLE else View.GONE
        dnsPrivacyErrorText.visibility = View.GONE
        if (!endpointVisible) return
        dnsPrivacyEndpointLayout.hint = when (mode) {
            DnsPrivacyMode.DoH -> getString(R.string.dns_doh_address)
            DnsPrivacyMode.DoT -> getString(R.string.dns_dot_address)
            DnsPrivacyMode.Automatic -> ""
        }
        dnsPrivacyEndpointLayout.helperText = when (mode) {
            DnsPrivacyMode.DoH -> getString(R.string.dns_doh_helper)
            DnsPrivacyMode.DoT -> getString(R.string.dns_dot_helper)
            DnsPrivacyMode.Automatic -> ""
        }
        if (!dnsPrivacyEndpointInput.hasFocus()) {
            val value = when (mode) {
                DnsPrivacyMode.DoH -> dnsPrivacyPreferenceStore.readDohUrl()
                DnsPrivacyMode.DoT -> dnsPrivacyPreferenceStore.readDotEndpoint()
                DnsPrivacyMode.Automatic -> ""
            }
            setDnsPrivacyEndpointInputText(displayDnsPrivacyEndpoint(mode, value))
        }
    }

    private fun displayDnsPrivacyEndpoint(mode: DnsPrivacyMode, value: String): String {
        return if (mode == DnsPrivacyMode.DoT) value.removePrefix("tls://") else value
    }

    private fun setDnsPrivacyEndpointInputText(value: String) {
        if (dnsPrivacyEndpointInput.text.toString() == value) return
        dnsPrivacyInputUpdating = true
        try {
            dnsPrivacyEndpointInput.setText(value)
            dnsPrivacyEndpointInput.setSelection(dnsPrivacyEndpointInput.text.length)
        } finally {
            dnsPrivacyInputUpdating = false
        }
    }

    private fun showDnsPrivacyError(message: String, focusOnError: Boolean) {
        dnsPrivacyErrorText.text = message
        dnsPrivacyErrorText.visibility = View.VISIBLE
        if (focusOnError) dnsPrivacyEndpointInput.requestFocus()
    }

    private fun showSplitTunnelSelector() {
        if (buttonModel.state == VpnState.Starting || buttonModel.state == VpnState.Stopping) return
        activityScope.launch {
            val apps = runCatching {
                withContext(Dispatchers.IO) {
                    installedAppRepository.loadLaunchableApps()
                }
            }.onFailure { error ->
                DiagnosticLogger.warn(this@MainActivity, "activity.splitTunnel.apps.failed", error = error)
            }.getOrNull()

            if (apps == null) {
                Toast.makeText(this@MainActivity, R.string.split_tunnel_empty_apps, Toast.LENGTH_SHORT).show()
                return@launch
            }
            showSplitTunnelDialog(apps)
        }
    }

    private fun showSplitTunnelDialog(apps: List<SplitTunnelInstalledApp>) {
        val launchablePackages = apps.map { it.packageName }.toSet()
        val savedSettings = splitTunnelPreferenceStore.readSettings()
        val prunedSettings = SplitTunnelPolicy.sanitizeSettings(
            savedSettings.copy(
                selectedPackages = savedSettings.selectedPackages.filter { it in launchablePackages }.toSet(),
            ),
            packageName,
        )
        if (prunedSettings.selectedPackages != savedSettings.selectedPackages) {
            splitTunnelPreferenceStore.saveSettings(prunedSettings)
            DiagnosticLogger.info(
                this,
                "activity.splitTunnel.pruned",
                "before=${savedSettings.selectedPackages.size} after=${prunedSettings.selectedPackages.size}",
            )
        }

        var currentMode = prunedSettings.mode
        val selectedPackages = prunedSettings.selectedPackages.toMutableSet()
        var saveButton: android.widget.Button? = null

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            setPadding(dp(20), dp(8), dp(20), dp(4))
        }

        val modeGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            setPadding(dp(4), dp(4), dp(4), dp(4))
            background = glassSurfaceDrawable(radiusDp = 12).apply {
                setStroke(dp(1), OUTLINE)
            }
            clipToOutline = true
        }
        val modeById = mutableMapOf<Int, SplitTunnelMode>()
        fun addModeButton(mode: SplitTunnelMode, label: String) {
            val id = View.generateViewId()
            modeById[id] = mode
            modeGroup.addView(
                RadioButton(this).apply {
                    this.id = id
                    text = label
                    textSize = 14f
                    typeface = WhiteDnsBodyTypeface
                    setTextColor(TEXT_PRIMARY)
                    minHeight = dp(48)
                    gravity = Gravity.CENTER_VERTICAL
                    buttonTintList = ColorStateList(
                        arrayOf(
                            intArrayOf(android.R.attr.state_checked),
                            intArrayOf(),
                        ),
                        intArrayOf(TEAL, TEXT_SECONDARY),
                    )
                    setSelectableBackground()
                    setPaddingRelative(dp(12), 0, dp(12), 0)
                    isChecked = currentMode == mode
                },
                RadioGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(48),
                ),
            )
        }
        addModeButton(SplitTunnelMode.Off, getString(R.string.split_tunnel_mode_off))
        addModeButton(SplitTunnelMode.BypassSelected, getString(R.string.split_tunnel_mode_bypass))
        addModeButton(SplitTunnelMode.VpnOnlySelected, getString(R.string.split_tunnel_mode_vpn_only))

        val searchInput = TextInputEditText(this).apply {
            setSingleLine(true)
            background = null
            setPaddingRelative(dp(16), dp(12), dp(16), dp(12))
            textSize = 14f
            typeface = WhiteDnsBodyTypeface
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_FILTER
            imeOptions = EditorInfo.IME_ACTION_DONE
            setTextColor(TEXT_PRIMARY)
            setHintTextColor(TEXT_SECONDARY)
        }
        val searchLayout = TextInputLayout(this).apply {
            hint = getString(R.string.split_tunnel_search_hint)
            boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
            boxBackgroundColor = withAlpha(SURFACE, if (palette.isDark) 232 else 246)
            boxStrokeColor = TEAL
            boxStrokeWidth = dp(1)
            boxStrokeWidthFocused = dp(1)
            defaultHintTextColor = ColorStateList.valueOf(TEXT_SECONDARY)
            setBoxCornerRadii(dp(8).toFloat(), dp(8).toFloat(), dp(8).toFloat(), dp(8).toFloat())
            addView(searchInput)
        }
        val selectedCountText = TextView(this).apply {
            textSize = 12f
            typeface = WhiteDnsBodyBoldTypeface
            includeFontPadding = false
            gravity = Gravity.START
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        }
        val appListHeight = minOf(dp(320), resources.displayMetrics.heightPixels * 36 / 100)
        val appList = ListView(this).apply {
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            divider = ColorDrawable(OUTLINE)
            dividerHeight = dp(1)
            cacheColorHint = Color.TRANSPARENT
        }
        val emptyAppListText = TextView(this).apply {
            setText(
                if (apps.isEmpty()) {
                    R.string.split_tunnel_empty_apps
                } else {
                    R.string.split_tunnel_empty_search
                },
            )
            textSize = 14f
            typeface = WhiteDnsBodyTypeface
            setTextColor(TEXT_SECONDARY)
            gravity = Gravity.CENTER
            includeFontPadding = false
            setPadding(dp(24), dp(24), dp(24), dp(24))
        }
        val appListContainer = FrameLayout(this).apply {
            background = glassSurfaceDrawable(radiusDp = 12).apply {
                setStroke(dp(1), OUTLINE)
            }
            clipToOutline = true
            addView(
                appList,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
            addView(
                emptyAppListText,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        appList.emptyView = emptyAppListText
        val appPicker = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_LOCALE
            addView(
                searchLayout,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                selectedCountText,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = dp(12)
                },
            )
            addView(
                appListContainer,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    appListHeight,
                ).apply {
                    topMargin = dp(8)
                },
            )
        }

        fun updateSaveState() {
            val requiresSelection = currentMode != SplitTunnelMode.Off
            val isValid = !requiresSelection || selectedPackages.isNotEmpty()
            saveButton?.isEnabled = isValid
            selectedCountText.text = if (isValid) {
                getString(R.string.split_tunnel_selected_count, selectedPackages.size)
            } else {
                getString(R.string.split_tunnel_select_app_required)
            }
            selectedCountText.setTextColor(if (isValid) TEXT_SECONDARY else ERROR)
        }

        fun updateModeUi() {
            val pickerVisible = currentMode != SplitTunnelMode.Off
            appPicker.visibility = if (pickerVisible) View.VISIBLE else View.GONE
            if (!pickerVisible) searchInput.clearFocus()
            updateSaveState()
        }

        var filteredApps = apps
        val appIconCache = mutableMapOf<String, android.graphics.drawable.Drawable>()
        val fallbackAppIcon = packageManager.defaultActivityIcon

        class AppRowHolder(
            val checkBox: CheckBox,
            val icon: ImageView,
            val label: TextView,
            val packageName: TextView,
        )

        val appListAdapter = object : BaseAdapter() {
            override fun getCount(): Int = filteredApps.size

            override fun getItem(position: Int): SplitTunnelInstalledApp = filteredApps[position]

            override fun getItemId(position: Int): Long = position.toLong()

            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val row: LinearLayout
                val holder: AppRowHolder
                if (convertView == null) {
                    val checkBox = CheckBox(this@MainActivity).apply {
                        buttonTintList = ColorStateList(
                            arrayOf(
                                intArrayOf(android.R.attr.state_checked),
                                intArrayOf(),
                            ),
                            intArrayOf(TEAL, TEXT_SECONDARY),
                        )
                    }
                    val icon = ImageView(this@MainActivity).apply {
                        scaleType = ImageView.ScaleType.CENTER_INSIDE
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                    }
                    val label = TextView(this@MainActivity).apply {
                        textSize = 14f
                        typeface = WhiteDnsBodyBoldTypeface
                        setTextColor(TEXT_PRIMARY)
                        includeFontPadding = false
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.END
                        textAlignment = View.TEXT_ALIGNMENT_GRAVITY
                    }
                    val packageName = TextView(this@MainActivity).apply {
                        textSize = 12f
                        typeface = WhiteDnsBodyTypeface
                        setTextColor(TEXT_SECONDARY)
                        includeFontPadding = false
                        maxLines = 1
                        ellipsize = TextUtils.TruncateAt.MIDDLE
                        textAlignment = View.TEXT_ALIGNMENT_GRAVITY
                        textDirection = View.TEXT_DIRECTION_LTR
                    }
                    val appText = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                        addView(label, LinearLayout.LayoutParams(-1, -2))
                        addView(
                            packageName,
                            LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) },
                        )
                    }
                    row = LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutDirection = View.LAYOUT_DIRECTION_LOCALE
                        gravity = Gravity.CENTER_VERTICAL
                        minimumHeight = dp(64)
                        setPadding(dp(8), dp(4), dp(12), dp(4))
                        setSelectableBackground()
                        isClickable = true
                        isFocusable = false
                        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                        addView(checkBox, LinearLayout.LayoutParams(dp(48), dp(56)))
                        addView(
                            icon,
                            LinearLayout.LayoutParams(dp(32), dp(32)).apply { marginStart = dp(4) },
                        )
                        addView(
                            appText,
                            LinearLayout.LayoutParams(0, -2, 1f).apply {
                                marginStart = dp(12)
                                marginEnd = dp(8)
                            },
                        )
                    }
                    holder = AppRowHolder(checkBox, icon, label, packageName)
                    row.tag = holder
                } else {
                    row = convertView as LinearLayout
                    holder = row.tag as AppRowHolder
                }

                val app = getItem(position)
                val appTextGravity =
                    if (resources.configuration.layoutDirection == View.LAYOUT_DIRECTION_RTL) {
                        Gravity.RIGHT
                    } else {
                        Gravity.LEFT
                    }
                holder.label.text = app.label
                holder.label.gravity = appTextGravity
                holder.packageName.text = app.packageName
                holder.packageName.gravity = appTextGravity
                holder.checkBox.setOnCheckedChangeListener(null)
                holder.checkBox.isChecked = app.packageName in selectedPackages
                holder.checkBox.contentDescription = "${app.label}, ${app.packageName}"
                holder.checkBox.setOnCheckedChangeListener { _, isChecked ->
                    if (isChecked) {
                        selectedPackages += app.packageName
                    } else {
                        selectedPackages -= app.packageName
                    }
                    updateSaveState()
                }
                row.setOnClickListener { holder.checkBox.performClick() }

                holder.icon.tag = app.packageName
                val cachedIcon = appIconCache[app.packageName]
                holder.icon.setImageDrawable(cachedIcon ?: fallbackAppIcon)
                if (cachedIcon == null) {
                    activityScope.launch {
                        val icon = withContext(Dispatchers.IO) {
                            runCatching { packageManager.getApplicationIcon(app.packageName) }
                                .getOrDefault(fallbackAppIcon)
                        }
                        appIconCache[app.packageName] = icon
                        if (holder.icon.tag == app.packageName) {
                            holder.icon.setImageDrawable(icon)
                        }
                    }
                }

                return row
            }
        }
        appList.adapter = appListAdapter

        fun renderAppList(query: String) {
            val normalizedQuery = query.trim().lowercase(Locale.getDefault())
            filteredApps = if (normalizedQuery.isBlank()) {
                apps
            } else {
                apps.filter { app ->
                    app.label.lowercase(Locale.getDefault()).contains(normalizedQuery) ||
                        app.packageName.lowercase(Locale.US).contains(normalizedQuery)
                }
            }
            emptyAppListText.setText(
                if (apps.isEmpty()) {
                    R.string.split_tunnel_empty_apps
                } else {
                    R.string.split_tunnel_empty_search
                },
            )
            appListAdapter.notifyDataSetChanged()
            updateSaveState()
        }

        modeGroup.setOnCheckedChangeListener { _, checkedId ->
            currentMode = modeById[checkedId] ?: SplitTunnelMode.Off
            updateModeUi()
        }
        searchInput.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) {
                renderAppList(s?.toString().orEmpty())
            }
        })

        content.addView(
            modeGroup,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        content.addView(
            appPicker,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(16)
            },
        )

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(R.string.split_tunnel_title)
            .setView(content)
            .setNegativeButton(R.string.split_tunnel_cancel, null)
            .setPositiveButton(R.string.split_tunnel_save, null)
            .create()

        updateModeUi()
        dialog.showWhiteDnsDialog {
            saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            saveButton?.apply {
                setTextColor(
                    ColorStateList(
                        arrayOf(
                            intArrayOf(android.R.attr.state_enabled),
                            intArrayOf(),
                        ),
                        intArrayOf(TEAL, withAlpha(TEXT_SECONDARY, 120)),
                    ),
                )
            }
            saveButton?.setOnClickListener {
                val nextSettings = SplitTunnelPolicy.sanitizeSettings(
                    SplitTunnelSettings(
                        mode = currentMode,
                        selectedPackages = selectedPackages,
                    ),
                    packageName,
                )
                if (nextSettings.mode != SplitTunnelMode.Off && nextSettings.selectedPackages.isEmpty()) {
                    updateSaveState()
                    return@setOnClickListener
                }

                val previousSettings = splitTunnelPreferenceStore.readSettings()
                splitTunnelPreferenceStore.saveSettings(nextSettings)
                DiagnosticLogger.info(
                    this@MainActivity,
                    "activity.splitTunnel.saved",
                    "mode=${nextSettings.mode.wireName} selected=${nextSettings.selectedPackages.size}",
                )
                dialog.dismiss()
                renderSplitTunnelSelection()
                if (buttonModel.state == VpnState.Started && previousSettings != nextSettings) {
                    buttonModel.onStateChanged(VpnState.Starting)
                    renderState(VpnState.Starting)
                    startVpnService(Actions.RECONNECT)
                }
            }
            updateModeUi()
        }
    }

    private fun renderSplitTunnelSelection() {
        if (!::splitTunnelRow.isInitialized) return
        val settings = splitTunnelPreferenceStore.readSettings()
        val rowValue = when (settings.mode) {
            SplitTunnelMode.Off -> getString(R.string.value_inactive)
            SplitTunnelMode.BypassSelected -> getString(
                R.string.split_tunnel_value_bypass,
                settings.selectedPackages.size,
            )
            SplitTunnelMode.VpnOnlySelected -> getString(
                R.string.split_tunnel_value_vpn_only,
                settings.selectedPackages.size,
            )
        }
        splitTunnelRow.setValue(rowValue)
        splitTunnelRow.contentDescription = getString(R.string.split_tunnel_content_description, rowValue)
    }

    private fun commitFrontingIpInput(
        reconnectIfChanged: Boolean,
        focusOnError: Boolean = false,
    ): Boolean {
        if (!::frontingIpInput.isInitialized) return true
        val previousValue = frontingIpPreferenceStore.readFrontingIp()
        val pendingValue = frontingIpInput.text?.toString().orEmpty()
        if (pendingValue.isNotBlank()) {
            val nextIps = runCatching {
                FrontingIpPolicy.normalizeIps((frontingIps + pendingValue.split(",")).joinToString(","))
            }.getOrElse { error ->
                showFrontingIpError(localizedError(error, R.string.fronting_invalid), focusOnError)
                return false
            }
            frontingIps = nextIps
            setFrontingIpInputText("")
        }
        return saveFrontingIps(reconnectIfChanged, previousValue)
    }

    private fun saveTlsIntegrityEnabled(enabled: Boolean) {
        val previousValue = tlsIntegrityPreferenceStore.isEnabled()
        if (previousValue == enabled) return
        tlsIntegrityPreferenceStore.saveEnabled(enabled)
        if (!enabled) WhiteDnsScanStateStore(this).clearTlsQuarantine()
        DiagnosticLogger.info(this, "activity.tlsIntegrity.saved", "enabled=$enabled")
        reconnectForConnectionOptionChange()
    }

    private fun saveLanSharingEnabled(enabled: Boolean) {
        if (lanSharingPreferenceStore.read().enabled == enabled) return
        lanSharingPreferenceStore.saveEnabled(enabled)
        DiagnosticLogger.info(this, "activity.lanSharing.saved", "enabled=$enabled")
        renderLanSharingControls(settingsEnabled = true)
        reconnectForConnectionOptionChange()
    }

    private fun saveConnectionMode(mode: ConnectionMode) {
        if (connectionModePreferenceStore.read() == mode) return
        connectionModePreferenceStore.save(mode)
        DiagnosticLogger.info(this, "activity.connectionMode.saved", "mode=${mode.wireName}")
        renderLanSharingControls(settingsEnabled = true)
        if (buttonModel.state != VpnState.Started) return
        if (mode == ConnectionMode.Vpn) {
            beginConnectFlow(Actions.RECONNECT)
        } else {
            buttonModel.onStateChanged(VpnState.Starting)
            renderState(VpnState.Starting)
            startVpnService(Actions.RECONNECT)
        }
    }

    private fun saveLanSharingPasswordRequired(required: Boolean) {
        val settings = lanSharingPreferenceStore.read()
        if (settings.passwordRequired == required) return
        lanSharingPreferenceStore.savePasswordRequired(required)
        DiagnosticLogger.info(this, "activity.lanSharing.passwordRequired.saved", "required=$required")
        renderLanSharingControls(settingsEnabled = true)
        if (settings.enabled) reconnectForConnectionOptionChange()
    }

    private fun regenerateLanSharingPassword() {
        val settings = lanSharingPreferenceStore.read()
        lanSharingPreferenceStore.regeneratePassword()
        DiagnosticLogger.info(this, "activity.lanSharing.password.regenerated", "enabled=${settings.enabled}")
        renderLanSharingControls(settingsEnabled = true)
        if (settings.enabled && settings.passwordRequired) reconnectForConnectionOptionChange()
    }

    private fun copyLanSharingSettings() {
        val settings = lanSharingPreferenceStore.read()
        val value = lanSharingDetails(settings)
        getSystemService(ClipboardManager::class.java)
            .setPrimaryClip(ClipData.newPlainText("WhiteVPN LAN proxy", value))
        Toast.makeText(this, R.string.lan_sharing_copied, Toast.LENGTH_SHORT).show()
    }

    private fun renderLanSharingControls(settingsEnabled: Boolean) {
        val settings = lanSharingPreferenceStore.read()
        val selectedMode = connectionModePreferenceStore.read()
        val usesVpnTunnel = ConnectionModePolicy.shouldStartTun(selectedMode, alwaysOnMode, lockdownMode)
        if (::connectionModeGroup.isInitialized) {
            val modeSelectionEnabled = settingsEnabled && !alwaysOnMode && !lockdownMode
            connectionModeGroup.isEnabled = modeSelectionEnabled
            vpnModeButton.isEnabled = modeSelectionEnabled
            proxyModeButton.isEnabled = modeSelectionEnabled
            val selectedId = if (usesVpnTunnel) vpnModeButton.id else proxyModeButton.id
            if (connectionModeGroup.checkedButtonId != selectedId) connectionModeGroup.check(selectedId)
        }
        renderDashboardLocalEndpoint(if (usesVpnTunnel) ConnectionMode.Vpn else ConnectionMode.Proxy)
        if (::lanSharingCheckbox.isInitialized) {
            val details = lanSharingDetails(settings)
            lanSharingCheckbox.isChecked = settings.enabled
            lanSharingCheckbox.isEnabled = settingsEnabled
            lanSharingPasswordCheckbox.isChecked = settings.passwordRequired
            lanSharingPasswordCheckbox.isEnabled = settingsEnabled
            lanSharingDetailsText.text = details
            lanSharingDetailsText.contentDescription =
                getString(R.string.lan_sharing_details_accessibility, details)
            lanSharingDetailsText.isEnabled = settingsEnabled
            lanSharingDetailsText.alpha = when {
                !settingsEnabled -> 0.45f
                settings.enabled -> 1f
                else -> 0.7f
            }
            lanSharingRegenerateButton.visibility =
                if (settings.passwordRequired) View.VISIBLE else View.GONE
            lanSharingRegenerateButton.isEnabled = settingsEnabled
        }
    }

    private fun lanSharingDetails(settings: LanSharingSettings): String {
        val localEndpoint = getString(
            R.string.lan_sharing_local_endpoint,
            MihomoRuntimeDefaults.MIXED_PORT,
        )
        val addresses = LanSharingAddresses.reachablePrivateIpv4Addresses()
        val endpoints = if (addresses.isEmpty()) {
            getString(R.string.lan_sharing_no_address)
        } else {
            addresses.joinToString("\n") { address ->
                getString(R.string.lan_sharing_endpoint, address, MihomoRuntimeDefaults.MIXED_PORT)
            }
        }
        val lanEndpoints = if (settings.passwordRequired) {
            getString(R.string.lan_sharing_credentials, endpoints, settings.username, settings.password)
        } else {
            getString(R.string.lan_sharing_no_credentials, endpoints)
        }
        return getString(R.string.lan_sharing_details, localEndpoint, lanEndpoints)
    }

    private fun saveAmneziaNoiseEnabled(enabled: Boolean) {
        val previous = connectionOptionsPreferenceStore.read()
        val settings = if (enabled) {
            runCatching(::readAmneziaNoiseInputs).getOrElse { error ->
                amneziaNoiseCheckbox.isChecked = false
                showAmneziaNoiseError(localizedError(error, R.string.amnezia_noise_invalid))
                return
            }
        } else {
            previous.amneziaNoise
        }
        connectionOptionsPreferenceStore.saveAmneziaNoise(enabled, settings)
        DiagnosticLogger.info(
            this,
            "activity.amneziaNoise.saved",
            "enabled=$enabled count=${settings.count} min=${settings.minSize} max=${settings.maxSize}",
        )
        renderConnectionOptionsControls(settingsEnabled = true)
        if (previous.amneziaNoiseEnabled != enabled) reconnectForConnectionOptionChange()
    }

    private fun applyAmneziaNoiseSettings() {
        val previous = connectionOptionsPreferenceStore.read()
        val settings = runCatching(::readAmneziaNoiseInputs).getOrElse { error ->
            showAmneziaNoiseError(localizedError(error, R.string.amnezia_noise_invalid))
            return
        }
        connectionOptionsPreferenceStore.saveAmneziaNoise(enabled = true, settings)
        amneziaNoiseErrorText.visibility = View.GONE
        if (previous.amneziaNoise != settings || !previous.amneziaNoiseEnabled) {
            DiagnosticLogger.info(
                this,
                "activity.amneziaNoise.applied",
                "count=${settings.count} min=${settings.minSize} max=${settings.maxSize}",
            )
            reconnectForConnectionOptionChange()
        }
    }

    private fun readAmneziaNoiseInputs(): AmneziaNoiseSettings {
        val settings = AmneziaNoiseSettings(
            count = amneziaNoiseCountInput.text.toString().toIntOrNull()
                ?: throw IllegalArgumentException(getString(R.string.amnezia_noise_count_invalid)),
            minSize = amneziaNoiseMinSizeInput.text.toString().toIntOrNull()
                ?: throw IllegalArgumentException(getString(R.string.amnezia_noise_min_size_invalid)),
            maxSize = amneziaNoiseMaxSizeInput.text.toString().toIntOrNull()
                ?: throw IllegalArgumentException(getString(R.string.amnezia_noise_max_size_invalid)),
        )
        return MihomoConnectionOptionsPolicy.validateNoise(settings)
    }

    private fun showAmneziaNoiseError(message: String) {
        amneziaNoiseErrorText.text = message
        amneziaNoiseErrorText.visibility = View.VISIBLE
    }

    private fun reconnectForConnectionOptionChange() {
        if (buttonModel.state != VpnState.Started) return
        buttonModel.onStateChanged(VpnState.Starting)
        renderState(VpnState.Starting)
        startVpnService(Actions.RECONNECT)
    }

    private fun renderConnectionOptionsControls(settingsEnabled: Boolean) {
        if (!::amneziaNoiseCheckbox.isInitialized) return
        val options = connectionOptionsPreferenceStore.read()
        amneziaNoiseCheckbox.isChecked = options.amneziaNoiseEnabled
        amneziaNoiseCheckbox.isEnabled = settingsEnabled
        val noiseFieldsEnabled = settingsEnabled && options.amneziaNoiseEnabled
        listOf(amneziaNoiseCountInput, amneziaNoiseMinSizeInput, amneziaNoiseMaxSizeInput).forEach {
            it.isEnabled = noiseFieldsEnabled
        }
        amneziaNoiseFields.alpha = if (noiseFieldsEnabled) 1f else 0.45f
        amneziaNoiseApplyButton.visibility = if (options.amneziaNoiseEnabled) View.VISIBLE else View.GONE
        amneziaNoiseApplyButton.isEnabled = noiseFieldsEnabled
        if (!amneziaNoiseCountInput.hasFocus()) amneziaNoiseCountInput.setText(options.amneziaNoise.count.toString())
        if (!amneziaNoiseMinSizeInput.hasFocus()) amneziaNoiseMinSizeInput.setText(options.amneziaNoise.minSize.toString())
        if (!amneziaNoiseMaxSizeInput.hasFocus()) amneziaNoiseMaxSizeInput.setText(options.amneziaNoise.maxSize.toString())
        if (noiseFieldsEnabled) amneziaNoiseErrorText.visibility = View.GONE
    }

    private fun saveFrontingIps(reconnectIfChanged: Boolean, previousValue: String?): Boolean {
        val nextValue = FrontingIpPolicy.normalize(frontingIps.joinToString(","))
        frontingIpPreferenceStore.saveFrontingIp(nextValue)
        frontingIps = FrontingIpPolicy.normalizeIps(nextValue)
        renderFrontingIpChips()
        frontingIpErrorText.visibility = View.GONE

        if (previousValue != nextValue) {
            DiagnosticLogger.info(this, "activity.frontingIp.saved", "enabled=${nextValue != null} count=${frontingIps.size}")
            if (reconnectIfChanged && buttonModel.state == VpnState.Started) {
                buttonModel.onStateChanged(VpnState.Starting)
                renderState(VpnState.Starting)
                startVpnService(Actions.RECONNECT)
            }
        }
        return true
    }

    private fun addFrontingIpTokens(tokens: List<String>, focusOnError: Boolean): Boolean {
        val nextTokens = tokens.map { it.trim() }.filter { it.isNotEmpty() }
        if (nextTokens.isEmpty()) return true
        val nextIps = runCatching {
            FrontingIpPolicy.normalizeIps((frontingIps + nextTokens).joinToString(","))
        }.getOrElse { error ->
            showFrontingIpError(localizedError(error, R.string.fronting_invalid), focusOnError)
            return false
        }
        frontingIps = nextIps
        renderFrontingIpChips()
        return true
    }

    private fun removeFrontingIp(ip: String) {
        val previousValue = frontingIpPreferenceStore.readFrontingIp()
        frontingIps = frontingIps.filterNot { it == ip }
        renderFrontingIpChips()
        saveFrontingIps(reconnectIfChanged = true, previousValue = previousValue)
    }

    private fun renderFrontingIpChips() {
        if (!::frontingIpChipGroup.isInitialized) return
        val controlsEnabled = !::frontingIpInput.isInitialized || frontingIpInput.isEnabled
        frontingIpChipGroup.removeAllViews()
        frontingIpChipGroup.visibility = if (frontingIps.isEmpty()) View.GONE else View.VISIBLE
        frontingIps.forEach { ip ->
            frontingIpChipGroup.addView(
                Chip(this).apply {
                    text = ip
                    textSize = 12f
                    layoutDirection = View.LAYOUT_DIRECTION_LTR
                    textDirection = View.TEXT_DIRECTION_LTR
                    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
                    isCheckable = false
                    isCloseIconVisible = true
                    setTextColor(TEXT_PRIMARY)
                    chipBackgroundColor = ColorStateList.valueOf(SURFACE)
                    chipStrokeColor = ColorStateList.valueOf(OUTLINE)
                    chipStrokeWidth = dp(1).toFloat()
                    closeIconTint = ColorStateList.valueOf(TEXT_SECONDARY)
                    isEnabled = controlsEnabled
                    setOnCloseIconClickListener { removeFrontingIp(ip) }
                },
            )
        }
        if (::frontingIpInputLayout.isInitialized) {
            frontingIpInputLayout.helperText = frontingIpInputHint()
        }
    }

    private fun setFrontingIpInputText(value: String) {
        frontingIpInputUpdating = true
        try {
            frontingIpInput.setText(value)
            frontingIpInput.setSelection(frontingIpInput.text.length)
        } finally {
            frontingIpInputUpdating = false
        }
    }

    private fun frontingIpInputHint(): String {
        return if (frontingIps.size >= 5) {
            getString(R.string.fronting_capacity_full)
        } else {
            getString(R.string.fronting_helper)
        }
    }

    private fun showFrontingIpError(message: String, focusOnError: Boolean) {
        frontingIpErrorText.text = message
        frontingIpErrorText.visibility = View.VISIBLE
        if (focusOnError) {
            frontingIpInput.requestFocus()
        }
    }

    private fun renderAdvancedControls() {
        val settingsEnabled = buttonModel.state != VpnState.Starting && buttonModel.state != VpnState.Stopping
        if (::tlsIntegrityCheckbox.isInitialized) {
            tlsIntegrityCheckbox.isChecked = tlsIntegrityPreferenceStore.isEnabled()
            tlsIntegrityCheckbox.isEnabled = settingsEnabled
        }
        renderConnectionOptionsControls(settingsEnabled)
        renderLanSharingControls(settingsEnabled)
        renderRoutingModeSelection()
        if (::routingModeRow.isInitialized) {
            routingModeRow.isEnabled = settingsEnabled
            routingModeRow.alpha = if (settingsEnabled) 1f else 0.45f
        }
        renderDnsPrivacySelection()
    }

    private fun beginConnectFlow(action: String = Actions.CONNECT) {
        connectFlowPending = true
        connectFlowAction = action
        buttonModel.onStateChanged(VpnState.Starting)
        renderState(VpnState.Starting)
        requestNotificationPermissionIfNeeded()
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (!connectFlowPending) {
            DiagnosticLogger.info(this, "permission.notification.skip", "reason=connect-canceled")
            return
        }
        if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            DiagnosticLogger.info(this, "permission.notification", "requesting")
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                REQUEST_NOTIFICATION_PERMISSION,
            )
            return
        }
        DiagnosticLogger.info(this, "permission.notification", "already granted or not required")
        requestVpnPermissionThenConnect()
    }

    private fun requestVpnPermissionThenConnect() {
        if (!connectFlowPending) {
            DiagnosticLogger.info(this, "permission.vpn.skip", "reason=connect-canceled")
            return
        }
        if (!ConnectionModePolicy.shouldStartTun(
                connectionModePreferenceStore.read(),
                alwaysOnMode,
                lockdownMode,
            )
        ) {
            DiagnosticLogger.info(this, "permission.vpn", "skipped mode=proxy")
            startPendingConnectAction()
            return
        }
        val intent = VpnService.prepare(this)
        if (intent != null) {
            DiagnosticLogger.info(this, "permission.vpn", "requesting")
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_VPN_PERMISSION)
        } else {
            DiagnosticLogger.info(this, "permission.vpn", "already granted")
            startPendingConnectAction()
        }
    }

    private fun startPendingConnectAction() {
        val action = connectFlowAction
        connectFlowPending = false
        connectFlowAction = Actions.CONNECT
        startVpnService(action)
    }

    private fun startVpnService(action: String) {
        DiagnosticLogger.info(this, "service.intent", "action=$action")
        val intent = Intent(this, WhiteDnsVpnService::class.java)
            .setAction(action)
            .putExtra(Actions.EXTRA_APP_INITIATED, true)
        if (action == Actions.CONNECT && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun requestActiveConnectionSwitch(subscriptionId: String, fingerprint: String) {
        startService(
            Intent(this, WhiteDnsVpnService::class.java)
                .setAction(Actions.SWITCH_CONNECTION)
                .putExtra(Actions.EXTRA_APP_INITIATED, true)
                .putExtra(Actions.EXTRA_SUBSCRIPTION_ID, subscriptionId)
                .putExtra(Actions.EXTRA_CONNECTION_FINGERPRINT, fingerprint),
        )
    }

    private fun renderAlwaysOnStatus() {
        if (!::alwaysOnStatusText.isInitialized) return
        alwaysOnStatusText.setText(
            when {
                lockdownMode -> R.string.always_on_status_lockdown
                alwaysOnMode -> R.string.always_on_status_active
                else -> R.string.always_on_status_inactive
            },
        )
        alwaysOnStatusText.setTextColor(if (alwaysOnMode) TEAL else TEXT_SECONDARY)
    }

    private fun renderState(state: VpnState) {
        scheduleDisconnectTimeout(state)
        val presentation = DashboardStatePresenter.forState(state)
        val accent = accentFor(presentation.tone)
        connectionOrb.setVpnState(state)
        connectionOrb.isEnabled = buttonModel.isEnabled()
        connectionOrb.contentDescription = getString(buttonModel.labelRes())
        // Update status dot color based on state
        (statusDot.background as? GradientDrawable)?.setColor(
            when (state) {
                VpnState.Started -> TEAL
                VpnState.Starting, VpnState.Stopping -> AMBER
                is VpnState.Error, VpnState.DailyLimitReached -> ERROR
                VpnState.Stopped -> palette.neutral
            }
        )
        statusText.text = getString(presentation.titleRes)
        statusText.setTextColor(TEXT_SECONDARY)
        // Update timer opacity based on connection state
        timerText.alpha = if (state == VpnState.Started) 1f else 0.25f
        connectionCountryText.text = when {
            state == VpnState.Started && connectionCountryFlag.isNotBlank() ->
                getString(R.string.route_location, connectionCountryFlag)
            state == VpnState.Started -> getString(R.string.route_automatic)
            state == VpnState.Starting -> getString(R.string.route_selecting)
            state == VpnState.Stopping -> getString(R.string.route_closing)
            state is VpnState.Error -> getString(R.string.route_unavailable)
            state == VpnState.DailyLimitReached -> getString(R.string.state_daily_limit)
            else -> getString(R.string.route_automatic)
        }
        connectionCountryText.setTextColor(if (state == VpnState.Started) accent else TEXT_SECONDARY)
        renderConnectionDetails(state)
        refreshActionButton.visibility = if (state == VpnState.Started) View.VISIBLE else View.INVISIBLE
        refreshActionButton.isEnabled = state == VpnState.Started
        refreshActionButton.contentDescription = getString(R.string.action_reconnect)
        // Light green background with dark green text
        val lightGreenBg = if (palette.isDark) withAlpha(0x3FBE90, 40) else withAlpha(0x007E50, 30)
        refreshActionButton.backgroundTintList = ColorStateList.valueOf(lightGreenBg)
        refreshActionButton.strokeColor = ColorStateList.valueOf(Color.TRANSPARENT)
        refreshActionButton.rippleColor = ColorStateList.valueOf(withAlpha(TEAL, 50))
        refreshActionButton.setTextColor(ColorStateList.valueOf(TEAL))
        refreshActionButton.iconTint = ColorStateList.valueOf(TEAL)
        val settingsEnabled = state != VpnState.Starting && state != VpnState.Stopping
        locationSelectorRow.isEnabled = settingsEnabled
        connectionSelectorRow.isEnabled = settingsEnabled
        splitTunnelRow.isEnabled = settingsEnabled
        if (::tlsIntegrityCheckbox.isInitialized) tlsIntegrityCheckbox.isEnabled = settingsEnabled
        renderConnectionOptionsControls(settingsEnabled)
        renderLanSharingControls(settingsEnabled)
        if (::routingModeRow.isInitialized) {
            routingModeRow.isEnabled = settingsEnabled
            routingModeRow.alpha = if (settingsEnabled) 1f else 0.45f
        }
        if (::dnsPrivacyRow.isInitialized) {
            dnsPrivacyRow.isEnabled = settingsEnabled
            dnsPrivacyRow.alpha = if (settingsEnabled) 1f else 0.45f
        }
        if (::dnsPrivacyEndpointLayout.isInitialized) {
            dnsPrivacyEndpointLayout.isEnabled = settingsEnabled
        }
        if (::dnsPrivacyEndpointInput.isInitialized) {
            dnsPrivacyEndpointInput.isEnabled = settingsEnabled
        }
        if (::frontingIpInputLayout.isInitialized) {
            frontingIpInputLayout.isEnabled = settingsEnabled
        }
        if (::frontingIpInput.isInitialized) {
            frontingIpInput.isEnabled = settingsEnabled
        }
        if (::frontingIpChipGroup.isInitialized) {
            frontingIpChipGroup.isEnabled = settingsEnabled
            for (index in 0 until frontingIpChipGroup.childCount) {
                frontingIpChipGroup.getChildAt(index).isEnabled = settingsEnabled
            }
        }
        renderLocationSelection()
        renderSplitTunnelSelection()
        if (
            state == VpnState.Stopped ||
            state == VpnState.DailyLimitReached ||
            state is VpnState.Error ||
            (state == VpnState.Started && sessionStartedAtElapsedMs <= 0L)
        ) {
            setTimerText(0L)
        }
    }

    private fun scheduleDisconnectTimeout(state: VpnState) {
        mainHandler.removeCallbacks(disconnectTimeoutRunnable)
        if (state == VpnState.Stopping) {
            mainHandler.postDelayed(disconnectTimeoutRunnable, DISCONNECT_UI_TIMEOUT_MS)
        }
    }

    private fun startTimerUpdates() {
        mainHandler.removeCallbacks(timerRunnable)
        resetTransferSpeeds()
        mainHandler.post(timerRunnable)
    }

    private fun setTimerText(elapsedMs: Long) {
        val isActive = buttonModel.state == VpnState.Started && sessionStartedAtElapsedMs > 0L
        timerText.setTextColor(TEXT_PRIMARY)
        timerText.alpha = if (isActive) 1f else 0.25f
        timerText.text = formatDuration(elapsedMs)
    }

    private fun toggleAppTheme() {
        val currentTheme = appThemePreferenceStore.read()
        val newTheme = if (currentTheme == AppThemeMode.Dark) AppThemeMode.Light else AppThemeMode.Dark
        appThemePreferenceStore.save(newTheme)
        recreate()
    }

    private fun updateTransferSpeeds() {
        val nowElapsedMs = SystemClock.elapsedRealtime()
        val rxBytes = TrafficStats.getUidRxBytes(Process.myUid())
        val txBytes = TrafficStats.getUidTxBytes(Process.myUid())
        val unsupported = TrafficStats.UNSUPPORTED.toLong()
        if (rxBytes == unsupported || txBytes == unsupported) {
            resetTransferSpeeds()
            return
        }

        if (lastTransferSampleElapsedMs > 0L && nowElapsedMs > lastTransferSampleElapsedMs) {
            val elapsedMs = nowElapsedMs - lastTransferSampleElapsedMs
            downloadSpeedText.text =
                formatTransferSpeed(bytesPerSecond(rxBytes, lastTransferRxBytes, elapsedMs))
            uploadSpeedText.text =
                formatTransferSpeed(bytesPerSecond(txBytes, lastTransferTxBytes, elapsedMs))
        } else {
            downloadSpeedText.text = formatTransferSpeed(0L)
            uploadSpeedText.text = formatTransferSpeed(0L)
        }
        lastTransferRxBytes = rxBytes
        lastTransferTxBytes = txBytes
        lastTransferSampleElapsedMs = nowElapsedMs
    }

    private fun resetTransferSpeeds() {
        lastTransferRxBytes = TrafficStats.UNSUPPORTED.toLong()
        lastTransferTxBytes = TrafficStats.UNSUPPORTED.toLong()
        lastTransferSampleElapsedMs = 0L
        if (::downloadSpeedText.isInitialized) {
            downloadSpeedText.text = formatTransferSpeed(0L)
            uploadSpeedText.text = formatTransferSpeed(0L)
        }
    }

    private fun bytesPerSecond(currentBytes: Long, previousBytes: Long, elapsedMs: Long): Long {
        if (elapsedMs <= 0L || previousBytes == TrafficStats.UNSUPPORTED.toLong()) return 0L
        return ((currentBytes - previousBytes).coerceAtLeast(0L).toDouble() * 1_000.0 / elapsedMs).toLong()
    }

    private fun formatDuration(elapsedMs: Long): String {
        val totalSeconds = maxOf(0L, elapsedMs) / 1_000L
        val hours = totalSeconds / 3_600L
        val minutes = (totalSeconds % 3_600L) / 60L
        val seconds = totalSeconds % 60L
        return String.format(Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
    }

    private fun copyDiagnosticsToClipboard() {
        val diagnostics = DiagnosticLogger.read(this).ifBlank { getString(R.string.diagnostics_empty) }
        val clipboard = getSystemService(ClipboardManager::class.java)
        val clip = ClipData.newPlainText("WhiteVPN diagnostics", diagnostics).apply {
            // Keeps the clipboard preview toast from rendering the log on screen (Android 13+).
            description.extras = PersistableBundle().apply {
                putBoolean(ClipDescription.EXTRA_IS_SENSITIVE, true)
            }
        }
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, R.string.diagnostics_copied, Toast.LENGTH_SHORT).show()
        DiagnosticLogger.info(this, "diagnostics.copy", "chars=${diagnostics.length}")
    }

    private fun checkForUpdates() {
        activityScope.launch {
            val release = runCatching { GitHubReleaseClient.latest() }
                .onFailure { DiagnosticLogger.warn(this@MainActivity, "update.check.failed", error = it) }
                .getOrNull()
                ?: return@launch
            if (!AppUpdatePolicy.isNewer(release.version, BuildConfig.VERSION_NAME)) return@launch

            MaterialAlertDialogBuilder(this@MainActivity)
                .setTitle(R.string.update_available_title)
                .setMessage(
                    getString(
                        R.string.update_available_message,
                        release.version.removePrefix("v"),
                    ),
                )
                .setNegativeButton(R.string.update_later, null)
                .setPositiveButton(R.string.update_view_release) { _, _ -> openExternalUrl(release.url) }
                .create()
                .showWhiteDnsDialog()
        }
    }

    private fun openFooterLink() = openExternalUrl(getString(R.string.footer_url))

    private fun openExternalUrl(url: String) {
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure { error ->
            Toast.makeText(this, url, Toast.LENGTH_SHORT).show()
            DiagnosticLogger.warn(this, "external.open.failed", "url=$url", error)
        }
    }

    @Suppress("DEPRECATION")
    private fun configureSystemBars() {
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_LOCALE
        window.decorView.textDirection = View.TEXT_DIRECTION_LOCALE
        window.statusBarColor = BACKGROUND
        window.navigationBarColor = SURFACE
        var flags = window.decorView.systemUiVisibility
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            flags = if (palette.isDark) {
                flags and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            } else {
                flags or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            flags = if (palette.isDark) {
                flags and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            } else {
                flags or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
        }
        window.decorView.systemUiVisibility = flags
    }

    private fun glassSurfaceDrawable(radiusDp: Int, highlighted: Boolean = false): GradientDrawable {
        return GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(if (highlighted) palette.surfaceElevated2 else palette.surface)
            if (highlighted) setStroke(dp(1), withAlpha(TEAL, 170))
        }
    }

    private fun AlertDialog.showWhiteDnsDialog(
        positiveColor: Int = TEAL,
        onShow: AlertDialog.() -> Unit = {},
    ) {
        setOnShowListener {
            styleWhiteDnsDialog(positiveColor)
            onShow(this)
        }
        show()
    }

    private fun AlertDialog.styleWhiteDnsDialog(positiveColor: Int) {
        window?.setBackgroundDrawable(glassSurfaceDrawable(radiusDp = 24))
        findViewById<TextView>(androidx.appcompat.R.id.alertTitle)?.setTextColor(TEXT_PRIMARY)
        val optionColors = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(TEAL, TEXT_PRIMARY),
        )
        listView?.let { options ->
            repeat(options.childCount) { index ->
                (options.getChildAt(index) as? TextView)?.apply {
                    setTextColor(optionColors)
                    typeface = WhiteDnsBodyTypeface
                }
            }
        }
        getButton(AlertDialog.BUTTON_POSITIVE)?.apply {
            isAllCaps = false
            typeface = WhiteDnsBodyBoldTypeface
            setTextColor(positiveColor)
        }
        listOf(AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL).forEach { buttonId ->
            getButton(buttonId)?.apply {
                isAllCaps = false
                typeface = WhiteDnsBodyBoldTypeface
                setTextColor(TEXT_SECONDARY)
            }
        }
    }

    private fun whiteDnsPopupMenu(anchor: View): PopupMenu =
        PopupMenu(ContextThemeWrapper(this, R.style.WhiteDnsPopupTheme), anchor)

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or (alpha.coerceIn(0, 255) shl 24)

    private fun View.setSelectableBackground() {
        val value = TypedValue()
        if (theme.resolveAttribute(android.R.attr.selectableItemBackground, value, true)) {
            setBackgroundResource(value.resourceId)
        }
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun accentFor(tone: DashboardTone): Int = when (tone) {
        DashboardTone.Connected -> TEAL
        DashboardTone.Progress -> AMBER
        DashboardTone.Error -> ERROR
        DashboardTone.Neutral -> palette.neutral
    }

    private companion object {
        const val REQUEST_VPN_PERMISSION = 10
        const val REQUEST_NOTIFICATION_PERMISSION = 11
        const val TIMER_TICK_MS = 1_000L
        const val DISCONNECT_UI_TIMEOUT_MS = 7_000L
        const val KEYBOARD_SCROLL_DELAY_MS = 250L
        const val HOME_MENU_THEME_ID = 100
        const val HOME_MENU_LANGUAGE_ID = 101
    }
}
