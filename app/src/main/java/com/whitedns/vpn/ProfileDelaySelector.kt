package com.whitedns.vpn

import kotlin.random.Random

object ProfileDelayDefaults {
    const val PROFILE_TEST_TIMEOUT_MS = 8_000L
    const val PROFILE_TEST_QUIET_MS = 1_000L
}

object RuntimeHealthDefaults {
    const val RUNTIME_HEALTH_TIMEOUT_MS = 12_000L
    const val RUNTIME_HEALTH_QUIET_MS = 2_000L
}

object ProfileDelaySelector {
    fun rankByDelay(
        profiles: List<ConnectionProfile>,
        delaysByTag: Map<String, Int>,
        selectedAt: Long = System.currentTimeMillis(),
    ): List<SelectedConnectionProfile> {
        return profiles
            .mapNotNull { profile ->
                val delay = delaysByTag[profile.tag]?.takeIf { it > 0 } ?: return@mapNotNull null
                SelectedConnectionProfile(profile, delay, selectedAt)
            }
            .sortedBy { it.delayMs }
    }

    fun chooseBest(
        profiles: List<ConnectionProfile>,
        delaysByTag: Map<String, Int>,
        selectedAt: Long = System.currentTimeMillis(),
    ): SelectedConnectionProfile? {
        return rankByDelay(profiles, delaysByTag, selectedAt).firstOrNull()
    }
}

object ConnectionProfileSelectionPolicy {
    fun shuffledForConnectionTest(
        profiles: List<ConnectionProfile>,
        random: Random = Random.Default,
    ): List<ConnectionProfile> {
        if (profiles.size < 2) return profiles
        return profiles.shuffled(random)
    }
}
