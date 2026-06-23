package com.whitedns.vpn

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

object WhiteDnsConfig {
    const val SING_BOX_SUBSCRIPTION_URL =
        "https://whitedns-sub.whitedns.workers.dev/encrypted"
    const val SUBSCRIPTION_ENCRYPTION_KEY = "#2gzwj1##z%BVq*7M2sfxe6sV23ut1LQr87JagD4D#&"
    const val ENCRYPTED_IP_LIST_URL =
        "https://whitedns-encrypted-ip-list.whitedns.workers.dev/v1/results/ips/encrypted"
    const val ENCRYPTED_IP_LIST_KEY = "kc*P\$Hfw\$YqRSf%Ypyfzx#F\$kncPk9QG5%!W8M83K@f"
    const val SUBSCRIPTION_REFRESH_INTERVAL_MS = 3 * 60 * 60 * 1_000L
}

class ConfigRepository(private val context: Context) {
    private val subscriptionStore = SubscriptionStore(context)
    private val cleanIpCache = CleanIpCache(context)
    private val scanStateStore = WhiteDnsScanStateStore(context)

    suspend fun fetchOrCachedCatalog(): SubscriptionCatalog = withContext(Dispatchers.IO) {
        val nowMs = System.currentTimeMillis()
        val cached = subscriptionStore.readCatalog()
        if (cached != null && nowMs - cached.fetchedAt in 0 until WhiteDnsConfig.SUBSCRIPTION_REFRESH_INTERVAL_MS) {
            pruneProfileCaches(cached)
            DiagnosticLogger.info(
                context,
                "subscription.cache.fresh",
                "profiles=${cached.profiles.size} fetchedAt=${cached.fetchedAt} ageMs=${nowMs - cached.fetchedAt}",
            )
            return@withContext cached
        }

        DiagnosticLogger.info(context, "subscription.fetch.start", "url=${WhiteDnsConfig.SING_BOX_SUBSCRIPTION_URL}")
        val fetched = runCatching { fetchCatalog() }
        if (fetched.isSuccess) {
            val catalog = fetched.getOrThrow()
            subscriptionStore.saveCatalog(catalog)
            pruneProfileCaches(catalog)
            DiagnosticLogger.info(
                context,
                "subscription.fetch.success",
                "profiles=${catalog.profiles.size} fetchedAt=${catalog.fetchedAt}",
            )
            return@withContext catalog
        }
        DiagnosticLogger.warn(context, "subscription.fetch.failed", error = fetched.exceptionOrNull())

        if (cached != null) {
            pruneProfileCaches(cached)
            DiagnosticLogger.info(
                context,
                "subscription.cache.hit",
                "profiles=${cached.profiles.size} fetchedAt=${cached.fetchedAt}",
            )
            return@withContext cached
        }

        DiagnosticLogger.error(context, "subscription.cache.miss", "no valid fetched catalog and no cached catalog")
        throw IOException(
            "Unable to fetch subscription and no cached profile catalog is available",
            fetched.exceptionOrNull(),
        )
    }

    private fun fetchCatalog(): SubscriptionCatalog {
        val connection = URL(WhiteDnsConfig.SING_BOX_SUBSCRIPTION_URL)
            .openConnection() as HttpURLConnection
        connection.connectTimeout = 12_000
        connection.readTimeout = 20_000
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/json,*/*;q=0.1")

        return connection.use {
            DiagnosticLogger.info(context, "subscription.http.response", "code=$responseCode")
            if (responseCode !in 200..299) {
                throw IOException("Subscription returned HTTP $responseCode")
            }
            val body = inputStream.bufferedReader().use { reader -> reader.readText() }
            val subscription = EncryptedPayloadCodec.decryptText(
                payloadJson = body,
                passphrase = WhiteDnsConfig.SUBSCRIPTION_ENCRYPTION_KEY,
                label = "encrypted subscription",
            )
            val parsed = ProxySubscriptionParser.parseBase64(subscription)
            DiagnosticLogger.info(
                context,
                "subscription.parse",
                "total=${parsed.stats.totalLinks} supported=${parsed.stats.supportedLinks} duplicates=${parsed.stats.duplicateLinks} malformed=${parsed.stats.malformedLinks} unsupported=${parsed.stats.unsupportedLinks}",
            )
            if (parsed.catalog.profiles.isEmpty()) {
                throw IOException("Subscription did not contain supported profiles")
            }
            parsed.catalog
        }
    }

    private fun pruneProfileCaches(catalog: SubscriptionCatalog) {
        val delayRemoved = subscriptionStore.pruneTopDelaySelections(catalog.profiles)
        val cleanIpRemoved = cleanIpCache.pruneForProfiles(catalog.profiles)
        val lastSelectedRemoved = scanStateStore.pruneLastSelectedProfile(catalog.profiles)
        if (delayRemoved > 0 || cleanIpRemoved > 0 || lastSelectedRemoved) {
            DiagnosticLogger.info(
                context,
                "subscription.cache.pruned",
                "delayRemoved=$delayRemoved cleanIpRemoved=$cleanIpRemoved lastSelectedRemoved=$lastSelectedRemoved activeProfiles=${catalog.profiles.size}",
            )
        }
    }

    private inline fun <T> HttpURLConnection.use(block: HttpURLConnection.() -> T): T {
        return try {
            block()
        } finally {
            disconnect()
        }
    }

}

object SingBoxConfigValidator {
    fun requireValid(config: String) {
        require(config.isNotBlank()) { "Config is blank" }

        val root = runCatching { JSONObject(config) }
            .getOrElse { throw IllegalArgumentException("Config is not valid JSON", it) }

        val inbounds = root.optJSONArray("inbounds")
            ?: throw IllegalArgumentException("Config is missing inbounds")
        val outbounds = root.optJSONArray("outbounds")
            ?: throw IllegalArgumentException("Config is missing outbounds")

        require(inbounds.hasObjectWithType("tun")) { "Config is missing a tun inbound" }
        require(outbounds.hasUsableOutbound()) { "Config is missing a usable outbound" }
    }

    private fun JSONArray.hasObjectWithType(type: String): Boolean {
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            if (item.optString("type") == type) return true
        }
        return false
    }

    private fun JSONArray.hasUsableOutbound(): Boolean {
        for (index in 0 until length()) {
            val item = optJSONObject(index) ?: continue
            val type = item.optString("type")
            val tag = item.optString("tag")
            if (type.isNotBlank() && type != "direct" && tag.isNotBlank()) return true
        }
        return false
    }
}
