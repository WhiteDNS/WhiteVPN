package com.whitedns.vpn

import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.pressBack
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.hamcrest.Matchers.allOf
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MainActivityAppPreferencesTest {
    private lateinit var originalLanguage: AppLanguage
    private lateinit var originalTheme: AppThemeMode

    @Before
    fun setUp() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        originalLanguage = AppLanguagePreferenceStore(context).read()
        originalTheme = AppThemePreferenceStore(context).read()
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

    private fun openAppPreferences() {
        onView(allOf(withContentDescription("Settings"), isDisplayed())).perform(click())
        onView(allOf(withContentDescription("App preferences"), isDisplayed())).perform(click())
    }
}
