package com.whitedns.vpn

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

enum class ConnectionDelayStatus(val wireName: String) {
    Success("success"),
    Failure("failure");

    companion object {
        fun fromWireName(value: String): ConnectionDelayStatus? =
            entries.firstOrNull { it.wireName == value }
    }
}

data class ConnectionDelayRecord(
    val subscriptionId: String,
    val fingerprint: String,
    val delayMs: Int?,
    val status: ConnectionDelayStatus,
    val testedAt: Long,
    val speedKbps: Int? = null,
)

object ConnectionDelayRecordPolicy {
    fun latest(records: List<ConnectionDelayRecord>): List<ConnectionDelayRecord> =
        records
            .groupBy { it.subscriptionId to it.fingerprint }
            .mapNotNull { (_, matches) -> matches.maxByOrNull(ConnectionDelayRecord::testedAt) }
}

class SubscriptionStore(private val context: Context) {
    fun readUserSubscriptions(): List<UserSubscription> {
        val file = userSubscriptionsFile()
        if (!file.exists() || file.length() == 0L) return emptyList()
        var needsSourceKindMigration = false
        val subscriptions = runCatching {
            val items = JSONArray(file.readText())
            buildList {
                for (index in 0 until items.length()) {
                    val item = items.optJSONObject(index) ?: continue
                    val id = item.optString("id").takeIf(String::isNotBlank) ?: continue
                    val name = item.optString("name").takeIf(String::isNotBlank) ?: continue
                    val input = item.optString("input").takeIf(String::isNotBlank) ?: continue
                    val updatedAt = item.optLong("updatedAt", 0L)
                    val sourceKind = item.optString("sourceKind").takeIf(String::isNotBlank)
                    if (sourceKind == null) needsSourceKindMigration = true
                    add(
                        UserSubscription(
                            id = id,
                            name = name,
                            input = input,
                            format = UserSubscriptionFormat.fromWireName(item.optString("format")),
                            connectionCount = item.optInt("connectionCount", 0).coerceAtLeast(0),
                            updatedAt = updatedAt,
                            lastError = item.optString("lastError"),
                            fetchedAt = item.optLong("fetchedAt", updatedAt),
                            sourceKind = UserSubscriptionSourceKind.fromWireName(
                                sourceKind,
                                input,
                            ),
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
        if (needsSourceKindMigration && subscriptions.isNotEmpty()) {
            runCatching { writeUserSubscriptions(subscriptions) }
        }
        return subscriptions
    }

    @Synchronized
    fun saveUserSubscription(subscription: UserSubscription, yaml: String? = null) {
        val updated = readUserSubscriptions()
            .filterNot { it.id == subscription.id }
            .plus(subscription)
        if (yaml == null) {
            writeUserSubscriptions(updated)
        } else {
            replaceSubscriptionSnapshot(userSubscriptionYamlFile(subscription.id), yaml) {
                writeUserSubscriptions(updated)
            }
        }
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
        writeUserSubscriptions(remaining)
        userSubscriptionYamlFile(id).delete()
        deleteConnectionDelayRecords(id)
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

    fun readConnectionDelayRecords(
        subscriptionId: String,
        profiles: List<ConnectionProfile>,
        nowMs: Long = System.currentTimeMillis(),
        ttlMs: Long = ProfileDelayCacheDefaults.DELAY_CACHE_TTL_MS,
    ): List<ConnectionDelayRecord> = synchronized(DELAY_RECORDS_LOCK) {
        val byFingerprint = profiles.associateBy { it.fingerprint }
        val matchingRecords = readDelayRecords()
            .filter { record ->
                (record.subscriptionId == subscriptionId || record.subscriptionId.isBlank()) &&
                    record.fingerprint in byFingerprint &&
                    record.testedAt > 0L &&
                    nowMs - record.testedAt in 0..ttlMs
            }
            .map { record ->
                if (record.subscriptionId.isBlank()) record.copy(subscriptionId = subscriptionId) else record
            }
        ConnectionDelayRecordPolicy.latest(matchingRecords)
            .sortedWith(
                compareBy<ConnectionDelayRecord> { it.status != ConnectionDelayStatus.Success }
                    .thenBy { it.delayMs ?: Int.MAX_VALUE }
                    .thenByDescending(ConnectionDelayRecord::testedAt),
            )
    }

    fun saveConnectionDelayRecord(record: ConnectionDelayRecord) =
        saveConnectionDelayRecords(listOf(record))

    fun saveConnectionDelayRecords(records: List<ConnectionDelayRecord>) {
        val normalizedUpdates = ConnectionDelayRecordPolicy.latest(
            records.mapNotNull(::normalizeConnectionDelayRecord),
        )
        if (normalizedUpdates.isEmpty()) return
        synchronized(DELAY_RECORDS_LOCK) {
            val updatedFingerprints = normalizedUpdates.mapTo(mutableSetOf()) { it.fingerprint }
            val existing = readDelayRecords().filterNot { record ->
                record.subscriptionId.isBlank() && record.fingerprint in updatedFingerprints
            }
            writeDelayRecords(
                ConnectionDelayRecordPolicy.latest(normalizedUpdates + existing),
            )
        }
    }

    fun pruneConnectionDelayRecords(
        subscriptionId: String,
        profiles: List<ConnectionProfile>,
        nowMs: Long = System.currentTimeMillis(),
        ttlMs: Long = ProfileDelayCacheDefaults.DELAY_CACHE_TTL_MS,
    ): Int = synchronized(DELAY_RECORDS_LOCK) {
        val validFingerprints = profiles.map { it.fingerprint }.toSet()
        val existing = readDelayRecords()
        var removed = 0
        val kept = existing.mapNotNull { record ->
            val belongsToSubscription = record.subscriptionId == subscriptionId ||
                (record.subscriptionId.isBlank() && record.fingerprint in validFingerprints)
            if (!belongsToSubscription) return@mapNotNull record
            val valid = record.fingerprint in validFingerprints &&
                record.testedAt > 0L &&
                nowMs - record.testedAt in 0..ttlMs &&
                (
                    record.status == ConnectionDelayStatus.Failure ||
                        record.delayMs?.let { it > 0 } == true
                    )
            if (!valid) {
                removed += 1
                null
            } else {
                record.copy(subscriptionId = subscriptionId)
            }
        }
        val normalized = ConnectionDelayRecordPolicy.latest(kept)
        if (removed > 0 || normalized != existing) {
            writeDelayRecords(normalized)
        }
        removed
    }

    fun deleteConnectionDelayRecords(subscriptionId: String, fingerprints: Set<String>) {
        if (fingerprints.isEmpty()) return
        synchronized(DELAY_RECORDS_LOCK) {
            val existing = readDelayRecords()
            val kept = existing.filterNot {
                it.subscriptionId == subscriptionId && it.fingerprint in fingerprints
            }
            if (kept.size != existing.size) writeDelayRecords(kept)
        }
    }

    private fun deleteConnectionDelayRecords(subscriptionId: String) {
        synchronized(DELAY_RECORDS_LOCK) {
            val existing = readDelayRecords()
            val kept = existing.filterNot { it.subscriptionId == subscriptionId }
            if (kept.size != existing.size) writeDelayRecords(kept)
        }
    }

    private fun readDelayRecords(): List<ConnectionDelayRecord> {
        val file = delaysFile()
        if (!file.exists() || file.length() == 0L) return emptyList()
        return runCatching {
            val items = JSONArray(file.readText())
            buildList {
                for (index in 0 until items.length()) {
                    val item = items.optJSONObject(index) ?: continue
                    val fingerprint = item.optString("fingerprint").takeIf(String::isNotBlank) ?: continue
                    val testedAt = item.optLong(
                        "testedAt",
                        item.optLong("selectedAt", 0L),
                    )
                    if (testedAt <= 0L) continue
                    val delayMs = item.optInt("delayMs", -1).takeIf { it > 0 }
                    val storedStatus = ConnectionDelayStatus.fromWireName(item.optString("status"))
                        ?: if (delayMs != null) ConnectionDelayStatus.Success else ConnectionDelayStatus.Failure
                    val status = if (storedStatus == ConnectionDelayStatus.Success && delayMs != null) {
                        ConnectionDelayStatus.Success
                    } else {
                        ConnectionDelayStatus.Failure
                    }
                    add(
                        ConnectionDelayRecord(
                            subscriptionId = item.optString("subscriptionId"),
                            fingerprint = fingerprint,
                            delayMs = delayMs,
                            status = status,
                            testedAt = testedAt,
                            speedKbps = item.optInt("speedKbps", -1).takeIf { it > 0 },
                        ),
                    )
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun normalizeConnectionDelayRecord(record: ConnectionDelayRecord): ConnectionDelayRecord? {
        if (record.subscriptionId.isBlank() || record.fingerprint.isBlank() || record.testedAt <= 0L) {
            return null
        }
        val validDelayMs = record.delayMs?.takeIf {
            record.status == ConnectionDelayStatus.Success && it > 0
        }
        return record.copy(
            delayMs = validDelayMs,
            speedKbps = record.speedKbps?.takeIf { validDelayMs != null && it > 0 },
            status = if (validDelayMs != null) {
                ConnectionDelayStatus.Success
            } else {
                ConnectionDelayStatus.Failure
            },
        )
    }

    private fun writeDelayRecords(records: List<ConnectionDelayRecord>) {
        val items = JSONArray()
        records.forEach { record ->
            items.put(
                JSONObject()
                    .put("subscriptionId", record.subscriptionId)
                    .put("fingerprint", record.fingerprint)
                    .put("delayMs", record.delayMs ?: JSONObject.NULL)
                    .put("speedKbps", record.speedKbps ?: JSONObject.NULL)
                    .put("status", record.status.wireName)
                    .put("testedAt", record.testedAt),
            )
        }
        writeTextAtomically(delaysFile(), items.toString())
    }

    private fun catalogFile(): File = File(context.filesDir, CATALOG_FILE)

    private fun delaysFile(): File = File(context.filesDir, DELAYS_FILE)

    private fun userSubscriptionsFile(): File = File(context.filesDir, USER_SUBSCRIPTIONS_FILE)

    private fun userSubscriptionYamlFile(id: String): File =
        File(context.filesDir, "mihomo/subscriptions/$id.yaml")

    private fun writeUserSubscriptions(subscriptions: List<UserSubscription>) {
        val items = JSONArray()
        subscriptions.forEach { items.put(it.toJson()) }
        writeFile(userSubscriptionsFile(), items.toString())
    }

    private fun UserSubscription.toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("name", name)
        .put("input", input)
        .put("format", format.wireName)
        .put("connectionCount", connectionCount)
        .put("updatedAt", updatedAt)
        .put("fetchedAt", fetchedAt)
        .put("sourceKind", sourceKind.wireName)
        .put("lastError", lastError)

    private fun writeFile(file: File, value: String) {
        writeSubscriptionTextAtomically(file, value)
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
        private val DELAY_RECORDS_LOCK = Any()
    }
}

object ProfileDelayCacheDefaults {
    const val DELAY_CACHE_TTL_MS = 24 * 60 * 60 * 1_000L
}
