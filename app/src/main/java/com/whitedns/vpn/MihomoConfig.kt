package com.whitedns.vpn

import org.json.JSONObject
import java.util.Locale

data class MihomoSubscriptionSnapshot(
    val rawConfig: String,
    val summary: MihomoConfigSummary,
    val catalog: SubscriptionCatalog,
)

data class MihomoConfigSummary(
    val proxies: List<MihomoProxy>,
    val groups: List<MihomoProxyGroup>,
)

data class MihomoProxy(
    val name: String,
    val type: String,
    val server: String,
    val port: Int,
)

data class MihomoProxyGroup(
    val name: String,
    val type: String,
)

data class MihomoGroupSelection(
    val selectorGroup: String,
    val selectedGroup: String,
)

object MihomoConfigParser {
    fun parse(
        yaml: String,
        fetchedAt: Long = System.currentTimeMillis(),
    ): MihomoSubscriptionSnapshot {
        val summary = parseSummary(yaml)
        val profiles = summary.proxies
            .map { proxy -> proxy.toConnectionProfile() }
            .distinctBy { it.fingerprint }
        return MihomoSubscriptionSnapshot(
            rawConfig = yaml,
            summary = summary,
            catalog = SubscriptionCatalog(profiles = profiles, fetchedAt = fetchedAt),
        )
    }

    fun parseSummary(yaml: String): MihomoConfigSummary {
        val proxies = mutableListOf<MihomoProxy>()
        val groups = mutableListOf<MihomoProxyGroup>()
        var section: String? = null
        var current = mutableMapOf<String, String>()

        fun flush() {
            when (section) {
                "proxies" -> current.toProxy()?.let(proxies::add)
                "proxy-groups" -> current.toGroup()?.let(groups::add)
            }
            current = mutableMapOf()
        }

        yaml.lineSequence().forEach { rawLine ->
            if (rawLine.isBlank()) return@forEach
            val indent = rawLine.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: return@forEach
            val line = rawLine.trimEnd()
            val content = line.trimStart()

            if (indent == 0) {
                flush()
                section = when (content.substringBefore(":").trim()) {
                    "proxies" -> "proxies"
                    "proxy-groups" -> "proxy-groups"
                    else -> null
                }
                return@forEach
            }

            val activeSection = section ?: return@forEach
            if (activeSection != "proxies" && activeSection != "proxy-groups") return@forEach

            if (indent == 2 && content.startsWith("- ")) {
                flush()
                parseKeyValue(content.removePrefix("- ").trim())?.let { (key, value) ->
                    current[key] = decodeScalar(value)
                }
                return@forEach
            }

            if (indent == 4) {
                parseKeyValue(content)?.let { (key, value) ->
                    if (key in setOf("name", "type", "server", "port")) {
                        current[key] = decodeScalar(value)
                    }
                }
            }
        }
        flush()

        return MihomoConfigSummary(
            proxies = proxies.distinctBy { it.name },
            groups = groups.distinctBy { it.name },
        )
    }

    private fun MutableMap<String, String>.toProxy(): MihomoProxy? {
        val name = this["name"]?.takeIf(String::isNotBlank) ?: return null
        val type = this["type"]?.takeIf(String::isNotBlank) ?: return null
        val server = this["server"]?.takeIf(String::isNotBlank) ?: return null
        val port = this["port"]?.toIntOrNull()?.takeIf { it > 0 } ?: return null
        return MihomoProxy(name = name, type = type, server = server, port = port)
    }

    private fun MutableMap<String, String>.toGroup(): MihomoProxyGroup? {
        val name = this["name"]?.takeIf(String::isNotBlank) ?: return null
        val type = this["type"]?.takeIf(String::isNotBlank) ?: return null
        return MihomoProxyGroup(name = name, type = type)
    }

    private fun MihomoProxy.toConnectionProfile(): ConnectionProfile {
        val outbound = JSONObject()
            .put("name", name)
            .put("type", type)
            .put("server", server)
            .put("port", port)
            .toString()
        return ConnectionProfile(
            tag = name,
            type = type,
            server = server,
            port = port,
            transport = "",
            validationHost = server,
            fingerprint = ProfileFingerprint.from(
                type = type,
                server = server,
                port = port,
                validationHost = server,
                outboundJson = outbound,
            ),
            outboundJson = outbound,
        )
    }

    private fun parseKeyValue(content: String): Pair<String, String>? {
        val index = content.indexOf(":")
        if (index <= 0) return null
        return content.substring(0, index).trim() to content.substring(index + 1).trim()
    }

    private fun decodeScalar(value: String): String {
        val trimmed = value.trim().removeSuffix("\r")
        if (trimmed.length >= 2 && trimmed.first() == '"' && trimmed.last() == '"') {
            return decodeDoubleQuoted(trimmed.substring(1, trimmed.lastIndex))
        }
        if (trimmed.length >= 2 && trimmed.first() == '\'' && trimmed.last() == '\'') {
            return trimmed.substring(1, trimmed.lastIndex).replace("''", "'")
        }
        return trimmed.substringBefore(" #").trim()
    }

    private fun decodeDoubleQuoted(value: String): String {
        val out = StringBuilder(value.length)
        var index = 0
        while (index < value.length) {
            val char = value[index]
            if (char != '\\' || index == value.lastIndex) {
                out.append(char)
                index += 1
                continue
            }

            when (val escaped = value[index + 1]) {
                '"' -> out.append('"')
                '\\' -> out.append('\\')
                '/' -> out.append('/')
                'b' -> out.append('\b')
                'f' -> out.append('\u000C')
                'n' -> out.append('\n')
                'r' -> out.append('\r')
                't' -> out.append('\t')
                'u' -> {
                    val hex = value.substringOrNull(index + 2, index + 6)
                    val codePoint = hex?.toIntOrNull(16)
                    if (codePoint != null) {
                        out.append(codePoint.toChar())
                        index += 4
                    } else {
                        out.append(escaped)
                    }
                }
                'U' -> {
                    val hex = value.substringOrNull(index + 2, index + 10)
                    val codePoint = hex?.toIntOrNull(16)
                    if (codePoint != null) {
                        out.appendCodePoint(codePoint)
                        index += 8
                    } else {
                        out.append(escaped)
                    }
                }
                else -> out.append(escaped)
            }
            index += 2
        }
        return out.toString()
    }

    private fun String.substringOrNull(startIndex: Int, endIndex: Int): String? {
        return if (startIndex >= 0 && endIndex <= length && startIndex <= endIndex) {
            substring(startIndex, endIndex)
        } else {
            null
        }
    }
}

object MihomoSelectionPolicy {
    fun desiredSelection(
        summary: MihomoConfigSummary,
        selectedCountryCode: String?,
    ): MihomoGroupSelection? {
        val selector = mainSelectorGroup(summary) ?: return null
        val selected = if (selectedCountryCode == null) {
            autoGroup(summary)
        } else {
            countryGroup(summary, selectedCountryCode)
        } ?: return null
        return MihomoGroupSelection(selectorGroup = selector.name, selectedGroup = selected.name)
    }

    fun mainSelectorGroup(summary: MihomoConfigSummary): MihomoProxyGroup? {
        return summary.groups.firstOrNull { it.name.contains("Proxy Select", ignoreCase = true) }
            ?: summary.groups.firstOrNull { it.type.equals("select", ignoreCase = true) }
    }

    fun autoGroup(summary: MihomoConfigSummary): MihomoProxyGroup? {
        return summary.groups.firstOrNull { group ->
            group.name.contains("Auto Select", ignoreCase = true) ||
                group.name.normalized().contains(" auto ")
        } ?: summary.groups.firstOrNull { group ->
            group.type.lowercase(Locale.US) in setOf("url-test", "fallback", "load-balance")
        }
    }

    fun countryGroup(
        summary: MihomoConfigSummary,
        rawCountryCode: String?,
    ): MihomoProxyGroup? {
        val code = ConnectionLocationPolicy.normalizeCountryCode(rawCountryCode) ?: return null
        return summary.groups
            .filter { group -> ConnectionLocationPolicy.countryFromText(group.name)?.code == code }
            .sortedWith(compareByDescending<MihomoProxyGroup> { it.type.equals("url-test", ignoreCase = true) }
                .thenBy { it.name })
            .firstOrNull()
    }

    private fun String.normalized(): String {
        return " " + lowercase(Locale.US).replace(Regex("[^a-z0-9]+"), " ").trim() + " "
    }
}
