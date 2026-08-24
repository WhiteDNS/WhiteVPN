package com.whitedns.vpn

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class InstalledAppRepositoryTest {
    @Test
    fun includesApplicationsWithoutLauncherActivities() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val targetContext = instrumentation.targetContext
        val testPackage = instrumentation.context.packageName

        assertNull(targetContext.packageManager.getLaunchIntentForPackage(testPackage))
        assertTrue(
            InstalledAppRepository(targetContext)
                .loadLaunchableApps()
                .any { it.packageName == testPackage },
        )
    }
}
