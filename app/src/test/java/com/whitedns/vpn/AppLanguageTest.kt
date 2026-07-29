package com.whitedns.vpn

import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Test

class AppLanguageTest {
    @Test
    fun storedLanguageTagsResolveAndDefaultToPersian() {
        assertEquals(AppLanguage.English, AppLanguage.fromLanguageTag("en"))
        assertEquals(AppLanguage.Persian, AppLanguage.fromLanguageTag("fa"))
        assertEquals(AppLanguage.Persian, AppLanguage.fromLanguageTag(null))
    }

    @Test
    fun themeModesResolveAndSystemModeFollowsDevice() {
        assertEquals(AppThemeMode.Light, AppThemeMode.fromWireName("light"))
        assertEquals(AppThemeMode.Dark, AppThemeMode.fromWireName("dark"))
        assertEquals(AppThemeMode.System, AppThemeMode.fromWireName(null))
        assertEquals(
            Configuration.UI_MODE_NIGHT_YES,
            AppTheme.resolveNightMode(AppThemeMode.System, Configuration.UI_MODE_NIGHT_YES),
        )
        assertEquals(
            Configuration.UI_MODE_NIGHT_NO,
            AppTheme.resolveNightMode(AppThemeMode.Light, Configuration.UI_MODE_NIGHT_YES),
        )
    }
}
