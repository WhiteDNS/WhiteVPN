package com.whitedns.vpn

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RoutingModePreferenceStoreTest {
    @Test
    fun defaultsPersistsAndRejectsInvalidValues() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("white_dns_routing", 0)
        prefs.edit().clear().commit()
        val store = RoutingModePreferenceStore(context)

        assertEquals(RoutingMode.Subscription, store.read())
        RoutingMode.values().forEach { mode ->
            store.save(mode)
            assertEquals(mode, RoutingModePreferenceStore(context).read())
        }
        prefs.edit().putString("mode", "invalid").commit()
        assertEquals(RoutingMode.Subscription, store.read())

        prefs.edit().clear().commit()
    }
}
