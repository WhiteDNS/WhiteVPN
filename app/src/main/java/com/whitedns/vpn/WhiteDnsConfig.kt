package com.whitedns.vpn

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
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
    private val subscriptionSnapshots = SubscriptionSnapshotResolver(
        persistence = AndroidSubscriptionSnapshotAdapter(context, subscriptionStore),
    )
    private val scanStateStore = WhiteDnsScanStateStore(context)

    suspend fun fetchOrCachedCatalog(): SubscriptionCatalog = fetchOrCachedMihomoConfig().catalog

    suspend fun refreshDefaultMihomoConfig(): MihomoSubscriptionSnapshot = withContext(Dispatchers.IO) {
        subscriptionSnapshots.resolve(
            SubscriptionStore.DEFAULT_SUBSCRIPTION_ID,
            SubscriptionRefreshPolicy.Force,
        ).snapshot.also { snapshot ->
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
        var fresh = 0
        val ids = listOf(SubscriptionStore.DEFAULT_SUBSCRIPTION_ID) +
            userSubscriptionManager.list().map(UserSubscription::id)
        ids.forEach { id ->
            try {
                when (subscriptionSnapshots.resolve(id).origin) {
                    SubscriptionSnapshotOrigin.FreshCache -> fresh += 1
                    SubscriptionSnapshotOrigin.Refreshed -> successful += 1
                    SubscriptionSnapshotOrigin.LastKnownGood -> failed += 1
                }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (error: Throwable) {
                failed += 1
                DiagnosticLogger.warn(
                    context,
                    "subscription.background.refresh.failed",
                    "subscription=$id",
                    error,
                )
            }
        }
        DiagnosticLogger.info(
            context,
            "subscription.background.refresh.done",
            "fresh=$fresh successful=$successful failed=$failed",
        )
    }

    suspend fun readCachedMihomoConfigOrNull(): MihomoSubscriptionSnapshot? = withContext(Dispatchers.IO) {
        val selectedId = subscriptionStore.readSelectedSubscriptionId()
        runCatching { subscriptionSnapshots.cached(selectedId) }
            .onFailure { error ->
                DiagnosticLogger.warn(context, "subscription.cache.local.failed", error = error)
            }
            .getOrNull()
            ?.also { snapshot ->
                pruneProfileCaches(snapshot.catalog)
                DiagnosticLogger.info(
                    context,
                    "subscription.cache.local",
                    "profiles=${snapshot.catalog.profiles.size} groups=${snapshot.summary.groups.size} fetchedAt=${snapshot.catalog.fetchedAt}",
                )
            }
    }

    suspend fun readCachedMihomoConfigOrNull(subscriptionId: String): MihomoSubscriptionSnapshot? {
        if (subscriptionId == subscriptionStore.readSelectedSubscriptionId()) {
            return readCachedMihomoConfigOrNull()
        }
        return withContext(Dispatchers.IO) { readCachedMihomoConfigOrNullNow(subscriptionId) }
    }

    internal fun readCachedMihomoConfigOrNullNow(subscriptionId: String): MihomoSubscriptionSnapshot? {
        return runCatching { subscriptionSnapshots.cached(subscriptionId) }.getOrNull()
    }

    suspend fun fetchOrCachedMihomoConfig(): MihomoSubscriptionSnapshot = withContext(Dispatchers.IO) {
        val selectedId = subscriptionStore.readSelectedSubscriptionId()
        val resolution = subscriptionSnapshots.resolve(selectedId)
        pruneProfileCaches(resolution.snapshot.catalog)
        DiagnosticLogger.info(
            context,
            "subscription.snapshot.resolved",
            "subscription=$selectedId origin=${resolution.origin} profiles=${resolution.snapshot.catalog.profiles.size} groups=${resolution.snapshot.summary.groups.size} fetchedAt=${resolution.snapshot.catalog.fetchedAt}",
        )
        resolution.snapshot
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

}
