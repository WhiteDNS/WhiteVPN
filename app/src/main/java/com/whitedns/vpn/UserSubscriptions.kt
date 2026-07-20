package com.whitedns.vpn

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.UUID

enum class UserSubscriptionFormat(val wireName: String, val label: String) {
    Mihomo("mihomo", "Mihomo YAML"),
    Links("links", "VLESS / VMess / Trojan / SS");

    companion object {
        fun fromWireName(value: String?): UserSubscriptionFormat =
            entries.firstOrNull { it.wireName == value } ?: Links
    }
}

data class UserSubscription(
    val id: String,
    val name: String,
    val input: String,
    val format: UserSubscriptionFormat,
    val connectionCount: Int,
    val updatedAt: Long,
    val lastError: String = "",
)

data class ImportedUserSubscription(
    val yaml: String,
    val format: UserSubscriptionFormat,
    val connectionCount: Int,
)

object UserSubscriptionImporter {
    fun import(content: String, nowMs: Long = System.currentTimeMillis()): ImportedUserSubscription {
        val trimmed = content.trim()
        require(trimmed.isNotBlank()) { "سابسکریپشن خالی است" }
        require(trimmed.toByteArray().size <= MAX_SUBSCRIPTION_BYTES) { "حجم سابسکریپشن بیش از حد مجاز است" }

        JsonSubscriptionImporter.import(trimmed, nowMs)?.let { return it }

        val mihomo = runCatching { MihomoConfigParser.parse(trimmed, nowMs) }.getOrNull()
        if (mihomo != null && mihomo.catalog.profiles.isNotEmpty()) {
            val yaml = if (mihomo.summary.groups.isEmpty()) {
                MihomoLinkConfigBuilder.addDefaultGroups(trimmed, mihomo.catalog.profiles.map { it.tag })
            } else {
                trimmed
            }
            return ImportedUserSubscription(yaml, UserSubscriptionFormat.Mihomo, mihomo.catalog.profiles.size)
        }

        val yaml = MihomoLinkConfigBuilder.build(SubConvConverter.convert(trimmed))
        val count = MihomoConfigParser.parse(yaml, nowMs).catalog.profiles.size
        require(count > 0) { "اتصال پشتیبانی‌شده‌ای پیدا نشد" }
        return ImportedUserSubscription(yaml, UserSubscriptionFormat.Links, count)
    }

    const val MAX_SUBSCRIPTION_BYTES = 2 * 1024 * 1024
}

object JsonSubscriptionImporter {
    fun import(content: String, nowMs: Long): ImportedUserSubscription? {
        val first = content.firstOrNull() ?: return null
        val (proxies, format) = when (first) {
            '{' -> clashProxies(runCatching { JSONObject(content) }.getOrNull() ?: return null) to
                UserSubscriptionFormat.Mihomo
            '[' -> xrayProxies(runCatching { JSONArray(content) }.getOrNull() ?: return null) to
                UserSubscriptionFormat.Links
            else -> return null
        }
        if (proxies.isEmpty()) return null

        val yaml = MihomoLinkConfigBuilder.build(proxies)
        val count = MihomoConfigParser.parse(yaml, nowMs).catalog.profiles.size
        require(count > 0) { "اتصال پشتیبانی‌شده‌ای پیدا نشد" }
        return ImportedUserSubscription(yaml, format, count)
    }

    private fun clashProxies(config: JSONObject): List<JSONObject> {
        val proxies = config.optJSONArray("proxies") ?: return emptyList()
        return (0 until proxies.length()).mapNotNull { index ->
            proxies.optJSONObject(index)?.takeIf(::isSupportedMihomoProxy)
        }
    }

    private fun xrayProxies(configs: JSONArray): List<JSONObject> {
        return (0 until configs.length()).mapNotNull { index ->
            configs.optJSONObject(index)?.let { xrayProxy(it, index) }
        }
    }

    private fun xrayProxy(config: JSONObject, index: Int): JSONObject? {
        val outbounds = config.optJSONArray("outbounds") ?: return null
        val supported = (0 until outbounds.length())
            .mapNotNull(outbounds::optJSONObject)
            .filter { it.optString("protocol") in setOf("vless", "vmess", "trojan") }
        val outbound = supported.firstOrNull { it.optString("tag") == "proxy" }
            ?: supported.singleOrNull()
            ?: return null
        val protocol = outbound.optString("protocol")
        val settings = outbound.optJSONObject("settings") ?: return null
        val stream = outbound.optJSONObject("streamSettings") ?: JSONObject()
        val proxy = JSONObject()
            .put("name", config.optString("remarks").ifBlank { "Xray ${index + 1}" })
            .put("type", protocol)
            .put("udp", true)
        when (protocol) {
            "vless", "vmess" -> {
                val endpoint = settings.optJSONArray("vnext")?.optJSONObject(0) ?: return null
                val user = endpoint.optJSONArray("users")?.optJSONObject(0) ?: return null
                proxy.put("server", endpoint.optString("address"))
                    .put("port", endpoint.optInt("port"))
                    .put("uuid", user.optString("id"))
                if (protocol == "vless") {
                    user.optString("flow").takeIf(String::isNotBlank)?.let { proxy.put("flow", it) }
                } else {
                    proxy.put("alterId", user.optInt("alterId", user.optInt("alter_id")))
                        .put("cipher", user.optString("security").ifBlank { "auto" })
                }
            }
            "trojan" -> {
                val endpoint = settings.optJSONArray("servers")?.optJSONObject(0) ?: return null
                proxy.put("server", endpoint.optString("address"))
                    .put("port", endpoint.optInt("port"))
                    .put("password", endpoint.optString("password"))
            }
        }

        val security = stream.optString("security")
        val tls = when (security) {
            "tls" -> stream.optJSONObject("tlsSettings")
            "reality" -> stream.optJSONObject("realitySettings")
            else -> null
        }
        if (tls != null) {
            proxy.put("tls", true)
            tls.optString("serverName").takeIf(String::isNotBlank)?.let { proxy.put("servername", it) }
            tls.optString("fingerprint").takeIf(String::isNotBlank)?.let { proxy.put("client-fingerprint", it) }
            if (tls.optBoolean("allowInsecure")) proxy.put("skip-cert-verify", true)
            tls.optJSONArray("alpn")?.takeIf { it.length() > 0 }?.let { proxy.put("alpn", it) }
            if (security == "reality") {
                val reality = JSONObject()
                    .put("public-key", tls.optString("publicKey"))
                tls.optString("shortId").takeIf(String::isNotBlank)?.let { reality.put("short-id", it) }
                proxy.put("reality-opts", reality)
            }
        }

        when (stream.optString("network")) {
            "ws" -> {
                val ws = stream.optJSONObject("wsSettings") ?: JSONObject()
                val options = JSONObject().put("path", ws.optString("path"))
                val host = ws.optString("host")
                    .ifBlank { ws.optJSONObject("headers")?.optString("Host").orEmpty() }
                if (host.isNotBlank()) options.put("headers", JSONObject().put("Host", host))
                proxy.put("network", "ws").put("ws-opts", options)
            }
            "grpc" -> {
                val grpc = stream.optJSONObject("grpcSettings") ?: JSONObject()
                proxy.put("network", "grpc").put(
                    "grpc-opts",
                    JSONObject().put("grpc-service-name", grpc.optString("serviceName")),
                )
            }
        }
        return proxy.takeIf(::isSupportedMihomoProxy)
    }

    private fun isSupportedMihomoProxy(proxy: JSONObject): Boolean =
        proxy.optString("type") in setOf("vless", "vmess", "trojan", "ss") &&
            proxy.optString("name").isNotBlank() &&
            proxy.optString("server").isNotBlank() &&
            proxy.optInt("port") in 1..65535

}

object MihomoLinkConfigBuilder {
    private const val SELECT_GROUP = "WhiteDNS Proxy"
    private const val AUTO_GROUP = "WhiteDNS Auto"

    fun build(proxies: List<JSONObject>): String {
        val unique = proxies.distinctBy { it.getString("name") }
        require(unique.isNotEmpty()) { "اتصال سازگار با v1 پیدا نشد" }
        val proxyYaml = buildString {
            appendLine("proxies:")
            unique.forEach { proxy ->
                val orderedKeys = listOf("name", "type", "server", "port") +
                    proxy.keys().asSequence().filterNot { it in setOf("name", "type", "server", "port") }
                orderedKeys.forEachIndexed { propertyIndex, key ->
                    val prefix = if (propertyIndex == 0) "  - " else "    "
                    append(prefix).append(key).append(": ").appendLine(yamlValue(proxy.get(key)))
                }
            }
        }
        return addDefaultGroups(proxyYaml, unique.map { it.getString("name") })
    }

    fun addDefaultGroups(yaml: String, proxyNames: List<String>): String {
        require(proxyNames.isNotEmpty()) { "سابسکریپشن Mihomo پروکسی ندارد" }
        return yaml.trimEnd() + "\n" + defaultGroups(proxyNames)
    }

    fun migrateGeneratedYaml(yaml: String): String {
        val renamed = yaml.replace("'WhiteDNS Select'", quote(SELECT_GROUP))
        if ("MATCH,$SELECT_GROUP" in renamed) return renamed
        return renamed.trimEnd() + "\nrules:\n  - ${quote("MATCH,$SELECT_GROUP")}\n"
    }

    private fun defaultGroups(proxyNames: List<String>): String = buildString {
        appendLine("proxy-groups:")
        appendLine("  - name: ${quote(SELECT_GROUP)}")
        appendLine("    type: select")
        appendLine("    proxies:")
        appendLine("      - ${quote(AUTO_GROUP)}")
        proxyNames.forEach { appendLine("      - ${quote(it)}") }
        appendLine("  - name: ${quote(AUTO_GROUP)}")
        appendLine("    type: url-test")
        appendLine("    url: 'https://connectivitycheck.gstatic.com/generate_204'")
        appendLine("    interval: 300")
        appendLine("    tolerance: 100")
        appendLine("    proxies:")
        proxyNames.forEach { appendLine("      - ${quote(it)}") }
        appendLine("rules:")
        appendLine("  - ${quote("MATCH,$SELECT_GROUP")}")
    }

    private fun yamlValue(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is String -> quote(value)
        is JSONObject -> value.keys().asSequence().joinToString(prefix = "{", postfix = "}") { key ->
            "${quote(key)}: ${yamlValue(value.get(key))}"
        }
        is JSONArray -> (0 until value.length()).joinToString(prefix = "[", postfix = "]") { index ->
            yamlValue(value.get(index))
        }
        is Map<*, *> -> value.entries.joinToString(prefix = "{", postfix = "}") { (key, item) ->
            "${quote(key.toString())}: ${yamlValue(item)}"
        }
        is Iterable<*> -> value.joinToString(prefix = "[", postfix = "]", transform = ::yamlValue)
        else -> value.toString()
    }

    private fun quote(value: String): String = "'${value.replace("'", "''")}'"
}

class UserSubscriptionManager(
    context: Context,
    private val store: SubscriptionStore = SubscriptionStore(context),
) {
    fun list(): List<UserSubscription> = store.readUserSubscriptions()

    fun selectedId(): String = store.readSelectedSubscriptionId()

    fun select(id: String) = store.saveSelectedSubscriptionId(id)

    fun test(input: String): ImportedUserSubscription = UserSubscriptionImporter.import(load(input))

    fun add(name: String, input: String): UserSubscription {
        val cleanName = name.trim().take(60)
        require(cleanName.isNotBlank()) { "نام الزامی است" }
        val cleanInput = input.trim()
        val imported = UserSubscriptionImporter.import(load(cleanInput))
        val item = UserSubscription(
            id = UUID.randomUUID().toString(),
            name = cleanName,
            input = cleanInput,
            format = imported.format,
            connectionCount = imported.connectionCount,
            updatedAt = System.currentTimeMillis(),
        )
        store.saveUserSubscription(item, imported.yaml)
        return item
    }

    fun refresh(id: String): UserSubscription {
        val existing = store.readUserSubscription(id) ?: throw IOException("سابسکریپشن دیگر وجود ندارد")
        return runCatching {
            val imported = UserSubscriptionImporter.import(load(existing.input))
            existing.copy(
                format = imported.format,
                connectionCount = imported.connectionCount,
                updatedAt = System.currentTimeMillis(),
                lastError = "",
            ).also { store.saveUserSubscription(it, imported.yaml) }
        }.getOrElse { error ->
            store.saveUserSubscription(existing.copy(lastError = userMessage(error)))
            throw error
        }
    }

    fun delete(id: String) = store.deleteUserSubscription(id)

    fun cachedSnapshot(id: String): MihomoSubscriptionSnapshot? {
        val item = store.readUserSubscription(id) ?: return null
        var yaml = store.readUserSubscriptionYaml(id).takeIf(String::isNotBlank) ?: return null
        if (item.format == UserSubscriptionFormat.Links) {
            if ("\\/" in yaml && (": {\"" in yaml || ": [\"" in yaml)) return null
            val migrated = MihomoLinkConfigBuilder.migrateGeneratedYaml(yaml)
            if (migrated != yaml) {
                yaml = migrated
                store.saveUserSubscription(item, yaml)
            }
        }
        return MihomoConfigParser.parse(yaml, item.updatedAt).takeIf { it.catalog.profiles.isNotEmpty() }
    }

    private fun load(input: String): String {
        if (!input.startsWith("http://", true) && !input.startsWith("https://", true)) return input
        val uri = runCatching { URI(input) }.getOrElse { throw IOException("URL سابسکریپشن نامعتبر است") }
        if (!uri.scheme.equals("https", true) || uri.host.isNullOrBlank()) {
            throw IOException("URL سابسکریپشن باید از HTTPS استفاده کند")
        }
        val connection = URL(input).openConnection() as HttpURLConnection
        connection.connectTimeout = 12_000
        connection.readTimeout = 20_000
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "text/yaml,text/plain,*/*;q=0.1")
        return try {
            if (connection.responseCode !in 200..299) throw IOException("سابسکریپشن پاسخ HTTP ${connection.responseCode} برگرداند")
            val bytes = connection.inputStream.use { it.readNBytes(UserSubscriptionImporter.MAX_SUBSCRIPTION_BYTES + 1) }
            if (bytes.size > UserSubscriptionImporter.MAX_SUBSCRIPTION_BYTES) throw IOException("حجم سابسکریپشن بیش از حد مجاز است")
            bytes.toString(Charsets.UTF_8)
        } finally {
            connection.disconnect()
        }
    }

    private fun userMessage(error: Throwable): String =
        error.message?.take(160)?.ifBlank { null } ?: "تازه‌سازی ناموفق بود"
}
