package com.whitedns.vpn

import android.content.Context
import com.follow.clash.core.Core
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.URI
import java.net.URL
import java.security.cert.CertPathValidatorException
import java.security.cert.CertificateException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLPeerUnverifiedException

object MihomoRuntimeDefaults {
    const val MIXED_PORT = 2080
    const val FALLBACK_CONTROL_PORT = 9090
    const val CONTROLLER_HOST = "127.0.0.1"
    const val HEALTH_URL = "https://www.gstatic.com/generate_204"
    const val EGRESS_TRACE_URL = "https://www.cloudflare.com/cdn-cgi/trace"
}

object TlsIntegrityPolicy {
    val TEST_URLS = listOf(
        "https://valid-isrgrootx1.letsencrypt.org/",
        "https://connectivitycheck.gstatic.com/generate_204",
        "https://cloudflare.com/cdn-cgi/trace",
    )
    const val PROBE_TIMEOUT_MS = 2_000
    const val TOTAL_TIMEOUT_MS = 7_000L
    const val QUARANTINE_DURATION_MS = 24L * 60L * 60L * 1_000L

    fun endpointKey(endpoint: CleanIpResult): String = "${endpoint.ip}:${endpoint.port}"

    fun quarantineUntil(nowMs: Long): Long = nowMs + QUARANTINE_DURATION_MS

    fun isQuarantined(untilMs: Long, nowMs: Long): Boolean = untilMs > nowMs

    fun isCertificateFailure(error: Throwable): Boolean {
        return generateSequence(error) { it.cause }.any { cause ->
            cause is CertificateException ||
                cause is CertPathValidatorException ||
                cause is SSLPeerUnverifiedException
        }
    }
}

class TlsIntegrityPreferenceStore(context: Context) {
    private val prefs = context.getSharedPreferences("white_dns_tls_integrity", Context.MODE_PRIVATE)

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, false)

    fun saveEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    private companion object {
        const val KEY_ENABLED = "enabled"
    }
}

class TlsIntegrityException(cause: Throwable) : IOException("TLS certificate validation failed", cause)

enum class DnsPrivacyMode(val wireName: String, val label: String) {
    Automatic("automatic", "Automatic"),
    DoH("doh", "DoH"),
    DoT("dot", "DoT");

    companion object {
        fun fromWireName(value: String?): DnsPrivacyMode {
            return values().firstOrNull { it.wireName == value } ?: Automatic
        }
    }
}

object DnsPrivacyPolicy {
    const val DEFAULT_DOH_URL = "https://1.1.1.1/dns-query"
    const val DEFAULT_DOT_ENDPOINT = "tls://1.1.1.1:853"

    fun normalizeDohUrl(value: String): String {
        val input = value.trim()
        val uri = runCatching { URI(input) }
            .getOrElse { throw IllegalArgumentException("DoH must be a valid HTTPS URL") }
        require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()) {
            "DoH must be a valid HTTPS URL"
        }
        require(uri.rawUserInfo == null && uri.rawFragment == null) {
            "DoH URL cannot contain credentials or a fragment"
        }
        require(uri.rawAuthority?.endsWith(':') == false) { "DoH port must be from 1 to 65535" }
        require(uri.port == -1 || uri.port in 1..65535) { "DoH port must be from 1 to 65535" }
        return uri.toASCIIString()
    }

    fun normalizeDotEndpoint(value: String): String {
        val input = value.trim()
        val uri = runCatching {
            URI(if (input.startsWith("tls://", ignoreCase = true)) input else "tls://$input")
        }.getOrElse { throw IllegalArgumentException("DoT must be a valid host[:port]") }
        require(uri.scheme.equals("tls", ignoreCase = true) && !uri.host.isNullOrBlank()) {
            "DoT must be a valid host[:port]"
        }
        require(
            uri.rawUserInfo == null &&
                uri.rawQuery == null &&
                uri.rawFragment == null &&
                (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/"),
        ) {
            "DoT must contain only a host and optional port"
        }
        require(uri.rawAuthority?.endsWith(':') == false) { "DoT port must be from 1 to 65535" }
        require(uri.port == -1 || uri.port in 1..65535) { "DoT port must be from 1 to 65535" }
        val port = uri.port.takeIf { it != -1 } ?: 853
        val host = when {
            uri.host.startsWith('[') -> uri.host
            uri.host.contains(':') -> "[${uri.host}]"
            else -> uri.host
        }
        return "tls://$host:$port"
    }
}

class DnsPrivacyPreferenceStore(context: Context) {
    private val prefs = context.getSharedPreferences("white_dns_privacy", Context.MODE_PRIVATE)

    fun readMode(): DnsPrivacyMode = DnsPrivacyMode.fromWireName(prefs.getString(KEY_MODE, null))

    fun readDohUrl(): String {
        return runCatching {
            DnsPrivacyPolicy.normalizeDohUrl(prefs.getString(KEY_DOH_URL, null) ?: DnsPrivacyPolicy.DEFAULT_DOH_URL)
        }.getOrDefault(DnsPrivacyPolicy.DEFAULT_DOH_URL)
    }

    fun readDotEndpoint(): String {
        return runCatching {
            DnsPrivacyPolicy.normalizeDotEndpoint(
                prefs.getString(KEY_DOT_ENDPOINT, null) ?: DnsPrivacyPolicy.DEFAULT_DOT_ENDPOINT,
            )
        }.getOrDefault(DnsPrivacyPolicy.DEFAULT_DOT_ENDPOINT)
    }

    fun saveMode(mode: DnsPrivacyMode) {
        prefs.edit().putString(KEY_MODE, mode.wireName).apply()
    }

    fun saveDohUrl(value: String) {
        prefs.edit().putString(KEY_DOH_URL, DnsPrivacyPolicy.normalizeDohUrl(value)).apply()
    }

    fun saveDotEndpoint(value: String) {
        prefs.edit().putString(KEY_DOT_ENDPOINT, DnsPrivacyPolicy.normalizeDotEndpoint(value)).apply()
    }

    private companion object {
        const val KEY_MODE = "mode"
        const val KEY_DOH_URL = "doh_url"
        const val KEY_DOT_ENDPOINT = "dot_endpoint"
    }
}

object MihomoDelayPolicy {
    fun acceptedDelayMs(delayMs: Int?): Long? {
        return delayMs?.takeIf { it > 0 }?.toLong()
    }
}

object MihomoControllerPort {
    fun allocate(): Int {
        return LocalTcpPort.allocate(MihomoRuntimeDefaults.FALLBACK_CONTROL_PORT)
    }

    fun canBind(port: Int): Boolean {
        return LocalTcpPort.canBind(MihomoRuntimeDefaults.CONTROLLER_HOST, port)
    }
}

object DpiBypassPort {
    fun allocate(): Int {
        return LocalTcpPort.allocate(DpiBypassDefaults.FALLBACK_PROXY_PORT)
    }

    fun canBind(port: Int): Boolean {
        return LocalTcpPort.canBind(DpiBypassDefaults.PROXY_HOST, port)
    }
}

private object LocalTcpPort {
    fun allocate(fallbackPort: Int): Int {
        return runCatching {
            ServerSocket(0).use { socket ->
                socket.reuseAddress = true
                socket.localPort
            }
        }.getOrDefault(fallbackPort)
    }

    fun canBind(host: String, port: Int): Boolean {
        return runCatching {
            ServerSocket().use { socket ->
                socket.reuseAddress = true
                socket.bind(InetSocketAddress(host, port))
            }
        }.isSuccess
    }
}

data class MihomoRuntimePaths(
    val baseDir: File,
    val runtimeConfigYaml: File,
    val profileYaml: File,
    val serviceJson: File,
    val patchFinalJson: File,
    val setupParamsJson: File,
    val logFile: File,
    val errorFile: File,
    val cacheDir: File,
    val secret: String,
    val controlPort: Int,
)

class MihomoRuntimeConfigBuilder(private val context: Context) {
    fun write(
        rawYaml: String,
        splitTunnelPlan: SplitTunnelRuntimePlan,
        dnsPrivacyMode: DnsPrivacyMode = DnsPrivacyMode.Automatic,
        dohUrl: String = DnsPrivacyPolicy.DEFAULT_DOH_URL,
        dotEndpoint: String = DnsPrivacyPolicy.DEFAULT_DOT_ENDPOINT,
        secret: String = MihomoControllerSecret.generate(),
    ): MihomoRuntimePaths {
        val baseDir = File(context.filesDir, "mihomo").apply { mkdirs() }
        val cacheDir = File(context.cacheDir, "mihomo").apply { mkdirs() }
        val runtimeConfigYaml = File(baseDir, "config.yaml")
        val profileYaml = File(baseDir, "service_core_runtime_profile.yaml")
        val patchFinal = File(baseDir, "service_core_patch_final.json")
        val setupParams = File(baseDir, "service_core_setup_params.json")
        val logFile = File(baseDir, "service_core.log")
        val errorFile = DiagnosticLogger.mihomoStderrFile(context)
        val serviceJson = File(context.filesDir, "service.json")
        val controlPort = MihomoControllerPort.allocate()
        DiagnosticLogger.info(
            context,
            "mihomo.controller.port",
            "selected=$controlPort bindable=${MihomoControllerPort.canBind(controlPort)} fallback=${controlPort == MihomoRuntimeDefaults.FALLBACK_CONTROL_PORT}",
        )

        profileYaml.writeText(rawYaml)
        runtimeConfigYaml.writeText(
            flClashRuntimeYaml(
                rawYaml = rawYaml,
                secret = secret,
                controlPort = controlPort,
                dnsPrivacyMode = dnsPrivacyMode,
                dohUrl = dohUrl,
                dotEndpoint = dotEndpoint,
            ),
        )
        patchFinal.writeText(corePatchJson(splitTunnelPlan, secret, controlPort).toString(2))
        setupParams.writeText(setupParamsJson().toString(2))
        serviceJson.writeText(
            serviceJson(
                appName = context.getString(R.string.app_name),
                versionName = BuildConfig.VERSION_NAME,
                baseDir = baseDir.absolutePath,
                cacheDir = cacheDir.absolutePath,
                profileYaml = profileYaml.absolutePath,
                patchFinal = patchFinal.absolutePath,
                logFile = logFile.absolutePath,
                errorFile = errorFile.absolutePath,
                secret = secret,
                controlPort = controlPort,
            ).toString(2),
        )
        File(context.filesDir, "vpn_profile.txt").writeText(profileYaml.absolutePath)
        logFile.parentFile?.mkdirs()
        errorFile.parentFile?.mkdirs()

        return MihomoRuntimePaths(
            baseDir = baseDir,
            runtimeConfigYaml = runtimeConfigYaml,
            profileYaml = profileYaml,
            serviceJson = serviceJson,
            patchFinalJson = patchFinal,
            setupParamsJson = setupParams,
            logFile = logFile,
            errorFile = errorFile,
            cacheDir = cacheDir,
            secret = secret,
            controlPort = controlPort,
        )
    }

    fun corePatchJson(
        splitTunnelPlan: SplitTunnelRuntimePlan,
        secret: String,
        controlPort: Int = MihomoRuntimeDefaults.FALLBACK_CONTROL_PORT,
    ): JSONObject {
        return corePatchJson(context.getString(R.string.app_name), splitTunnelPlan, secret, controlPort)
    }

    companion object {
        fun corePatchJson(
            appName: String,
            splitTunnelPlan: SplitTunnelRuntimePlan,
            secret: String,
            controlPort: Int = MihomoRuntimeDefaults.FALLBACK_CONTROL_PORT,
        ): JSONObject {
            val tun = JSONObject()
                .put("enable", false)

            return JSONObject()
                .put("mixed-port", MihomoRuntimeDefaults.MIXED_PORT)
                .put(
                    "external-controller",
                    "${MihomoRuntimeDefaults.CONTROLLER_HOST}:$controlPort",
                )
                .put("secret", secret)
                .put("allow-lan", false)
                .put("mode", "rule")
                .put("log-level", "warning")
                .put("ipv6", false)
                .put("unified-delay", true)
                .put("global-client-fingerprint", "chrome")
                .put("tun", tun)
        }

        fun serviceJson(
            appName: String,
            versionName: String,
            baseDir: String,
            cacheDir: String,
            profileYaml: String,
            patchFinal: String,
            logFile: String,
            errorFile: String,
            secret: String,
            controlPort: Int = MihomoRuntimeDefaults.FALLBACK_CONTROL_PORT,
        ): JSONObject {
            return JSONObject()
                .put("control_port", controlPort)
                .put("base_dir", baseDir)
                .put("work_dir", "")
                .put("cache_dir", cacheDir)
                .put("core_path", profileYaml)
                .put("core_path_patch", "")
                .put("core_path_patch_final", patchFinal)
                .put("log_path", logFile)
                .put("err_path", errorFile)
                .put("id", secret.take(16))
                .put("version", versionName)
                .put("name", appName)
                .put("secret", secret)
                .put("install_refer", "")
                .put("prepare", true)
                .put("wake_lock", false)
                .put("auto_connect_at_boot", false)
                .put("include_all_networks", false)
                .put("exclude_local_networks", false)
                .put("exclude_cellular_services", false)
                .put("exclude_apns", false)
                .put("exclude_device_communication", false)
                .put("enforce_routes", false)
                .put("auto_route_use_sub_ranges_by_default", false)
        }

        fun initParamsJson(baseDir: String, sdkInt: Int): JSONObject {
            return JSONObject()
                .put("home-dir", baseDir)
                .put("version", sdkInt)
        }

        fun setupParamsJson(
            selectedMap: Map<String, String> = emptyMap(),
            testUrl: String = MihomoRuntimeDefaults.HEALTH_URL,
        ): JSONObject {
            return JSONObject()
                .put("selected-map", JSONObject(selectedMap))
                .put("test-url", testUrl)
        }

        fun flClashRuntimeYaml(
            rawYaml: String,
            secret: String,
            controlPort: Int = MihomoRuntimeDefaults.FALLBACK_CONTROL_PORT,
            dnsPrivacyMode: DnsPrivacyMode = DnsPrivacyMode.Automatic,
            dohUrl: String = DnsPrivacyPolicy.DEFAULT_DOH_URL,
            dotEndpoint: String = DnsPrivacyPolicy.DEFAULT_DOT_ENDPOINT,
        ): String {
            val subscriptionYaml = stripTopLevelKeys(rawYaml, FLCLASH_OVERRIDE_KEYS)
            val dnsProxyGroup = dnsProxyGroup(rawYaml)
            val proxySuffix = dnsProxyGroup?.let { "#$it" }.orEmpty()
            val dnsServers = when (dnsPrivacyMode) {
                DnsPrivacyMode.Automatic -> DOH_SERVERS + DOT_SERVERS
                DnsPrivacyMode.DoH ->
                    (listOf(DnsPrivacyPolicy.normalizeDohUrl(dohUrl)) + DOH_SERVERS).distinct()
                DnsPrivacyMode.DoT ->
                    (listOf(DnsPrivacyPolicy.normalizeDotEndpoint(dotEndpoint)) + DOT_SERVERS).distinct()
            }
            return buildString {
                if (subscriptionYaml.isNotBlank()) {
                    append(subscriptionYaml.trimEnd())
                    append("\n\n")
                }
                append("# WhiteDNS Android runtime overrides\n")
                append("mixed-port: ${MihomoRuntimeDefaults.MIXED_PORT}\n")
                append("external-controller: ${MihomoRuntimeDefaults.CONTROLLER_HOST}:$controlPort\n")
                append("secret: \"${secret}\"\n")
                append("allow-lan: false\n")
                append("mode: rule\n")
                append("log-level: warning\n")
                append("ipv6: false\n")
                append("unified-delay: true\n")
                append("global-client-fingerprint: chrome\n")
                append("dns:\n")
                append("  enable: true\n")
                append("  listen: 0.0.0.0:1053\n")
                append("  ipv6: false\n")
                append("  respect-rules: ${dnsProxyGroup != null}\n")
                append("  enhanced-mode: fake-ip\n")
                append("  fake-ip-range: 198.18.0.1/16\n")
                append("  default-nameserver:\n")
                append("    - 1.1.1.1\n")
                append("    - 8.8.8.8\n")
                append("  nameserver:\n")
                dnsServers.forEach { server ->
                    append("    - ${yamlSingleQuoted("$server$proxySuffix")}\n")
                }
                append("  proxy-server-nameserver:\n")
                append("    - 1.1.1.1\n")
                append("    - 8.8.8.8\n")
                append("tun:\n")
                append("  enable: false\n")
            }
        }

        private fun dnsProxyGroup(rawYaml: String): String? {
            return runCatching {
                MihomoSelectionPolicy.trafficProbeGroup(MihomoConfigParser.parseSummary(rawYaml))?.name
            }.getOrNull()
        }

        private fun yamlSingleQuoted(value: String): String {
            return "'${value.replace("'", "''")}'"
        }

        private fun stripTopLevelKeys(rawYaml: String, keys: Set<String>): String {
            val output = mutableListOf<String>()
            var skipping = false

            rawYaml.replace("\r\n", "\n").replace('\r', '\n').lineSequence().forEach { line ->
                val key = topLevelKey(line)
                if (skipping) {
                    val topLevelBoundary = key != null || (line.isNotBlank() && line.first().isWhitespace().not())
                    if (!topLevelBoundary) return@forEach
                    skipping = false
                }

                if (key in keys) {
                    skipping = true
                    return@forEach
                }
                output += line
            }

            return output.joinToString("\n").trimEnd()
        }

        private fun topLevelKey(line: String): String? {
            if (line.isBlank() || line.first().isWhitespace() || line.trimStart().startsWith("#")) return null
            val index = line.indexOf(':')
            if (index <= 0) return null
            return line.substring(0, index).trim().takeIf { it.isNotBlank() }
        }

        private val FLCLASH_OVERRIDE_KEYS = setOf(
            "mixed-port",
            "external-controller",
            "secret",
            "allow-lan",
            "mode",
            "log-level",
            "ipv6",
            "unified-delay",
            "global-client-fingerprint",
            "dns",
            "tun",
        )

        private val DOH_SERVERS = listOf(
            "https://1.1.1.1/dns-query",
            "https://8.8.8.8/dns-query",
        )
        private val DOT_SERVERS = listOf(
            "tls://1.1.1.1:853",
            "tls://8.8.8.8:853",
        )
    }
}

object MihomoFrontingPatcher {
    fun patchProxyServers(
        rawYaml: String,
        serverOverrideIp: String?,
        serverOverridePort: Int? = null,
    ): String {
        val override = serverOverrideIp?.trim()?.takeIf { it.isNotBlank() } ?: return rawYaml
        val normalized = rawYaml.replace("\r\n", "\n").replace('\r', '\n')
        val output = mutableListOf<String>()
        var inProxies = false
        var currentProxy = mutableListOf<String>()

        fun flushProxy() {
            if (currentProxy.isEmpty()) return
            output += patchProxyBlock(currentProxy, override, serverOverridePort)
            currentProxy = mutableListOf()
        }

        normalized.split('\n').forEach { line ->
            val topLevelKey = topLevelKey(line)
            if (topLevelKey != null) {
                if (inProxies) flushProxy()
                inProxies = topLevelKey == "proxies"
                output += line
                return@forEach
            }

            if (!inProxies) {
                output += line
                return@forEach
            }

            val indent = indentation(line)
            val content = line.trimStart()
            if (indent == 2 && content.startsWith("- ")) {
                flushProxy()
                currentProxy += line
                return@forEach
            }

            if (currentProxy.isNotEmpty()) {
                currentProxy += line
            } else {
                output += line
            }
        }
        if (inProxies) flushProxy()

        return output.joinToString("\n")
    }

    private fun patchProxyBlock(lines: List<String>, override: String, portOverride: Int?): List<String> {
        return lines.map { line ->
            when {
                isProxyField(line, "server") -> replaceYamlValue(line, "server", override)
                portOverride != null && isProxyField(line, "port") ->
                    replaceYamlValue(line, "port", portOverride.toString())
                isInlineProxyMap(line) -> replaceInlineServerAndPort(line, override, portOverride)
                else -> line
            }
        }
    }

    private fun isProxyField(line: String, key: String): Boolean {
        val indent = indentation(line)
        if (indent != 4) return false
        val content = line.trimStart()
        return content.startsWith("$key:")
    }

    private fun replaceYamlValue(line: String, key: String, value: String): String {
        val indent = line.takeWhile(Char::isWhitespace)
        val comment = inlineComment(line.substringAfter(":", ""))
        return "$indent$key: $value$comment"
    }

    private fun inlineComment(value: String): String {
        val index = value.indexOf(" #")
        return if (index >= 0) value.substring(index) else ""
    }

    private fun isInlineProxyMap(line: String): Boolean {
        val content = line.trimStart()
        return content.startsWith("- {") && content.contains("server:")
    }

    private fun replaceInlineServerAndPort(line: String, value: String, portOverride: Int?): String {
        val patched = line.replace(Regex("""server:\s*([^,}]+)"""), "server: $value")
        return if (portOverride == null) patched else {
            patched.replace(Regex("""port:\s*([^,}]+)"""), "port: $portOverride")
        }
    }

    private fun topLevelKey(line: String): String? {
        if (line.isBlank() || line.first().isWhitespace() || line.trimStart().startsWith("#")) return null
        val index = line.indexOf(':')
        if (index <= 0) return null
        return line.substring(0, index).trim().takeIf { it.isNotBlank() }
    }

    private fun indentation(line: String): Int {
        return line.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: line.length
    }
}

object MihomoDpiBypassPatcher {
    fun patch(
        rawYaml: String,
        enabled: Boolean,
        proxyPort: Int = DpiBypassDefaults.FALLBACK_PROXY_PORT,
    ): String {
        if (!enabled) return rawYaml
        val normalized = rawYaml.replace("\r\n", "\n").replace('\r', '\n')
        val output = mutableListOf<String>()
        var inProxies = false
        var sawProxies = false
        var sawDpiProxy = false
        var currentProxy = mutableListOf<String>()

        fun flushProxy() {
            if (currentProxy.isEmpty()) return
            if (proxyName(currentProxy) == DpiBypassDefaults.PROXY_NAME) {
                sawDpiProxy = true
                output += dpiProxyBlock(proxyPort)
            } else {
                output += withDialerProxy(currentProxy)
            }
            currentProxy = mutableListOf()
        }

        fun appendDpiProxy() {
            if (sawDpiProxy) return
            output += dpiProxyBlock(proxyPort)
            sawDpiProxy = true
        }

        normalized.split('\n').forEach { line ->
            val topLevelKey = topLevelKey(line)
            if (topLevelKey != null) {
                if (inProxies) {
                    flushProxy()
                    appendDpiProxy()
                }
                inProxies = topLevelKey == "proxies"
                sawProxies = sawProxies || inProxies
                output += line
                return@forEach
            }

            if (!inProxies) {
                output += line
                return@forEach
            }

            val content = line.trimStart()
            if (indentation(line) == 2 && content.startsWith("- ")) {
                flushProxy()
                currentProxy += line
                return@forEach
            }

            if (currentProxy.isNotEmpty()) {
                currentProxy += line
            } else {
                output += line
            }
        }
        if (inProxies) {
            flushProxy()
            appendDpiProxy()
        }
        if (!sawProxies) {
            if (output.isNotEmpty() && output.last().isNotBlank()) output += ""
            output += "proxies:"
            output += dpiProxyBlock(proxyPort)
        }

        return output.joinToString("\n")
    }

    private fun withDialerProxy(lines: List<String>): List<String> {
        if (lines.size == 1 && isInlineProxyMap(lines.single())) {
            return listOf(withInlineDialerProxy(lines.single()))
        }
        var replaced = false
        val patched = lines.map { line ->
            if (isDialerProxyField(line)) {
                replaced = true
                replaceYamlValue(line, DpiBypassDefaults.PROXY_NAME)
            } else {
                line
            }
        }.toMutableList()
        if (!replaced) {
            patched += "    dialer-proxy: ${yamlSingleQuoted(DpiBypassDefaults.PROXY_NAME)}"
        }
        return patched
    }

    private fun dpiProxyBlock(proxyPort: Int): List<String> {
        return listOf(
            "  - name: ${yamlSingleQuoted(DpiBypassDefaults.PROXY_NAME)}",
            "    type: socks5",
            "    server: ${DpiBypassDefaults.PROXY_HOST}",
            "    port: $proxyPort",
            "    udp: true",
        )
    }

    private fun proxyName(lines: List<String>): String? {
        lines.forEach { line ->
            val content = line.trimStart()
            val value = when {
                content.startsWith("- {") -> Regex("""name:\s*([^,}]+)""").find(content)?.groupValues?.get(1)
                content.startsWith("- name:") -> content.substringAfter("- name:")
                indentation(line) == 4 && content.startsWith("name:") -> content.substringAfter("name:")
                else -> null
            }
            value?.let { return decodeYamlScalar(it) }
        }
        return null
    }

    private fun isDialerProxyField(line: String): Boolean {
        return indentation(line) == 4 && line.trimStart().startsWith("dialer-proxy:")
    }

    private fun replaceYamlValue(line: String, value: String): String {
        val indent = line.takeWhile(Char::isWhitespace)
        val comment = inlineComment(line.substringAfter(":", ""))
        return "$indent" + "dialer-proxy: ${yamlSingleQuoted(value)}$comment"
    }

    private fun withInlineDialerProxy(line: String): String {
        val quoted = yamlSingleQuoted(DpiBypassDefaults.PROXY_NAME)
        if ("dialer-proxy:" in line) {
            return line.replace(Regex("""dialer-proxy:\s*([^,}]+)"""), "dialer-proxy: $quoted")
        }
        return line.replaceFirst(Regex("""\}\s*$"""), ", dialer-proxy: $quoted }")
    }

    private fun isInlineProxyMap(line: String): Boolean {
        return line.trimStart().startsWith("- {")
    }

    private fun inlineComment(value: String): String {
        val index = value.indexOf(" #")
        return if (index >= 0) value.substring(index) else ""
    }

    private fun decodeYamlScalar(value: String): String {
        val trimmed = value.trim().substringBefore(" #").trim()
        if (trimmed.length >= 2 && trimmed.first() == '\'' && trimmed.last() == '\'') {
            return trimmed.substring(1, trimmed.lastIndex).replace("''", "'")
        }
        if (trimmed.length >= 2 && trimmed.first() == '"' && trimmed.last() == '"') {
            return trimmed.substring(1, trimmed.lastIndex)
        }
        return trimmed
    }

    private fun yamlSingleQuoted(value: String): String {
        return "'${value.replace("'", "''")}'"
    }

    private fun topLevelKey(line: String): String? {
        if (line.isBlank() || line.first().isWhitespace() || line.trimStart().startsWith("#")) return null
        val index = line.indexOf(':')
        if (index <= 0) return null
        return line.substring(0, index).trim().takeIf { it.isNotBlank() }
    }

    private fun indentation(line: String): Int {
        return line.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: line.length
    }
}

class MihomoControllerClient(
    @Suppress("UNUSED_PARAMETER") private val secret: String,
    @Suppress("UNUSED_PARAMETER") private val host: String = MihomoRuntimeDefaults.CONTROLLER_HOST,
    @Suppress("UNUSED_PARAMETER") private val port: Int = MihomoRuntimeDefaults.FALLBACK_CONTROL_PORT,
) {
    val endpoint: String
        get() = "core-actions"

    fun getProxies(): JSONObject {
        return invokeAction("getProxies")
            .optJSONObject("data")
            ?: throw IOException("Mihomo core getProxies returned no data")
    }

    fun activeProxyName(selectedName: String?): String? {
        return MihomoControllerProxies.activeProxyName(getProxies(), selectedName)
    }

    fun selectProxy(groupName: String, selectedName: String) {
        val message = invokeAction(
            method = "changeProxy",
            data = JSONObject()
                .put("group-name", groupName)
                .put("proxy-name", selectedName)
                .toString(),
        ).optString("data")
        if (message.isNotBlank()) {
            throw IOException("Mihomo core changeProxy failed: $message")
        }
    }

    fun delay(
        name: String,
        timeoutMs: Int = 5_000,
        url: String = MihomoRuntimeDefaults.HEALTH_URL,
    ): Int? {
        val payload = invokeAction(
            method = "asyncTestDelay",
            data = JSONObject()
                .put("proxy-name", name)
                .put("test-url", url)
                .put("timeout", timeoutMs)
                .toString(),
            timeoutMs = timeoutMs + CORE_ACTION_TIMEOUT_PADDING_MS,
        ).optString("data")
        return runCatching { JSONObject(payload).optInt("value", -1).takeIf { it >= 0 } }.getOrNull()
    }

    private fun invokeAction(
        method: String,
        data: String? = null,
        timeoutMs: Int = CORE_ACTION_TIMEOUT_MS,
    ): JSONObject {
        val action = JSONObject()
            .put("id", "$method-${System.nanoTime()}")
            .put("method", method)
        if (data != null) {
            action.put("data", data)
        }

        val latch = CountDownLatch(1)
        var response: String? = null
        Core.invokeAction(action.toString()) { result ->
            response = result
            latch.countDown()
        }
        if (!latch.await(timeoutMs.toLong(), TimeUnit.MILLISECONDS)) {
            throw IOException("Mihomo core action $method timed out")
        }
        val root = runCatching { JSONObject(response.orEmpty()) }
            .getOrElse { error -> throw IOException("Mihomo core action $method returned invalid JSON", error) }
        if (root.optInt("code", -1) != 0) {
            throw IOException("Mihomo core action $method failed: ${root.opt("data")}")
        }
        return root
    }

    private companion object {
        const val CORE_ACTION_TIMEOUT_MS = 5_000
        const val CORE_ACTION_TIMEOUT_PADDING_MS = 1_000
    }
}

object MihomoControllerProxies {
    fun activeProxyName(response: JSONObject, selectedName: String?): String? {
        val proxies = response.optJSONObject("proxies") ?: return selectedName
        var name = selectedName?.takeIf(String::isNotBlank) ?: return null
        val seen = mutableSetOf<String>()
        repeat(MAX_GROUP_DEPTH) {
            if (!seen.add(name)) return name
            val item = proxies.optJSONObject(name) ?: return name
            val now = item.optString("now").takeIf(String::isNotBlank) ?: return name
            name = now
        }
        return name
    }

    private const val MAX_GROUP_DEPTH = 8
}

object MihomoRuntimeHealth {
    fun httpStatusThroughMixedProxy(
        url: String = MihomoRuntimeDefaults.HEALTH_URL,
        timeoutMs: Int = 3_000,
    ): Int {
        val proxy = Proxy(
            Proxy.Type.HTTP,
            InetSocketAddress(MihomoRuntimeDefaults.CONTROLLER_HOST, MihomoRuntimeDefaults.MIXED_PORT),
        )
        return httpStatus(url, proxy, timeoutMs)
    }

    fun httpStatusThroughSocksProxy(
        port: Int,
        url: String = MihomoRuntimeDefaults.HEALTH_URL,
        timeoutMs: Int = 3_000,
    ): Int {
        val proxy = Proxy(
            Proxy.Type.SOCKS,
            InetSocketAddress(DpiBypassDefaults.PROXY_HOST, port),
        )
        return httpStatus(url, proxy, timeoutMs)
    }

    fun egressCountryCodeThroughMixedProxy(
        url: String = MihomoRuntimeDefaults.EGRESS_TRACE_URL,
        timeoutMs: Int = 2_000,
    ): String? {
        val proxy = Proxy(
            Proxy.Type.HTTP,
            InetSocketAddress(MihomoRuntimeDefaults.CONTROLLER_HOST, MihomoRuntimeDefaults.MIXED_PORT),
        )
        val connection = URL(url).openConnection(proxy) as HttpURLConnection
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        connection.instanceFollowRedirects = false
        return connection.use {
            if (responseCode !in 200..299) return@use null
            countryCodeFromTrace(inputStream.bufferedReader().use { it.readText() })
        }
    }

    fun countryCodeFromTrace(trace: String): String? {
        return trace.lineSequence()
            .firstOrNull { it.startsWith("loc=") }
            ?.substringAfter("=")
            ?.let(ConnectionLocationPolicy::normalizeCountryCode)
    }

    private fun httpStatus(url: String, proxy: Proxy, timeoutMs: Int): Int {
        val connection = URL(url).openConnection(proxy) as HttpURLConnection
        connection.connectTimeout = timeoutMs
        connection.readTimeout = timeoutMs
        connection.instanceFollowRedirects = false
        return connection.use {
            responseCode
        }
    }

    private inline fun <T> HttpURLConnection.use(block: HttpURLConnection.() -> T): T {
        return try {
            block()
        } finally {
            disconnect()
        }
    }
}
