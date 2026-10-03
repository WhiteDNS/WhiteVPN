package com.whitedns.vpn

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.*
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.tabs.TabLayout
import org.hamcrest.Matchers.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ProfilesNavigationInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var language: AppLanguage
    private lateinit var theme: AppThemeMode
    private lateinit var ids: Set<String>
    private var engine: String? = null
    private lateinit var subscription: String
    private var accepted: Int? = null
    @Before fun before() {
        language = AppLanguagePreferenceStore(context).read(); theme = AppThemePreferenceStore(context).read()
        val store = EngineProfileStore(context)
        ids = store.profiles().map { it.id }.toSet(); engine = store.selectedEngineId()
        subscription = UserSubscriptionManager(context).selectedId()
        val privacy = context.getSharedPreferences("white_dns_privacy_policy", Context.MODE_PRIVATE)
        accepted = if (privacy.contains("accepted_policy_version")) privacy.getInt("accepted_policy_version", 0) else null
        PrivacyPolicyAcceptanceStore(context).acceptCurrentVersion()
        store.selectMihomo()
        UserSubscriptionManager(context).select(SubscriptionStore.DEFAULT_SUBSCRIPTION_ID)
        assertEquals(VpnState.Stopped, VpnRuntimeStateStore.read(context))
    }
    @After fun after() {
        val store = EngineProfileStore(context)
        store.profiles().filterNot { it.id in ids }.forEach { store.delete(it.id) }
        val route = context.getSharedPreferences("whitevpn_route", Context.MODE_PRIVATE).edit()
        if (engine == null) route.remove("engine") else route.putString("engine", engine)
        route.commit()
        UserSubscriptionManager(context).select(subscription)
        AppLanguagePreferenceStore(context).save(language); AppThemePreferenceStore(context).save(theme)
        val policy = context.getSharedPreferences("white_dns_privacy_policy", Context.MODE_PRIVATE).edit()
        if (accepted == null) policy.remove("accepted_policy_version") else policy.putInt("accepted_policy_version", accepted!!)
        policy.commit()
    }
    private fun nodes(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { nodes(view.getChildAt(it)) } else emptyList()
    private fun launch() = ActivityScenario.launch<ProfilesNavigationTestActivity>(Intent(context, ProfilesNavigationTestActivity::class.java))
    private fun screenshot(name: String) {
        instrumentation.waitForIdleSync(); SystemClock.sleep(250)
        // The profiles/home pages expose names and public metadata, never profile secrets.
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: error("No screenshot")
        File(context.filesDir, "profile-navigation-" + name + ".png").outputStream().use {
            bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        bitmap.recycle()
    }
    @Test fun creationSelectionAndSourceSwitchUseOneProfileListAndNeverStartAVpn() {
        listOf(AppLanguage.English to AppThemeMode.Light, AppLanguage.Persian to AppThemeMode.Dark).forEach { (lang, mode) ->
            AppLanguagePreferenceStore(context).save(lang); AppThemePreferenceStore(context).save(mode)
            EngineProfileStore(context).selectMihomo()
            launch().use { scenario ->
                onView(withTagValue(equalTo("home-selected-profile"))).perform(scrollTo(), click())
                onView(withText(R.string.engine_entry_title)).check(doesNotExist())
                scenario.onActivity { host ->
                    val list = nodes(host.window.decorView).single { it.tag == "profiles-list" }
                    SubscriptionStore.BUILT_IN_SUBSCRIPTION_IDS.forEach { id ->
                        assertEquals(1, nodes(list).count { it.tag == "saved-profile:subscription:" + id })
                    }
                }
                screenshot(lang.languageTag + "-before")
                onView(withTagValue(equalTo("add-profile"))).perform(scrollTo(), click())
                onView(withText("Psiphon")).inRoot(isDialog()).perform(click())
                val name = "UI Psiphon " + lang.languageTag
                onView(withTagValue(equalTo("name"))).inRoot(isDialog()).perform(replaceText(name), closeSoftKeyboard())
                scenario.recreate()
                onView(withTagValue(equalTo("name"))).inRoot(isDialog()).check(matches(withText(name)))
                scenario.onActivity { host ->
                    assertEquals(2, nodes(host.window.decorView).filterIsInstance<TabLayout>().single().selectedTabPosition)
                }
                onView(withText(R.string.engine_save)).inRoot(isDialog()).perform(click())
                val saved = EngineProfileStore(context).profiles().single { it.name == name }
                assertNull(EngineProfileStore(context).selectedEngineId())
                assertEquals(VpnState.Stopped, VpnRuntimeStateStore.read(context))
                onView(withTagValue(equalTo("saved-profile:engine:" + saved.id))).perform(scrollTo()).check(matches(isDisplayed()))
                screenshot(lang.languageTag + "-saved")
                onView(withTagValue(equalTo("saved-profile:engine:" + saved.id))).perform(click())
                onView(withText(R.string.engine_select)).inRoot(isDialog()).perform(click())
                assertEquals(saved.id, EngineProfileStore(context).selectedEngineId())
                assertEquals(VpnState.Stopped, VpnRuntimeStateStore.read(context))
                onView(withTagValue(equalTo("home-selected-profile"))).perform(scrollTo()).check(matches(isDisplayed()))
                scenario.onActivity { host ->
                    val root = host.window.decorView
                    assertEquals(1, nodes(root).filterIsInstance<TabLayout>().single().selectedTabPosition)
                    val row = nodes(root).single { it.tag == "home-selected-profile" }
                    assertTrue(nodes(row).filterIsInstance<TextView>().any { it.text.contains(name) })
                    assertFalse(nodes(root).any { it.tag == "settings-connection-mode" && it.isShown })
                }
                screenshot(lang.languageTag + "-selected-home")
                onView(withTagValue(equalTo("home-selected-profile"))).perform(click())
                scenario.onActivity { host ->
                    val list = nodes(host.window.decorView).single { it.tag == "profiles-list" }
                    assertEquals(1, nodes(list).count { it.tag == "selected-profile-badge" })
                    val selectedCard = nodes(list).single { it.tag == "saved-profile:engine:" + saved.id }
                    assertEquals(1, nodes(selectedCard).count { it.tag == "selected-profile-badge" })
                }
                screenshot(lang.languageTag + "-selected-list")
                // The default Mihomo source remains remembered while an engine is selected.
                // Selecting that same source must still clear the engine reference.
                onView(withTagValue(equalTo("saved-profile:subscription:" + SubscriptionStore.DEFAULT_SUBSCRIPTION_ID))).perform(scrollTo(), click())
                assertNull(EngineProfileStore(context).selectedEngineId())
                assertEquals(VpnState.Stopped, VpnRuntimeStateStore.read(context))
                onView(withTagValue(equalTo("home-selected-profile"))).perform(scrollTo()).check(matches(isDisplayed()))
            }
        }
    }
}
