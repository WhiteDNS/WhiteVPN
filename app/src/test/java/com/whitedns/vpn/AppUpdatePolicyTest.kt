package com.whitedns.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppUpdatePolicyTest {
    @Test
    fun onlyNewerReleaseVersionsPromptForUpdate() {
        assertTrue(AppUpdatePolicy.isNewer("v1.4.0", "1.3.0"))
        assertTrue(AppUpdatePolicy.isNewer("v1.10.0", "1.9.9"))
        assertFalse(AppUpdatePolicy.isNewer("v1.3", "1.3.0"))
        assertFalse(AppUpdatePolicy.isNewer("v1.2.9", "1.3.0"))
        assertFalse(AppUpdatePolicy.isNewer("latest", "1.3.0"))
    }
}
