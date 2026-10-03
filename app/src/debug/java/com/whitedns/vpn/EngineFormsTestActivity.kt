package com.whitedns.vpn

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.Gravity
import android.widget.TextView
import kotlinx.coroutines.*

/** Non-exported, debug-only host: no VPN starts or subscription fetching. */
class EngineFormsTestActivity : Activity() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    internal lateinit var ui: EngineProfilesUi
    var selections = 0
    override fun attachBaseContext(newBase: Context) { super.attachBaseContext(AppLocale.wrap(AppTheme.wrap(newBase))) }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val colors = WhiteDnsDesignTokens.forContext(this)
        setContentView(TextView(this).apply {
            text = "WhiteVPN"; typeface = WhiteDnsBodyBoldTypeface; textSize = 24f
            gravity = Gravity.CENTER; setTextColor(colors.textPrimary); setBackgroundColor(colors.background)
        })
        ui = EngineProfilesUi(this, scope, { false }) { selections++ }
        (lastNonConfigurationInstance as? EngineEditorState)?.let(ui::restoreEditor)
    }
    override fun onRetainNonConfigurationInstance(): Any? = ui.retainEditor()
    @Deprecated("Deprecated in Android API")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        ui.handleActivityResult(requestCode, resultCode, data)
    }
    override fun onDestroy() { ui.dispose(); scope.cancel(); super.onDestroy() }
}
