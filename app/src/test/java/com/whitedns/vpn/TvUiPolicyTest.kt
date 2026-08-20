package com.whitedns.vpn

import android.content.res.Configuration
import org.junit.Assert.assertEquals
import org.junit.Test

class TvUiPolicyTest {
    @Test
    fun safeInsetsApplyOnlyToTelevisions() {
        assertEquals(96 to 54, televisionSafeInsets(Configuration.UI_MODE_TYPE_TELEVISION, 1920, 1080))
        assertEquals(0 to 0, televisionSafeInsets(Configuration.UI_MODE_TYPE_NORMAL, 1920, 1080))
    }
}
