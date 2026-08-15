package com.whitedns.vpn

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object WhiteDnsConfig {
    const val SUBSCRIPTION_REFRESH_INTERVAL_MS = 30 * 60 * 1_000L

    // Injected at build time from the environment with production defaults; see app/build.gradle.kts.
    val MIHOMO_SUBSCRIPTION_URL: String get() = BuildConfig.MIHOMO_SUBSCRIPTION_URL
    val ENCRYPTED_IP_LIST_URL: String get() = BuildConfig.ENCRYPTED_IP_LIST_URL

    // Release builds fail when these are unset; debug builds get an empty string.
    val MIHOMO_SUBSCRIPTION_KEY: String get() = BuildConfig.MIHOMO_SUBSCRIPTION_KEY
    val ENCRYPTED_IP_LIST_KEY: String get() = BuildConfig.ENCRYPTED_IP_LIST_KEY
}

internal fun decodeSubscriptionPayload(url: URL, payload: String, key: String): String =
    if (url.path.contains("encrypted", ignoreCase = true)) {
        EncryptedPayloadCodec.decryptText(payload, key, label = "encrypted Mihomo subscription")
    } else {
        payload
    }

class ConfigRepository(private val context: Context) {
    private val subscriptionStore = SubscriptionStore(context)
    private val userSubscriptionManager = UserSubscriptionManager(context, subscriptionStore)
    private val scanStateStore = WhiteDnsScanStateStore(context)

    suspend fun fetchOrCachedCatalog(): SubscriptionCatalog = fetchOrCachedMihomoConfig().catalog

    suspend fun refreshDefaultMihomoConfig(): MihomoSubscriptionSnapshot = withContext(Dispatchers.IO) {
        fetchAndCacheDefaultMihomoConfig(System.currentTimeMillis()).also { snapshot ->
            pruneProfileCaches(snapshot.catalog)
            DiagnosticLogger.info(
                context,
                "subscription.fetch.manual.success",
                "profiles=${snapshot.catalog.profiles.size} groups=${snapshot.summary.groups.size}",
            )
        }
    }

    suspend fun refreshAllSubscriptions() = withContext(Dispatchers.IO) {
        var successful = 0
        var failed = 0
        fun refresh(label: String, block: () -> Unit) {
            runCatching(block)
                .onSuccess { successful += 1 }
                .onFailure { error ->
                    failed += 1
                    DiagnosticLogger.warn(
                        context,
                        "subscription.background.refresh.failed",
                        "subscription=$label",
                        error,
                    )
                }
        }

        refresh(SubscriptionStore.DEFAULT_SUBSCRIPTION_ID) {
            fetchAndCacheDefaultMihomoConfig(System.currentTimeMillis())
        }
        userSubscriptionManager.list().forEach { subscription ->
            refresh(subscription.id) { userSubscriptionManager.refresh(subscription.id) }
        }
        DiagnosticLogger.info(
            context,
            "subscription.background.refresh.done",
            "successful=$successful failed=$failed",
        )
    }

    suspend fun readCachedMihomoConfigOrNull(): MihomoSubscriptionSnapshot? = withContext(Dispatchers.IO) {
        val selectedId = subscriptionStore.readSelectedSubscriptionId()
        if (selectedId != SubscriptionStore.DEFAULT_SUBSCRIPTION_ID) {
            return@withContext runCatching { userSubscriptionManager.cachedSnapshot(selectedId) }
                .onFailure { error ->
                    DiagnosticLogger.warn(context, "subscription.user.cache.failed", error = error)
                }
                .getOrNull()
        }
        val cachedYaml = readCachedYaml()
        if (cachedYaml.isBlank()) return@withContext null
        val cachedCatalog = subscriptionStore.readCatalog()
        val fetchedAt = cachedCatalog?.fetchedAt
            ?: cachedYamlFile().lastModified().takeIf { it > 0L }
            ?: System.currentTimeMillis()
        runCatching {
            MihomoConfigParser.parse(cachedYaml, fetchedAt).also { snapshot ->
                if (snapshot.catalog.profiles.isEmpty()) {
                    throw IOException("Cached Mihomo subscription did not contain proxies")
                }
                pruneProfileCaches(snapshot.catalog)
                DiagnosticLogger.info(
                    context,
                    "subscription.cache.local",
                    "profiles=${snapshot.catalog.profiles.size} groups=${snapshot.summary.groups.size} fetchedAt=${snapshot.catalog.fetchedAt}",
                )
            }
        }.onFailure { error ->
            DiagnosticLogger.warn(context, "subscription.cache.local.failed", error = error)
        }.getOrNull()
    }

    suspend fun fetchOrCachedMihomoConfig(): MihomoSubscriptionSnapshot = withContext(Dispatchers.IO) {
        val selectedId = subscriptionStore.readSelectedSubscriptionId()
        if (selectedId != SubscriptionStore.DEFAULT_SUBSCRIPTION_ID) {
            return@withContext fetchOrCachedUserSubscription(selectedId)
        }
        val nowMs = System.currentTimeMillis()
        val cachedCatalog = subscriptionStore.readCatalog()
        val cachedYaml = readCachedYaml()
        if (
            cachedCatalog != null &&
            cachedYaml.isNotBlank() &&
            nowMs - cachedCatalog.fetchedAt in 0 until WhiteDnsConfig.SUBSCRIPTION_REFRESH_INTERVAL_MS
        ) {
            val snapshot = MihomoConfigParser.parse(cachedYaml, cachedCatalog.fetchedAt)
            pruneProfileCaches(snapshot.catalog)
            DiagnosticLogger.info(
                context,
                "subscription.cache.fresh",
                "profiles=${snapshot.catalog.profiles.size} groups=${snapshot.summary.groups.size} fetchedAt=${snapshot.catalog.fetchedAt} ageMs=${nowMs - snapshot.catalog.fetchedAt}",
            )
            return@withContext snapshot
        }

        val fetched = runCatching { fetchAndCacheDefaultMihomoConfig(nowMs) }
        if (fetched.isSuccess) {
            val snapshot = fetched.getOrThrow()
            pruneProfileCaches(snapshot.catalog)
            DiagnosticLogger.info(
                context,
                "subscription.fetch.success",
                "profiles=${snapshot.catalog.profiles.size} groups=${snapshot.summary.groups.size} fetchedAt=${snapshot.catalog.fetchedAt}",
            )
            return@withContext snapshot
        }

        DiagnosticLogger.warn(context, "subscription.fetch.failed", error = fetched.exceptionOrNull())
        if (cachedYaml.isNotBlank()) {
            val fetchedAt = cachedCatalog?.fetchedAt ?: cachedYamlFile().lastModified().takeIf { it > 0L } ?: nowMs
            val snapshot = MihomoConfigParser.parse(cachedYaml, fetchedAt)
            pruneProfileCaches(snapshot.catalog)
            DiagnosticLogger.info(
                context,
                "subscription.cache.hit",
                "profiles=${snapshot.catalog.profiles.size} groups=${snapshot.summary.groups.size} fetchedAt=${snapshot.catalog.fetchedAt}",
            )
            return@withContext snapshot
        }

        DiagnosticLogger.error(context, "subscription.cache.miss", "no valid fetched YAML and no cached YAML")
        throw IOException(
            "Unable to fetch Mihomo subscription and no cached YAML is available",
            fetched.exceptionOrNull(),
        )
    }

    private fun fetchAndCacheDefaultMihomoConfig(nowMs: Long): MihomoSubscriptionSnapshot {
        DiagnosticLogger.info(context, "subscription.fetch.start", "url=${WhiteDnsConfig.MIHOMO_SUBSCRIPTION_URL}")
        val yaml = fetchYaml()
        val snapshot = MihomoConfigParser.parse(yaml, nowMs)
        if (snapshot.catalog.profiles.isEmpty()) {
            throw IOException("Mihomo subscription did not contain proxies")
        }
        writeCachedYaml(yaml)
        subscriptionStore.saveCatalog(snapshot.catalog)
        return snapshot
    }

    private fun fetchOrCachedUserSubscription(id: String): MihomoSubscriptionSnapshot {
        val cached = userSubscriptionManager.cachedSnapshot(id)
        val source = subscriptionStore.readUserSubscription(id)
            ?: throw IOException("Selected subscription no longer exists")
        val nowMs = System.currentTimeMillis()
        if (
            cached != null &&
            nowMs - source.updatedAt in 0 until WhiteDnsConfig.SUBSCRIPTION_REFRESH_INTERVAL_MS
        ) {
            pruneProfileCaches(cached.catalog)
            return cached
        }

        val refreshed = runCatching {
            userSubscriptionManager.refresh(id)
            userSubscriptionManager.cachedSnapshot(id)
                ?: throw IOException("Refreshed subscription did not contain proxies")
        }
        if (refreshed.isSuccess) {
            return refreshed.getOrThrow().also { pruneProfileCaches(it.catalog) }
        }
        if (cached != null) {
            DiagnosticLogger.warn(context, "subscription.user.refresh.failed", error = refreshed.exceptionOrNull())
            pruneProfileCaches(cached.catalog)
            return cached
        }
        throw IOException("Unable to refresh selected subscription", refreshed.exceptionOrNull())
    }

    private fun fetchYaml(): String {
        val subscriptionUrl = URL(WhiteDnsConfig.MIHOMO_SUBSCRIPTION_URL)
        val connection = subscriptionUrl
            .openConnection() as HttpURLConnection
        connection.connectTimeout = 12_000
        connection.readTimeout = 20_000
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/json,text/plain,*/*;q=0.1")

        return connection.use {
            DiagnosticLogger.info(context, "subscription.http.response", "code=$responseCode")
            if (responseCode !in 200..299) {
                throw IOException("Subscription returned HTTP $responseCode")
            }
            val payload = inputStream.bufferedReader().use { it.readText() }
            decodeSubscriptionPayload(subscriptionUrl, payload, WhiteDnsConfig.MIHOMO_SUBSCRIPTION_KEY)
        }
    }

    private fun pruneProfileCaches(catalog: SubscriptionCatalog) {
        val delayRemoved = subscriptionStore.pruneConnectionDelayRecords(
            subscriptionId = subscriptionStore.readSelectedSubscriptionId(),
            profiles = catalog.profiles,
        )
        val lastSelectedRemoved = scanStateStore.pruneLastSelectedProfile(catalog.profiles)
        if (delayRemoved > 0 || lastSelectedRemoved) {
            DiagnosticLogger.info(
                context,
                "subscription.cache.pruned",
                "delayRemoved=$delayRemoved lastSelectedRemoved=$lastSelectedRemoved activeProfiles=${catalog.profiles.size}",
            )
        }
    }

    private fun readCachedYaml(): String {
        val file = cachedYamlFile()
        return if (file.isFile && file.length() > 0L) file.readText() else ""
    }

    private fun writeCachedYaml(value: String) {
        val file = cachedYamlFile()
        file.parentFile?.mkdirs()
        file.writeText(value)
    }

    private fun cachedYamlFile(): File = File(context.filesDir, "mihomo/encrypted_mihomo_subscription.yaml")

    private inline fun <T> HttpURLConnection.use(block: HttpURLConnection.() -> T): T {
        return try {
            block()
        } finally {
            disconnect()
        }
    }
}
