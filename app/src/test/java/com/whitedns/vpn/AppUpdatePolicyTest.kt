package com.whitedns.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class AppUpdatePolicyTest {
    @Test
    fun onlyNewerReleaseVersionsPromptForUpdate() {
        assertTrue(AppUpdatePolicy.isNewer("v1.4.0", "1.3.0"))
        assertTrue(AppUpdatePolicy.isNewer("v1.10.0", "1.9.9"))
        assertFalse(AppUpdatePolicy.isNewer("v1.3", "1.3.0"))
        assertFalse(AppUpdatePolicy.isNewer("v1.2.9", "1.3.0"))
        assertFalse(AppUpdatePolicy.isNewer("latest", "1.3.0"))
        assertTrue(AppUpdatePolicy.isNewer("V1.2147483648", "1.2147483647"))
        assertTrue(AppUpdatePolicy.isNewer("  v1.4.0 ", "1.3.0"))
        for (invalid in listOf("1.-1", "1..4", "vV1.4", "1.4-rc1", "1.4+build", "9999999999999999999999999")) {
            assertFalse(invalid, AppUpdatePolicy.isNewer(invalid, "1.3.0"))
            assertFalse(invalid, AppUpdatePolicy.isNewer("1.4.0", invalid))
        }
    }

    @Test
    fun skippedVersionStaysSuppressedButNextVersionPromptsAndBadgeStillComparesNewer() {
        val persistedSkip = "v1.4.0"
        repeat(2) {
            assertFalse(AppUpdatePolicy.shouldPrompt("1.4", "1.3.0", persistedSkip))
            assertTrue(AppUpdatePolicy.isNewer("v1.4.0", "1.3.0"))
        }
        assertTrue(AppUpdatePolicy.shouldPrompt("v1.4.1", "1.3.0", persistedSkip))
        assertTrue(AppUpdatePolicy.shouldPrompt("v1.4.0", "1.3.0", null))
        assertTrue(AppUpdatePolicy.shouldPrompt("v1.4.0", "1.3.0", "invalid"))
        assertFalse(AppUpdatePolicy.shouldPrompt("v1.4.0", "1.4.0", null))
    }

    @Test
    fun detectsOnlyExactSupportedNativeAbiSets() {
        val nativeAbis = ApkVariant.entries.filter { it != ApkVariant.Universal }
        for (variant in nativeAbis) {
            assertEquals(variant, AppUpdatePolicy.detectVariant(sequenceOf("AndroidManifest.xml", "lib/${variant.suffix}/libclash.so")))
        }
        assertEquals(ApkVariant.Universal, AppUpdatePolicy.detectVariant(nativeAbis.asSequence().map { "lib/${it.suffix}/libclash.so" }))
        for (entries in listOf(
            emptyList(),
            listOf("lib/arm64-v8a/libother.so"),
            listOf("lib/mips/libclash.so"),
            listOf("lib/arm64-v8a/libclash.so", "lib/armeabi-v7a/libclash.so"),
            listOf("lib/x86/libclash.so", "lib/x86/libclash.so"),
            nativeAbis.map { "lib/${it.suffix}/libclash.so" } + "lib/mips/libclash.so",
        )) assertNull(entries.toString(), AppUpdatePolicy.detectVariant(entries.asSequence()))
    }

    @Test
    fun inspectsRealZipEntriesAndRejectsCorruptArchive() {
        val file = File.createTempFile("update-variant", ".apk")
        try {
            ZipOutputStream(file.outputStream()).use {
                it.putNextEntry(ZipEntry("lib/x86_64/libclash.so"))
                it.write(byteArrayOf(1, 2, 3))
                it.closeEntry()
            }
            assertEquals(ApkVariant.X86_64, AppUpdatePolicy.variantOfApk(file))
            file.writeText("not an APK")
            assertThrows(IOException::class.java) { AppUpdatePolicy.variantOfApk(file) }
        } finally {
            file.delete()
        }
    }

    @Test
    fun choosesEachExactReleaseApkAndChecksums() {
        val assets = ApkVariant.entries.map { asset(apkName(it)) } + asset("SHA256SUMS")
        for (variant in ApkVariant.entries) {
            val parsed = GitHubReleaseClient.parseRelease(releaseJson(assets).toString(), variant)
            assertEquals("v1.4.0", parsed.version)
            assertEquals(apkName(variant), parsed.apk?.name)
            assertEquals("SHA256SUMS", parsed.checksums?.name)
            assertTrue(parsed.downloadable)
        }
        assertFalse(GitHubReleaseClient.parseRelease(releaseJson(assets).toString(), null).downloadable)
    }

    @Test
    fun retainsReleaseWhileAssetsAreMissingOrStillUploading() {
        for (assets in listOf(
            emptyList(),
            listOf(asset(apkName())),
            listOf(asset("SHA256SUMS")),
            listOf(asset(apkName()).put("state", "starter"), asset("SHA256SUMS")),
            listOf(asset("WhiteVPN-V1.3.0-universal.apk"), asset("SHA256SUMS")),
            listOf(asset(apkName(ApkVariant.Arm64V8a)), asset("SHA256SUMS")),
        )) {
            val parsed = GitHubReleaseClient.parseRelease(releaseJson(assets).toString(), ApkVariant.Universal)
            assertEquals("v1.4.0", parsed.version)
            assertFalse(parsed.downloadable)
        }
    }

    @Test
    fun rejectsDuplicateAssetsAndInvalidAssetMetadata() {
        assertInvalidRelease(releaseJson(listOf(asset(apkName()), asset(apkName()))))
        assertInvalidRelease(releaseJson(listOf(asset("SHA256SUMS"), asset("SHA256SUMS"))))
        for (key in listOf("id", "size")) {
            for (invalid in listOf(0, -1, 1.5, "12")) {
                assertInvalidRelease(releaseJson(listOf(asset(apkName()).put(key, invalid))))
            }
        }
        assertInvalidRelease(releaseJson().put("draft", true))
        assertInvalidRelease(releaseJson().put("prerelease", true))
        for (tag in listOf("latest", "v1.4.0-rc1", " v1.4.0", "vV1.4.0")) {
            assertInvalidRelease(releaseJson().put("tag_name", tag))
        }
        assertThrows(IOException::class.java) { GitHubReleaseClient.parseRelease("{}", ApkVariant.Universal) }
    }

    @Test
    fun rejectsUntrustedOrMismatchedReleaseAndAssetUrls() {
        for (url in listOf(
            "http://github.com/WhiteDNS/WhiteVPN/releases/tag/v1.4.0",
            "https://github.com/another/repo/releases/tag/v1.4.0",
            "https://github.com/WhiteDNS/WhiteVPN/releases/tag/v1.3.0",
            "https://github.com.evil.test/WhiteDNS/WhiteVPN/releases/tag/v1.4.0",
            "https://github.com:443/WhiteDNS/WhiteVPN/releases/tag/v1.4.0",
            "https://user@github.com/WhiteDNS/WhiteVPN/releases/tag/v1.4.0",
            "https://github.com/WhiteDNS/WhiteVPN/releases/tag/v1.4.0?download=1",
            "https://github.com/WhiteDNS/WhiteVPN/releases/tag/v1.4.0#fragment",
        )) assertInvalidRelease(releaseJson().put("html_url", url))
        for (url in listOf(
            "http://github.com/WhiteDNS/WhiteVPN/releases/download/v1.4.0/${apkName()}",
            "https://github.com/another/repo/releases/download/v1.4.0/${apkName()}",
            "https://github.com/WhiteDNS/WhiteVPN/releases/download/v1.3.0/${apkName()}",
            "https://github.com/WhiteDNS/WhiteVPN/releases/download/v1.4.0/wrong.apk",
            "https://github.com/WhiteDNS/WhiteVPN/releases/download/v1.4.0/${apkName()}?token=1",
            "https://evil.test/${apkName()}",
        )) assertInvalidRelease(releaseJson(listOf(asset(apkName()).put("browser_download_url", url))))
    }

    @Test
    fun checksumRequiresOneExactFilenameAndWellFormedHash() {
        val sha256 = "ab".repeat(32)
        assertEquals(sha256, GitHubReleaseClient.parseChecksum("${sha256.uppercase()}  ${apkName()}\r\n", apkName()))
        assertEquals(sha256, GitHubReleaseClient.parseChecksum("$sha256 *${apkName()}\n$sha256  another.apk\n", apkName()))
        for (text in listOf(
            "", "$sha256  another.apk", "$sha256  ./${apkName()}", "${"x".repeat(64)}  ${apkName()}",
            "abc  ${apkName()}", "$sha256  ${apkName()}\n$sha256  ${apkName()}",
            "$sha256  ${apkName()}\nmalformed line", "$sha256  ${apkName()}.bak",
        )) assertThrows(IOException::class.java) { GitHubReleaseClient.parseChecksum(text, apkName()) }
    }

    @Test
    fun cachedReleaseMustMeetSameUrlAssetAndVariantRules() {
        val release = GitHubReleaseClient.parseRelease(releaseJson().toString(), ApkVariant.Universal)
        GitHubReleaseClient.validateRelease(release, ApkVariant.Universal)
        for (invalid in listOf(
            release.copy(url = "https://github.com/other/repo/releases/tag/v1.4.0"),
            release.copy(version = "invalid"),
            release.copy(apk = release.apk!!.copy(name = apkName(ApkVariant.X86))),
            release.copy(apk = release.apk!!.copy(url = "https://evil.test/update.apk")),
            release.copy(apk = release.apk!!.copy(id = 0)),
            release.copy(apk = release.apk!!.copy(size = -1)),
            release.copy(checksums = release.checksums!!.copy(name = "checksums.txt")),
        )) assertThrows(IOException::class.java) { GitHubReleaseClient.validateRelease(invalid, ApkVariant.Universal) }
        assertThrows(IOException::class.java) { GitHubReleaseClient.validateRelease(release, ApkVariant.Arm64V8a) }
        assertThrows(IOException::class.java) { GitHubReleaseClient.validateRelease(release, null) }
    }

    @Test
    fun validatesArchiveIdentityVersionSignerAndVariantBeforeInstall() {
        val installed = AppApkMetadata("com.whitedns.vpn", "1.3.0", 10, setOf("ab".repeat(32)), ApkVariant.Universal)
        val candidate = installed.copy(versionName = "1.4.0", versionCode = 11)
        val release = AppRelease("v1.4.0", "https://github.com/WhiteDNS/WhiteVPN/releases/tag/v1.4.0")
        AppUpdatePolicy.validateApk(candidate, installed, release)
        for (invalid in listOf(
            candidate.copy(packageName = "other.app"),
            candidate.copy(versionName = "1.5.0"),
            candidate.copy(versionName = ""),
            candidate.copy(versionCode = 10),
            candidate.copy(versionCode = 9),
            candidate.copy(signerSha256 = emptySet()),
            candidate.copy(signerSha256 = setOf("cd".repeat(32))),
            candidate.copy(signerSha256 = candidate.signerSha256 + "cd".repeat(32)),
            candidate.copy(variant = ApkVariant.Arm64V8a),
            candidate.copy(variant = null),
        )) assertThrows(IOException::class.java) { AppUpdatePolicy.validateApk(invalid, installed, release) }
        assertThrows(IOException::class.java) { AppUpdatePolicy.validateApk(candidate, installed.copy(variant = null), release) }
        assertThrows(IOException::class.java) { AppUpdatePolicy.validateApk(candidate, installed.copy(signerSha256 = emptySet()), release) }
        assertThrows(IOException::class.java) { AppUpdatePolicy.validateApk(candidate, installed.copy(versionName = "1.5.0"), release) }
    }

    private fun apkName(variant: ApkVariant = ApkVariant.Universal) = "WhiteVPN-V1.4.0-${variant.suffix}.apk"

    private fun asset(name: String) = JSONObject()
        .put("id", 123)
        .put("name", name)
        .put("state", "uploaded")
        .put("size", 1_024)
        .put("browser_download_url", "https://github.com/WhiteDNS/WhiteVPN/releases/download/v1.4.0/$name")

    private fun releaseJson(assets: List<JSONObject> = listOf(asset(apkName()), asset("SHA256SUMS"))) = JSONObject()
        .put("tag_name", "v1.4.0")
        .put("html_url", "https://github.com/WhiteDNS/WhiteVPN/releases/tag/v1.4.0")
        .put("draft", false)
        .put("prerelease", false)
        .put("assets", JSONArray(assets))

    private fun assertInvalidRelease(json: JSONObject) {
        assertThrows(IOException::class.java) { GitHubReleaseClient.parseRelease(json.toString(), ApkVariant.Universal) }
    }
}
