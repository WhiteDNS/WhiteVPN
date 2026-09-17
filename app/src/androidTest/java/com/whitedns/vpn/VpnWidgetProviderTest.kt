package com.whitedns.vpn

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.res.Configuration
import android.view.View
import android.widget.ImageButton
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class VpnWidgetProviderTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val target = instrumentation.targetContext
    private val id = "widget-test-${System.nanoTime()}"
    private val root = File(target.cacheDir, id)
    private fun isolated(base: Context): Context = object : ContextWrapper(base) {
        override fun getFilesDir(): File = root.apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int) = target.getSharedPreferences("$id-$name", mode)
        override fun createConfigurationContext(configuration: Configuration): Context = isolated(base.createConfigurationContext(configuration))
    }
    private val context = isolated(target)
    private val preferenceNames = listOf("white_dns_theme", "white_dns_language", "white_dns_connection_mode",
        "white_dns_runtime_state", "white_dns_privacy_policy", "white_dns_user_subscriptions", "white_dns_connection_chain")

    @After
    fun cleanUp() {
        preferenceNames.forEach { target.deleteSharedPreferences("$id-$it") }
        root.deleteRecursively()
        AppLocale.wrap(target)
    }

    @Test
    fun cachedConnectedStateCannotEnableDisconnectAfterProcessRestart() {
        assertEquals(VpnState.Stopped, currentVpnServiceState)
        VpnRuntimeStateStore.save(context, VpnState.Started)
        assertEquals(VpnState.Started, VpnRuntimeStateStore.read(context))
        assertEquals(VpnWidgetAction.OpenApp, VpnWidgetProvider.action(context))
        instrumentation.runOnMainSync {
            val view = VpnWidgetProvider.views(context).apply(context, null)
            assertTrue(view.findViewById<TextView>(R.id.widget_status).text.contains(
                AppLocale.wrap(context).getString(R.string.widget_disconnected),
            ))
        }
    }

    @Test
    fun setupAndPrivacyGateProxyConnectionWithoutRequestingVpnPermission() {
        ConnectionModePreferenceStore(context).save(ConnectionMode.Proxy)
        assertEquals(VpnWidgetAction.OpenApp, VpnWidgetProvider.action(context))
        PrivacyPolicyAcceptanceStore(context).acceptCurrentVersion()
        assertEquals(VpnWidgetAction.OpenApp, VpnWidgetProvider.action(context))
        SubscriptionStore(context).saveCatalog(SubscriptionStore.DEFAULT_SUBSCRIPTION_ID, SubscriptionCatalog(
            listOf(ConnectionProfile("test", "http", "example.com", 443, "", "example.com", "test", "{}")), 1L,
        ))
        assertEquals(VpnWidgetAction.Connect, VpnWidgetProvider.action(context))
        VpnRuntimeStateStore.save(context, VpnState.Stopped, alwaysOn = true)
        // Android 8/9 read the live platform setting rather than retaining an obsolete restriction.
        assertEquals(if (android.os.Build.VERSION.SDK_INT < 29) VpnWidgetAction.Connect else VpnWidgetAction.OpenApp,
            VpnWidgetProvider.action(context))
    }

    @Test
    fun remoteViewsInflateAndDisableTransitionsInBothLanguagesAndThemes() {
        for (language in AppLanguage.entries) for (theme in AppThemeMode.entries) {
            AppLanguagePreferenceStore(context).save(language)
            AppThemePreferenceStore(context).save(theme)
            instrumentation.runOnMainSync {
                for (state in listOf(VpnState.Stopped, VpnState.Starting, VpnState.Started, VpnState.Stopping, VpnState.Error("test"))) {
                    val view = VpnWidgetProvider.views(context, state).apply(context, null)
                    val button = view.findViewById<ImageButton>(R.id.widget_power)
                    assertEquals(state != VpnState.Starting && state != VpnState.Stopping, button.isEnabled)
                    assertFalse(button.contentDescription.isNullOrBlank())
                    assertEquals(AppLocale.wrap(context).resources.configuration.layoutDirection,
                        view.findViewById<View>(R.id.widget_root).layoutDirection)
                    val width = (134 * context.resources.displayMetrics.density).toInt()
                    val height = (110 * context.resources.displayMetrics.density).toInt()
                    view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY))
                    view.layout(0, 0, width, height)
                    assertEquals(0, view.findViewById<TextView>(R.id.widget_name).layout.getEllipsisCount(0))
                }
            }
        }
    }

    @Test
    fun pendingIntentsUseActivityOrBroadcastAndTransitionsHaveNoIntent() {
        assertTrue(VpnWidgetProvider.pendingIntent(context, VpnWidgetAction.OpenApp)!!.isActivity)
        assertFalse(VpnWidgetProvider.pendingIntent(context, VpnWidgetAction.Connect)!!.isActivity)
        assertFalse(VpnWidgetProvider.pendingIntent(context, VpnWidgetAction.Disconnect)!!.isActivity)
        assertNull(VpnWidgetProvider.pendingIntent(context, VpnWidgetAction.None))
    }

    @Test
    fun staleConnectTapRechecksPrivacyAndOpensApp() {
        var opened: Intent? = null
        val recording = object : ContextWrapper(context) {
            override fun startActivity(intent: Intent) { opened = intent }
            override fun startForegroundService(intent: Intent): android.content.ComponentName? {
                fail("An unaccepted privacy policy must not start the VPN")
                return null
            }
        }
        VpnWidgetProvider().onReceive(recording, Intent(Actions.CONNECT))
        assertEquals(MainActivity::class.java.name, opened?.component?.className)
    }
}
