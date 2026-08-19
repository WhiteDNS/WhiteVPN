package com.whitedns.vpn

import android.content.Context
import org.json.JSONObject
import java.io.IOException
import java.util.Locale

enum class ConnectionChainSlot(val wireName: String) {
    Before("before"),
    Base("base"),
    After("after");

    companion object {
        fun fromWireName(value: String?): ConnectionChainSlot? =
            entries.firstOrNull { it.wireName == value }
    }
}

enum class ConnectionChainHopMode(val wireName: String) {
    Off("off"),
    Automatic("automatic"),
    Fixed("fixed");

    companion object {
        fun fromWireName(value: String?): ConnectionChainHopMode =
            entries.firstOrNull { it.wireName == value } ?: Off
    }
}

data class ConnectionChainProfileRef(
    val subscriptionId: String,
    val fingerprint: String,
)

data class ConnectionChainHop(
    val mode: ConnectionChainHopMode,
    val profileRef: ConnectionChainProfileRef? = null,
) {
    companion object {
        fun off() = ConnectionChainHop(ConnectionChainHopMode.Off)
        fun automatic() = ConnectionChainHop(ConnectionChainHopMode.Automatic)
        fun fixed(subscriptionId: String, fingerprint: String) = ConnectionChainHop(
            mode = ConnectionChainHopMode.Fixed,
            profileRef = ConnectionChainProfileRef(subscriptionId, fingerprint),
        )
    }
}

data class ConnectionChainSettings(
    val enabled: Boolean = false,
    val before: ConnectionChainHop = ConnectionChainHop.off(),
    val base: ConnectionChainHop = ConnectionChainHop.automatic(),
    val after: ConnectionChainHop = ConnectionChainHop.off(),
) {
    fun hop(slot: ConnectionChainSlot): ConnectionChainHop = when (slot) {
        ConnectionChainSlot.Before -> before
        ConnectionChainSlot.Base -> base
        ConnectionChainSlot.After -> after
    }

    fun withHop(slot: ConnectionChainSlot, hop: ConnectionChainHop): ConnectionChainSettings = when (slot) {
        ConnectionChainSlot.Before -> copy(before = hop)
        ConnectionChainSlot.Base -> copy(base = hop)
        ConnectionChainSlot.After -> copy(after = hop)
    }.normalized()

    val configuredHopCount: Int
        get() = listOf(before, base, after).count { it.mode != ConnectionChainHopMode.Off }

    val isActive: Boolean
        get() = enabled && configuredHopCount > 1

    fun normalized(): ConnectionChainSettings = copy(
        before = before.normalized(optional = true),
        base = base.normalized(optional = false),
        after = after.normalized(optional = true),
    )

    private fun ConnectionChainHop.normalized(optional: Boolean): ConnectionChainHop {
        if (mode == ConnectionChainHopMode.Fixed) {
            val ref = profileRef
            if (ref != null && ref.subscriptionId.isNotBlank() && ref.fingerprint.isNotBlank()) return this
            return if (optional) ConnectionChainHop.off() else ConnectionChainHop.automatic()
        }
        if (mode == ConnectionChainHopMode.Off && !optional) return ConnectionChainHop.automatic()
        return copy(profileRef = null)
    }
}

class ConnectionChainPreferenceStore(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun read(): ConnectionChainSettings {
        val raw = prefs.getString(KEY_SETTINGS, null) ?: return ConnectionChainSettings()
        return runCatching {
            val root = JSONObject(raw)
            ConnectionChainSettings(
                enabled = root.optBoolean("enabled", false),
                before = ConnectionChainHop.off(),
                base = root.optJSONObject("base").toHop(defaultAutomatic = true),
                after = root.optJSONObject("after").toHop(),
            ).normalized()
        }.getOrDefault(ConnectionChainSettings())
    }

    fun save(settings: ConnectionChainSettings) {
        val value = settings.copy(before = ConnectionChainHop.off()).normalized()
        prefs.edit().putString(
            KEY_SETTINGS,
            JSONObject()
                .put("enabled", value.enabled)
                .put("before", value.before.toJson())
                .put("base", value.base.toJson())
                .put("after", value.after.toJson())
                .toString(),
        ).apply()
    }

    private fun ConnectionChainHop.toJson(): JSONObject = JSONObject()
        .put("mode", mode.wireName)
        .apply {
            profileRef?.let { ref ->
                put("subscriptionId", ref.subscriptionId)
                put("fingerprint", ref.fingerprint)
            }
        }

    private fun JSONObject?.toHop(defaultAutomatic: Boolean = false): ConnectionChainHop {
        if (this == null) {
            return if (defaultAutomatic) ConnectionChainHop.automatic() else ConnectionChainHop.off()
        }
        return when (ConnectionChainHopMode.fromWireName(optString("mode"))) {
            ConnectionChainHopMode.Off -> ConnectionChainHop.off()
            ConnectionChainHopMode.Automatic -> ConnectionChainHop.automatic()
            ConnectionChainHopMode.Fixed -> ConnectionChainHop.fixed(
                subscriptionId = optString("subscriptionId"),
                fingerprint = optString("fingerprint"),
            )
        }
    }

    private companion object {
        const val PREFS_NAME = "white_dns_connection_chain"
        const val KEY_SETTINGS = "settings"
    }
}

data class ConnectionChainSource(
    val subscriptionId: String,
    val subscriptionName: String,
    val snapshot: MihomoSubscriptionSnapshot,
)

data class ConnectionChainPickerOption(
    val subscriptionId: String,
    val subscriptionName: String,
    val profile: ConnectionProfile,
)

enum class ConnectionChainCompatibilityIssue {
    SelectedConnectionUnavailable,
    SameConnection,
    SharedProxyDependency,
    DownstreamRequiresUdpCapableUpstream,
}

data class MihomoProxyBlock(
    val name: String,
    val type: String,
    val server: String,
    val network: String,
    val transport: String,
    val proto: String,
    val udpEnabled: Boolean,
    val udpOverStream: Boolean,
    val dialerProxy: String?,
    val lines: List<String>,
) {
    val requiresUdpDialer: Boolean
        get() = when (type.lowercase(Locale.US)) {
            "openvpn" -> !proto.lowercase(Locale.US).startsWith("tcp")
            "masque" -> !network.equals("h2", ignoreCase = true)
            else -> type.lowercase(Locale.US) in UDP_TRANSPORT_TYPES ||
            network.lowercase(Locale.US) in UDP_NETWORKS ||
            transport.equals("udp", ignoreCase = true) ||
            (
                udpEnabled &&
                    !udpOverStream &&
                    type.lowercase(Locale.US) in NATIVE_PACKET_TYPES
            )
        }

    val supportsUdpDialing: Boolean
        get() = udpEnabled || type.lowercase(Locale.US) in UDP_CAPABLE_TYPES

    fun render(runtimeName: String, dialerProxy: String?): List<String> {
        var nameReplaced = false
        var dialerReplaced = false
        val rendered = lines.mapNotNull { line ->
            val indent = indentation(line)
            val content = line.trimStart()
            when {
                indent == 2 && content.startsWith("- name:") -> {
                    nameReplaced = true
                    "  - name: ${yamlSingleQuoted(runtimeName)}"
                }
                indent == 4 && fieldName(line) == "name" -> {
                    nameReplaced = true
                    "    name: ${yamlSingleQuoted(runtimeName)}"
                }
                indent == 4 && fieldName(line) == "dialer-proxy" -> {
                    if (dialerProxy == null) {
                        null
                    } else {
                        dialerReplaced = true
                        "    dialer-proxy: ${yamlSingleQuoted(dialerProxy)}"
                    }
                }
                else -> line
            }
        }.toMutableList()
        if (!nameReplaced) throw IOException("Proxy [$name] does not have a patchable name")
        if (dialerProxy != null && !dialerReplaced) {
            val insertAt = rendered.indexOfLast { it.isNotBlank() }.let { if (it < 0) rendered.size else it + 1 }
            rendered.add(insertAt, "    dialer-proxy: ${yamlSingleQuoted(dialerProxy)}")
        }
        return rendered
    }

    private companion object {
        val UDP_TRANSPORT_TYPES = setOf(
            "wireguard", "hysteria", "hysteria2", "hy2", "tuic", "juicity",
            "shadowquic",
        )
        val UDP_CAPABLE_TYPES = setOf(
            "hysteria", "hysteria2", "hy2", "tuic", "shadowquic", "openvpn",
        )
        val NATIVE_PACKET_TYPES = setOf(
            "ss", "shadowsocks", "ssr", "shadowsocksr", "socks5", "gost-relay",
        )
        val UDP_NETWORKS = setOf("quic", "h3", "kcp", "mkcp")
    }
}

object MihomoProxyBlockParser {
    fun parse(rawYaml: String): List<MihomoProxyBlock> {
        val blocks = mutableListOf<MihomoProxyBlock>()
        var inProxies = false
        var current = mutableListOf<String>()

        fun flush() {
            if (current.isEmpty()) return
            toBlock(current)?.let(blocks::add)
            current = mutableListOf()
        }

        rawYaml.replace("\r\n", "\n").replace('\r', '\n').split('\n').forEach { line ->
            val topLevel = topLevelKey(line)
            if (topLevel != null) {
                if (inProxies) flush()
                inProxies = topLevel == "proxies"
                return@forEach
            }
            if (!inProxies) return@forEach
            if (indentation(line) == 2 && line.trimStart().startsWith("- ")) {
                flush()
                current += line
            } else if (current.isNotEmpty()) {
                current += line
            }
        }
        if (inProxies) flush()
        val duplicate = blocks.groupingBy(MihomoProxyBlock::name).eachCount().entries
            .firstOrNull { it.value > 1 }
        if (duplicate != null) throw IOException("Duplicate proxy name [${duplicate.key}]")
        return blocks
    }

    private fun toBlock(lines: List<String>): MihomoProxyBlock? {
        val name = proxyFieldValue(lines, "name")?.takeIf(String::isNotBlank) ?: return null
        val type = proxyFieldValue(lines, "type")?.takeIf(String::isNotBlank) ?: return null
        return MihomoProxyBlock(
            name = name,
            type = type,
            server = proxyFieldValue(lines, "server").orEmpty(),
            network = proxyFieldValue(lines, "network").orEmpty(),
            transport = proxyFieldValue(lines, "transport").orEmpty(),
            proto = proxyFieldValue(lines, "proto").orEmpty(),
            udpEnabled = proxyFieldValue(lines, "udp").equals("true", ignoreCase = true),
            udpOverStream = listOf("udp-over-tcp", "udp-over-stream")
                .any { proxyFieldValue(lines, it).equals("true", ignoreCase = true) },
            dialerProxy = proxyFieldValue(lines, "dialer-proxy")?.takeIf(String::isNotBlank),
            lines = lines.dropLastWhile(String::isBlank),
        )
    }
}

data class ConnectionChainCandidate(
    val subscriptionId: String,
    val subscriptionName: String,
    val profile: ConnectionProfile,
    val blocks: List<MihomoProxyBlock>,
    val rank: Int = 0,
    val automaticEligible: Boolean = true,
) {
    val ref: ConnectionChainProfileRef
        get() = ConnectionChainProfileRef(subscriptionId, profile.fingerprint)

    val entryBlock: MihomoProxyBlock get() = blocks.first()
    val rootBlock: MihomoProxyBlock get() = blocks.last()
}

data class ResolvedConnectionChainProxy(
    val block: MihomoProxyBlock,
    val runtimeName: String,
)

data class ResolvedConnectionChainHop(
    val slot: ConnectionChainSlot,
    val candidate: ConnectionChainCandidate,
    val proxies: List<ResolvedConnectionChainProxy>,
) {
    val runtimeName: String get() = proxies.last().runtimeName
}

data class ConnectionChainRuntimePlan(
    val hops: List<ResolvedConnectionChainHop>,
    val rawYaml: String,
) {
    val finalHop: ResolvedConnectionChainHop get() = hops.last()
    val finalRuntimeName: String get() = finalHop.runtimeName
    val summary: String get() = hops.joinToString(" -> ") { it.candidate.profile.tag }
}

object ConnectionChainPlanner {
    const val MAX_RUNTIME_ATTEMPTS = 12
    const val GROUP_NAME = "WhiteDNS Proxy Chain"
    private const val MAX_COMPATIBILITY_CHECKS = 1_000_000

    fun plans(
        settings: ConnectionChainSettings,
        sources: List<ConnectionChainSource>,
        delayRecords: List<ConnectionDelayRecord>,
        allowIpv6Literals: Boolean = true,
    ): List<ConnectionChainRuntimePlan> {
        val normalized = settings.normalized()
        if (!normalized.isActive) return emptyList()
        val configuredSlots = ConnectionChainSlot.entries.filter {
            normalized.hop(it).mode != ConnectionChainHopMode.Off
        }
        val fixedSubscriptionIds = configuredSlots.mapNotNull { slot ->
            normalized.hop(slot).profileRef?.subscriptionId
        }.toSet()
        val hasAutomaticHop = configuredSlots.any {
            normalized.hop(it).mode == ConnectionChainHopMode.Automatic
        }
        val candidateSources = if (hasAutomaticHop) {
            sources
        } else {
            sources.filter { it.subscriptionId in fixedSubscriptionIds }
        }
        val recordsByRef = delayRecords.associateBy {
            ConnectionChainProfileRef(it.subscriptionId, it.fingerprint)
        }
        val candidates = candidateSources.flatMap { source ->
            val blocks = try {
                MihomoProxyBlockParser.parse(source.snapshot.rawConfig).associateBy(MihomoProxyBlock::name)
            } catch (error: IOException) {
                if (source.subscriptionId in fixedSubscriptionIds) throw error
                return@flatMap emptyList()
            }
            source.snapshot.catalog.profiles.mapNotNull { profile ->
                val proxyChain = resolveProxyChain(profile.tag, blocks) ?: return@mapNotNull null
                if (!allowIpv6Literals && proxyChain.any { it.server.isIpv6Literal() }) {
                    return@mapNotNull null
                }
                val record = recordsByRef[ConnectionChainProfileRef(source.subscriptionId, profile.fingerprint)]
                val rank = when {
                    record?.status == ConnectionDelayStatus.Success && record.delayMs != null -> record.delayMs
                    else -> Int.MAX_VALUE / 2
                }
                ConnectionChainCandidate(
                    subscriptionId = source.subscriptionId,
                    subscriptionName = source.subscriptionName,
                    profile = profile,
                    blocks = proxyChain,
                    rank = rank,
                    automaticEligible = record?.status != ConnectionDelayStatus.Failure,
                )
            }
        }.sortedWith(
            compareBy<ConnectionChainCandidate> { it.rank }
                .thenBy { it.subscriptionName.lowercase(Locale.getDefault()) }
                .thenBy { it.profile.tag.lowercase(Locale.getDefault()) },
        )
        if (candidates.isEmpty()) throw IOException("No cached connections are available for chaining")

        val choices = configuredSlots.map { slot -> choicesFor(normalized.hop(slot), candidates) }
        return bestCombinations(configuredSlots, choices)
            .map(::toRuntimePlan)
            .also { plans ->
                if (plans.isEmpty()) throw IOException("No compatible connection chain is available")
            }
    }

    fun selectableFingerprints(snapshot: MihomoSubscriptionSnapshot): Set<String> = runCatching {
        val blocks = MihomoProxyBlockParser.parse(snapshot.rawConfig).associateBy(MihomoProxyBlock::name)
        snapshot.catalog.profiles.mapNotNullTo(mutableSetOf()) { profile ->
            profile.fingerprint.takeIf { resolveProxyChain(profile.tag, blocks) != null }
        }
    }.getOrDefault(emptySet())

    fun udpSupportByFingerprint(snapshot: MihomoSubscriptionSnapshot): Map<String, Boolean> = runCatching {
        val blocks = MihomoProxyBlockParser.parse(snapshot.rawConfig).associateBy(MihomoProxyBlock::name)
        snapshot.catalog.profiles.mapNotNull { profile ->
            blocks[profile.tag]?.let { block -> profile.fingerprint to block.supportsUdpDialing }
        }.toMap()
    }.getOrDefault(emptyMap())

    fun selectedCompatibilityIssue(
        settings: ConnectionChainSettings,
        sources: List<ConnectionChainSource>,
    ): ConnectionChainCompatibilityIssue? {
        val normalized = settings.normalized()
        if (!normalized.isActive) return null
        val slots = ConnectionChainSlot.entries.filter {
            normalized.hop(it).mode != ConnectionChainHopMode.Off
        }
        val sourcesById = sources.associateBy(ConnectionChainSource::subscriptionId)
        val parsedBlocks = mutableMapOf<String, Map<String, MihomoProxyBlock>>()
        val candidates = mutableListOf<ConnectionChainCandidate>()
        var hasAutomaticHop = false

        for (slot in slots) {
            val hop = normalized.hop(slot)
            if (hop.mode == ConnectionChainHopMode.Automatic) {
                hasAutomaticHop = true
                continue
            }
            val ref = hop.profileRef
                ?: return ConnectionChainCompatibilityIssue.SelectedConnectionUnavailable
            val source = sourcesById[ref.subscriptionId]
                ?: return ConnectionChainCompatibilityIssue.SelectedConnectionUnavailable
            val blocks = parsedBlocks[ref.subscriptionId] ?: runCatching {
                MihomoProxyBlockParser.parse(source.snapshot.rawConfig).associateBy(MihomoProxyBlock::name)
            }.getOrNull()?.also { parsedBlocks[ref.subscriptionId] = it }
                ?: return ConnectionChainCompatibilityIssue.SelectedConnectionUnavailable
            val profile = source.snapshot.catalog.profiles.firstOrNull {
                it.fingerprint == ref.fingerprint
            } ?: return ConnectionChainCompatibilityIssue.SelectedConnectionUnavailable
            val proxyChain = resolveProxyChain(profile.tag, blocks)
                ?: return ConnectionChainCompatibilityIssue.SelectedConnectionUnavailable
            candidates += ConnectionChainCandidate(
                subscriptionId = source.subscriptionId,
                subscriptionName = source.subscriptionName,
                profile = profile,
                blocks = proxyChain,
            )
        }
        if (hasAutomaticHop) return null

        for (firstIndex in candidates.indices) {
            for (secondIndex in firstIndex + 1 until candidates.size) {
                distinctIssue(candidates[firstIndex], candidates[secondIndex])?.let { return it }
            }
        }
        for (index in 0 until candidates.lastIndex) {
            pairIssue(candidates[index], candidates[index + 1])?.let { return it }
        }
        return null
    }

    private fun choicesFor(
        hop: ConnectionChainHop,
        candidates: List<ConnectionChainCandidate>,
    ): List<ConnectionChainCandidate> = when (hop.mode) {
        ConnectionChainHopMode.Off -> emptyList()
        ConnectionChainHopMode.Automatic -> candidates
            .filter(ConnectionChainCandidate::automaticEligible)
        ConnectionChainHopMode.Fixed -> {
            val ref = hop.profileRef
            listOfNotNull(candidates.firstOrNull { it.ref == ref }).also { resolved ->
                if (resolved.isEmpty()) throw IOException("A selected chain connection is unavailable")
            }
        }
    }

    private fun bestCombinations(
        slots: List<ConnectionChainSlot>,
        choices: List<List<ConnectionChainCandidate>>,
    ): List<List<Pair<ConnectionChainSlot, ConnectionChainCandidate>>> {
        if (choices.any(List<ConnectionChainCandidate>::isEmpty)) return emptyList()
        val viable = choices.toMutableList()
        for (index in viable.lastIndex - 1 downTo 0) {
            viable[index] = viable[index].filter { upstream ->
                viable[index + 1].any { downstream ->
                    canFollow(upstream, downstream)
                }
            }
        }
        val usage = mutableMapOf<Pair<String, String>, Int>()
        val seen = mutableSetOf<List<ConnectionChainProfileRef>>()
        return buildList {
            repeat(MAX_RUNTIME_ATTEMPTS) {
                val ordered = viable.map { slotChoices ->
                    slotChoices.sortedWith(
                        compareBy<ConnectionChainCandidate> { candidate ->
                            physicalRefs(candidate).sumOf { usage[it] ?: 0 }
                        }
                            .thenBy(ConnectionChainCandidate::rank)
                            .thenBy { it.subscriptionName.lowercase(Locale.getDefault()) }
                            .thenBy { it.profile.tag.lowercase(Locale.getDefault()) },
                    )
                }
                val searchOrder = slots.indices.sortedWith(
                    compareBy<Int> { ordered[it].size }.thenBy { it },
                )
                val selected = MutableList<ConnectionChainCandidate?>(slots.size) { null }
                var checks = 0
                var exhausted = false
                fun find(orderIndex: Int):
                    List<Pair<ConnectionChainSlot, ConnectionChainCandidate>>? {
                    if (orderIndex == searchOrder.size) {
                        val combination = slots.indices.map { index -> slots[index] to selected[index]!! }
                        return combination.takeIf {
                            combination.map { it.second.ref } !in seen
                        }
                    }
                    val slotIndex = searchOrder[orderIndex]
                    for (candidate in ordered[slotIndex]) {
                        if (selected.any { it != null && !areDistinct(it, candidate) }) continue
                        val previous = selected.getOrNull(slotIndex - 1)
                        if (previous != null && !canFollow(previous, candidate)) continue
                        val next = selected.getOrNull(slotIndex + 1)
                        if (next != null && !canFollow(candidate, next)) continue
                        // ponytail: chain depth is capped at three; stop a pathological catalog
                        // after one million valid partial assignments and add indexed joins if observed.
                        if (++checks > MAX_COMPATIBILITY_CHECKS) {
                            exhausted = true
                            return null
                        }
                        selected[slotIndex] = candidate
                        find(orderIndex + 1)?.let { return it }
                        selected[slotIndex] = null
                        if (exhausted) return null
                    }
                    return null
                }
                val next = find(0) ?: return@buildList
                add(next)
                seen += next.map { it.second.ref }
                next.forEach { (_, candidate) ->
                    physicalRefs(candidate).forEach { ref ->
                        usage[ref] = (usage[ref] ?: 0) + 1
                    }
                }
            }
        }
    }

    private fun canFollow(
        upstream: ConnectionChainCandidate,
        downstream: ConnectionChainCandidate,
    ): Boolean = pairIssue(upstream, downstream) == null

    private fun pairIssue(
        upstream: ConnectionChainCandidate,
        downstream: ConnectionChainCandidate,
    ): ConnectionChainCompatibilityIssue? =
        distinctIssue(upstream, downstream)
            ?: if (downstream.entryBlock.requiresUdpDialer && !upstream.rootBlock.supportsUdpDialing) {
                ConnectionChainCompatibilityIssue.DownstreamRequiresUdpCapableUpstream
            } else {
                null
            }

    private fun distinctIssue(
        first: ConnectionChainCandidate,
        second: ConnectionChainCandidate,
    ): ConnectionChainCompatibilityIssue? = when {
        first.profile.fingerprint == second.profile.fingerprint ->
            ConnectionChainCompatibilityIssue.SameConnection
        first.blocks.any { firstBlock ->
            second.blocks.any { secondBlock ->
                first.subscriptionId == second.subscriptionId && firstBlock.name == secondBlock.name
            }
        } -> ConnectionChainCompatibilityIssue.SharedProxyDependency
        else -> null
    }

    private fun areDistinct(
        first: ConnectionChainCandidate,
        second: ConnectionChainCandidate,
    ): Boolean = distinctIssue(first, second) == null

    private fun physicalRefs(candidate: ConnectionChainCandidate): List<Pair<String, String>> =
        candidate.blocks.map { candidate.subscriptionId to it.name }

    private fun toRuntimePlan(
        combination: List<Pair<ConnectionChainSlot, ConnectionChainCandidate>>,
    ): ConnectionChainRuntimePlan {
        val hops = combination.mapIndexed { index, (slot, candidate) ->
            ResolvedConnectionChainHop(
                slot = slot,
                candidate = candidate,
                proxies = candidate.blocks.mapIndexed { proxyIndex, block ->
                    ResolvedConnectionChainProxy(
                        block = block,
                        runtimeName = "WhiteDNS Chain ${index + 1}.${proxyIndex + 1}",
                    )
                },
            )
        }
        val rawYaml = buildString {
            append("proxies:\n")
            var previousLogicalRoot: String? = null
            hops.forEach { hop ->
                hop.proxies.forEachIndexed { proxyIndex, proxy ->
                    val dialerProxy = if (proxyIndex == 0) {
                        previousLogicalRoot
                    } else {
                        hop.proxies[proxyIndex - 1].runtimeName
                    }
                    proxy.block.render(proxy.runtimeName, dialerProxy).forEach { line ->
                        append(line)
                        append('\n')
                    }
                }
                previousLogicalRoot = hop.runtimeName
            }
            append("proxy-groups:\n")
            append("  - name: ${yamlSingleQuoted(GROUP_NAME)}\n")
            append("    type: select\n")
            append("    proxies:\n")
            append("      - ${yamlSingleQuoted(hops.last().runtimeName)}\n")
            append("rules:\n")
            append("  - ${yamlSingleQuoted("MATCH,$GROUP_NAME")}\n")
        }
        return ConnectionChainRuntimePlan(hops = hops, rawYaml = rawYaml)
    }

    private fun resolveProxyChain(
        rootName: String,
        blocks: Map<String, MihomoProxyBlock>,
    ): List<MihomoProxyBlock>? {
        val rootFirst = mutableListOf<MihomoProxyBlock>()
        val visited = mutableSetOf<String>()
        var name: String? = rootName
        while (name != null) {
            if (!visited.add(name)) return null
            val block = blocks[name] ?: return null
            rootFirst += block
            name = block.dialerProxy
        }
        return rootFirst.asReversed().takeIf { ordered ->
            ordered.zipWithNext().all { (upstream, downstream) ->
                !downstream.requiresUdpDialer || upstream.supportsUdpDialing
            }
        }
    }
}

private fun proxyFieldValue(lines: List<String>, key: String): String? {
    lines.forEach { line ->
        val content = line.trimStart()
        val value = when {
            indentation(line) == 2 && content.startsWith("- $key:") -> content.substringAfter(":")
            indentation(line) == 4 && fieldName(line) == key -> content.substringAfter(":")
            else -> null
        }
        value?.let { return MihomoConfigParser.decodeScalar(it) }
    }
    return null
}

private fun fieldName(line: String): String =
    line.trimStart().substringBefore(':').trim().removeSurrounding("\"").removeSurrounding("'")

private fun yamlSingleQuoted(value: String): String = "'${value.replace("'", "''")}'"

private fun String.isIpv6Literal(): Boolean =
    trim().removePrefix("[").removeSuffix("]").contains(":")

private fun topLevelKey(line: String): String? {
    if (line.isBlank() || line.first().isWhitespace() || line.trimStart().startsWith("#")) return null
    val index = line.indexOf(':')
    if (index <= 0) return null
    return line.substring(0, index).trim().takeIf(String::isNotBlank)
}

private fun indentation(line: String): Int =
    line.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: line.length
