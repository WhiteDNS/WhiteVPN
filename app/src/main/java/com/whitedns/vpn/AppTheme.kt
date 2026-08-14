package com.whitedns.vpn

import android.content.Context
import android.content.res.Configuration
import android.content.res.Resources
import androidx.annotation.StringRes

enum class AppThemeMode(
    val wireName: String,
    @param:StringRes val labelRes: Int,
) {
    System("system", R.string.theme_system),
    Light("light", R.string.theme_light),
    Dark("dark", R.string.theme_dark),
    ;

    companion object {
        fun fromWireName(value: String?): AppThemeMode =
            entries.firstOrNull { it.wireName == value } ?: System
    }
}

class AppThemePreferenceStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun read(): AppThemeMode = AppThemeMode.fromWireName(prefs.getString(KEY_THEME, null))

    fun save(mode: AppThemeMode) {
        prefs.edit().putString(KEY_THEME, mode.wireName).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "white_dns_theme"
        const val KEY_THEME = "theme"
    }
}

object AppTheme {
    fun wrap(context: Context): Context {
        val configuration = Configuration(context.resources.configuration)
        configuration.uiMode =
            (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or resolveNightMode(
                AppThemePreferenceStore(context).read(),
                Resources.getSystem().configuration.uiMode,
            )
        return context.createConfigurationContext(configuration)
    }

    internal fun resolveNightMode(mode: AppThemeMode, systemUiMode: Int): Int = when (mode) {
        AppThemeMode.System -> systemUiMode and Configuration.UI_MODE_NIGHT_MASK
        AppThemeMode.Light -> Configuration.UI_MODE_NIGHT_NO
        AppThemeMode.Dark -> Configuration.UI_MODE_NIGHT_YES
    }
}
