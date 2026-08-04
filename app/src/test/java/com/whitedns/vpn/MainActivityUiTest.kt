package com.whitedns.vpn

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.tabs.TabLayout
import com.google.android.material.textfield.TextInputLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = WhiteDnsApplication::class)
class MainActivityUiTest {

    private fun launch(): MainActivity {
        PrivacyPolicyAcceptanceStore(RuntimeEnvironment.getApplication()).acceptCurrentVersion()
        return Robolectric.buildActivity(MainActivity::class.java).setup().get()
    }

    private fun View.allViews(): Sequence<View> = sequence {
        yield(this@allViews)
        if (this@allViews is ViewGroup) {
            for (i in 0 until childCount) yieldAll(getChildAt(i).allViews())
        }
    }

    private fun MainActivity.root(): View = findViewById(android.R.id.content)

    @Test
    fun `dashboard shows connect button as an accessible button`() {
        val activity = launch()
        val connect = activity.root().allViews().filterIsInstance<SignalArcView>().single()

        assertEquals(activity.getString(R.string.connect_action_connect), connect.contentDescription)
        assertEquals("android.widget.Button", connect.accessibilityClassName.toString())
        assertTrue(connect.isClickable)
        assertTrue(connect.isEnabled)
    }

    @Test
    fun `status text announces state changes as a polite live region`() {
        val activity = launch()
        val status = activity.root().allViews()
            .filterIsInstance<TextView>()
            .firstOrNull { it.accessibilityLiveRegion == View.ACCESSIBILITY_LIVE_REGION_POLITE }

        assertNotNull("no live-region status TextView on the dashboard", status)
        val expected = activity.getString(
            R.string.status_with_indicator,
            activity.getString(DashboardStatePresenter.forState(VpnState.Stopped).titleRes),
        )
        assertEquals(expected, status!!.text.toString())
    }

    private fun MainActivity.selectTab(index: Int) {
        root().allViews().filterIsInstance<TabLayout>().single().getTabAt(index)!!.select()
    }

    private fun View.visibleTexts(): List<TextView> =
        allViews().filterIsInstance<TextView>().filter { it.isShown }.toList()

    @Test
    fun `subscriptions tab shows its screen with a heading title`() {
        val activity = launch()
        activity.selectTab(0)

        val title = activity.root().visibleTexts()
            .firstOrNull { it.text == activity.getString(R.string.subscriptions_title) }
        assertNotNull("subscriptions title not visible after selecting tab", title)
        assertTrue("subscriptions title must be an accessibility heading", title!!.isAccessibilityHeading)
    }

    @Test
    fun `advanced tab shows settings with heading sections`() {
        val activity = launch()
        activity.selectTab(2)

        val visible = activity.root().visibleTexts()
        val title = visible.firstOrNull { it.text == activity.getString(R.string.settings_title) }
        assertNotNull("settings title not visible after selecting tab", title)
        assertTrue(title!!.isAccessibilityHeading)

        val sectionHeadings = visible.count { it.isAccessibilityHeading }
        assertTrue(
            "expected settings title plus section headings, found $sectionHeadings",
            sectionHeadings > 1,
        )
    }

    private fun View.hasAccessibleName(): Boolean {
        if (!contentDescription.isNullOrBlank()) return true
        if (this is TextView && (!text.isNullOrBlank() || !hint.isNullOrBlank())) return true
        if (allViews().filterIsInstance<TextView>().any { !it.text.isNullOrBlank() }) return true
        // TalkBack resolves an empty edit text's name from its enclosing TextInputLayout hint.
        var parent = this.parent
        while (parent is View) {
            if (parent is TextInputLayout && !parent.hint.isNullOrBlank()) return true
            parent = parent.parent
        }
        return false
    }

    @Test
    fun `every clickable control on every tab exposes an accessible name`() {
        val activity = launch()
        for (tab in 0..2) {
            activity.selectTab(tab)
            val unnamed = activity.root().allViews()
                .filter { it.isShown && it.isClickable && it.isImportantForAccessibility }
                .filter { view -> !view.hasAccessibleName() }
                .toList()
            assertEquals(
                "tab $tab has clickable views with no accessible name: $unnamed",
                emptyList<View>(),
                unnamed,
            )
        }
    }
}
