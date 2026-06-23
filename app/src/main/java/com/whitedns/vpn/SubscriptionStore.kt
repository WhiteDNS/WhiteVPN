package com.whitedns.vpn

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class SubscriptionStore(private val context: Context) {
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

    private companion object {
        const val CATALOG_FILE = "subscription-catalog.json"
        const val DELAYS_FILE = "profile-delay-cache.json"
    }
}

object ProfileDelayCacheDefaults {
    const val DELAY_CACHE_TTL_MS = 24 * 60 * 60 * 1_000L
    const val MAX_DELAY_CACHE_PROFILES = 24
}
