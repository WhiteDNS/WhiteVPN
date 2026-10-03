package com.whitedns.vpn

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.net.VpnService
import android.os.Bundle
import android.widget.RemoteViews

internal enum class VpnWidgetAction { Connect, Disconnect, OpenApp, None }

internal fun vpnWidgetAction(
    state: VpnState,
    privacyAccepted: Boolean,
    configured: Boolean,
    permissionGranted: Boolean,
    alwaysOn: Boolean,
): VpnWidgetAction = when {
    state == VpnState.Starting || state == VpnState.Stopping -> VpnWidgetAction.None
    alwaysOn -> VpnWidgetAction.OpenApp
    state == VpnState.Started -> VpnWidgetAction.Disconnect
    state is VpnState.Error -> VpnWidgetAction.OpenApp
    !privacyAccepted || !configured || !permissionGranted -> VpnWidgetAction.OpenApp
    else -> VpnWidgetAction.Connect
}

class VpnWidgetProvider : AppWidgetProvider() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Actions.CONNECT && intent.action != Actions.DISCONNECT) {
            super.onReceive(context, intent)
            return
        }
        // A launcher can still hold the previous RemoteViews. Recheck consent and state at tap time.
        when (action(context)) {
            VpnWidgetAction.OpenApp -> context.startActivity(appIntent(context))
            VpnWidgetAction.None -> Unit
            VpnWidgetAction.Connect, VpnWidgetAction.Disconnect -> {
                val expected = if (liveState() == VpnState.Started) Actions.DISCONNECT else Actions.CONNECT
                if (intent.action == expected) {
                    runCatching { RouteServiceDispatcher.dispatch(context, expected) }.onFailure {
                        DiagnosticLogger.warn(context, "widget.action.failed", error = it)
                        context.startActivity(appIntent(context))
                    }
                }
            }
        }
        refresh(context)
    }

    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) = refresh(context)

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) =
        refresh(context)

    override fun onRestored(context: Context, oldWidgetIds: IntArray, newWidgetIds: IntArray) = refresh(context)

    companion object {
        private fun liveState(): VpnState = PlatformIkev2Controller.observedState()
            .takeUnless { it == VpnState.Stopped } ?: currentVpnServiceState

        fun refresh(context: Context) {
            // Widget hosts must never interrupt a VPN state transition if unavailable.
            runCatching {
                val manager = AppWidgetManager.getInstance(context)
                val ids = manager.getAppWidgetIds(ComponentName(context, VpnWidgetProvider::class.java))
                if (ids.isNotEmpty()) manager.updateAppWidget(ids, views(context))
            }.onFailure { DiagnosticLogger.warn(context, "widget.refresh.failed", error = it) }
        }

        internal fun action(context: Context, state: VpnState = liveState()): VpnWidgetAction {
            val mode = ConnectionModePreferenceStore(context).read()
            val alwaysOn = vpnAlwaysOnEnabled(context)
            val needsTun = ConnectionModePolicy.shouldStartTun(mode, alwaysOn, alwaysOn && VpnRuntimeStateStore.readLockdown(context))
            val subscriptions = SubscriptionStore(context)
            val subscriptionId = subscriptions.readSelectedSubscriptionId()
            val engineId = EngineProfileStore(context).selectedEngineId()
            val engineConfigured = if (engineId == null) false else runCatching {
                val profile = EngineProfileStore(context).profile(engineId) ?: return@runCatching false
                if (profile.kind == EngineKind.IKEV2) return@runCatching false
                EnginePreflight.validate(profile, EngineNativeAvailability.check(context, profile),
                    needsTun, alwaysOn && VpnRuntimeStateStore.readLockdown(context))
                true
            }.getOrDefault(false)
            return vpnWidgetAction(
                state = state,
                privacyAccepted = PrivacyPolicyAcceptanceStore(context).isAccepted(),
                configured = if (engineId != null) engineConfigured else ConnectionChainPreferenceStore(context).read().isActive || if (SubscriptionStore.isBuiltInSubscription(subscriptionId)) {
                    subscriptions.readCatalog(subscriptionId) != null
                } else {
                    subscriptions.readUserSubscription(subscriptionId) != null
                },
                permissionGranted = !needsTun || VpnService.prepare(context) == null,
                alwaysOn = alwaysOn,
            )
        }

        internal fun views(context: Context, state: VpnState = liveState()): RemoteViews {
            val localized = AppLocale.wrap(AppTheme.wrap(context))
            val action = action(context, state)
            val status = localized.getString(
                if (state == VpnState.Stopped || state == VpnState.DailyLimitReached) R.string.widget_disconnected
                else QuickSettingsTileStateMapper.presentationFor(state).subtitleRes,
            )
            val proxy = ConnectionModePreferenceStore(context).read() == ConnectionMode.Proxy &&
                !vpnAlwaysOnEnabled(context)
            val mode = localized.getString(if (proxy) R.string.connection_mode_proxy else R.string.connection_mode_vpn)
            val dark = localized.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
                Configuration.UI_MODE_NIGHT_YES
            val actionLabel = localized.getString(when (action) {
                VpnWidgetAction.Connect -> R.string.widget_connect
                VpnWidgetAction.Disconnect -> R.string.notification_action_disconnect
                VpnWidgetAction.OpenApp -> R.string.widget_open_app
                VpnWidgetAction.None -> QuickSettingsTileStateMapper.presentationFor(state).subtitleRes
            })
            return RemoteViews(context.packageName, R.layout.vpn_widget).apply {
                setInt(R.id.widget_root, "setBackgroundResource", if (dark) R.drawable.widget_background_dark else R.drawable.widget_background_light)
                setInt(R.id.widget_root, "setLayoutDirection", localized.resources.configuration.layoutDirection)
                setTextViewText(R.id.widget_name, localized.getString(R.string.app_name))
                setTextViewText(R.id.widget_status, if (proxy) "$mode · $status" else status)
                setTextColor(R.id.widget_name, localized.getColor(R.color.whitedns_on_surface))
                setTextColor(R.id.widget_status, localized.getColor(R.color.whitedns_on_surface_variant))
                setInt(R.id.widget_power, "setColorFilter", localized.getColor(
                    when (state) {
                        VpnState.Started -> R.color.whitedns_primary
                        is VpnState.Error -> R.color.whitedns_error
                        else -> R.color.whitedns_on_surface_variant
                    },
                ))
                setBoolean(R.id.widget_power, "setEnabled", action != VpnWidgetAction.None)
                setInt(R.id.widget_power, "setImageAlpha", if (action == VpnWidgetAction.None) 100 else 255)
                setContentDescription(R.id.widget_brand, "$mode · $status. ${localized.getString(R.string.widget_open_app)}")
                setContentDescription(R.id.widget_power, actionLabel)
                setOnClickPendingIntent(R.id.widget_brand, openApp(context))
                setOnClickPendingIntent(R.id.widget_power, pendingIntent(context, action))
            }
        }

        internal fun pendingIntent(context: Context, action: VpnWidgetAction): PendingIntent? = when (action) {
            VpnWidgetAction.None -> null
            VpnWidgetAction.OpenApp -> openApp(context)
            VpnWidgetAction.Connect, VpnWidgetAction.Disconnect -> {
                val intent = Intent(context, VpnWidgetProvider::class.java)
                    .setAction(if (action == VpnWidgetAction.Connect) Actions.CONNECT else Actions.DISCONNECT)
                PendingIntent.getBroadcast(context, 20, intent, FLAGS)
            }
        }

        private fun openApp(context: Context): PendingIntent = PendingIntent.getActivity(
            context, 20,
            appIntent(context),
            FLAGS,
        )

        private fun appIntent(context: Context) = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        private const val FLAGS = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
    }
}
