package com.whitedns.vpn

import android.content.ComponentName
import android.content.Intent
import android.content.pm.PackageManager
import android.service.quicksettings.TileService
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class QuickSettingsTileIntentTest {
    @Test
    fun longPressResolvesToMainActivity() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val intent = Intent(TileService.ACTION_QS_TILE_PREFERENCES)
            .setPackage(context.packageName)
            .putExtra(Intent.EXTRA_COMPONENT_NAME, ComponentName(context, WhiteDnsTileService::class.java))

        val activity = context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo

        assertEquals(MainActivity::class.java.name, activity?.name)
        assertTrue("System UI must be allowed to open the activity", activity?.exported == true)
    }
}
