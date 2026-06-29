package com.whitedns.vpn

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object WhiteDnsConfig {
    const val MIHOMO_SUBSCRIPTION_URL = "https://whitedns-sub.whitedns.workers.dev/mihomo/encrypted"
    const val MIHOMO_SUBSCRIPTION_KEY = "#2gzwj1##z%BVq*7M2sfxe6sV23ut1LQr87JagD4D#&"
    const val ENCRYPTED_IP_LIST_URL =
        "https://whitedns-encrypted-ip-list.whitedns.workers.dev/v1/results/ips/encrypted"
    const val ENCRYPTED_IP_LIST_KEY = "kc*P\$Hfw\$YqRSf%Ypyfzx#F\$kncPk9QG5%!W8M83K@f"
    const val SUBSCRIPTION_REFRESH_INTERVAL_MS = 3 * 60 * 60 * 1_000L
}

class ConfigRepository(private val context: Context) {
    private val subscriptionStore = SubscriptionStore(context)
    private val scanStateStore = WhiteDnsScanStateStore(context)

    suspend fun fetchOrCachedCatalog(): SubscriptionCatalog = fetchOrCachedMihomoConfig().catalog

    suspend fun readCachedMihomoConfigOrNull(): MihomoSubscriptionSnapshot? = withContext(Dispatchers.IO) {
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

        DiagnosticLogger.info(context, "subscription.fetch.start", "url=${WhiteDnsConfig.MIHOMO_SUBSCRIPTION_URL}")
        val fetched = runCatching {
            val yaml = fetchEncryptedYaml()
            val snapshot = MihomoConfigParser.parse(yaml, nowMs)
            if (snapshot.catalog.profiles.isEmpty()) {
                throw IOException("Mihomo subscription did not contain proxies")
            }
            writeCachedYaml(yaml)
            subscriptionStore.saveCatalog(snapshot.catalog)
            snapshot
        }
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

    private fun fetchEncryptedYaml(): String {
        val connection = URL(WhiteDnsConfig.MIHOMO_SUBSCRIPTION_URL)
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
            val encryptedPayload = inputStream.bufferedReader().use { it.readText() }
            EncryptedPayloadCodec.decryptText(
                encryptedPayload,
                WhiteDnsConfig.MIHOMO_SUBSCRIPTION_KEY,
                label = "encrypted Mihomo subscription",
            )
        }
    }

    private fun pruneProfileCaches(catalog: SubscriptionCatalog) {
        val delayRemoved = subscriptionStore.pruneTopDelaySelections(catalog.profiles)
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
