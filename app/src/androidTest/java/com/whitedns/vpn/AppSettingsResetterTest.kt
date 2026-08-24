package com.whitedns.vpn

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppSettingsResetterTest {
    @Test
    fun resetsSettingsWithoutDeletingUserData() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val settingsPreferences = listOf(
            "white_dns_language",
            "white_dns_theme",
            "white_dns_connection_location",
            "white_dns_split_tunnel",
            "white_dns_fronting_ip",
            "white_dns_tls_integrity",
            "white_dns_connection_options",
            "white_dns_routing",
            "white_dns_privacy",
            "white_dns_connection_mode",
            "white_dns_lan_sharing",
            "white_dns_connection_selection",
            "white_dns_connection_test",
            "white_dns_connection_chain",
        )
        val preservedPreferences = listOf(
            "white_dns_user_subscriptions",
            "white_dns_privacy_policy",
            "white_dns_scan_state",
            "white_dns_clean_ip",
            "white_dns_runtime_state",
        )
        val markerKey = "app_settings_resetter_test"
        val subscriptionId = "app-settings-resetter-test"
        val profile = ConnectionProfile(
            tag = "Reset test",
            type = "vless",
            server = "example.com",
            port = 443,
            transport = "tcp",
            validationHost = "example.com",
            fingerprint = "app-settings-resetter-profile",
        )
        val testedAt = System.currentTimeMillis()
        val subscriptionStore = SubscriptionStore(context)

        subscriptionStore.deleteUserSubscription(subscriptionId)
        preservedPreferences.forEach { name ->
            context.getSharedPreferences(name, Context.MODE_PRIVATE)
                .edit()
                .putString(markerKey, "preserved")
                .commit()
        }
        subscriptionStore.saveUserSubscription(
            UserSubscription(
                id = subscriptionId,
                name = "Reset test",
                input = "test input",
                format = UserSubscriptionFormat.Mihomo,
                connectionCount = 1,
                updatedAt = testedAt,
            ),
            yaml = "proxies: []",
        )
        subscriptionStore.saveSelectedSubscriptionId(subscriptionId)
        subscriptionStore.saveConnectionDelayRecord(
            ConnectionDelayRecord(
                subscriptionId = subscriptionId,
                fingerprint = profile.fingerprint,
                delayMs = 42,
                status = ConnectionDelayStatus.Success,
                testedAt = testedAt,
            ),
        )

        try {
            AppLanguagePreferenceStore(context).save(AppLanguage.English)
            AppThemePreferenceStore(context).save(AppThemeMode.Dark)
            ConnectionLocationPreferenceStore(context).saveSelectedCountryCode("US")
            SplitTunnelPreferenceStore(context).saveSettings(
                SplitTunnelSettings(SplitTunnelMode.BypassSelected, setOf("com.example.reset")),
            )
            FrontingIpPreferenceStore(context).saveFrontingIp("1.1.1.1")
            TlsIntegrityPreferenceStore(context).saveEnabled(true)
            MihomoConnectionOptionsPreferenceStore(context).saveAmneziaNoise(
                enabled = true,
                settings = MihomoConnectionOptionsPolicy.DEFAULT_NOISE,
            )
            RoutingModePreferenceStore(context).save(RoutingMode.GlobalProxy)
            DnsPrivacyPreferenceStore(context).apply {
                saveMode(DnsPrivacyMode.DoH)
                saveDohUrl("https://example.com/dns-query")
                saveDotEndpoint("tls://example.com:853")
            }
            ConnectionModePreferenceStore(context).save(ConnectionMode.Proxy)
            LanSharingPreferenceStore(context).apply {
                saveEnabled(true)
                savePasswordRequired(false)
            }
            ConnectionSelectionPreferenceStore(context).apply {
                saveSelectedProfile(subscriptionId, profile)
                saveAutomaticTypes(subscriptionId, setOf(profile.type), listOf(profile))
                saveDelaySortEnabled(subscriptionId, true)
                saveAutoSortedTestId(subscriptionId, "test-id")
            }
            ConnectionTestSettingsPreferenceStore(context).save(ConnectionTestSettings(7, 4, 8))
            ConnectionChainPreferenceStore(context).save(ConnectionChainSettings(enabled = true))

            AppSettingsResetter.reset(context)

            settingsPreferences.forEach { name ->
                assertTrue(name, context.getSharedPreferences(name, Context.MODE_PRIVATE).all.isEmpty())
            }
            preservedPreferences.forEach { name ->
                assertEquals(
                    name,
                    "preserved",
                    context.getSharedPreferences(name, Context.MODE_PRIVATE).getString(markerKey, null),
                )
            }
            assertEquals(AppLanguage.Persian, AppLanguagePreferenceStore(context).read())
            assertEquals(AppThemeMode.System, AppThemePreferenceStore(context).read())
            assertNull(ConnectionLocationPreferenceStore(context).readSelectedCountryCode())
            assertEquals(SplitTunnelSettings(), SplitTunnelPreferenceStore(context).readSettings())
            assertTrue(FrontingIpPreferenceStore(context).readFrontingIps().isEmpty())
            assertFalse(TlsIntegrityPreferenceStore(context).isEnabled())
            assertFalse(MihomoConnectionOptionsPreferenceStore(context).read().amneziaNoiseEnabled)
            assertEquals(RoutingMode.Subscription, RoutingModePreferenceStore(context).read())
            assertEquals(DnsPrivacyMode.Automatic, DnsPrivacyPreferenceStore(context).readMode())
            assertEquals(DnsPrivacyPolicy.DEFAULT_DOH_URL, DnsPrivacyPreferenceStore(context).readDohUrl())
            assertEquals(DnsPrivacyPolicy.DEFAULT_DOT_ENDPOINT, DnsPrivacyPreferenceStore(context).readDotEndpoint())
            assertEquals(ConnectionMode.Vpn, ConnectionModePreferenceStore(context).read())
            LanSharingPreferenceStore(context).read().let { settings ->
                assertFalse(settings.enabled)
                assertTrue(settings.passwordRequired)
            }
            ConnectionSelectionPreferenceStore(context).let { store ->
                assertNull(store.readSelectedProfile(subscriptionId, listOf(profile)))
                assertTrue(store.readAutomaticTypes(subscriptionId, listOf(profile)).isEmpty())
                assertFalse(store.readDelaySortEnabled(subscriptionId))
                assertNull(store.readAutoSortedTestId(subscriptionId))
            }
            assertEquals(ConnectionTestSettings(), ConnectionTestSettingsPreferenceStore(context).read())
            assertEquals(ConnectionChainSettings(), ConnectionChainPreferenceStore(context).read())
            assertEquals(SubscriptionStore.DEFAULT_SUBSCRIPTION_ID, subscriptionStore.readSelectedSubscriptionId())
            assertNotNull(subscriptionStore.readUserSubscription(subscriptionId))
            assertEquals("proxies: []", subscriptionStore.readUserSubscriptionYaml(subscriptionId))
            assertEquals(
                42,
                subscriptionStore.readConnectionDelayRecords(
                    subscriptionId = subscriptionId,
                    profiles = listOf(profile),
                    nowMs = testedAt,
                ).single().delayMs,
            )
        } finally {
            preservedPreferences.forEach { name ->
                context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().remove(markerKey).commit()
            }
            subscriptionStore.deleteUserSubscription(subscriptionId)
            settingsPreferences.forEach { name ->
                context.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
            }
        }
    }
}
