package com.whitedns.vpn

import android.content.Context

class ConnectionSelectionPreferenceStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun readSelectedProfile(
        subscriptionId: String,
        profiles: List<ConnectionProfile>,
    ): ConnectionProfile? {
        val fingerprint = prefs.getString(key(subscriptionId), null) ?: return null
        return profiles.firstOrNull { it.fingerprint == fingerprint }
    }

    fun saveSelectedProfile(subscriptionId: String, profile: ConnectionProfile?) {
        prefs.edit().apply {
            if (profile == null) {
                remove(key(subscriptionId))
            } else {
                putString(key(subscriptionId), profile.fingerprint)
            }
        }.apply()
    }

    fun readAutomaticTypes(
        subscriptionId: String,
        profiles: List<ConnectionProfile>,
    ): Set<String> {
        return ConnectionTypeSelectionPolicy.restrictedTypes(
            selectedTypes = prefs.getStringSet(typesKey(subscriptionId), emptySet()).orEmpty(),
            profiles = profiles,
        )
    }

    fun saveAutomaticTypes(
        subscriptionId: String,
        selectedTypes: Set<String>,
        profiles: List<ConnectionProfile>,
    ) {
        val restrictedTypes = ConnectionTypeSelectionPolicy.restrictedTypes(selectedTypes, profiles)
        prefs.edit().apply {
            if (restrictedTypes.isEmpty()) {
                remove(typesKey(subscriptionId))
            } else {
                putStringSet(typesKey(subscriptionId), restrictedTypes)
            }
        }.apply()
    }

    fun readDelaySortEnabled(subscriptionId: String): Boolean =
        prefs.getBoolean(sortKey(subscriptionId), false)

    fun saveDelaySortEnabled(subscriptionId: String, enabled: Boolean) {
        prefs.edit().putBoolean(sortKey(subscriptionId), enabled).apply()
    }

    fun readAutoSortedTestId(subscriptionId: String): String? =
        prefs.getString(sortedTestKey(subscriptionId), null)

    fun saveAutoSortedTestId(subscriptionId: String, testId: String) {
        prefs.edit().putString(sortedTestKey(subscriptionId), testId).apply()
    }

    private fun key(subscriptionId: String): String = "$KEY_PREFIX$subscriptionId"
    private fun typesKey(subscriptionId: String): String = "$TYPES_KEY_PREFIX$subscriptionId"
    private fun sortKey(subscriptionId: String): String = "$SORT_KEY_PREFIX$subscriptionId"
    private fun sortedTestKey(subscriptionId: String): String = "$SORTED_TEST_KEY_PREFIX$subscriptionId"

    private companion object {
        const val PREFS_NAME = "white_dns_connection_selection"
        const val KEY_PREFIX = "profile:"
        const val TYPES_KEY_PREFIX = "types:"
        const val SORT_KEY_PREFIX = "delay-sort:"
        const val SORTED_TEST_KEY_PREFIX = "delay-sorted-test:"
    }
}

object ConnectionTypeSelectionPolicy {
    fun availableTypes(profiles: List<ConnectionProfile>): List<String> {
        return profiles
            .map { normalize(it.type) }
            .filter(String::isNotBlank)
            .distinct()
            .sorted()
    }

    fun restrictedTypes(
        selectedTypes: Set<String>,
        profiles: List<ConnectionProfile>,
    ): Set<String> {
        val available = availableTypes(profiles).toSet()
        val selected = selectedTypes.map(::normalize).filter { it in available }.toSet()
        return selected.takeIf { it.isNotEmpty() && it.size < available.size }.orEmpty()
    }

    fun filterProfiles(
        profiles: List<ConnectionProfile>,
        selectedTypes: Set<String>,
    ): List<ConnectionProfile> {
        val normalized = selectedTypes.map(::normalize).filter(String::isNotBlank).toSet()
        return if (normalized.isEmpty()) {
            profiles
        } else {
            profiles.filter { normalize(it.type) in normalized }
        }
    }

    private fun normalize(type: String): String = type.trim().lowercase()
}

object AutomaticConnectionCandidatePolicy {
    fun order(
        profiles: List<ConnectionProfile>,
        records: List<ConnectionDelayRecord>,
        lastSelectedProfile: ConnectionProfile?,
    ): List<ConnectionProfile> {
        val failedFingerprints = records
            .filter { it.status == ConnectionDelayStatus.Failure }
            .mapTo(mutableSetOf(), ConnectionDelayRecord::fingerprint)
        val delayByFingerprint = records
            .filter { it.status == ConnectionDelayStatus.Success && it.delayMs != null }
            .associate { it.fingerprint to it.delayMs!! }
        val originalOrder = profiles.mapIndexed { index, profile -> profile.fingerprint to index }.toMap()
        return profiles.filterNot { it.fingerprint in failedFingerprints }.sortedWith(
            compareBy<ConnectionProfile> { profile ->
                when {
                    profile.fingerprint in delayByFingerprint -> 0
                    profile.fingerprint == lastSelectedProfile?.fingerprint -> 1
                    else -> 2
                }
            }.thenBy { delayByFingerprint[it.fingerprint] ?: Int.MAX_VALUE }
                .thenBy { originalOrder[it.fingerprint] ?: Int.MAX_VALUE },
        )
    }
}
