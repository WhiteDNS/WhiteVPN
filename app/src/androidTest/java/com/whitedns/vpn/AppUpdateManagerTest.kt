package com.whitedns.vpn

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest

@RunWith(AndroidJUnit4::class)
class AppUpdateManagerTest {
    private val target = InstrumentationRegistry.getInstrumentation().targetContext
    private val testId = "update-test-${System.nanoTime()}"
    private val root = File(target.cacheDir, testId)
    private val context = object : ContextWrapper(target) {
        override fun getApplicationContext(): Context = this
        override fun getFilesDir(): File = File(root, "files").apply { mkdirs() }
        override fun getExternalFilesDir(type: String?): File = File(root, "external/${type.orEmpty()}").apply { mkdirs() }
        override fun getSharedPreferences(name: String, mode: Int) = target.getSharedPreferences(testId, mode)
    }
    private val preferences = context.getSharedPreferences("app_updates", Context.MODE_PRIVATE)

    @After
    fun cleanUp() {
        target.deleteSharedPreferences(testId)
        root.deleteRecursively()
    }

    @Test
    fun skippedVersionAndCachedBadgeSurviveManagerRecreation() {
        preferences.edit().putString("latest", releaseJson("v999.0.0").toString()).commit()
        AppUpdateManager(context).skipVersion("v999.0.0")
        val reopened = AppUpdateManager(context)
        assertEquals("999.0.0", reopened.skippedVersion())
        assertEquals("v999.0.0", reopened.availableRelease()?.version)
        assertFalse(AppUpdatePolicy.shouldPrompt("v999.0.0", BuildConfig.VERSION_NAME, reopened.skippedVersion()))
        assertTrue(AppUpdatePolicy.shouldPrompt("v999.1.0", BuildConfig.VERSION_NAME, reopened.skippedVersion()))
    }

    @Test
    fun malformedCacheIsIgnoredAndInstalledUpdateIsCleaned() = runBlocking {
        preferences.edit().putString("latest", releaseJson("v999.0.0")
            .put("html_url", "https://example.com/releases/v999.0.0").toString()).commit()
        assertNull(AppUpdateManager(context).availableRelease())
        val snapshot = File(context.filesDir, "updates/update.apk").apply { parentFile!!.mkdirs(); writeText("old") }
        seedPending(releaseJson(BuildConfig.VERSION_NAME, snapshot.length()), "0".repeat(64))
        preferences.edit().putString("latest", releaseJson(BuildConfig.VERSION_NAME).toString()).commit()
        val reopened = AppUpdateManager(context)
        assertEquals(AppDownloadState.Idle, reopened.refreshDownload())
        assertNull(reopened.availableRelease())
        assertNull(reopened.pendingRelease())
        assertFalse(snapshot.exists())
    }

    @Test
    fun damagedOrInvalidPrivateApksNeverReachInstaller() = runBlocking {
        val bytes = "This is not an APK".toByteArray()
        val correctDigest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        for (digest in listOf("0".repeat(64), correctDigest)) {
            val snapshot = File(context.filesDir, "updates/update.apk").apply { parentFile!!.mkdirs(); writeBytes(bytes) }
            seedPending(releaseJson("v999.0.0", bytes.size.toLong()), digest)
            val reopened = AppUpdateManager(context)
            assertNull(reopened.apkForInstall())
            assertEquals(AppDownloadState.Failed, reopened.refreshDownload())
            assertFalse(snapshot.exists())
            reopened.cancelDownload()
            assertEquals(AppDownloadState.Idle, reopened.refreshDownload())
        }
    }

    @Test
    @Suppress("DEPRECATION")
    fun fileProviderOnlyGrantsPrivateUpdateDirectory() {
        val authority = "${target.packageName}.updates"
        val provider = target.packageManager.resolveContentProvider(authority, 0)!!
        assertFalse(provider.exported)
        assertTrue(provider.grantUriPermissions)
        assertTrue(target.packageManager.getPackageInfo(target.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions.orEmpty().contains(Manifest.permission.REQUEST_INSTALL_PACKAGES))
        val uri = FileProvider.getUriForFile(target, authority, File(target.filesDir, "updates/update.apk"))
        assertEquals("content", uri.scheme)
        try {
            FileProvider.getUriForFile(target, authority, File(target.filesDir, "private-subscription-catalog.json"))
            fail("Provider exposed files outside the update directory")
        } catch (_: IllegalArgumentException) {
            // Expected: subscription storage is outside the provider root.
        }
    }

    private fun seedPending(release: JSONObject, digest: String) {
        preferences.edit().putString("pending", JSONObject().put("id", Long.MAX_VALUE)
            .put("release", release).put("sha256", digest).put("failed", false).toString()).commit()
    }

    private fun releaseJson(version: String, size: Long = 100L): JSONObject {
        val variant = AppUpdatePolicy.variantOfApk(File(target.applicationInfo.sourceDir))!!
        val apkName = "WhiteVPN-V${AppUpdatePolicy.normalizedVersion(version)}-${variant.suffix}.apk"
        val base = "https://github.com/WhiteDNS/WhiteVPN/releases/download/$version"
        val assets = JSONArray()
        for ((index, name) in listOf(apkName, "SHA256SUMS").withIndex()) {
            assets.put(JSONObject().put("id", index + 1).put("name", name).put("size", size)
                .put("state", "uploaded").put("browser_download_url", "$base/$name"))
        }
        return JSONObject().put("tag_name", version).put("html_url", "https://github.com/WhiteDNS/WhiteVPN/releases/tag/$version")
            .put("draft", false).put("prerelease", false).put("variant", variant.name).put("assets", assets)
    }
}
