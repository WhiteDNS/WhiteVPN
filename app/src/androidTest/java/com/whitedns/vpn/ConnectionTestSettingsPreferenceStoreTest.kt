package com.whitedns.vpn

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConnectionTestSettingsPreferenceStoreTest {
    @Test
    fun defaultsPersistAndInvalidStoredValuesAreClamped() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("white_dns_connection_test", 0)
        prefs.edit().clear().commit()
        val store = ConnectionTestSettingsPreferenceStore(context)

        assertEquals(ConnectionTestSettings(15, 10, 1), store.read())
        store.save(ConnectionTestSettings(7, 4, 8))
        assertEquals(ConnectionTestSettings(7, 4, 8), ConnectionTestSettingsPreferenceStore(context).read())

        store.save(ConnectionTestSettings(-1, 999, 0))
        assertEquals(ConnectionTestSettings(1, 100, 1), store.read())

        prefs.edit()
            .putInt("timeout_seconds", 99)
            .putInt("concurrency", 999)
            .putInt("speed_test_megabytes", 999)
            .commit()
        assertEquals(ConnectionTestSettings(30, 100, 100), store.read())

        prefs.edit().clear().commit()
    }
}
