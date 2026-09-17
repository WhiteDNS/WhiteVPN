package com.whitedns.vpn

import android.app.DownloadManager
import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlin.coroutines.coroutineContext

sealed interface AppDownloadState {
    data object Idle : AppDownloadState
    data class Downloading(val downloaded: Long, val total: Long, val paused: Boolean) : AppDownloadState
    data object Verifying : AppDownloadState
    data class Ready(val file: File, val release: AppRelease) : AppDownloadState
    data object Failed : AppDownloadState
}

class AppUpdateManager(context: Context) {
    private val context = context.applicationContext
    private val preferences = this.context.getSharedPreferences("app_updates", Context.MODE_PRIVATE)
    private val downloads = this.context.getSystemService(DownloadManager::class.java)
    private val updateDirectory get() = File(context.filesDir, "updates")
    private val snapshot get() = File(updateDirectory, "update.apk")
    private var installed: AppApkMetadata? = null
    private var verified: PendingDownload? = null

    fun availableRelease(): AppRelease? = readRelease(preferences.getString("latest", null))
        ?.takeIf { AppUpdatePolicy.isNewer(it.version, BuildConfig.VERSION_NAME) }

    fun skippedVersion(): String? = preferences.getString("skipped", null)

    fun skipVersion(version: String) {
        AppUpdatePolicy.normalizedVersion(version).takeIf { it.isNotEmpty() }?.let {
            preferences.edit().putString("skipped", it).apply()
        }
    }

    fun pendingRelease(): AppRelease? = pending()?.release
        ?.takeIf { AppUpdatePolicy.isNewer(it.version, BuildConfig.VERSION_NAME) }

    suspend fun check(): AppRelease = withContext(Dispatchers.IO) {
        mutationLock.withLock {
            reconcileInstalledUpdate()
            val release = GitHubReleaseClient.latest(installedMetadata().variant)
            preferences.edit().putString("latest", releaseJson(release).toString()).apply()
            release
        }
    }

    @Suppress("DEPRECATION")
    suspend fun download(release: AppRelease) = withContext(Dispatchers.IO) {
        mutationLock.withLock {
            reconcileInstalledUpdate()
            val installed = installedMetadata()
            GitHubReleaseClient.validateRelease(release, installed.variant)
            if (!release.downloadable || !AppUpdatePolicy.isNewer(release.version, installed.versionName)) {
                throw IOException("No compatible newer APK is available")
            }
            val previous = pending()
            if (previous != null && !previous.failed) throw IOException("An update is already downloading")
            val digest = GitHubReleaseClient.expectedSha256(release)
            coroutineContext.ensureActive()
            clearDownload(previous)
            val apk = release.apk!!
            val request = DownloadManager.Request(Uri.parse(apk.url))
                .setTitle(context.getString(R.string.app_name))
                .setDescription(context.getString(R.string.update_downloading))
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE)
                .setVisibleInDownloadsUi(false)
                .setDestinationInExternalFilesDir(context, "updates", "download.apk")
                .addRequestHeader("User-Agent", "WhiteVPN/${BuildConfig.VERSION_NAME}")
            val id = downloads.enqueue(request)
            try {
                savePending(PendingDownload(id, release, digest))
            } catch (error: Exception) {
                downloads.remove(id)
                throw error
            }
        }
    }

    suspend fun refreshDownload(): AppDownloadState = withContext(Dispatchers.IO) {
        mutationLock.withLock {
            reconcileInstalledUpdate()
            val pending = pending() ?: return@withLock AppDownloadState.Idle
            if (pending.failed) return@withLock AppDownloadState.Failed
            if (verified == pending && snapshot.isFile && snapshot.length() == pending.release.apk?.size) {
                return@withLock AppDownloadState.Ready(snapshot, pending.release)
            }
            try {
                if (snapshot.isFile) {
                    verifySnapshot(pending)
                    return@withLock AppDownloadState.Ready(snapshot, pending.release)
                }
                downloads.query(DownloadManager.Query().setFilterById(pending.id)).use { cursor ->
                    if (cursor == null || !cursor.moveToFirst()) throw IOException("Update download no longer exists")
                    when (cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                        DownloadManager.STATUS_SUCCESSFUL -> {
                            copyDownload(pending)
                            verifySnapshot(pending)
                            AppDownloadState.Ready(snapshot, pending.release)
                        }
                        DownloadManager.STATUS_PENDING, DownloadManager.STATUS_RUNNING, DownloadManager.STATUS_PAUSED -> {
                            AppDownloadState.Downloading(
                                cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)),
                                cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)),
                                cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_PAUSED,
                            )
                        }
                        else -> throw IOException("Update download failed")
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failDownload(pending, error)
                AppDownloadState.Failed
            }
        }
    }

    suspend fun cancelDownload() = withContext(Dispatchers.IO) {
        mutationLock.withLock { clearDownload(pending()) }
    }

    suspend fun apkForInstall(): File? = withContext(Dispatchers.IO) {
        mutationLock.withLock {
            reconcileInstalledUpdate()
            val pending = pending() ?: return@withLock null
            if (pending.failed) return@withLock null
            try {
                verifySnapshot(pending)
                snapshot
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                failDownload(pending, error)
                null
            }
        }
    }

    private suspend fun copyDownload(pending: PendingDownload) {
        if (!updateDirectory.isDirectory && !updateDirectory.mkdirs()) throw IOException("Cannot create update storage")
        val partial = File(updateDirectory, "update.apk.part")
        try {
            downloads.openDownloadedFile(pending.id).use { descriptor ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                    partial.outputStream().use { output ->
                        val buffer = ByteArray(65_536)
                        var copied = 0L
                        while (true) {
                            coroutineContext.ensureActive()
                            val count = input.read(buffer)
                            if (count == -1) break
                            copied += count
                            if (copied > pending.release.apk!!.size) throw IOException("Update APK is larger than its release asset")
                            output.write(buffer, 0, count)
                        }
                        output.fd.sync()
                    }
                }
            }
            coroutineContext.ensureActive()
            if (!partial.renameTo(snapshot)) throw IOException("Cannot save private update APK")
        } finally {
            partial.delete()
        }
    }

    private suspend fun verifySnapshot(pending: PendingDownload) {
        val installed = installedMetadata()
        GitHubReleaseClient.validateRelease(pending.release, installed.variant)
        if (!snapshot.isFile || snapshot.length() != pending.release.apk?.size) {
            throw IOException("Update APK size does not match the release")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        snapshot.inputStream().use { input ->
            val buffer = ByteArray(65_536)
            while (true) {
                coroutineContext.ensureActive()
                val count = input.read(buffer)
                if (count == -1) break
                digest.update(buffer, 0, count)
            }
        }
        if (hex(digest.digest()) != pending.sha256) throw IOException("Update APK checksum does not match the release")
        val info = context.packageManager.getPackageArchiveInfo(snapshot.path, signatureFlags())
            ?: throw IOException("Update APK cannot be read")
        AppUpdatePolicy.validateApk(metadata(info, snapshot), installed, pending.release)
        verified = pending
    }

    @Suppress("DEPRECATION")
    private fun installedMetadata(): AppApkMetadata = installed ?: metadata(
        context.packageManager.getPackageInfo(context.packageName, signatureFlags()),
        File(context.applicationInfo.sourceDir),
    ).also { installed = it }

    @Suppress("DEPRECATION")
    private fun metadata(info: PackageInfo, file: File): AppApkMetadata {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return AppApkMetadata(
            packageName = info.packageName,
            versionName = info.versionName ?: throw IOException("APK version is missing"),
            versionCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong(),
            signerSha256 = signatures.orEmpty().map { hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }.toSet(),
            variant = AppUpdatePolicy.variantOfApk(file),
        )
    }

    @Suppress("DEPRECATION")
    private fun signatureFlags(): Int = if (Build.VERSION.SDK_INT >= 28) {
        PackageManager.GET_SIGNING_CERTIFICATES
    } else {
        PackageManager.GET_SIGNATURES
    }

    private fun reconcileInstalledUpdate() {
        val pending = pending()
        if (pending != null && !AppUpdatePolicy.isNewer(pending.release.version, BuildConfig.VERSION_NAME)) {
            clearDownload(pending)
        } else if (pending == null && preferences.contains("pending")) {
            clearDownload(null)
        }
        val latest = readRelease(preferences.getString("latest", null))
        if (latest != null && !AppUpdatePolicy.isNewer(latest.version, BuildConfig.VERSION_NAME)) {
            preferences.edit().remove("latest").apply()
        }
    }

    private fun clearDownload(pending: PendingDownload?) {
        pending?.let { downloads.remove(it.id) }
        if (!preferences.edit().remove("pending").commit()) throw IOException("Cannot clear pending update")
        verified = null
        snapshot.delete()
        File(updateDirectory, "update.apk.part").delete()
        context.getExternalFilesDir("updates")?.let { File(it, "download.apk").delete() }
    }

    private fun failDownload(pending: PendingDownload, error: Exception) {
        DiagnosticLogger.warn(context, "update.failed", error = error)
        verified = null
        snapshot.delete()
        File(updateDirectory, "update.apk.part").delete()
        savePending(pending.copy(failed = true))
        downloads.remove(pending.id)
        context.getExternalFilesDir("updates")?.let { File(it, "download.apk").delete() }
    }

    private fun pending(): PendingDownload? = try {
        preferences.getString("pending", null)?.let { value ->
            val json = JSONObject(value)
            val release = readRelease(json.getJSONObject("release").toString()) ?: return@let null
            val id = json.getLong("id")
            val digest = json.getString("sha256")
            if (id <= 0 || !release.downloadable || !digest.matches(Regex("[a-f0-9]{64}"))) return@let null
            PendingDownload(id, release, digest, json.getBoolean("failed"))
        }
    } catch (_: Exception) {
        null
    }

    private fun savePending(pending: PendingDownload) {
        val json = JSONObject().put("id", pending.id).put("release", releaseJson(pending.release))
            .put("sha256", pending.sha256).put("failed", pending.failed)
        if (!preferences.edit().putString("pending", json.toString()).commit()) throw IOException("Cannot save pending update")
    }

    private fun readRelease(value: String?): AppRelease? = try {
        value?.let {
            val json = JSONObject(it)
            val variant = json.optString("variant").takeIf { name -> name.isNotEmpty() }
                ?.let { name -> ApkVariant.valueOf(name) }
            GitHubReleaseClient.parseRelease(it, variant)
        }
    } catch (_: Exception) {
        null
    }

    private fun releaseJson(release: AppRelease): JSONObject {
        val variant = ApkVariant.entries.singleOrNull {
            release.apk?.name == "WhiteVPN-V${AppUpdatePolicy.normalizedVersion(release.version)}-${it.suffix}.apk"
        }
        val assets = JSONArray()
        listOfNotNull(release.apk, release.checksums).forEach {
            assets.put(JSONObject().put("id", it.id).put("name", it.name).put("browser_download_url", it.url)
                .put("size", it.size).put("state", "uploaded"))
        }
        return JSONObject().put("tag_name", release.version).put("html_url", release.url)
            .put("draft", false).put("prerelease", false).put("variant", variant?.name.orEmpty()).put("assets", assets)
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    private data class PendingDownload(val id: Long, val release: AppRelease, val sha256: String, val failed: Boolean = false)

    companion object {
        private val mutationLock = Mutex()
    }
}
