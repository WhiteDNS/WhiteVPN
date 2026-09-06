package com.whitedns.vpn

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runInterruptible
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

enum class UserSubscriptionSourceKind(val wireName: String) {
    Inline("inline"),
    Https("https");

    companion object {
        fun fromWireName(value: String?, legacyInput: String): UserSubscriptionSourceKind =
            entries.firstOrNull { it.wireName == value }
                ?: if (
                    legacyInput.startsWith("http://", ignoreCase = true) ||
                    legacyInput.startsWith("https://", ignoreCase = true)
                ) {
                    Https
                } else {
                    Inline
                }
    }
}

internal sealed interface SubscriptionSource {
    data class RemoteHttps(val url: String) : SubscriptionSource

    data class Inline(val content: String) : SubscriptionSource

    data class ManagedHttps(
        val url: String,
        val encrypted: Boolean,
        val decryptionKey: String,
    ) : SubscriptionSource
}

internal object SubscriptionSourceLoader {
    suspend fun load(source: SubscriptionSource): String = when (source) {
        is SubscriptionSource.Inline -> source.content
        is SubscriptionSource.RemoteHttps -> loadHttps(source.url)
        is SubscriptionSource.ManagedHttps -> loadHttps(source.url).let { payload ->
            if (source.encrypted) {
                EncryptedPayloadCodec.decryptText(
                    payload,
                    source.decryptionKey,
                    label = "encrypted Mihomo subscription",
                )
            } else {
                payload
            }
        }
    }

    private suspend fun loadHttps(value: String): String = runInterruptible(Dispatchers.IO) {
        val uri = runCatching { URI(value) }
            .getOrElse { throw IOException("Subscription Source URL is invalid", it) }
        if (!uri.scheme.equals("https", ignoreCase = true) || uri.host.isNullOrBlank()) {
            throw IOException("Subscription Source must use HTTPS")
        }
        val connection = uri.toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 12_000
        connection.readTimeout = 20_000
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "text/yaml,application/json,text/plain,*/*;q=0.1")
        try {
            if (connection.responseCode !in 200..299) {
                throw IOException("Subscription Source returned HTTP ${connection.responseCode}")
            }
            val bytes = connection.inputStream.use {
                it.readAtMost(UserSubscriptionImporter.MAX_SUBSCRIPTION_BYTES + 1)
            }
            if (bytes.size > UserSubscriptionImporter.MAX_SUBSCRIPTION_BYTES) {
                throw IOException("Subscription Source exceeds the maximum size")
            }
            bytes.toString(Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }
}

internal fun replaceSubscriptionSnapshot(
    file: File,
    value: String,
    writeMetadata: () -> Unit,
) {
    val previous = file.takeIf(File::isFile)?.readText()
    writeSubscriptionTextAtomically(file, value)
    try {
        writeMetadata()
    } catch (error: Exception) {
        try {
            if (previous == null) {
                if (file.exists() && !file.delete()) {
                    throw IOException("Unable to restore Subscription Snapshot")
                }
            } else {
                writeSubscriptionTextAtomically(file, previous)
            }
        } catch (restoreError: Exception) {
            error.addSuppressed(restoreError)
        }
        throw error
    }
}

internal fun writeSubscriptionTextAtomically(file: File, value: String) {
    val parent = file.parentFile
        ?: throw IOException("Subscription Snapshot file does not have a parent directory")
    if (!parent.isDirectory && !parent.mkdirs()) {
        throw IOException("Unable to create Subscription Snapshot directory")
    }
    val temporary = File.createTempFile(".${file.name}.", ".tmp", parent)
    try {
        temporary.writeText(value)
        try {
            Files.move(
                temporary.toPath(),
                file.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (error: AtomicMoveNotSupportedException) {
            throw IOException("Atomic Subscription Snapshot replacement is not supported", error)
        }
    } finally {
        temporary.delete()
    }
}

internal fun userSubscriptionSource(
    input: String,
    kind: UserSubscriptionSourceKind = UserSubscriptionSourceKind.fromWireName(null, input),
): SubscriptionSource = when (kind) {
    UserSubscriptionSourceKind.Inline -> SubscriptionSource.Inline(input)
    UserSubscriptionSourceKind.Https -> SubscriptionSource.RemoteHttps(input)
}

internal class AndroidSubscriptionSnapshotAdapter(
    private val context: Context,
    private val store: SubscriptionStore,
) : SubscriptionSnapshotPersistence {
    override fun read(id: String): SubscriptionSnapshotEntry? {
        if (SubscriptionStore.isBuiltInSubscription(id)) {
            val file = builtInSnapshotFile(id)
            return SubscriptionSnapshotEntry(
                source = managedSource(id),
                cachedYaml = file.takeIf { it.isFile && it.length() > 0L }?.readText(),
                fetchedAt = store.readCatalog(id)?.fetchedAt
                    ?: file.lastModified().takeIf { it > 0L }
                    ?: 0L,
            )
        }

        val subscription = store.readUserSubscription(id) ?: return null
        var yaml = store.readUserSubscriptionYaml(id).takeIf(String::isNotBlank)
        if (subscription.format == UserSubscriptionFormat.Links && yaml != null) {
            if ("\\/" in yaml && (": {\"" in yaml || ": [\"" in yaml)) {
                yaml = null
            } else {
                val migrated = MihomoLinkConfigBuilder.migrateGeneratedYaml(yaml)
                if (migrated != yaml) {
                    yaml = migrated
                    store.saveUserSubscription(subscription, migrated)
                }
            }
        }
        return SubscriptionSnapshotEntry(
            source = userSubscriptionSource(subscription.input, subscription.sourceKind),
            cachedYaml = yaml,
            fetchedAt = subscription.fetchedAt,
        )
    }

    override fun save(id: String, subscription: CompiledSubscription) {
        if (SubscriptionStore.isBuiltInSubscription(id)) {
            val file = builtInSnapshotFile(id)
            replaceSubscriptionSnapshot(file, subscription.snapshot.rawConfig) {
                store.saveCatalog(id, subscription.snapshot.catalog)
            }
            return
        }

        val existing = store.readUserSubscription(id)
            ?: throw IOException("Subscription no longer exists")
        val fetchedAt = subscription.snapshot.catalog.fetchedAt
        store.saveUserSubscription(
            existing.copy(
                format = subscription.format,
                connectionCount = subscription.snapshot.catalog.profiles.size,
                updatedAt = fetchedAt,
                fetchedAt = fetchedAt,
                lastError = "",
            ),
            subscription.snapshot.rawConfig,
        )
    }

    override fun recordFailure(id: String, error: Throwable) {
        if (SubscriptionStore.isBuiltInSubscription(id)) {
            DiagnosticLogger.warn(
                context,
                "subscription.builtin.refresh.failed",
                "subscription=$id",
                error = error,
            )
            return
        }
        val existing = store.readUserSubscription(id) ?: return
        runCatching {
            store.saveUserSubscription(
                existing.copy(
                    lastError = error.message?.take(160)?.ifBlank { null }
                        ?: "تازه‌سازی ناموفق بود",
                ),
            )
        }
    }

    private fun managedSource(id: String): SubscriptionSource.ManagedHttps {
        val url = if (id == SubscriptionStore.PRIVATE_SUBSCRIPTION_ID) {
            WhiteDnsConfig.PRIVATE_MIHOMO_SUBSCRIPTION_URL
        } else {
            WhiteDnsConfig.MIHOMO_SUBSCRIPTION_URL
        }
        val encrypted = runCatching {
            URI(url).path.orEmpty().contains("encrypted", ignoreCase = true)
        }
            .getOrDefault(false)
        return SubscriptionSource.ManagedHttps(
            url = url,
            encrypted = encrypted,
            decryptionKey = WhiteDnsConfig.MIHOMO_SUBSCRIPTION_KEY,
        )
    }

    private fun builtInSnapshotFile(id: String): File = File(
        context.filesDir,
        if (id == SubscriptionStore.PUBLIC_SUBSCRIPTION_ID) {
            "mihomo/encrypted_mihomo_subscription.yaml"
        } else {
            "mihomo/private_mihomo_subscription.yaml"
        },
    )
}

internal data class SubscriptionSnapshotEntry(
    val source: SubscriptionSource,
    val cachedYaml: String?,
    val fetchedAt: Long,
)

internal data class CompiledSubscription(
    val format: UserSubscriptionFormat,
    val snapshot: MihomoSubscriptionSnapshot,
)

internal object SubscriptionCompiler {
    fun compile(content: String, fetchedAt: Long): CompiledSubscription {
        val imported = UserSubscriptionImporter.import(content, fetchedAt)
        return CompiledSubscription(
            format = imported.format,
            snapshot = parseSubscriptionSnapshot(imported.yaml, fetchedAt),
        )
    }
}

internal interface SubscriptionSnapshotPersistence {
    fun read(id: String): SubscriptionSnapshotEntry?

    fun save(id: String, subscription: CompiledSubscription)

    fun recordFailure(id: String, error: Throwable)
}

internal enum class SubscriptionSnapshotOrigin {
    FreshCache,
    Refreshed,
    LastKnownGood,
}

internal data class SubscriptionSnapshotResolution(
    val snapshot: MihomoSubscriptionSnapshot,
    val origin: SubscriptionSnapshotOrigin,
)

internal fun isSubscriptionSnapshotFresh(
    fetchedAt: Long,
    nowMs: Long,
    freshnessMs: Long,
): Boolean = fetchedAt > 0L && nowMs - fetchedAt in 0 until freshnessMs

internal enum class SubscriptionRefreshPolicy {
    IfStale,
    Force,
}

internal class SubscriptionSnapshotResolver(
    private val persistence: SubscriptionSnapshotPersistence,
    private val loadSource: suspend (SubscriptionSource) -> String = SubscriptionSourceLoader::load,
    private val nowMs: () -> Long = System::currentTimeMillis,
    private val freshnessMs: Long = WhiteDnsConfig.SUBSCRIPTION_REFRESH_INTERVAL_MS,
) {
    suspend fun resolve(
        id: String,
        refreshPolicy: SubscriptionRefreshPolicy = SubscriptionRefreshPolicy.IfStale,
    ): SubscriptionSnapshotResolution {
        val entry = persistence.read(id)
            ?: throw IOException("Subscription no longer exists")
        val cached = entry.cachedYaml?.let { yaml ->
            runCatching { parseSubscriptionSnapshot(yaml, entry.fetchedAt) }.getOrNull()
        }
        val now = nowMs()
        if (
            refreshPolicy == SubscriptionRefreshPolicy.IfStale &&
            cached != null &&
            isSubscriptionSnapshotFresh(entry.fetchedAt, now, freshnessMs)
        ) {
            return SubscriptionSnapshotResolution(cached, SubscriptionSnapshotOrigin.FreshCache)
        }
        return try {
            val fetchedAt = now
            val compiled = SubscriptionCompiler.compile(loadSource(entry.source), fetchedAt)
            currentCoroutineContext().ensureActive()
            persistence.save(id, compiled)
            SubscriptionSnapshotResolution(
                snapshot = compiled.snapshot,
                origin = SubscriptionSnapshotOrigin.Refreshed,
            )
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (error: Exception) {
            runCatching { persistence.recordFailure(id, error) }
            cached?.takeIf { refreshPolicy == SubscriptionRefreshPolicy.IfStale }?.let {
                SubscriptionSnapshotResolution(it, SubscriptionSnapshotOrigin.LastKnownGood)
            } ?: throw IOException("Unable to resolve Subscription Snapshot", error)
        }
    }

    fun cached(id: String): MihomoSubscriptionSnapshot? {
        val entry = persistence.read(id) ?: return null
        val yaml = entry.cachedYaml ?: return null
        return runCatching { parseSubscriptionSnapshot(yaml, entry.fetchedAt) }.getOrNull()
    }
}

private fun parseSubscriptionSnapshot(yaml: String, fetchedAt: Long): MihomoSubscriptionSnapshot =
    MihomoConfigParser.parse(yaml, fetchedAt).also { snapshot ->
        if (snapshot.catalog.profiles.isEmpty()) {
            throw IOException("Subscription did not contain proxies")
        }
    }
