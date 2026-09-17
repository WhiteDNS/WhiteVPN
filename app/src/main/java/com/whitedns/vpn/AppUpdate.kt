package com.whitedns.vpn

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.zip.ZipFile
import kotlin.coroutines.coroutineContext

enum class ApkVariant(val suffix: String) {
    Universal("universal"),
    ArmeabiV7a("armeabi-v7a"),
    Arm64V8a("arm64-v8a"),
    X86("x86"),
    X86_64("x86_64"),
}

data class ReleaseAsset(val id: Long, val name: String, val url: String, val size: Long)

data class AppRelease(
    val version: String,
    val url: String,
    val apk: ReleaseAsset? = null,
    val checksums: ReleaseAsset? = null,
) {
    val downloadable: Boolean get() = apk != null && checksums != null
}

data class AppApkMetadata(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val signerSha256: Set<String>,
    val variant: ApkVariant?,
)

object AppUpdatePolicy {
    fun normalizedVersion(version: String): String {
        val value = version.trim().let { if (it.startsWith("v", ignoreCase = true)) it.drop(1) else it }
        return value.takeIf {
            it.length <= 100 && it.matches(Regex("[0-9]+(?:\\.[0-9]+)*")) &&
                it.split('.').all { part -> part.toLongOrNull() != null }
        }.orEmpty()
    }

    fun isNewer(latestVersion: String, currentVersion: String): Boolean {
        val latest = parts(latestVersion) ?: return false
        val current = parts(currentVersion) ?: return false
        for (index in 0 until maxOf(latest.size, current.size)) {
            val comparison = latest.getOrElse(index) { 0L }.compareTo(current.getOrElse(index) { 0L })
            if (comparison != 0) return comparison > 0
        }
        return false
    }

    fun shouldPrompt(latest: String, current: String, skipped: String?): Boolean =
        isNewer(latest, current) &&
            (skipped == null || parts(latest)?.dropLastWhile { it == 0L } != parts(skipped)?.dropLastWhile { it == 0L })

    fun detectVariant(entries: Sequence<String>): ApkVariant? {
        val nativeLibrary = Regex("^lib/([^/]+)/libclash\\.so$")
        val nativeEntries = entries.mapNotNull { nativeLibrary.matchEntire(it)?.groupValues?.get(1) }
            .toList()
        val abis = nativeEntries.toSet()
        if (abis.size != nativeEntries.size) return null
        val variants = ApkVariant.entries.filter { it != ApkVariant.Universal }
        return when {
            abis == variants.map { it.suffix }.toSet() -> ApkVariant.Universal
            abis.size == 1 -> variants.singleOrNull { it.suffix == abis.single() }
            else -> null
        }
    }

    fun variantOfApk(file: File): ApkVariant? = ZipFile(file).use { archive ->
        detectVariant(archive.entries().asSequence().filterNot { it.isDirectory }.map { it.name })
    }

    fun validateApk(candidate: AppApkMetadata, installed: AppApkMetadata, release: AppRelease) {
        if (candidate.packageName != installed.packageName || candidate.packageName.isBlank()) {
            throw IOException("Update package does not match this app")
        }
        val releaseVersion = normalizedVersion(release.version)
        if (releaseVersion.isEmpty() || normalizedVersion(candidate.versionName) != releaseVersion ||
            !isNewer(release.version, installed.versionName) || candidate.versionCode <= installed.versionCode
        ) {
            throw IOException("Update version does not match the newer release")
        }
        if (candidate.signerSha256.isEmpty() || installed.signerSha256.isEmpty() ||
            candidate.signerSha256 != installed.signerSha256
        ) {
            throw IOException("Update signing certificate does not match this app")
        }
        if (candidate.variant == null || candidate.variant != installed.variant) {
            throw IOException("Update APK type does not match this app")
        }
    }

    private fun parts(version: String): List<Long>? = normalizedVersion(version)
        .takeIf { it.isNotEmpty() }?.split('.')?.map { it.toLong() }
}

object GitHubReleaseClient {
    suspend fun latest(variant: ApkVariant?): AppRelease = withContext(Dispatchers.IO) {
        parseRelease(readText(LATEST_RELEASE_URL, 1_048_576, asset = false), variant)
    }

    suspend fun expectedSha256(release: AppRelease): String = withContext(Dispatchers.IO) {
        val apk = release.apk ?: throw IOException("Release APK is not uploaded yet")
        val checksums = release.checksums ?: throw IOException("Release checksums are not uploaded yet")
        requireAssetUrl(checksums.url, release.version, "SHA256SUMS")
        requireAssetUrl(apk.url, release.version, apk.name)
        parseChecksum(readText(checksums.url, 65_536, asset = true), apk.name)
    }

    fun parseRelease(json: String, variant: ApkVariant?): AppRelease {
        try {
            val response = JSONObject(json)
            if (response.getBoolean("draft") || response.getBoolean("prerelease")) {
                throw IOException("GitHub release is not a stable release")
            }
            val tag = response.getString("tag_name")
            val version = AppUpdatePolicy.normalizedVersion(tag)
            if (version.isEmpty() || tag != tag.trim()) throw IOException("GitHub release version is invalid")
            val releaseUrl = response.getString("html_url")
            val apkName = variant?.let { "WhiteVPN-V$version-${it.suffix}.apk" }
            val assets = response.getJSONArray("assets")
            val selected = mutableMapOf<String, ReleaseAsset?>()
            for (index in 0 until assets.length()) {
                val asset = assets.getJSONObject(index)
                val name = asset.getString("name")
                if (name != apkName && name != "SHA256SUMS") continue
                if (selected.containsKey(name)) throw IOException("GitHub release contains duplicate assets")
                selected[name] = null
                if (asset.getString("state") != "uploaded") continue
                val id = positiveLong(asset, "id")
                val size = positiveLong(asset, "size")
                val url = asset.getString("browser_download_url")
                selected[name] = ReleaseAsset(id, name, url, size)
            }
            return AppRelease(tag, releaseUrl, selected[apkName], selected["SHA256SUMS"])
                .also { validateRelease(it, variant) }
        } catch (error: JSONException) {
            throw IOException("GitHub release response was invalid", error)
        }
    }

    fun parseChecksum(text: String, assetName: String): String {
        val checksumLine = Regex("^([a-fA-F0-9]{64}) [ *](.+)$")
        val matches = text.lineSequence().filter { it.isNotBlank() }.map { line ->
            checksumLine.matchEntire(line.removeSuffix("\r"))
                ?: throw IOException("Release checksum file is malformed")
        }.filter { it.groupValues[2] == assetName }.map { it.groupValues[1].lowercase() }.toList()
        return matches.singleOrNull() ?: throw IOException("Release checksum is missing or duplicated")
    }

    fun validateRelease(release: AppRelease, variant: ApkVariant?) {
        val version = AppUpdatePolicy.normalizedVersion(release.version)
        if (version.isEmpty() || release.version != release.version.trim()) {
            throw IOException("GitHub release version is invalid")
        }
        requireRepositoryUrl(release.url, "/WhiteDNS/WhiteVPN/releases/tag/${release.version}")
        for ((asset, expectedName) in listOf(
            release.apk to variant?.let { "WhiteVPN-V$version-${it.suffix}.apk" },
            release.checksums to "SHA256SUMS",
        )) {
            if (asset == null) continue
            if (asset.id <= 0 || asset.size <= 0 || asset.name != expectedName) {
                throw IOException("GitHub release asset is invalid")
            }
            requireAssetUrl(asset.url, release.version, asset.name)
        }
    }

    internal fun requireAssetUrl(url: String, tag: String, name: String) {
        requireRepositoryUrl(url, "/WhiteDNS/WhiteVPN/releases/download/$tag/$name")
    }

    private fun positiveLong(json: JSONObject, key: String): Long {
        val value = json.get(key)
        if (value !is Int && value !is Long) throw IOException("GitHub release asset $key is invalid")
        return (value as Number).toLong().takeIf { it > 0 }
            ?: throw IOException("GitHub release asset $key is invalid")
    }

    private fun requireRepositoryUrl(url: String, path: String) {
        val uri = checkedUri(url)
        if (uri.host != "github.com" || uri.rawPath != path || uri.rawQuery != null) {
            throw IOException("GitHub release URL is invalid")
        }
    }

    private fun checkedUri(url: String): URI {
        val uri = try {
            URI(url)
        } catch (error: Exception) {
            throw IOException("GitHub release URL is invalid", error)
        }
        if (uri.scheme != "https" || uri.userInfo != null || uri.port != -1 || uri.fragment != null || uri.host == null) {
            throw IOException("GitHub release URL is invalid")
        }
        return uri
    }

    private suspend fun readText(initialUrl: String, maxBytes: Int, asset: Boolean): String {
        var url = initialUrl
        repeat(5) {
            coroutineContext.ensureActive()
            val uri = checkedUri(url)
            if (asset) {
                if (uri.host !in setOf("github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")) {
                    throw IOException("GitHub asset redirected to an invalid host")
                }
            } else if (url != LATEST_RELEASE_URL) {
                throw IOException("GitHub API redirected to an invalid URL")
            }
            val connection = URL(url).openConnection() as HttpURLConnection
            connection.connectTimeout = 5_000
            connection.readTimeout = 5_000
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Accept", if (asset) "application/octet-stream" else "application/vnd.github+json")
            connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
            connection.setRequestProperty("User-Agent", "WhiteVPN/${BuildConfig.VERSION_NAME}")
            try {
                val status = connection.responseCode
                if (status in setOf(301, 302, 303, 307, 308)) {
                    val location = connection.getHeaderField("Location") ?: throw IOException("GitHub redirect has no URL")
                    url = uri.resolve(location).toString()
                } else {
                    if (status !in 200..299) throw IOException("GitHub release request failed: $status")
                    if (connection.contentLengthLong > maxBytes) throw IOException("GitHub response is too large")
                    return connection.inputStream.use { input ->
                        val output = ByteArrayOutputStream()
                        val buffer = ByteArray(8_192)
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = input.read(buffer)
                            if (count == -1) break
                            if (output.size() + count > maxBytes) throw IOException("GitHub response is too large")
                            output.write(buffer, 0, count)
                        }
                        output.toString(Charsets.UTF_8.name())
                    }
                }
            } finally {
                connection.disconnect()
            }
        }
        throw IOException("GitHub release redirected too many times")
    }

    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/WhiteDNS/WhiteVPN/releases/latest"
}
