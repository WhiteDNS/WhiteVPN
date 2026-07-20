package com.whitedns.vpn

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class SubscriptionStore(private val context: Context) {
    fun readUserSubscriptions(): List<UserSubscription> {
        val file = userSubscriptionsFile()
        if (!file.exists() || file.length() == 0L) return emptyList()
        return runCatching {
            val items = JSONArray(file.readText())
            buildList {
                for (index in 0 until items.length()) {
                    val item = items.optJSONObject(index) ?: continue
                    val id = item.optString("id").takeIf(String::isNotBlank) ?: continue
                    val name = item.optString("name").takeIf(String::isNotBlank) ?: continue
                    val input = item.optString("input").takeIf(String::isNotBlank) ?: continue
                    add(
                        UserSubscription(
                            id = id,
                            name = name,
                            input = input,
                            format = UserSubscriptionFormat.fromWireName(item.optString("format")),
                            connectionCount = item.optInt("connectionCount", 0).coerceAtLeast(0),
                            updatedAt = item.optLong("updatedAt", 0L),
                            lastError = item.optString("lastError"),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    @Synchronized
    fun saveUserSubscription(subscription: UserSubscription, yaml: String? = null) {
        val updated = readUserSubscriptions()
            .filterNot { it.id == subscription.id }
            .plus(subscription)
        val items = JSONArray()
        updated.forEach { item ->
            items.put(
                JSONObject()
                    .put("id", item.id)
                    .put("name", item.name)
                    .put("input", item.input)
                    .put("format", item.format.wireName)
                    .put("connectionCount", item.connectionCount)
                    .put("updatedAt", item.updatedAt)
                    .put("lastError", item.lastError),
            )
        }
        writeFile(userSubscriptionsFile(), items.toString())
        yaml?.let { writeFile(userSubscriptionYamlFile(subscription.id), it) }
    }

    fun readUserSubscription(id: String): UserSubscription? =
        readUserSubscriptions().firstOrNull { it.id == id }

    fun readUserSubscriptionYaml(id: String): String {
        val file = userSubscriptionYamlFile(id)
        return if (file.isFile && file.length() > 0L) file.readText() else ""
    }

    @Synchronized
    fun deleteUserSubscription(id: String) {
        val remaining = readUserSubscriptions().filterNot { it.id == id }
        val items = JSONArray()
        remaining.forEach { item ->
            items.put(
                JSONObject()
                    .put("id", item.id)
                    .put("name", item.name)
                    .put("input", item.input)
                    .put("format", item.format.wireName)
                    .put("connectionCount", item.connectionCount)
                    .put("updatedAt", item.updatedAt)
                    .put("lastError", item.lastError),
            )
        }
        writeFile(userSubscriptionsFile(), items.toString())
        userSubscriptionYamlFile(id).delete()
        if (readSelectedSubscriptionId() == id) saveSelectedSubscriptionId(DEFAULT_SUBSCRIPTION_ID)
    }

    fun readSelectedSubscriptionId(): String = context
        .getSharedPreferences(USER_SUBSCRIPTION_PREFS, Context.MODE_PRIVATE)
        .getString(KEY_SELECTED_SUBSCRIPTION, DEFAULT_SUBSCRIPTION_ID)
        .orEmpty()
        .ifBlank { DEFAULT_SUBSCRIPTION_ID }

    fun saveSelectedSubscriptionId(id: String) {
        val validId = if (id == DEFAULT_SUBSCRIPTION_ID || readUserSubscription(id) != null) {
            id
        } else {
            DEFAULT_SUBSCRIPTION_ID
        }
        context.getSharedPreferences(USER_SUBSCRIPTION_PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_SELECTED_SUBSCRIPTION, validId)
            .apply()
    }

    fun readCatalog(): SubscriptionCatalog? {
        val file = catalogFile()
        if (!file.exists() || file.length() == 0L) return null
        return runCatching {
            val root = JSONObject(file.readText())
            val profiles = root.optJSONArray("profiles").orEmptyObjects().mapNotNull { item ->
                val outboundJson = item.optString("outboundJson").takeIf(String::isNotBlank) ?: return@mapNotNull null
                ConnectionProfile(
                    tag = item.optString("tag"),
                    type = item.optString("type"),
                    server = item.optString("server"),
                    port = item.optInt("port", -1),
                    transport = item.optString("transport"),
                    validationHost = item.optString("validationHost"),
                    fingerprint = item.optString("fingerprint"),
                    outboundJson = outboundJson,
                ).takeIf {
                    it.tag.isNotBlank() &&
                        it.type.isNotBlank() &&
                        it.server.isNotBlank() &&
                        it.port > 0 &&
                        it.fingerprint.isNotBlank()
                }
            }.toList()
            SubscriptionCatalog(
                profiles = profiles,
                fetchedAt = root.optLong("fetchedAt", 0L),
            ).takeIf { it.profiles.isNotEmpty() }
        }.getOrNull()
    }

    fun saveCatalog(catalog: SubscriptionCatalog) {
        val profiles = JSONArray()
        catalog.profiles.forEach { profile ->
            profiles.put(
                JSONObject()
                    .put("tag", profile.tag)
                    .put("type", profile.type)
                    .put("server", profile.server)
                    .put("port", profile.port)
                    .put("transport", profile.transport)
                    .put("validationHost", profile.validationHost)
                    .put("fingerprint", profile.fingerprint)
                    .put("outboundJson", profile.outboundJson),
            )
        }
        writeFile(
            catalogFile(),
            JSONObject()
                .put("fetchedAt", catalog.fetchedAt)
                .put("profiles", profiles)
                .toString(),
        )
    }

    fun readTopDelaySelections(
        profiles: List<ConnectionProfile>,
        nowMs: Long = System.currentTimeMillis(),
        ttlMs: Long = ProfileDelayCacheDefaults.DELAY_CACHE_TTL_MS,
    ): List<SelectedConnectionProfile> {
        val byFingerprint = profiles.associateBy { it.fingerprint }
        val file = delaysFile()
        if (!file.exists() || file.length() == 0L) return emptyList()
        return runCatching {
            val items = JSONArray(file.readText())
            val selections = mutableListOf<SelectedConnectionProfile>()
            for (index in 0 until items.length()) {
                val item = items.optJSONObject(index) ?: continue
                val selectedAt = item.optLong("selectedAt", 0L)
                if (selectedAt <= 0L || nowMs - selectedAt > ttlMs) continue
                val profile = byFingerprint[item.optString("fingerprint")] ?: continue
                val delayMs = item.optInt("delayMs", -1).takeIf { it > 0 } ?: continue
                selections += SelectedConnectionProfile(profile, delayMs, selectedAt)
            }
            selections.sortedWith(compareBy<SelectedConnectionProfile> { it.delayMs }.thenByDescending { it.selectedAt })
        }.getOrDefault(emptyList())
    }

    fun mergeTopDelaySelections(
        profiles: List<ConnectionProfile>,
        newSelections: List<SelectedConnectionProfile>,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        val existing = readTopDelaySelections(profiles, nowMs)
        val merged = (newSelections + existing)
            .filter { it.delayMs > 0 }
            .groupBy { it.profile.fingerprint }
            .map { (_, selections) ->
                selections.minWith(compareBy<SelectedConnectionProfile> { it.delayMs }.thenByDescending { it.selectedAt })
            }
            .sortedWith(compareBy<SelectedConnectionProfile> { it.delayMs }.thenByDescending { it.selectedAt })
            .take(ProfileDelayCacheDefaults.MAX_DELAY_CACHE_PROFILES)

        val items = JSONArray()
        merged.forEach { selection ->
            items.put(
                JSONObject()
                    .put("fingerprint", selection.profile.fingerprint)
                    .put("delayMs", selection.delayMs)
                    .put("selectedAt", selection.selectedAt),
            )
        }
        writeFile(delaysFile(), items.toString())
    }

    fun pruneTopDelaySelections(
        profiles: List<ConnectionProfile>,
        nowMs: Long = System.currentTimeMillis(),
        ttlMs: Long = ProfileDelayCacheDefaults.DELAY_CACHE_TTL_MS,
    ): Int {
        val file = delaysFile()
        if (!file.exists() || file.length() == 0L) return 0
        val validFingerprints = profiles.map { it.fingerprint }.toSet()
        return runCatching {
            val existing = JSONArray(file.readText())
            val kept = JSONArray()
            var removed = 0
            for (index in 0 until existing.length()) {
                val item = existing.optJSONObject(index)
                val selectedAt = item?.optLong("selectedAt", 0L) ?: 0L
                val fingerprint = item?.optString("fingerprint").orEmpty()
                val isValid = fingerprint in validFingerprints &&
                    selectedAt > 0L &&
                    nowMs - selectedAt <= ttlMs &&
                    item?.optInt("delayMs", -1)?.let { it > 0 } == true
                if (isValid) {
                    kept.put(item)
                } else {
                    removed += 1
                }
            }
            if (removed > 0) {
                writeFile(file, kept.toString())
            }
            removed
        }.getOrDefault(0)
    }

    private fun catalogFile(): File = File(context.filesDir, CATALOG_FILE)

    private fun delaysFile(): File = File(context.filesDir, DELAYS_FILE)

    private fun userSubscriptionsFile(): File = File(context.filesDir, USER_SUBSCRIPTIONS_FILE)

    private fun userSubscriptionYamlFile(id: String): File =
        File(context.filesDir, "mihomo/subscriptions/$id.yaml")

    private fun writeFile(file: File, value: String) {
        file.parentFile?.mkdirs()
        file.writeText(value)
    }

    private fun JSONArray?.orEmptyObjects(): Sequence<JSONObject> {
        val array = this ?: return emptySequence()
        return sequence {
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let { yield(it) }
            }
        }
    }

    companion object {
        const val DEFAULT_SUBSCRIPTION_ID = "whitedns"
        private const val CATALOG_FILE = "subscription-catalog.json"
        private const val DELAYS_FILE = "profile-delay-cache.json"
        private const val USER_SUBSCRIPTIONS_FILE = "user-subscriptions.json"
        private const val USER_SUBSCRIPTION_PREFS = "white_dns_user_subscriptions"
        private const val KEY_SELECTED_SUBSCRIPTION = "selected_subscription"
    }
}

object ProfileDelayCacheDefaults {
    const val DELAY_CACHE_TTL_MS = 24 * 60 * 60 * 1_000L
    const val MAX_DELAY_CACHE_PROFILES = 24
}
