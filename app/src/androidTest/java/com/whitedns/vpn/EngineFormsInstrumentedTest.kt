package com.whitedns.vpn

import android.app.Activity
import android.content.Intent
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.content.FileProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.RootMatchers.isPlatformPopup
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.Base64

@RunWith(AndroidJUnit4::class)
class EngineFormsInstrumentedTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private lateinit var language: AppLanguage
    private lateinit var theme: AppThemeMode
    private lateinit var ids: Set<String>
    private val config = """
        [Interface]
        PrivateKey = AQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQEBAQE=
        Address = 10.55.0.2/32
        DNS = 10.55.0.1
        Jc = 4
        Jmin = 75
        Jmax = 135
        [Peer]
        PublicKey = AgICAgICAgICAgICAgICAgICAgICAgICAgICAgICAgI=
        AllowedIPs = 0.0.0.0/0, ::/0
        Endpoint = offline.example:10970
    """.trimIndent()
    @Before fun before() {
        language = AppLanguagePreferenceStore(context).read()
        theme = AppThemePreferenceStore(context).read()
        ids = EngineProfileStore(context).profiles().map { it.id }.toSet()
        AppLanguagePreferenceStore(context).save(AppLanguage.English)
        AppThemePreferenceStore(context).save(AppThemeMode.Light)
    }
    @After fun after() {
        val store = EngineProfileStore(context)
        store.profiles().filterNot { it.id in ids }.forEach { store.delete(it.id) }
        AppLanguagePreferenceStore(context).save(language)
        AppThemePreferenceStore(context).save(theme)
        File(context.cacheDir, "engine-import-tests").deleteRecursively()
    }
    private fun launch() = ActivityScenario.launch<EngineFormsTestActivity>(Intent(context, EngineFormsTestActivity::class.java))
    private fun editor(scenario: ActivityScenario<EngineFormsTestActivity>, kind: EngineKind = EngineKind.AMNEZIAWG) {
        scenario.onActivity { it.ui.restoreEditor(EngineEditorState(null, kind, emptyMap(), "", false)) }
    }
    private fun fields(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { fields(view.getChildAt(it)) } else emptyList()
    private fun dialog(host: EngineFormsTestActivity): androidx.appcompat.app.AlertDialog {
        val field = EngineProfilesUi::class.java.getDeclaredField("editorDialog").apply { isAccessible = true }
        return field.get(host.ui) as androidx.appcompat.app.AlertDialog
    }
    private fun screenshot(scenario: ActivityScenario<EngineFormsTestActivity>, name: String) {
        // Capture empty forms/generated test keys only; production editors remain FLAG_SECURE.
        scenario.onActivity { dialog(it).window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
        instrumentation.waitForIdleSync(); SystemClock.sleep(350)
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: error("No screenshot")
        File(context.filesDir, "engine-form-" + name + ".png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private fun assertSecretSpacing(edit: TextInputEditText, label: String) {
        val layout = generateSequence(edit.parent) { it.parent }.filterIsInstance<TextInputLayout>().first()
        val eye = layout.findViewById<View>(com.google.android.material.R.id.text_input_end_icon)
        assertTrue(label + ": eye must be measured", eye.width > 0)
        val editPosition = IntArray(2); val eyePosition = IntArray(2)
        edit.getLocationOnScreen(editPosition); eye.getLocationOnScreen(eyePosition)
        val textLeft = editPosition[0] + edit.compoundPaddingLeft
        val textRight = editPosition[0] + edit.width - edit.compoundPaddingRight
        val eyeLeft = eyePosition[0]; val eyeRight = eyeLeft + eye.width
        assertTrue(label + ": text viewport overlaps eye (text=" + textLeft + ".." + textRight + ", eye=" + eyeLeft + ".." + eyeRight + ")",
            textRight <= eyeLeft || textLeft >= eyeRight)
        assertTrue(label + ": text viewport must remain usable", textRight - textLeft > eye.width)
    }
    @Test fun longSecretsStayOutsideTheEyeIconWhenHiddenAndRevealed() {
        val cases = listOf(
            EngineKind.SSH to emptyMap(), EngineKind.SSH to mapOf("authType" to "key"),
            EngineKind.DNS to emptyMap(), EngineKind.DNS to mapOf("engine" to "vaydns"),
            EngineKind.DNS to mapOf("engine" to "masterdns"),
            EngineKind.IKEV2 to emptyMap(), EngineKind.IKEV2 to mapOf("authType" to "psk"),
            EngineKind.AMNEZIAWG to emptyMap())
        listOf(AppLanguage.English to AppThemeMode.Light, AppLanguage.Persian to AppThemeMode.Dark).forEach { (lang, mode) ->
            AppLanguagePreferenceStore(context).save(lang); AppThemePreferenceStore(context).save(mode)
            launch().use { scenario ->
                cases.forEach { (kind, options) ->
                    val secrets = EngineFields.fields(kind, options).filter { it.secret }
                    val values = options + secrets.associate { it.key to ("Generated-test-secret-0123456789".repeat(24) + if (it.multiline) "\n" + "Generated-test-key-ABC123".repeat(24) else "") }
                    scenario.onActivity { it.ui.restoreEditor(EngineEditorState(null, kind, values, "", false)) }
                    instrumentation.waitForIdleSync()
                    val screenshotCase = (kind == EngineKind.SSH && options.isEmpty()) || kind == EngineKind.AMNEZIAWG
                    listOf(false, true, false).forEach { revealed ->
                        scenario.onActivity { host ->
                            val inputs = fields(dialog(host).window!!.decorView).filterIsInstance<TextInputEditText>()
                            secrets.forEach { secret ->
                                val edit = inputs.single { it.tag == secret.key }
                                val layout = generateSequence(edit.parent) { it.parent }.filterIsInstance<TextInputLayout>().first()
                                val hidden = edit.transformationMethod is android.text.method.PasswordTransformationMethod
                                if (hidden == revealed) layout.findViewById<View>(com.google.android.material.R.id.text_input_end_icon).performClick()
                                assertEquals(!revealed, edit.transformationMethod is android.text.method.PasswordTransformationMethod)
                                edit.requestFocus(); edit.setSelection(edit.length())
                            }
                        }
                        instrumentation.waitForIdleSync()
                        scenario.onActivity { host ->
                            val inputs = fields(dialog(host).window!!.decorView).filterIsInstance<TextInputEditText>()
                            secrets.forEach { secret ->
                                val edit = inputs.single { it.tag == secret.key }
                                assertEquals(values[secret.key], edit.text.toString())
                                assertSecretSpacing(edit, lang.languageTag + "/" + kind.wireName + "/" + secret.key + "/" + revealed)
                            }
                        }
                        if (screenshotCase) screenshot(scenario, "password-" + lang.languageTag + "-" + kind.wireName + "-" + if (revealed) "revealed" else "hidden")
                    }
                }
                // The link import dialog shares the same secret input but has its own window.
                onView(withText(R.string.engine_import_link)).inRoot(isDialog()).perform(click())
                onView(withTagValue(org.hamcrest.Matchers.equalTo("vpnLink"))).inRoot(isDialog()).perform(replaceText("vpn://" + "GeneratedTestLink0123456789".repeat(24)), closeSoftKeyboard())
                listOf(false, true, false).forEach { revealed ->
                    onView(withTagValue(org.hamcrest.Matchers.equalTo("vpnLink"))).inRoot(isDialog()).check { view, error ->
                        if (error != null) throw error
                        val edit = view as TextInputEditText
                        val layout = generateSequence(edit.parent) { it.parent }.filterIsInstance<TextInputLayout>().first()
                        val hidden = edit.transformationMethod is android.text.method.PasswordTransformationMethod
                        if (hidden == revealed) layout.findViewById<View>(com.google.android.material.R.id.text_input_end_icon).performClick()
                        assertEquals(!revealed, edit.transformationMethod is android.text.method.PasswordTransformationMethod)
                    }
                    instrumentation.waitForIdleSync()
                    onView(withTagValue(org.hamcrest.Matchers.equalTo("vpnLink"))).inRoot(isDialog()).check { view, error ->
                        if (error != null) throw error
                        assertSecretSpacing(view as TextInputEditText, lang.languageTag + "/vpnLink/" + revealed)
                    }
                }
            }
        }
    }
    @Test fun psiphonCountryPickerShowsLocalizedNamesAndPreservesChoiceWhenChangingMode() {
        listOf(AppLanguage.English to AppThemeMode.Light, AppLanguage.Persian to AppThemeMode.Dark).forEach { (lang, mode) ->
            AppLanguagePreferenceStore(context).save(lang); AppThemePreferenceStore(context).save(mode)
            launch().use { scenario ->
                scenario.onActivity { host ->
                    host.ui.restoreEditor(EngineEditorState(null, EngineKind.PSIPHON, mapOf("country" to "us"), "", false))
                    val country = fields(dialog(host).window!!.decorView).single { it.tag == "country" } as com.google.android.material.textfield.MaterialAutoCompleteTextView
                    assertEquals(android.text.InputType.TYPE_NULL, country.inputType)
                    assertEquals(PsiphonRegions.name("US", host.resources.configuration.locales[0]), country.text.toString())
                    assertEquals("US", host.ui.retainEditor()!!.values["country"])
                }
                onView(withTagValue(org.hamcrest.Matchers.equalTo("country"))).inRoot(isDialog()).perform(click())
                var auto = ""
                scenario.onActivity { auto = it.getString(R.string.engine_option_auto) }
                onData(org.hamcrest.Matchers.equalTo(auto)).inRoot(isPlatformPopup()).perform(click())
                scenario.onActivity { assertEquals("", it.ui.retainEditor()!!.values["country"]) }
                onView(withTagValue(org.hamcrest.Matchers.equalTo("country"))).inRoot(isDialog()).perform(click())
                val label = PsiphonRegions.name("US", java.util.Locale.forLanguageTag(lang.languageTag))
                onData(org.hamcrest.Matchers.equalTo(label)).inRoot(isPlatformPopup()).perform(click())
                onView(withTagValue(org.hamcrest.Matchers.equalTo("mode"))).inRoot(isDialog()).perform(click())
                var direct = ""
                scenario.onActivity { direct = it.getString(R.string.engine_option_direct) }
                onData(org.hamcrest.Matchers.equalTo(direct)).inRoot(isPlatformPopup()).perform(click())
                scenario.onActivity { assertEquals("US", it.ui.retainEditor()!!.values["country"]) }
                screenshot(scenario, "country-" + lang.languageTag)
            }
        }
    }

    @Test fun everyEngineUsesAppTypographyPaletteAndOutlinedFieldsInBothLanguagesAndThemes() {
        listOf(AppLanguage.English to AppThemeMode.Light, AppLanguage.Persian to AppThemeMode.Dark).forEach { (lang, mode) ->
            AppLanguagePreferenceStore(context).save(lang); AppThemePreferenceStore(context).save(mode)
            launch().use { scenario ->
                EngineKind.selectable.forEach { kind ->
                    editor(scenario, kind)
                    scenario.onActivity { host ->
                        val nodes = fields(dialog(host).window!!.decorView)
                        val colors = WhiteDnsDesignTokens.palette(mode == AppThemeMode.Dark)
                        assertEquals(colors.surface, (dialog(host).window!!.decorView.background as android.graphics.drawable.GradientDrawable).color!!.defaultColor)
                        val inputs = nodes.filterIsInstance<TextInputEditText>()
                        assertTrue(inputs.isNotEmpty())
                        inputs.forEach { assertEquals(WhiteDnsBodyTypeface, it.typeface); assertEquals(colors.textPrimary, it.currentTextColor); assertFalse(it.isSaveEnabled) }
                        nodes.filterIsInstance<TextInputLayout>().forEach { assertEquals(TextInputLayout.BOX_BACKGROUND_OUTLINE, it.boxBackgroundMode) }
                        assertTrue(dialog(host).window!!.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
                    }
                    screenshot(scenario, lang.languageTag + "-" + mode.wireName + "-" + kind.wireName)
                }
            }
        }
    }
    @Test fun linkImportValidatesBeforeReplacingDraftAndSavesWithoutSelectingOrConnecting() {
        val store = EngineProfileStore(context); val selected = store.selectedEngineId()
        launch().use { scenario ->
            editor(scenario)
            onView(withTagValue(org.hamcrest.Matchers.equalTo("name"))).inRoot(isDialog()).perform(replaceText("Imported test"), closeSoftKeyboard())
            onView(withText(R.string.engine_import_link)).inRoot(isDialog()).perform(click())
            onView(withTagValue(org.hamcrest.Matchers.equalTo("vpnLink"))).inRoot(isDialog()).perform(replaceText("vpn://invalid"), closeSoftKeyboard())
            onView(withText(R.string.engine_import_apply)).inRoot(isDialog()).perform(click())
            onView(withTagValue(org.hamcrest.Matchers.equalTo("vpnLink"))).inRoot(isDialog()).perform(replaceText("vpn://" + Base64.getUrlEncoder().withoutPadding().encodeToString(config.toByteArray())), closeSoftKeyboard())
            onView(withText(R.string.engine_import_apply)).inRoot(isDialog()).perform(click())
            scenario.onActivity { host ->
                assertEquals(config, host.ui.retainEditor()!!.values["config"]); assertEquals("Imported test", host.ui.retainEditor()!!.name)
                assertEquals(0, host.selections)
            }
            onView(withText(R.string.engine_save)).inRoot(isDialog()).perform(click())
            val imported = store.profiles().single { it.name == "Imported test" }
            assertEquals(config, imported.value("config")); assertEquals(selected, store.selectedEngineId())
            assertEquals(VpnState.Stopped, VpnRuntimeStateStore.read(context))
        }
    }
    @Test fun documentResultImportsContentAndRotationAndCancelKeepTheUnsavedDraft() {
        launch().use { scenario ->
            editor(scenario)
            onView(withTagValue(org.hamcrest.Matchers.equalTo("name"))).inRoot(isDialog()).perform(replaceText("File draft"), closeSoftKeyboard())
            val file = File(context.cacheDir, "engine-import-tests/client.conf").apply { parentFile!!.mkdirs(); writeText("\uFEFF" + config) }
            val uri = FileProvider.getUriForFile(context, "com.whitedns.vpn.engine-test-files", file)
            scenario.onActivity { host ->
                val field = EngineProfilesUi::class.java.getDeclaredField("waitingForFile").apply { isAccessible = true }
                field.setBoolean(host.ui, true)
            }
            scenario.recreate()
            scenario.onActivity { host -> host.ui.handleActivityResult(EngineProfilesUi.REQUEST_AMNEZIA_FILE, Activity.RESULT_OK, Intent().setData(uri)) }
            val deadline = SystemClock.elapsedRealtime() + 5_000
            var imported = false
            while (!imported && SystemClock.elapsedRealtime() < deadline) {
                scenario.onActivity { imported = it.ui.retainEditor()?.values?.get("config") == config }
                if (!imported) SystemClock.sleep(50)
            }
            assertTrue(imported)
            scenario.onActivity { host ->
                assertEquals("File draft", host.ui.retainEditor()!!.name)
                host.ui.handleActivityResult(EngineProfilesUi.REQUEST_AMNEZIA_FILE, Activity.RESULT_CANCELED, null)
                assertEquals(config, host.ui.retainEditor()!!.values["config"])
                assertEquals(0, host.selections)
            }
            scenario.recreate()
            scenario.onActivity { assertEquals(config, it.ui.retainEditor()!!.values["config"]) }
        }
    }
    @Test fun changingEngineOptionsKeepsValuesAndShowsTheCorrectStyledFields() {
        launch().use { scenario ->
            val cases = listOf(
                Triple(EngineKind.SSH, "authType", "key"), Triple(EngineKind.PSIPHON, "mode", "direct"),
                Triple(EngineKind.TOR, "bridgeMode", "custom"), Triple(EngineKind.DNS, "engine", "masterdns"),
                Triple(EngineKind.IKEV2, "authType", "certificate"))
            cases.forEach { (kind, key, value) ->
                editor(scenario, kind)
                onView(withTagValue(org.hamcrest.Matchers.equalTo("name"))).inRoot(isDialog()).perform(replaceText("Keep this name"), closeSoftKeyboard())
                onView(withTagValue(org.hamcrest.Matchers.equalTo(key))).inRoot(isDialog()).perform(scrollTo(), click())
                val label = when (value) {
                    "key" -> context.getString(R.string.engine_private_key)
                    "direct" -> context.getString(R.string.engine_option_direct)
                    "custom" -> context.getString(R.string.engine_option_custom_bridges)
                    "certificate" -> context.getString(R.string.engine_option_certificate)
                    else -> "MasterDNS"
                }
                onData(org.hamcrest.Matchers.equalTo(label)).inRoot(isPlatformPopup()).perform(click())
                scenario.onActivity { host ->
                    val state = host.ui.retainEditor()!!
                    assertEquals(value, state.values[key]); assertEquals("Keep this name", state.name)
                    val tags = fields(dialog(host).window!!.decorView).mapNotNull { it.tag as? String }.toSet()
                    when (kind) {
                        EngineKind.SSH -> { assertTrue("privateKey" in tags); assertFalse("password" in tags) }
                        EngineKind.PSIPHON -> { assertFalse("cdnIps" in tags); assertFalse("cdnSni" in tags) }
                        EngineKind.TOR -> assertTrue("bridges" in tags)
                        EngineKind.DNS -> { assertTrue("encryptionMethod" in tags); assertFalse("dnsTransport" in tags) }
                        EngineKind.IKEV2 -> { assertTrue("userCertAlias" in tags); assertFalse("password" in tags) }
                        else -> error("Unexpected engine")
                    }
                }
                screenshot(scenario, "options-" + kind.wireName)
            }
        }
    }
    @Test fun fileButtonOpensSystemDocumentPickerWithoutStoragePermission() {
        launch().use { scenario ->
            editor(scenario)
            onView(withText(R.string.engine_import_file)).inRoot(isDialog()).perform(click())
            instrumentation.waitForIdleSync(); SystemClock.sleep(350)
            val resumed = instrumentation.uiAutomation.executeShellCommand("dumpsys activity activities").let { pfd -> android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).bufferedReader().use { it.readText() } }
            assertTrue(resumed.contains("documentsui"))
            val pickerDeadline = SystemClock.elapsedRealtime() + 5_000
            var pickerPackage: String? = null
            while (SystemClock.elapsedRealtime() < pickerDeadline) {
                pickerPackage = instrumentation.uiAutomation.rootInActiveWindow?.packageName?.toString()
                if (pickerPackage == "com.android.documentsui") break
                SystemClock.sleep(100)
            }
            assertEquals("com.android.documentsui", pickerPackage)
            assertTrue(instrumentation.uiAutomation.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK))
            instrumentation.waitForIdleSync()
            val deadline = SystemClock.elapsedRealtime() + 5_000
            while (scenario.state != androidx.lifecycle.Lifecycle.State.RESUMED && SystemClock.elapsedRealtime() < deadline) SystemClock.sleep(100)
            assertEquals(androidx.lifecycle.Lifecycle.State.RESUMED, scenario.state)
        }
    }
}
