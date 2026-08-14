package com.whitedns.vpn

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LanSharingPreferenceStoreTest {
    @Test
    fun credentialsPersistUntilRegenerated() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.getSharedPreferences("white_dns_lan_sharing", 0).edit().clear().commit()
        val store = LanSharingPreferenceStore(context)

        val initial = store.read()
        assertFalse(initial.enabled)
        assertTrue(initial.passwordRequired)
        assertEquals(24, initial.password.length)
        assertEquals(initial.password, LanSharingPreferenceStore(context).read().password)

        store.saveEnabled(true)
        store.savePasswordRequired(false)
        assertTrue(store.read().enabled)
        assertFalse(store.read().passwordRequired)
        assertNotEquals(initial.password, store.regeneratePassword())

        context.getSharedPreferences("white_dns_lan_sharing", 0).edit().clear().commit()
    }
}
