package com.whitedns.vpn

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.closeSoftKeyboard
import androidx.test.espresso.action.ViewActions.replaceText
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withHint
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Matchers.allOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityAppPreferencesTest {
    private lateinit var originalLanguage: AppLanguage
    private lateinit var originalTheme: AppThemeMode
    private lateinit var originalDnsMode: DnsPrivacyMode
    private lateinit var originalDohUrl: String
    private lateinit var originalDotEndpoint: String

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        originalLanguage = AppLanguagePreferenceStore(context).read()
        originalTheme = AppThemePreferenceStore(context).read()
        DnsPrivacyPreferenceStore(context).let { store ->
            originalDnsMode = store.readMode()
            originalDohUrl = store.readDohUrl()
            originalDotEndpoint = store.readDotEndpoint()
        }
        AppLocale.apply(context, AppLanguage.English)
        AppThemePreferenceStore(context).save(AppThemeMode.System)
        SubscriptionStore(context).saveSelectedSubscriptionId(SubscriptionStore.DEFAULT_SUBSCRIPTION_ID)
        PrivacyPolicyAcceptanceStore(context).acceptCurrentVersion()
    }

    @After
    fun tearDown() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        AppLocale.apply(context, originalLanguage)
        AppThemePreferenceStore(context).save(originalTheme)
        DnsPrivacyPreferenceStore(context).apply {
            saveMode(originalDnsMode)
            saveDohUrl(originalDohUrl)
            saveDotEndpoint(originalDotEndpoint)
        }
    }

    @Test
    fun appPreferencesReplaceTheVpnHomeMenu() {
        ActivityScenario.launch(MainActivity::class.java).use {
            onView(withContentDescription("Home options menu")).check(doesNotExist())

            openAppPreferences()

            onView(withContentDescription("Subscription: WhiteVPN")).check(matches(isDisplayed()))
            onView(withContentDescription("Theme: System default")).check(matches(isDisplayed()))
            onView(withContentDescription("App language: English")).check(matches(isDisplayed()))

            onView(withContentDescription("Subscription: WhiteVPN")).perform(click())
            onView(allOf(withText("WhiteVPN"), isDisplayed())).check(matches(isDisplayed()))
            pressBack()
            onView(withContentDescription("Theme: System default")).perform(click())
            onView(allOf(withText("Dark"), isDisplayed())).check(matches(isDisplayed()))
            pressBack()
            onView(withContentDescription("App language: English")).perform(click())
            onView(allOf(withText("فارسی"), isDisplayed())).check(matches(isDisplayed()))
        }
    }

    @Test
    fun switchingDnsModeCommitsTheFocusedEndpointAndShowsTheOtherModeValue() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = DnsPrivacyPreferenceStore(context).apply {
            saveMode(DnsPrivacyMode.DoH)
            saveDohUrl("https://doh.example/dns-query")
            saveDotEndpoint("tls://dot.example:853")
        }

        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            openConnections()
            onView(withHint("DoH address")).perform(
                scrollTo(),
                replaceText("https://new-doh.example/dns-query"),
                closeSoftKeyboard(),
            )
            onView(withContentDescription("Encrypted DNS: DoH")).perform(scrollTo(), click())
            onView(allOf(withText("DoT"), isDisplayed())).perform(click())

            assertEquals(DnsPrivacyMode.DoT, store.readMode())
            assertEquals("https://new-doh.example/dns-query", store.readDohUrl())
            onView(withHint("DoT address")).check(
                matches(allOf(isDisplayed(), withText("dot.example:853"))),
            )

            onView(withHint("DoT address")).perform(
                replaceText("new-dot.example:8853"),
                closeSoftKeyboard(),
            )
            onView(withContentDescription("Encrypted DNS: DoT")).perform(scrollTo(), click())
            onView(allOf(withText("DoH"), isDisplayed())).perform(click())

            assertEquals(DnsPrivacyMode.DoH, store.readMode())
            assertEquals("tls://new-dot.example:8853", store.readDotEndpoint())
            onView(withHint("DoH address")).check(
                matches(allOf(isDisplayed(), withText("https://new-doh.example/dns-query"))),
            )

            scenario.recreate()
            openConnections()
            onView(withContentDescription("Encrypted DNS: DoH")).perform(scrollTo()).check(matches(isDisplayed()))
            onView(withHint("DoH address")).perform(scrollTo()).check(
                matches(allOf(isDisplayed(), withText("https://new-doh.example/dns-query"))),
            )

            onView(withContentDescription("Encrypted DNS: DoH")).perform(click())
            onView(allOf(withText("Automatic"), isDisplayed())).perform(click())
            assertEquals(DnsPrivacyMode.Automatic, store.readMode())
            onView(withContentDescription("Encrypted DNS: Automatic")).perform(click())
            onView(allOf(withText("DoT"), isDisplayed())).perform(click())
            assertEquals(DnsPrivacyMode.DoT, store.readMode())
            onView(withHint("DoT address")).perform(scrollTo()).check(
                matches(allOf(isDisplayed(), withText("new-dot.example:8853"))),
            )
        }
    }

    @Test
    fun invalidDnsEndpointCannotChangeModeOrSavedValues() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val store = DnsPrivacyPreferenceStore(context).apply {
            saveMode(DnsPrivacyMode.DoH)
            saveDohUrl("https://doh.example/dns-query")
            saveDotEndpoint("tls://dot.example:853")
        }

        ActivityScenario.launch(MainActivity::class.java).use {
            openConnections()
            onView(withHint("DoH address")).perform(
                scrollTo(),
                replaceText("http://plaintext.example/dns-query"),
                closeSoftKeyboard(),
            )
            onView(withContentDescription("Encrypted DNS: DoH")).perform(scrollTo(), click())
            onView(allOf(withText("DoT"), isDisplayed())).perform(click())

            assertEquals(DnsPrivacyMode.DoH, store.readMode())
            assertEquals("https://doh.example/dns-query", store.readDohUrl())
            assertEquals("tls://dot.example:853", store.readDotEndpoint())
            pressBack()
            onView(withText("Invalid encrypted DNS address.")).check(matches(isDisplayed()))
        }
    }

    private fun openAppPreferences() {
        onView(allOf(withContentDescription("Settings"), isDisplayed())).perform(click())
        onView(allOf(withContentDescription("App preferences"), isDisplayed())).perform(click())
    }

    private fun openConnections() {
        onView(allOf(withContentDescription("Settings"), isDisplayed())).perform(click())
        onView(allOf(withContentDescription("Connections"), isDisplayed())).perform(click())
    }
}
