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
import android.graphics.Rect
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
import android.text.SpannableString
import android.text.Spanned
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.google.android.material.button.MaterialButton
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

class MainActivity : Activity() {
    private val palette: WhiteDnsPalette by lazy { WhiteDnsDesignTokens.forContext(this) }
    private val buttonModel = ConnectButtonModel()
    private val mainHandler = Handler(Looper.getMainLooper())
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var privacyPolicyStore: PrivacyPolicyAcceptanceStore
    private lateinit var locationPreferenceStore: ConnectionLocationPreferenceStore
    private lateinit var splitTunnelPreferenceStore: SplitTunnelPreferenceStore
    private lateinit var frontingIpPreferenceStore: FrontingIpPreferenceStore
    private lateinit var installedAppRepository: InstalledAppRepository
    private var privacyPolicyDialog: AlertDialog? = null
    private var sessionStartedAtElapsedMs: Long = 0L
    private var connectFlowPending: Boolean = false
    private var disconnectAnalyticsPending: Boolean = false
    private var locationOptions: List<LocationSelectorOption> = listOf(LocationSelectorOption.AUTO)

    private lateinit var signalArc: SignalArcView
    private lateinit var statusText: TextView
    private lateinit var timerText: TextView
    private lateinit var locationSelectorRow: DashboardDataRowView
    private lateinit var splitTunnelRow: DashboardDataRowView
    private lateinit var advancedBody: LinearLayout
    private lateinit var advancedToggleText: TextView
    private lateinit var frontingIpChipGroup: ChipGroup
    private lateinit var frontingIpInput: EditText
    private lateinit var frontingIpErrorText: TextView
    private lateinit var connectActionButton: MaterialButton
    private lateinit var refreshActionButton: MaterialButton
    private var connectionCountryFlag: String = ""
    private var debugFrontingIp: String = ""
    private var advancedExpanded: Boolean = false
    private var frontingIps: List<String> = emptyList()
    private var frontingIpInputUpdating: Boolean = false
    private var lastTransferRxBytes: Long = TrafficStats.UNSUPPORTED.toLong()
    private var lastTransferTxBytes: Long = TrafficStats.UNSUPPORTED.toLong()
    private var lastTransferSampleElapsedMs: Long = 0L

    private val BACKGROUND: Int get() = palette.background
    private val SURFACE: Int get() = palette.surface
    private val SURFACE_VARIANT: Int get() = palette.surfaceVariant
    private val OUTLINE: Int get() = palette.outline
    private val TEXT_PRIMARY: Int get() = palette.textPrimary
    private val TEXT_SECONDARY: Int get() = palette.textSecondary
    private val DARK_GRAY: Int get() = palette.neutral
    private val TIMER_MUTED: Int get() = palette.outline
    private val TEAL: Int get() = palette.teal
    private val AMBER: Int get() = palette.amber
    private val AMBER_TRACK: Int get() = palette.amberTrack
    private val ERROR: Int get() = palette.red
    private val ERROR_TRACK: Int get() = palette.redTrack
    private val ON_PROMINENT: Int get() = palette.onProminent

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
        installedAppRepository = InstalledAppRepository(this)
        DiagnosticLogger.info(this, "activity.onCreate")
        configureSystemBars()
        setContentView(buildDashboard())
        renderState(VpnState.Stopped)
        refreshLocationOptions()
        mainHandler.post {
            showPrivacyPolicyIfNeeded()
        }
    }

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
            VpnRuntimeStateStore.readDebugFrontingIp(this),
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
            setBackgroundColor(BACKGROUND)
            clipToPadding = false
        }
        val viewport = FrameLayout(this).apply {
            setPadding(0, 0, 0, dp(28))
        }
        scrollView.addView(
            viewport,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )

        val dashboardContent = MaxWidthLinearLayout(this).apply {
            maxWidthPx = dp(390)
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(0, 0, 0, dp(18))
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

        val wordmarkRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(
                TextView(this@MainActivity).apply {
                    text = "WhiteDNS"
                    textSize = 22f
                    letterSpacing = 0.04f
                    typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
                    setTextColor(TEXT_PRIMARY)
                    includeFontPadding = false
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                TextView(this@MainActivity).apply {
                    text = "VPN"
                    textSize = 11f
                    letterSpacing = 0.08f
                    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                    setTextColor(TEAL)
                    includeFontPadding = false
                    setPadding(dp(12), dp(5), dp(12), dp(5))
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.RECTANGLE
                        cornerRadius = dp(6).toFloat()
                        setColor(palette.brandPillBackground)
                        setStroke(dp(1), palette.brandPillOutline)
                    }
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    leftMargin = dp(8)
                },
            )
        }
        val headerBlock = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            addView(
                wordmarkRow,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                TextView(this@MainActivity).apply {
                    text = "v${BuildConfig.VERSION_NAME}"
                    textSize = 11f
                    letterSpacing = 0.06f
                    typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                    setTextColor(TEXT_SECONDARY)
                    includeFontPadding = false
                },
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = dp(6)
                },
            )
        }

        signalArc = SignalArcView(this).apply {
            isClickable = true
            isFocusable = true
            setOnClickListener { handleButtonClick() }
        }
        statusText = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 13f
            letterSpacing = 0.04f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            includeFontPadding = false
        }
        timerText = TextView(this).apply {
            gravity = Gravity.CENTER
            textSize = 44f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            includeFontPadding = false
        }

        val signalSection = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            addView(
                signalArc,
                LinearLayout.LayoutParams(dp(220), dp(220)),
            )
            addView(
                statusText,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = dp(4)
                },
            )
            addView(
                timerText,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = dp(10)
                },
            )
        }

        locationSelectorRow = DashboardDataRowView(this).apply {
            setRow("Exit node", LocationSelectorOption.AUTO.label, "AUTO")
            setOnRowClickListener { showLocationSelector() }
        }
        splitTunnelRow = DashboardDataRowView(this).apply {
            setRow("Split tunnel", "Disabled", "OFF")
            setOnRowClickListener { showSplitTunnelSelector() }
        }
        val dataRows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(
                locationSelectorRow,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ),
            )
            addView(
                splitTunnelRow,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = dp(8)
                },
            )
        }
        val advancedSection = buildAdvancedSection()

        connectActionButton = MaterialButton(this).apply {
            textSize = 15f
            letterSpacing = 0.08f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            minHeight = dp(60)
            minimumHeight = dp(60)
            insetTop = 0
            insetBottom = 0
            cornerRadius = dp(10)
            strokeWidth = dp(2)
            elevation = 0f
            stateListAnimator = null
            setAllCaps(false)
            setOnClickListener { handleButtonClick() }
            setOnLongClickListener {
                copyDiagnosticsToClipboard()
                true
            }
        }
        refreshActionButton = MaterialButton(this).apply {
            text = "Refresh"
            textSize = 15f
            letterSpacing = 0.02f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            minHeight = dp(60)
            minimumHeight = dp(60)
            minWidth = dp(122)
            minimumWidth = dp(122)
            insetTop = 0
            insetBottom = 0
            cornerRadius = dp(10)
            strokeWidth = dp(2)
            elevation = 0f
            stateListAnimator = null
            setAllCaps(false)
            setIconResource(R.drawable.ic_refresh)
            iconGravity = MaterialButton.ICON_GRAVITY_TEXT_START
            iconPadding = dp(8)
            visibility = View.GONE
            setOnClickListener { handleRefreshClick() }
        }
        val actionButtons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(
                connectActionButton,
                LinearLayout.LayoutParams(
                    0,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    1f,
                ),
            )
            addView(
                refreshActionButton,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ).apply {
                    marginStart = dp(8)
                },
            )
        }

        val footerText = TextView(this).apply {
            gravity = Gravity.CENTER
            text = getString(R.string.footer_copyright)
            textSize = 11f
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
                contentParams(dp(48)).apply {
                    bottomMargin = dp(14)
                },
            )
            addView(
                signalSection,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                ).apply {
                    topMargin = dp(28)
                },
            )
            addView(
                dataRows,
                contentParams(dp(28)),
            )
            addView(
                advancedSection,
                contentParams(dp(8)),
            )
            addView(
                actionButtons,
                contentParams(dp(28)).apply {
                    height = dp(60)
                },
            )
            addView(
                footerText,
                contentParams(dp(28)),
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

    private fun buildAdvancedSection(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(10).toFloat()
                setColor(SURFACE_VARIANT)
                setStroke(dp(1), OUTLINE)
            }
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                advancedExpanded = !advancedExpanded
                renderAdvancedSection()
            }
        }
        header.addView(
            TextView(this).apply {
                text = "ADVANCED"
                textSize = 12f
                letterSpacing = 0.04f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                setTextColor(TEXT_SECONDARY)
                includeFontPadding = false
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f),
        )
        advancedToggleText = TextView(this).apply {
            textSize = 11f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
            setTextColor(TEXT_SECONDARY)
            includeFontPadding = false
            gravity = Gravity.END
        }
        header.addView(
            advancedToggleText,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )

        advancedBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), 0, dp(16), dp(16))
        }
        advancedBody.addView(
            TextView(this).apply {
                text = "Fronting IP"
                textSize = 13f
                typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                setTextColor(TEXT_PRIMARY)
                includeFontPadding = false
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ),
        )
        frontingIps = frontingIpPreferenceStore.readFrontingIps()
        frontingIpChipGroup = ChipGroup(this).apply {
            isSingleLine = false
            chipSpacingHorizontal = dp(6)
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
        frontingIpInput = EditText(this).apply {
            hint = frontingIpInputHint()
            setSingleLine(true)
            textSize = 14f
            inputType = InputType.TYPE_CLASS_PHONE
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
                    postDelayed(
                        { requestRectangleOnScreen(Rect(0, 0, width, height + dp(96)), false) },
                        KEYBOARD_SCROLL_DELAY_MS,
                    )
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
        advancedBody.addView(
            frontingIpInput,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply {
                topMargin = dp(8)
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
        locationSelectorRow.setValue(option.label, option.countryCode ?: "AUTO")
        locationSelectorRow.setSubColor(if (option.countryCode == null) TEXT_SECONDARY else TEAL)
        locationSelectorRow.contentDescription = "Exit node: ${option.label}"
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
                        topMargin = dp(18)
                    },
                )
            } else {
                filteredApps.forEach { app ->
                    appList.addView(
                        CheckBox(this).apply {
                            text = "${app.label}\n${app.packageName}"
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
                topMargin = dp(6)
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
        val rowValue: String
        val rowSub: String
        when (settings.mode) {
            SplitTunnelMode.Off -> {
                rowValue = "Disabled"
                rowSub = "OFF"
            }
            SplitTunnelMode.BypassSelected -> {
                rowValue = "Enabled"
                rowSub = "BYPASS ${settings.selectedPackages.size}"
            }
            SplitTunnelMode.VpnOnlySelected -> {
                rowValue = "Enabled"
                rowSub = "VPN ${settings.selectedPackages.size}"
            }
        }
        splitTunnelRow.setValue(rowValue, rowSub)
        splitTunnelRow.setSubColor(if (settings.mode == SplitTunnelMode.Off) TEXT_SECONDARY else TEAL)
        splitTunnelRow.contentDescription = "Split tunnel: $rowValue"
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
                showFrontingIpError(error.message ?: "Invalid Fronting IP", focusOnError)
                return false
            }
            frontingIps = nextIps
            setFrontingIpInputText("")
        }
        return saveFrontingIps(reconnectIfChanged, previousValue)
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
            showFrontingIpError(error.message ?: "Invalid Fronting IP", focusOnError)
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
        if (::frontingIpInput.isInitialized) {
            frontingIpInput.hint = frontingIpInputHint()
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
        return if (frontingIps.size >= 5) "Limit reached" else "Optional IPv4, comma separated"
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
        advancedToggleText.text = if (advancedExpanded) "HIDE" else "SHOW"
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
        val colors = colorsFor(presentation.tone)
        signalArc.setVpnState(state)
        signalArc.isEnabled = buttonModel.isEnabled()
        signalArc.contentDescription = buttonModel.label()
        statusText.setTextColor(colors.accent)
        val baseStatus = if (state == VpnState.Started && connectionCountryFlag.isNotBlank()) {
            "$connectionCountryFlag ${presentation.title}"
        } else {
            "\u2022 ${presentation.title}"
        }
        statusText.text = if (state == VpnState.Started && debugFrontingIp.isNotBlank()) {
            "$baseStatus\nFronting IP: $debugFrontingIp"
        } else {
            baseStatus
        }
        connectActionButton.text = buttonModel.label()
        connectActionButton.isEnabled = buttonModel.isEnabled()
        connectActionButton.contentDescription = buttonModel.label()
        connectActionButton.backgroundTintList = ColorStateList.valueOf(colors.buttonBackground)
        connectActionButton.strokeColor = ColorStateList.valueOf(colors.buttonBorder)
        connectActionButton.rippleColor = ColorStateList.valueOf(colors.buttonRipple)
        connectActionButton.setTextColor(ColorStateList.valueOf(colors.buttonText))
        refreshActionButton.visibility = if (state == VpnState.Started) View.VISIBLE else View.GONE
        refreshActionButton.isEnabled = state == VpnState.Started
        refreshActionButton.contentDescription = "Refresh connection"
        refreshActionButton.backgroundTintList = ColorStateList.valueOf(SURFACE)
        refreshActionButton.strokeColor = ColorStateList.valueOf(TEAL)
        refreshActionButton.rippleColor = ColorStateList.valueOf(OUTLINE)
        refreshActionButton.setTextColor(ColorStateList.valueOf(TEAL))
        refreshActionButton.iconTint = ColorStateList.valueOf(TEAL)
        val settingsEnabled = state != VpnState.Starting && state != VpnState.Stopping
        locationSelectorRow.isEnabled = settingsEnabled
        splitTunnelRow.isEnabled = settingsEnabled
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
        val value = formatDuration(elapsedMs)
        if (buttonModel.state != VpnState.Started || sessionStartedAtElapsedMs <= 0L) {
            timerText.setTextColor(TIMER_MUTED)
            timerText.text = value
            return
        }
        val spannable = SpannableString(value)
        spannable.setSpan(
            ForegroundColorSpan(TIMER_MUTED),
            0,
            minOf(6, value.length),
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
        )
        timerText.setTextColor(TEXT_PRIMARY)
        timerText.text = spannable
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
            signalArc.setTransferSpeeds(
                formatTransferSpeed(bytesPerSecond(rxBytes, lastTransferRxBytes, elapsedMs)),
                formatTransferSpeed(bytesPerSecond(txBytes, lastTransferTxBytes, elapsedMs)),
            )
        } else {
            signalArc.setTransferSpeeds(formatTransferSpeed(0L), formatTransferSpeed(0L))
        }
        lastTransferRxBytes = rxBytes
        lastTransferTxBytes = txBytes
        lastTransferSampleElapsedMs = nowElapsedMs
    }

    private fun resetTransferSpeeds() {
        lastTransferRxBytes = TrafficStats.UNSUPPORTED.toLong()
        lastTransferTxBytes = TrafficStats.UNSUPPORTED.toLong()
        lastTransferSampleElapsedMs = 0L
        if (::signalArc.isInitialized) {
            signalArc.setTransferSpeeds(null, null)
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
        val diagnostics = DiagnosticLogger.read(this).ifBlank { "WhiteDNS diagnostics are empty." }
        val clipboard = getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("WhiteDNS diagnostics", diagnostics))
        Toast.makeText(this, "Debug log copied", Toast.LENGTH_SHORT).show()
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
        window.statusBarColor = BACKGROUND
        window.navigationBarColor = BACKGROUND
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

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun colorsFor(tone: DashboardTone): DashboardColors {
        return when (tone) {
            DashboardTone.Connected -> DashboardColors(
                accent = TEAL,
                buttonBackground = SURFACE,
                buttonBorder = ERROR,
                buttonRipple = ERROR_TRACK,
                buttonText = ERROR,
            )
            DashboardTone.Progress -> DashboardColors(
                accent = AMBER,
                buttonBackground = SURFACE,
                buttonBorder = AMBER,
                buttonRipple = AMBER_TRACK,
                buttonText = AMBER,
            )
            DashboardTone.Error -> DashboardColors(
                accent = ERROR,
                buttonBackground = TEXT_PRIMARY,
                buttonBorder = TEXT_PRIMARY,
                buttonRipple = OUTLINE,
                buttonText = ON_PROMINENT,
            )
            DashboardTone.Neutral -> DashboardColors(
                accent = DARK_GRAY,
                buttonBackground = TEXT_PRIMARY,
                buttonBorder = TEXT_PRIMARY,
                buttonRipple = OUTLINE,
                buttonText = ON_PROMINENT,
            )
        }
    }

    private data class DashboardColors(
        val accent: Int,
        val buttonBackground: Int,
        val buttonBorder: Int,
        val buttonRipple: Int,
        val buttonText: Int,
    )

    private companion object {
        const val REQUEST_VPN_PERMISSION = 10
        const val REQUEST_NOTIFICATION_PERMISSION = 11
        const val TIMER_TICK_MS = 1_000L
        const val DISCONNECT_UI_TIMEOUT_MS = 7_000L
        const val KEYBOARD_SCROLL_DELAY_MS = 250L
    }
}
