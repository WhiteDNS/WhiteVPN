package com.whitedns.vpn

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.TrafficStats
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.materialswitch.MaterialSwitch
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

/* Hallmark · macrostructure: Workbench · genre: modern-minimal · design-system: design.md */
/* Hallmark · pre-emit critique: P5 H5 E5 S5 R5 V4 · contrast: pass (40–41) · slop: pass */
class MainActivity : Activity() {
    private val palette: WhiteDnsPalette by lazy { WhiteDnsDesignTokens.forContext(this) }
    private val buttonModel = ConnectButtonModel()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var privacyPolicyStore: PrivacyPolicyAcceptanceStore
    private lateinit var locationPreferenceStore: ConnectionLocationPreferenceStore
    private lateinit var splitTunnelPreferenceStore: SplitTunnelPreferenceStore
    private lateinit var frontingIpPreferenceStore: FrontingIpPreferenceStore
    private lateinit var dnsPrivacyPreferenceStore: DnsPrivacyPreferenceStore
    private lateinit var tlsIntegrityPreferenceStore: TlsIntegrityPreferenceStore
    private lateinit var installedAppRepository: InstalledAppRepository
    private lateinit var userSubscriptionManager: UserSubscriptionManager
    private var privacyPolicyDialog: AlertDialog? = null
    private var sessionStartedAtElapsedMs: Long = 0L
    private var connectFlowPending: Boolean = false
    private var disconnectAnalyticsPending: Boolean = false
    private var locationOptions: List<LocationSelectorOption> = listOf(LocationSelectorOption.AUTO)

    private lateinit var signalArc: SignalArcView
    private lateinit var statusText: TextView
    private lateinit var timerText: TextView
    private lateinit var downloadSpeedText: TextView
    private lateinit var uploadSpeedText: TextView
    private lateinit var connectionCountryText: TextView
    private lateinit var locationSelectorRow: DashboardDataRowView
    private lateinit var splitTunnelRow: DashboardDataRowView
    private lateinit var advancedBody: LinearLayout
    private lateinit var advancedToggleText: TextView
    private lateinit var tlsIntegrityCheckbox: MaterialSwitch
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
    private var connectionCountryFlag: String = ""
    private var debugFrontingIp: String = ""
    private var advancedExpanded: Boolean = false
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
        val farsi = Locale.forLanguageTag("fa")
        val configuration = Configuration(newBase.resources.configuration).apply {
            setLocale(farsi)
            setLayoutDirection(farsi)
        }
        super.attachBaseContext(newBase.createConfigurationContext(configuration))
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
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) {
            AnalyticsEvents.appOpened(this)
        }
        privacyPolicyStore = PrivacyPolicyAcceptanceStore(this)
        locationPreferenceStore = ConnectionLocationPreferenceStore(this)
        splitTunnelPreferenceStore = SplitTunnelPreferenceStore(this)
        frontingIpPreferenceStore = FrontingIpPreferenceStore(this)
        dnsPrivacyPreferenceStore = DnsPrivacyPreferenceStore(this)
        tlsIntegrityPreferenceStore = TlsIntegrityPreferenceStore(this)
        installedAppRepository = InstalledAppRepository(this)
        userSubscriptionManager = UserSubscriptionManager(this)
        DiagnosticLogger.info(this, "activity.onCreate")
        configureSystemBars()
        setContentView(buildAppShell())
        renderState(VpnState.Stopped)
        refreshLocationOptions()
        mainHandler.post {
            showPrivacyPolicyIfNeeded()
        }
    }

    private fun buildAppShell(): View {
        val root = FrameLayout(this).apply {
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setBackgroundColor(BACKGROUND)
        }

        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        vpnTabContent = buildDashboard()
        subscriptionsTabContent = buildSubscriptionsScreen().apply { visibility = View.GONE }
        val content = FrameLayout(this).apply {
            addView(vpnTabContent, FrameLayout.LayoutParams(-1, -1))
            addView(subscriptionsTabContent, FrameLayout.LayoutParams(-1, -1))
        }
        shell.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))

        val tabs = TabLayout(this).apply {
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            minimumHeight = dp(60)
            setBackgroundColor(SURFACE)
            elevation = 0f
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
            addTab(newTab().setText("وی‌پی‌ان").setIcon(R.drawable.ic_vpn_tab), true)
            addTab(newTab().setText("سابسکریپشن").setIcon(R.drawable.ic_subscriptions_tab))
            addOnTabSelectedListener(object : TabLayout.OnTabSelectedListener {
                override fun onTabSelected(tab: TabLayout.Tab) {
                    showAppTab(showSubscriptions = tab.position == 1)
                }

                override fun onTabUnselected(tab: TabLayout.Tab) = Unit

                override fun onTabReselected(tab: TabLayout.Tab) = Unit
            })
        }
        val tabsHost = FrameLayout(this).apply {
            setBackgroundColor(SURFACE)
            setPadding(0, dp(1), 0, dp(8))
            addView(
                View(this@MainActivity).apply { setBackgroundColor(OUTLINE) },
                FrameLayout.LayoutParams(-1, dp(1), Gravity.TOP),
            )
            addView(tabs, FrameLayout.LayoutParams(-1, dp(60)).apply { topMargin = dp(1) })
        }
        ViewCompat.setOnApplyWindowInsetsListener(tabsHost) { view, insets ->
            val navigationBottom = insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            view.setPadding(0, dp(1), 0, navigationBottom + dp(8))
            insets
        }
        shell.addView(tabsHost, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(shell, FrameLayout.LayoutParams(-1, -1))
        return root
    }

    private fun showAppTab(showSubscriptions: Boolean) {
        vpnTabContent.visibility = if (showSubscriptions) View.GONE else View.VISIBLE
        subscriptionsTabContent.visibility = if (showSubscriptions) View.VISIBLE else View.GONE
        if (showSubscriptions) renderSubscriptions()
    }

    private fun buildSubscriptionsScreen(): View {
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            setBackgroundColor(withAlpha(BACKGROUND, 0))
        }
        val content = MaxWidthLinearLayout(this).apply {
            maxWidthPx = dp(520)
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(34), dp(24), dp(40))
        }
        val subscriptionsHeaderCopy = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            addView(TextView(this@MainActivity).apply {
                text = "سابسکریپشن"
                textSize = 32f
                typeface = WhiteDnsDisplayTypeface
                setTextColor(TEXT_PRIMARY)
                includeFontPadding = false
                gravity = Gravity.START
            })
            addView(
                TextView(this@MainActivity).apply {
                    text = "منبع اتصال وی‌پی‌ان را انتخاب کنید."
                    textSize = 13f
                    typeface = WhiteDnsBodyTypeface
                    setTextColor(TEXT_SECONDARY)
                    includeFontPadding = false
                    gravity = Gravity.START
                },
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) },
            )
        }
        val addSubscriptionButton = MaterialButton(this).apply {
            text = "+ افزودن"
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            textDirection = View.TEXT_DIRECTION_RTL
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
        content.addView(
            LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                gravity = Gravity.CENTER_VERTICAL
                addView(addSubscriptionButton, LinearLayout.LayoutParams(dp(84), dp(44)))
                addView(
                    subscriptionsHeaderCopy,
                    LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(16) },
                )
            },
        )
        subscriptionsList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glassSurfaceDrawable(radiusDp = 12)
            clipToOutline = true
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
                title = "WhiteDNS VPN",
                detail = "داخلی · ${connectionCountLabel(whiteDnsCount)}",
                selected = selectedId == SubscriptionStore.DEFAULT_SUBSCRIPTION_ID,
                error = "",
                actions = listOf(
                    "انتخاب" to {
                        userSubscriptionManager.select(SubscriptionStore.DEFAULT_SUBSCRIPTION_ID)
                        onSubscriptionSelected()
                    },
                    "تازه‌سازی" to { refreshDefaultSubscription() },
                ),
            ),
        )
        userSubscriptionManager.list().forEach { item ->
            val updated = item.updatedAt.takeIf { it > 0 }?.let {
                DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(it)
            } ?: "هرگز"
            subscriptionsList.addView(subscriptionDivider())
            subscriptionsList.addView(
                subscriptionCard(
                    title = item.name,
                    detail = "${item.format.label} · ${connectionCountLabel(item.connectionCount)} · $updated",
                    selected = selectedId == item.id,
                    error = item.lastError,
                    actions = listOf(
                        "انتخاب" to {
                            userSubscriptionManager.select(item.id)
                            onSubscriptionSelected()
                        },
                        "تست" to { testSubscription(item) },
                        "تازه‌سازی" to { refreshSubscription(item) },
                        "حذف" to { confirmDeleteSubscription(item) },
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
        actions: List<Pair<String, () -> Unit>>,
    ): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        setPadding(dp(16), dp(16), dp(16), dp(16))
        elevation = 0f
        addView(
            LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                gravity = Gravity.CENTER_VERTICAL
                if (selected) addView(
                    LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        layoutDirection = View.LAYOUT_DIRECTION_LTR
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
                            text = "فعال"
                            textSize = 10f
                            typeface = WhiteDnsBodyBoldTypeface
                            setTextColor(TEAL)
                            includeFontPadding = false
                            layoutDirection = View.LAYOUT_DIRECTION_RTL
                            textDirection = View.TEXT_DIRECTION_RTL
                        })
                    },
                    LinearLayout.LayoutParams(-2, -2).apply { marginEnd = dp(16) },
                )
                addView(
                    LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutDirection = View.LAYOUT_DIRECTION_RTL
                        addView(TextView(this@MainActivity).apply {
                            text = title
                            textSize = 16f
                            typeface = WhiteDnsBodyBoldTypeface
                            setTextColor(TEXT_PRIMARY)
                            includeFontPadding = false
                            layoutDirection = View.LAYOUT_DIRECTION_RTL
                            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
                            gravity = Gravity.RIGHT
                        }, LinearLayout.LayoutParams(-1, -2))
                        addView(TextView(this@MainActivity).apply {
                            text = detail
                            textSize = 11f
                            typeface = WhiteDnsBodyTypeface
                            setTextColor(TEXT_SECONDARY)
                            includeFontPadding = false
                            maxLines = 2
                            layoutDirection = View.LAYOUT_DIRECTION_RTL
                            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
                            gravity = Gravity.RIGHT
                        }, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(4) })
                    },
                    LinearLayout.LayoutParams(0, -2, 1f),
                )
            },
        )
        if (error.isNotBlank()) addView(TextView(this@MainActivity).apply {
            text = "خطا  /  $error"
            textSize = 11f
            typeface = WhiteDnsBodyBoldTypeface
            setTextColor(ERROR)
            setPadding(0, dp(8), 0, 0)
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            gravity = Gravity.START
        }, LinearLayout.LayoutParams(-1, -2))
        addView(
            LinearLayout(this@MainActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                layoutDirection = View.LAYOUT_DIRECTION_LTR
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                val selectAction = actions.firstOrNull { it.first == "انتخاب" }
                val overflowActions = actions.filterNot { it.first == "انتخاب" }
                val showSelect = selectAction != null && !selected
                if (showSelect) selectAction?.let { (_, action) ->
                    addView(subscriptionActionButton("انتخاب", !selected) { action() }, actionButtonParams())
                }
                if (overflowActions.isNotEmpty()) {
                    addView(
                        subscriptionActionButton("مدیریت", true) { view ->
                            PopupMenu(this@MainActivity, view).apply {
                                overflowActions.forEachIndexed { index, (label, _) ->
                                    menu.add(0, index, index, label)
                                }
                                setOnMenuItemClickListener { item ->
                                    overflowActions[item.itemId].second()
                                    true
                                }
                            }.show()
                        },
                        actionButtonParams(hasLeadingButton = showSelect),
                    )
                }
            },
            LinearLayout.LayoutParams(-1, dp(44)).apply { topMargin = dp(12) },
        )
    }

    private fun subscriptionActionButton(
        label: String,
        enabled: Boolean,
        action: (View) -> Unit,
    ): MaterialButton = MaterialButton(this).apply {
        text = if (label == "مدیریت") "مدیریت  ▾" else label
        layoutDirection = View.LAYOUT_DIRECTION_RTL
        textDirection = View.TEXT_DIRECTION_RTL
        setAllCaps(false)
        textSize = 11f
        typeface = WhiteDnsBodyBoldTypeface
        minWidth = 0
        insetTop = 0
        insetBottom = 0
        cornerRadius = dp(8)
        val primary = label == "انتخاب"
        backgroundTintList = ColorStateList.valueOf(
            if (primary) TEAL else SURFACE,
        )
        strokeWidth = if (primary) 0 else dp(1)
        strokeColor = ColorStateList.valueOf(if (primary) TEAL else OUTLINE)
        rippleColor = ColorStateList.valueOf(withAlpha(TEAL, 28))
        setTextColor(if (primary) palette.onAccent else TEXT_PRIMARY)
        isEnabled = enabled
        setOnClickListener(action)
    }

    private fun actionButtonParams(hasLeadingButton: Boolean = false): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)).apply {
            if (hasLeadingButton) marginStart = dp(8)
        }

    private fun showAddSubscriptionDialog() {
        val nameInput = TextInputEditText(this).apply {
            setSingleLine(true)
            background = null
            setPaddingRelative(dp(16), dp(12), dp(16), dp(12))
            setTextColor(TEXT_PRIMARY)
            setHintTextColor(TEXT_SECONDARY)
        }
        val nameLayout = TextInputLayout(this).apply {
            hint = "نام سابسکریپشن"
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
        }
        val sourceLayout = TextInputLayout(this).apply {
            hint = "URL، YAML، JSON یا لینک‌های پروکسی"
            helperText = "پشتیبانی از Clash/Xray JSON و vless، vmess، trojan، ss"
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
        val dialog = AlertDialog.Builder(this)
            .setTitle("افزودن سابسکریپشن")
            .setView(body)
            .setNegativeButton("لغو", null)
            .setPositiveButton("افزودن", null)
            .create()
        dialog.setOnShowListener {
            dialog.window?.setBackgroundDrawable(glassSurfaceDrawable(radiusDp = 24))
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setTextColor(TEAL)
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setTextColor(TEXT_SECONDARY)
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val name = nameInput.text.toString()
                val source = sourceInput.text.toString()
                if (name.isBlank() || source.isBlank()) {
                    Toast.makeText(this, "نام و سابسکریپشن الزامی است", Toast.LENGTH_SHORT).show()
                    return@setOnClickListener
                }
                dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
                activityScope.launch {
                    val result = runCatching { withContext(Dispatchers.IO) { userSubscriptionManager.add(name, source) } }
                    result.onSuccess { item ->
                        dialog.dismiss()
                        renderSubscriptions()
                        Toast.makeText(this@MainActivity, "${connectionCountLabel(item.connectionCount)} اضافه شد", Toast.LENGTH_SHORT).show()
                    }.onFailure { error ->
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
                        Toast.makeText(this@MainActivity, error.message ?: "افزودن سابسکریپشن ممکن نشد", Toast.LENGTH_LONG).show()
                    }
                }
            }
        }
        dialog.show()
    }

    private fun testSubscription(item: UserSubscription) {
        runSubscriptionAction("در حال تست ${item.name}…") {
            val imported = userSubscriptionManager.test(item.input)
            "${connectionCountLabel(imported.connectionCount)} پیدا شد"
        }
    }

    private fun refreshSubscription(item: UserSubscription) {
        runSubscriptionAction("در حال تازه‌سازی ${item.name}…") {
            val refreshed = userSubscriptionManager.refresh(item.id)
            "${connectionCountLabel(refreshed.connectionCount)} تازه‌سازی شد"
        }
    }

    private fun refreshDefaultSubscription() {
        runSubscriptionAction("در حال تازه‌سازی WhiteDNS…") {
            val refreshed = ConfigRepository(this@MainActivity).refreshDefaultMihomoConfig()
            "${connectionCountLabel(refreshed.catalog.profiles.size)} تازه‌سازی شد"
        }
    }

    private fun runSubscriptionAction(startMessage: String, action: suspend () -> String) {
        Toast.makeText(this, startMessage, Toast.LENGTH_SHORT).show()
        activityScope.launch {
            val result = runCatching { withContext(Dispatchers.IO) { action() } }
            renderSubscriptions()
            result.onSuccess { message -> Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show() }
                .onFailure { error -> Toast.makeText(this@MainActivity, error.message ?: "عملیات سابسکریپشن ناموفق بود", Toast.LENGTH_LONG).show() }
        }
    }

    private fun confirmDeleteSubscription(item: UserSubscription) {
        AlertDialog.Builder(this)
            .setTitle("سابسکریپشن «${item.name}» حذف شود؟")
            .setMessage("سابسکریپشن ذخیره‌شده و تنظیمات کش‌شدهٔ آن حذف می‌شود.")
            .setNegativeButton("لغو", null)
            .setPositiveButton("حذف") { _, _ ->
                userSubscriptionManager.delete(item.id)
                renderSubscriptions()
                refreshLocationOptions()
            }
            .show()
    }

    private fun onSubscriptionSelected() {
        locationPreferenceStore.clearSelectedCountry()
        renderSubscriptions()
        refreshLocationOptions()
        val suffix = if (buttonModel.state == VpnState.Started) " برای اعمال، دوباره متصل شوید." else ""
        Toast.makeText(this, "سابسکریپشن انتخاب شد.$suffix", Toast.LENGTH_LONG).show()
    }

    private fun connectionCountLabel(count: Int): String = "$count اتصال"

    override fun onStart() {
        super.onStart()
        DiagnosticLogger.info(this, "activity.onStart")
        val filter = IntentFilter().apply {
            addAction(Actions.STATE_CHANGED)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stateReceiver, filter, RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(stateReceiver, filter)
        }
        applyRuntimeState(
            VpnRuntimeStateStore.read(this),
            VpnRuntimeStateStore.readSessionStartedAtElapsedMs(this),
            VpnRuntimeStateStore.readConnectionCountryFlag(this),
            VpnRuntimeStateStore.readDebugFrontingIp(this)
                .takeIf { it in frontingIpPreferenceStore.readFrontingIps() }
                .orEmpty(),
        )
    }

    override fun onStop() {
        DiagnosticLogger.info(this, "activity.onStop")
        mainHandler.removeCallbacks(timerRunnable)
        mainHandler.removeCallbacks(disconnectTimeoutRunnable)
        privacyPolicyDialog?.dismiss()
        privacyPolicyDialog = null
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
        connectFlowPending = false
        if (resultCode == RESULT_OK) {
            DiagnosticLogger.info(this, "permission.vpn", "granted")
            startVpnService(Actions.CONNECT)
        } else {
            DiagnosticLogger.warn(this, "permission.vpn", "denied resultCode=$resultCode")
            AnalyticsEvents.connectionTryFailed(this)
            buttonModel.onStateChanged(VpnState.Stopped)
            renderState(VpnState.Stopped)
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
        }
        ViewCompat.setOnApplyWindowInsetsListener(scrollView) { view, insets ->
            val bottomInset = insets.getInsets(
                WindowInsetsCompat.Type.ime() or WindowInsetsCompat.Type.navigationBars(),
            ).bottom
            view.setPadding(view.paddingLeft, view.paddingTop, view.paddingRight, bottomInset)
            view.post {
                view.findFocus()?.let { focusedView ->
                    scrollFieldIntoView(scrollView, focusedView, delayMs = 0L)
                }
            }
            insets
        }
        val viewport = FrameLayout(this).apply {
            setPadding(0, 0, 0, dp(24))
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

        val headerBlock = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            gravity = Gravity.BOTTOM
            addView(
                TextView(this@MainActivity).apply {
                    text = "نسخه  ${BuildConfig.VERSION_NAME}"
                    textSize = 9f
                    typeface = WhiteDnsBodyBoldTypeface
                    setTextColor(TEXT_SECONDARY)
                    includeFontPadding = false
                    layoutDirection = View.LAYOUT_DIRECTION_RTL
                    textDirection = View.TEXT_DIRECTION_FIRST_STRONG
                },
                LinearLayout.LayoutParams(-2, -2).apply { bottomMargin = dp(4) },
            )
            addView(
                TextView(this@MainActivity).apply {
                    text = "WhiteDNS"
                    textSize = 36f
                    typeface = WhiteDnsDisplayTypeface
                    setTextColor(TEXT_PRIMARY)
                    includeFontPadding = false
                    gravity = Gravity.END
                    layoutDirection = View.LAYOUT_DIRECTION_LTR
                    textDirection = View.TEXT_DIRECTION_LTR
                },
                LinearLayout.LayoutParams(0, -2, 1f).apply { marginStart = dp(16) },
            )
        }

        signalArc = SignalArcView(this).apply {
            isClickable = true
            isFocusable = true
            setOnClickListener { handleButtonClick() }
            setOnLongClickListener {
                copyDiagnosticsToClipboard()
                true
            }
        }
        statusText = TextView(this).apply {
            gravity = Gravity.RIGHT
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            textDirection = View.TEXT_DIRECTION_RTL
            textSize = 13f
            typeface = WhiteDnsBodyBoldTypeface
            includeFontPadding = false
        }
        timerText = TextView(this).apply {
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            textDirection = View.TEXT_DIRECTION_LTR
            text = "00:00:00"
            textSize = 17f
            typeface = WhiteDnsDataTypeface
            setTextColor(TIMER_MUTED)
            includeFontPadding = false
        }
        downloadSpeedText = TextView(this).apply {
            text = "0 B/s"
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            textDirection = View.TEXT_DIRECTION_LTR
            textSize = 12f
            typeface = WhiteDnsDataTypeface
            setTextColor(TEXT_PRIMARY)
            includeFontPadding = false
        }
        uploadSpeedText = TextView(this).apply {
            text = "0 B/s"
            gravity = Gravity.CENTER
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            textDirection = View.TEXT_DIRECTION_LTR
            textSize = 12f
            typeface = WhiteDnsDataTypeface
            setTextColor(TEXT_PRIMARY)
            includeFontPadding = false
        }
        connectionCountryText = TextView(this).apply {
            text = "🌐  خروجی خودکار"
            gravity = Gravity.LEFT
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            textSize = 11f
            typeface = WhiteDnsBodyBoldTypeface
            setTextColor(TEXT_SECONDARY)
            includeFontPadding = false
        }

        fun metricColumn(label: String, value: TextView): View = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            addView(TextView(this@MainActivity).apply {
                text = label
                textSize = 9f
                letterSpacing = 0f
                typeface = WhiteDnsDataTypeface
                setTextColor(TEXT_SECONDARY)
                includeFontPadding = false
                gravity = Gravity.CENTER
            })
            addView(
                value,
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) },
            )
        }
        val metricDivider = { View(this).apply { setBackgroundColor(withAlpha(OUTLINE, 180)) } }
        val metricsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(76)
            setPadding(dp(8), dp(12), dp(8), dp(12))
            background = glassSurfaceDrawable(radiusDp = 12)
            addView(metricColumn("زمان اتصال", timerText), LinearLayout.LayoutParams(0, -2, 1.25f))
            addView(metricDivider(), LinearLayout.LayoutParams(dp(1), dp(34)))
            addView(metricColumn("دریافت", downloadSpeedText), LinearLayout.LayoutParams(0, -2, 1f))
            addView(metricDivider(), LinearLayout.LayoutParams(dp(1), dp(34)))
            addView(metricColumn("ارسال", uploadSpeedText), LinearLayout.LayoutParams(0, -2, 1f))
        }

        val signalSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutDirection = View.LAYOUT_DIRECTION_LTR
                    gravity = Gravity.CENTER_VERTICAL
                    addView(connectionCountryText, LinearLayout.LayoutParams(-2, -2))
                    addView(statusText, LinearLayout.LayoutParams(0, -2, 1f).apply {
                        marginStart = dp(12)
                    })
                },
                LinearLayout.LayoutParams(-1, -2),
            )
            addView(
                signalArc,
                LinearLayout.LayoutParams(-1, dp(220)).apply { topMargin = dp(12) },
            )
            addView(
                metricsRow,
                LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(12) },
            )
        }

        locationSelectorRow = DashboardDataRowView(this).apply {
            setRow("موقعیت", LocationSelectorOption.AUTO.label)
            setOnRowClickListener { showLocationSelector() }
        }
        splitTunnelRow = DashboardDataRowView(this).apply {
            setRow("تقسیم تونل", "غیرفعال")
            setOnRowClickListener { showSplitTunnelSelector() }
        }
        val dataRowsList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glassSurfaceDrawable(radiusDp = 12)
            clipToOutline = true
            addView(locationSelectorRow, LinearLayout.LayoutParams(-1, -2))
            addView(View(this@MainActivity).apply { setBackgroundColor(OUTLINE) },
                LinearLayout.LayoutParams(-1, dp(1)).apply {
                    marginStart = dp(16)
                    marginEnd = dp(16)
                })
            addView(splitTunnelRow, LinearLayout.LayoutParams(-1, -2))
        }
        val dataRows = dataRowsList
        val advancedSection = buildAdvancedSection(scrollView)

        refreshActionButton = MaterialButton(this).apply {
            text = "اتصال مجدد"
            textSize = 12f
            typeface = WhiteDnsBodyBoldTypeface
            minHeight = dp(44)
            minimumHeight = dp(44)
            insetTop = 0
            insetBottom = 0
            cornerRadius = dp(8)
            strokeWidth = dp(1)
            strokeColor = ColorStateList.valueOf(OUTLINE)
            elevation = 0f
            stateListAnimator = null
            setAllCaps(false)
            setIconResource(R.drawable.ic_refresh)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            iconPadding = dp(8)
            visibility = View.GONE
            setOnClickListener { handleRefreshClick() }
        }

        val footerText = TextView(this).apply {
            gravity = Gravity.START or Gravity.CENTER_VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            text = getString(R.string.footer_copyright)
            textSize = 9f
            typeface = WhiteDnsBodyTypeface
            setTextColor(TEXT_SECONDARY)
            includeFontPadding = false
            paint.isUnderlineText = false
            isClickable = true
            isFocusable = true
            contentDescription = getString(R.string.footer_copyright)
            setOnClickListener { openFooterLink() }
        }

        dashboardContent.apply {
            addView(
                headerBlock,
                contentParams(dp(32)),
            )
            addView(
                signalSection,
                contentParams(dp(24)),
            )
            addView(
                dataRows,
                contentParams(dp(24)),
            )
            addView(
                advancedSection,
                contentParams(dp(12)),
            )
            addView(
                refreshActionButton,
                contentParams(dp(20)).apply {
                    height = dp(44)
                },
            )
            addView(
                footerText,
                contentParams(dp(24)).apply { height = dp(44) },
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

    private fun buildAdvancedSection(scrollView: ScrollView): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = glassSurfaceDrawable(radiusDp = 12)
            clipToOutline = true
            elevation = 0f
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setSelectableBackground()
            isClickable = true
            isFocusable = true
            setOnClickListener {
                advancedExpanded = !advancedExpanded
                renderAdvancedSection()
            }
        }
        val headerText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            addView(
                TextView(this@MainActivity).apply {
                    text = "پیشرفته"
                    textSize = 19f
                    typeface = WhiteDnsDisplayTypeface
                    setTextColor(TEXT_PRIMARY)
                    includeFontPadding = false
                },
            )
            addView(
                TextView(this@MainActivity).apply {
                    text = "کنترل‌های اتصال و حریم خصوصی"
                    textSize = 12f
                    typeface = WhiteDnsBodyTypeface
                    setTextColor(TEXT_SECONDARY)
                    includeFontPadding = false
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = dp(4)
                },
            )
        }
        advancedToggleText = TextView(this).apply {
            textSize = 11f
            typeface = WhiteDnsBodyBoldTypeface
            setTextColor(TEAL)
            includeFontPadding = false
            gravity = Gravity.START
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            textDirection = View.TEXT_DIRECTION_RTL
        }
        header.addView(
            advancedToggleText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { marginEnd = dp(16) },
        )
        header.addView(
            headerText,
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )

        advancedBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            setPadding(dp(16), dp(4), dp(16), dp(20))
        }
        advancedBody.addView(advancedSectionLabel("امنیت اتصال"))
        val safetyPanel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        tlsIntegrityCheckbox = MaterialSwitch(this).apply {
            isChecked = tlsIntegrityPreferenceStore.isEnabled()
            contentDescription = "بررسی یکپارچگی TLS"
            setOnClickListener { saveTlsIntegrityEnabled(isChecked) }
        }
        safetyPanel.addView(
            advancedToggleRow(
                title = "یکپارچگی TLS",
                detail = "سایت‌های امن را بررسی و مسیرهای ناامن را مسدود می‌کند",
                toggle = tlsIntegrityCheckbox,
            ),
        )
        advancedBody.addView(
            safetyPanel,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(12)
            },
        )
        advancedBody.addView(
            advancedSectionLabel("جایگذاری IP / IP Fronting"),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(24)
            },
        )
        advancedBody.addView(
            advancedSectionDetail("مقصد پروکسی را با آدرس‌های IP[:port] جایگزین کنید."),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(4)
            },
        )
        frontingIps = frontingIpPreferenceStore.readFrontingIps()
        frontingIpChipGroup = ChipGroup(this).apply {
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            isSingleLine = false
            chipSpacingHorizontal = dp(8)
            chipSpacingVertical = dp(4)
        }
        advancedBody.addView(
            frontingIpChipGroup,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
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
            hint = "آدرس‌های IP Fronting"
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
        advancedBody.addView(
            frontingIpInputLayout,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(12)
            },
        )
        frontingIpErrorText = TextView(this).apply {
            textSize = 12f
            setTextColor(ERROR)
            includeFontPadding = true
            visibility = View.GONE
        }
        advancedBody.addView(
            frontingIpErrorText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        advancedBody.addView(
            advancedSectionLabel("حریم خصوصی DNS"),
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(24)
            },
        )
        dnsPrivacyDetailText = TextView(this).apply {
            textSize = 11f
            typeface = WhiteDnsBodyTypeface
            setTextColor(TEXT_SECONDARY)
            includeFontPadding = false
        }
        val dnsText = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            addView(
                TextView(this@MainActivity).apply {
                    text = "DNS رمزنگاری‌شده"
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
            textSize = 13f
            typeface = WhiteDnsBodyBoldTypeface
            setTextColor(TEAL)
            includeFontPadding = false
            gravity = Gravity.START
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            textDirection = View.TEXT_DIRECTION_RTL
        }
        dnsPrivacyRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            setSelectableBackground()
            isClickable = true
            isFocusable = true
            setOnClickListener { showDnsPrivacySelector() }
            addView(
                TextView(this@MainActivity).apply {
                    text = "‹"
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
                ),
            )
            addView(
                dnsPrivacyValueText,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginStart = dp(8) },
            )
            addView(
                dnsText,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginStart = dp(16)
                },
            )
        }
        advancedBody.addView(
            dnsPrivacyRow,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(12)
            },
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
            hint = "آدرس سرور DNS"
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
        advancedBody.addView(
            dnsPrivacyEndpointLayout,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(12)
            },
        )
        dnsPrivacyErrorText = TextView(this).apply {
            textSize = 12f
            setTextColor(ERROR)
            includeFontPadding = true
            visibility = View.GONE
        }
        advancedBody.addView(
            dnsPrivacyErrorText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        root.addView(
            header,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        root.addView(
            advancedBody,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        renderAdvancedSection()
        return root
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
            textSize = 11f
            letterSpacing = 0f
            typeface = WhiteDnsBodyBoldTypeface
            setTextColor(TEXT_SECONDARY)
            includeFontPadding = false
            layoutDirection = View.LAYOUT_DIRECTION_RTL
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
            layoutDirection = View.LAYOUT_DIRECTION_RTL
            textDirection = View.TEXT_DIRECTION_FIRST_STRONG
            gravity = Gravity.START
        }
    }

    private fun advancedDivider(): View {
        return View(this).apply {
            setBackgroundColor(OUTLINE)
            alpha = 0.65f
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(1),
            ).apply {
                marginStart = dp(16)
                marginEnd = dp(16)
            }
        }
    }

    private fun advancedToggleRow(
        title: String,
        detail: String,
        toggle: MaterialSwitch,
    ): View {
        val textColumn = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutDirection = View.LAYOUT_DIRECTION_RTL
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
            layoutDirection = View.LAYOUT_DIRECTION_LTR
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
                toggle,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply { marginEnd = dp(12) },
            )
            addView(
                textColumn,
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
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

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.privacy_policy_title)
            .setView(scrollView)
            .setNegativeButton(R.string.privacy_policy_not_now, null)
            .setPositiveButton(R.string.privacy_policy_accept, null)
            .create()

        dialog.setOnShowListener {
            val acceptButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
            acceptButton.isEnabled = false
            checkbox.setOnCheckedChangeListener { _, isChecked ->
                acceptButton.isEnabled = isChecked
            }
            acceptButton.setOnClickListener {
                privacyPolicyStore.acceptCurrentVersion()
                DiagnosticLogger.info(this, "privacy.accepted", "version=${PrivacyPolicyAcceptancePolicy.CURRENT_VERSION}")
                dialog.dismiss()
                onAccepted?.invoke()
            }
        }
        dialog.setOnDismissListener {
            if (privacyPolicyDialog === dialog) {
                privacyPolicyDialog = null
            }
        }
        privacyPolicyDialog = dialog
        dialog.show()
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
        applyRuntimeState(
            state,
            intent.getLongExtra(Actions.EXTRA_SESSION_STARTED_AT_ELAPSED_MS, 0L),
            intent.getStringExtra(Actions.EXTRA_CONNECTION_COUNTRY_FLAG).orEmpty(),
            intent.getStringExtra(Actions.EXTRA_DEBUG_FRONTING_IP).orEmpty(),
        )
    }

    private fun applyRuntimeState(
        state: VpnState,
        startedAt: Long,
        countryFlag: String = "",
        frontingIp: String = "",
    ) {
        buttonModel.onStateChanged(state)
        if (state == VpnState.Started) {
            connectionCountryFlag = countryFlag
            debugFrontingIp = frontingIp
            sessionStartedAtElapsedMs = if (startedAt > 0L) startedAt else SystemClock.elapsedRealtime()
            startTimerUpdates()
        } else if (state == VpnState.Stopped || state == VpnState.DailyLimitReached || state is VpnState.Error) {
            connectionCountryFlag = ""
            debugFrontingIp = ""
            sessionStartedAtElapsedMs = 0L
            mainHandler.removeCallbacks(timerRunnable)
            resetTransferSpeeds()
        }
        renderState(state)
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
        activityScope.launch {
            val cachedCatalog = withContext(Dispatchers.IO) {
                SubscriptionStore(this@MainActivity).readCatalog()
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
        val options = ConnectionLocationPolicy.selectorOptions(profiles)
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
    }

    private fun showLocationSelector() {
        if (buttonModel.state == VpnState.Starting || buttonModel.state == VpnState.Stopping) return
        val options = dialogLocationOptions()
        val selectedCountryCode = locationPreferenceStore.readSelectedCountryCode()
        val checkedIndex = options.indexOfFirst { it.countryCode == selectedCountryCode }.takeIf { it >= 0 } ?: 0
        AlertDialog.Builder(this)
            .setTitle(R.string.location_selector_title)
            .setSingleChoiceItems(options.map { it.label }.toTypedArray(), checkedIndex) { dialog, which ->
                handleLocationSelected(options[which])
                dialog.dismiss()
            }
            .show()
    }

    private fun dialogLocationOptions(): List<LocationSelectorOption> {
        val selectedCountryCode = locationPreferenceStore.readSelectedCountryCode()
        val selectedOption = if (
            selectedCountryCode != null &&
            locationOptions.none { it.countryCode == selectedCountryCode }
        ) {
            ConnectionLocationPolicy.optionForCode(selectedCountryCode)
        } else {
            null
        }
        if (selectedOption == null) return locationOptions
        return listOf(LocationSelectorOption.AUTO, selectedOption) +
            locationOptions.filter { it.countryCode != null && it.countryCode != selectedCountryCode }
    }

    private fun handleLocationSelected(option: LocationSelectorOption) {
        val previousCountryCode = locationPreferenceStore.readSelectedCountryCode()
        if (previousCountryCode == option.countryCode) return
        locationPreferenceStore.saveSelectedCountryCode(option.countryCode)
        DiagnosticLogger.info(
            this,
            "activity.location.selected",
            "code=${option.countryCode ?: "auto"} label=${option.label}",
        )
        renderLocationSelection()
        if (buttonModel.state == VpnState.Started) {
            buttonModel.onStateChanged(VpnState.Starting)
            renderState(VpnState.Starting)
            startVpnService(Actions.RECONNECT)
        }
    }

    private fun renderLocationSelection() {
        if (!::locationSelectorRow.isInitialized) return
        val selectedCountryCode = locationPreferenceStore.readSelectedCountryCode()
        val option = locationOptions.firstOrNull { it.countryCode == selectedCountryCode }
            ?: ConnectionLocationPolicy.optionForCode(selectedCountryCode)
            ?: LocationSelectorOption.AUTO
        locationSelectorRow.setValue(option.label)
        locationSelectorRow.contentDescription = "موقعیت: ${option.label}"
    }

    private fun showDnsPrivacySelector() {
        if (buttonModel.state == VpnState.Starting || buttonModel.state == VpnState.Stopping) return
        val modes = DnsPrivacyMode.values()
        val selectedMode = dnsPrivacyPreferenceStore.readMode()
        AlertDialog.Builder(this)
            .setTitle("DNS رمزنگاری‌شده")
            .setSingleChoiceItems(
                modes.map { it.label }.toTypedArray(),
                modes.indexOf(selectedMode),
            ) { dialog, which ->
                handleDnsPrivacySelected(modes[which])
                dialog.dismiss()
            }
            .show()
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
            showDnsPrivacyError(error.message ?: "آدرس DNS رمزنگاری‌شده نامعتبر است", focusOnError)
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
        dnsPrivacyValueText.text = mode.label
        dnsPrivacyDetailText.text = when (mode) {
            DnsPrivacyMode.Automatic -> "DoH + DoT داخلی با مسیر جایگزین"
            DnsPrivacyMode.DoH -> "سرور HTTPS با مسیر جایگزین رمزنگاری‌شده"
            DnsPrivacyMode.DoT -> "سرور TLS با مسیر جایگزین رمزنگاری‌شده"
        }
        dnsPrivacyRow.contentDescription = "DNS رمزنگاری‌شده: ${mode.label}"
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
            DnsPrivacyMode.DoH -> "آدرس DoH"
            DnsPrivacyMode.DoT -> "آدرس DoT"
            DnsPrivacyMode.Automatic -> ""
        }
        dnsPrivacyEndpointLayout.helperText = when (mode) {
            DnsPrivacyMode.DoH -> "URL مبتنی بر HTTPS"
            DnsPrivacyMode.DoT -> "نام میزبان با پورت اختیاری"
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
        advancedExpanded = true
        renderAdvancedSection()
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
            setPadding(dp(4), dp(4), dp(4), 0)
        }

        val modeGroup = RadioGroup(this).apply {
            orientation = RadioGroup.VERTICAL
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
                    setTextColor(TEXT_PRIMARY)
                    isChecked = currentMode == mode
                },
                RadioGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        addModeButton(SplitTunnelMode.Off, getString(R.string.split_tunnel_mode_off))
        addModeButton(SplitTunnelMode.BypassSelected, getString(R.string.split_tunnel_mode_bypass))
        addModeButton(SplitTunnelMode.VpnOnlySelected, getString(R.string.split_tunnel_mode_vpn_only))

        val searchInput = EditText(this).apply {
            hint = getString(R.string.split_tunnel_search_hint)
            setSingleLine(true)
            textSize = 14f
            setTextColor(TEXT_PRIMARY)
            setHintTextColor(TEXT_SECONDARY)
        }
        val selectedCountText = TextView(this).apply {
            textSize = 13f
            includeFontPadding = true
        }
        val appList = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        val appListScroll = ScrollView(this).apply {
            addView(
                appList,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
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

        fun renderAppList(query: String) {
            val normalizedQuery = query.trim().lowercase(Locale.getDefault())
            val filteredApps = if (normalizedQuery.isBlank()) {
                apps
            } else {
                apps.filter { app ->
                    app.label.lowercase(Locale.getDefault()).contains(normalizedQuery) ||
                        app.packageName.lowercase(Locale.US).contains(normalizedQuery)
                }
            }

            appList.removeAllViews()
            if (filteredApps.isEmpty()) {
                appList.addView(
                    TextView(this).apply {
                        text = if (apps.isEmpty()) {
                            getString(R.string.split_tunnel_empty_apps)
                        } else {
                            getString(R.string.split_tunnel_empty_search)
                        }
                        textSize = 14f
                        setTextColor(TEXT_SECONDARY)
                        gravity = Gravity.CENTER
                        includeFontPadding = true
                    },
                    LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                    ).apply {
                        topMargin = dp(16)
                    },
                )
            } else {
                filteredApps.forEach { app ->
                    appList.addView(
                        CheckBox(this).apply {
                            text = "${app.label}\n\u2066${app.packageName}\u2069"
                            textSize = 14f
                            setTextColor(TEXT_PRIMARY)
                            isChecked = app.packageName in selectedPackages
                            setOnCheckedChangeListener { _, isChecked ->
                                if (isChecked) {
                                    selectedPackages += app.packageName
                                } else {
                                    selectedPackages -= app.packageName
                                }
                                updateSaveState()
                            }
                        },
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                        ),
                    )
                }
            }
            updateSaveState()
        }

        modeGroup.setOnCheckedChangeListener { _, checkedId ->
            currentMode = modeById[checkedId] ?: SplitTunnelMode.Off
            updateSaveState()
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
            searchInput,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(12)
            },
        )
        content.addView(
            selectedCountText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(8)
            },
        )
        content.addView(
            appListScroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(320),
            ).apply {
                topMargin = dp(8)
            },
        )

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.split_tunnel_title)
            .setView(content)
            .setNegativeButton(R.string.split_tunnel_cancel, null)
            .setPositiveButton(R.string.split_tunnel_save, null)
            .create()

        dialog.setOnShowListener {
            saveButton = dialog.getButton(AlertDialog.BUTTON_POSITIVE)
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
                    this,
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
            updateSaveState()
        }

        renderAppList("")
        dialog.show()
    }

    private fun renderSplitTunnelSelection() {
        if (!::splitTunnelRow.isInitialized) return
        val settings = splitTunnelPreferenceStore.readSettings()
        val rowValue = when (settings.mode) {
            SplitTunnelMode.Off -> "غیرفعال"
            SplitTunnelMode.BypassSelected ->
                "فعال · خارج از وی‌پی‌ان · ${settings.selectedPackages.size}"
            SplitTunnelMode.VpnOnlySelected ->
                "فعال · داخل وی‌پی‌ان · ${settings.selectedPackages.size}"
        }
        splitTunnelRow.setValue(rowValue)
        splitTunnelRow.contentDescription = "تقسیم تونل: $rowValue"
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
                showFrontingIpError(error.message ?: "IP Fronting نامعتبر است", focusOnError)
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
        if (buttonModel.state == VpnState.Started) {
            buttonModel.onStateChanged(VpnState.Starting)
            renderState(VpnState.Starting)
            startVpnService(Actions.RECONNECT)
        }
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
            showFrontingIpError(error.message ?: "IP Fronting نامعتبر است", focusOnError)
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
            "ظرفیت ۵ آدرس تکمیل است"
        } else {
            "تا ۵ آدرس • جداشده با ویرگول • پورت اختیاری"
        }
    }

    private fun showFrontingIpError(message: String, focusOnError: Boolean) {
        advancedExpanded = true
        renderAdvancedSection()
        frontingIpErrorText.text = message
        frontingIpErrorText.visibility = View.VISIBLE
        if (focusOnError) {
            frontingIpInput.requestFocus()
        }
    }

    private fun renderAdvancedSection() {
        if (!::advancedBody.isInitialized || !::advancedToggleText.isInitialized) return
        advancedBody.visibility = if (advancedExpanded) View.VISIBLE else View.GONE
        advancedToggleText.text = if (advancedExpanded) "بستن  ↑" else "باز کردن  ↓"
        if (::tlsIntegrityCheckbox.isInitialized) {
            tlsIntegrityCheckbox.isChecked = tlsIntegrityPreferenceStore.isEnabled()
        }
        renderDnsPrivacySelection()
    }

    private fun beginConnectFlow() {
        connectFlowPending = true
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
        val intent = VpnService.prepare(this)
        if (intent != null) {
            DiagnosticLogger.info(this, "permission.vpn", "requesting")
            @Suppress("DEPRECATION")
            startActivityForResult(intent, REQUEST_VPN_PERMISSION)
        } else {
            DiagnosticLogger.info(this, "permission.vpn", "already granted")
            connectFlowPending = false
            startVpnService(Actions.CONNECT)
        }
    }

    private fun startVpnService(action: String) {
        DiagnosticLogger.info(this, "service.intent", "action=$action")
        val intent = Intent(this, WhiteDnsVpnService::class.java).setAction(action)
        if (action == Actions.CONNECT && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
    }

    private fun renderState(state: VpnState) {
        scheduleDisconnectTimeout(state)
        val presentation = DashboardStatePresenter.forState(state)
        val accent = accentFor(presentation.tone)
        signalArc.setVpnState(state)
        signalArc.isEnabled = buttonModel.isEnabled()
        signalArc.contentDescription = buttonModel.label()
        statusText.setTextColor(accent)
        statusText.text = "●  ${presentation.title}"
        statusText.background = null
        connectionCountryText.text = when {
            state == VpnState.Started && connectionCountryFlag.isNotBlank() ->
                "$connectionCountryFlag  موقعیت"
            state == VpnState.Started -> "خروجی / خودکار"
            state == VpnState.Starting -> "در حال انتخاب مسیر"
            state == VpnState.Stopping -> "در حال بستن مسیر"
            state is VpnState.Error -> "خروجی در دسترس نیست"
            state == VpnState.DailyLimitReached -> "سقف مصرف روزانه"
            else -> "خروجی / خودکار"
        }
        connectionCountryText.setTextColor(if (state == VpnState.Started) accent else TEXT_SECONDARY)
        refreshActionButton.visibility = if (state == VpnState.Started) View.VISIBLE else View.GONE
        refreshActionButton.isEnabled = state == VpnState.Started
        refreshActionButton.contentDescription = "اتصال مجدد"
        refreshActionButton.backgroundTintList = ColorStateList.valueOf(SURFACE)
        refreshActionButton.strokeColor = ColorStateList.valueOf(OUTLINE)
        refreshActionButton.rippleColor = ColorStateList.valueOf(withAlpha(TEAL, 24))
        refreshActionButton.setTextColor(ColorStateList.valueOf(TEAL))
        refreshActionButton.iconTint = ColorStateList.valueOf(TEAL)
        val settingsEnabled = state != VpnState.Starting && state != VpnState.Stopping
        locationSelectorRow.isEnabled = settingsEnabled
        splitTunnelRow.isEnabled = settingsEnabled
        if (::tlsIntegrityCheckbox.isInitialized) {
            tlsIntegrityCheckbox.isEnabled = settingsEnabled
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
        timerText.setTextColor(
            if (buttonModel.state == VpnState.Started && sessionStartedAtElapsedMs > 0L) {
                TEXT_PRIMARY
            } else {
                TIMER_MUTED
            },
        )
        timerText.text = formatDuration(elapsedMs)
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
        val diagnostics = DiagnosticLogger.read(this).ifBlank { "گزارش عیب‌یابی WhiteDNS خالی است." }
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("WhiteDNS diagnostics", diagnostics))
        Toast.makeText(this, "گزارش عیب‌یابی کپی شد", Toast.LENGTH_SHORT).show()
        DiagnosticLogger.info(this, "diagnostics.copy", "chars=${diagnostics.length}")
    }

    private fun openFooterLink() {
        val url = getString(R.string.footer_url)
        runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        }.onFailure { error ->
            Toast.makeText(this, url, Toast.LENGTH_SHORT).show()
            DiagnosticLogger.warn(this, "footer.open.failed", "url=$url", error)
        }
    }

    @Suppress("DEPRECATION")
    private fun configureSystemBars() {
        window.decorView.layoutDirection = View.LAYOUT_DIRECTION_RTL
        window.decorView.textDirection = View.TEXT_DIRECTION_RTL
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
    }
}
