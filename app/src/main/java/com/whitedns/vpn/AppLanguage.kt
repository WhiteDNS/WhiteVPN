package com.whitedns.vpn

import android.content.Context
import android.content.res.Configuration
import androidx.annotation.StringRes
import java.util.Locale

enum class AppLanguage(
    val languageTag: String,
    @param:StringRes val labelRes: Int,
) {
    Persian("fa", R.string.language_persian),
    English("en", R.string.language_english),
    ;

    companion object {
        fun fromLanguageTag(value: String?): AppLanguage =
            entries.firstOrNull { it.languageTag == value } ?: Persian
    }
}

class AppLanguagePreferenceStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun read(): AppLanguage = AppLanguage.fromLanguageTag(prefs.getString(KEY_LANGUAGE, null))

    fun save(language: AppLanguage) {
        prefs.edit().putString(KEY_LANGUAGE, language.languageTag).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "white_dns_language"
        const val KEY_LANGUAGE = "language"
    }
}

object AppLocale {
    fun wrap(context: Context): Context {
        val language = AppLanguagePreferenceStore(context).read()
        val locale = Locale.forLanguageTag(language.languageTag)
        Locale.setDefault(locale)
        val configuration = Configuration(context.resources.configuration).apply {
            setLocale(locale)
            setLayoutDirection(locale)
        }
        return context.createConfigurationContext(configuration)
    }

    @Suppress("DEPRECATION")
    fun apply(context: Context, language: AppLanguage) {
        AppLanguagePreferenceStore(context).save(language)
        val locale = Locale.forLanguageTag(language.languageTag)
        Locale.setDefault(locale)
        val configuration = Configuration(context.resources.configuration).apply {
            setLocale(locale)
            setLayoutDirection(locale)
        }
        context.resources.updateConfiguration(configuration, context.resources.displayMetrics)
    }
}
