package com.whitedns.vpn

import io.nekohasekai.libbox.CommandClientHandler
import io.nekohasekai.libbox.ConnectionEvents
import io.nekohasekai.libbox.LogIterator
import io.nekohasekai.libbox.OutboundGroupIterator
import io.nekohasekai.libbox.StatusMessage
import io.nekohasekai.libbox.StringIterator
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
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

class ProfileDelayCollector(
    private val groupTag: String,
    private val profileTags: Set<String>,
    private val updateEvent: String = "profile.urlTest.update",
    private val logger: (event: String, message: String) -> Unit = { _, _ -> },
) : CommandClientHandler {
    private val delays = ConcurrentHashMap<String, Int>()
    private val updates = Channel<Map<String, Int>>(Channel.CONFLATED)

    suspend fun awaitBest(
        profiles: List<ConnectionProfile>,
        timeoutMs: Long = ProfileDelayDefaults.PROFILE_TEST_TIMEOUT_MS,
        quietMs: Long = ProfileDelayDefaults.PROFILE_TEST_QUIET_MS,
    ): SelectedConnectionProfile? {
        return awaitRanked(profiles, timeoutMs, quietMs).firstOrNull()
    }

    suspend fun awaitRanked(
        profiles: List<ConnectionProfile>,
        timeoutMs: Long = ProfileDelayDefaults.PROFILE_TEST_TIMEOUT_MS,
        quietMs: Long = ProfileDelayDefaults.PROFILE_TEST_QUIET_MS,
    ): List<SelectedConnectionProfile> {
        val deadline = System.currentTimeMillis() + timeoutMs
        var ranked = ProfileDelaySelector.rankByDelay(profiles, delays.toMap())
        while (System.currentTimeMillis() < deadline) {
            val remaining = deadline - System.currentTimeMillis()
            val waitMs = if (ranked.isEmpty()) remaining else minOf(quietMs, remaining)
            val update = withTimeoutOrNull(waitMs) { updates.receive() }
            if (update == null) {
                if (ranked.isNotEmpty()) return ranked
                continue
            }
            ranked = ProfileDelaySelector.rankByDelay(profiles, update)
        }
        return ranked
    }

    override fun writeGroups(message: OutboundGroupIterator) {
        var changed = false
        while (message.hasNext()) {
            val group = message.next()
            if (group.getTag() != groupTag) continue
            val items = group.getItems()
            while (items.hasNext()) {
                val item = items.next()
                val tag = item.getTag()
                val delay = item.getURLTestDelay()
                if (tag !in profileTags || delay <= 0) continue
                val previous = delays.put(tag, delay)
                changed = changed || previous != delay
            }
        }
        if (changed) {
            val snapshot = delays.toMap()
            logger(updateEvent, snapshot.entries.joinToString { "${it.key}=${it.value}ms" })
            updates.trySend(snapshot)
        }
    }

    override fun connected() {
        logger("profile.commandClient.connected", "")
    }

    override fun disconnected(message: String?) {
        logger("profile.commandClient.disconnected", message.orEmpty())
    }

    override fun clearLogs() = Unit

    override fun initializeClashMode(modeList: StringIterator, currentMode: String?) = Unit

    override fun setDefaultLogLevel(level: Int) = Unit

    override fun updateClashMode(newMode: String?) = Unit

    override fun writeConnectionEvents(events: ConnectionEvents?) = Unit

    override fun writeLogs(messageList: LogIterator?) = Unit

    override fun writeStatus(message: StatusMessage?) = Unit
}
