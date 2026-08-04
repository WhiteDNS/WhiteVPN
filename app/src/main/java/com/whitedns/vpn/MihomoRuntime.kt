package com.whitedns.vpn

import android.content.Context
import com.follow.clash.core.Core
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.InputStream
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
    val HEALTH_URLS = listOf(
        "https://valid-isrgrootx1.letsencrypt.org/",
        "https://connectivitycheck.gstatic.com/generate_204",
        "https://cloudflare.com/cdn-cgi/trace",
    )
    val HEALTH_URL = HEALTH_URLS.first()
    const val EGRESS_TRACE_URL = "https://www.cloudflare.com/cdn-cgi/trace"
}

object TlsIntegrityPolicy {
    val TEST_URLS = MihomoRuntimeDefaults.HEALTH_URLS
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

data class MihomoConnectionOptions(
    val amneziaNoiseEnabled: Boolean = false,
    val amneziaNoise: AmneziaNoiseSettings = MihomoConnectionOptionsPolicy.DEFAULT_NOISE,
)

object MihomoConnectionOptionsPolicy {
    const val MIN_NOISE_COUNT = 1
    const val MAX_NOISE_COUNT = 20
    const val MIN_NOISE_SIZE = 1
    const val MAX_NOISE_SIZE = 1280
    val DEFAULT_NOISE = AmneziaNoiseSettings(count = 5, minSize = 50, maxSize = 100)

    fun validateNoise(settings: AmneziaNoiseSettings): AmneziaNoiseSettings {
        require(settings.count in MIN_NOISE_COUNT..MAX_NOISE_COUNT) {
            "Count must be between $MIN_NOISE_COUNT and $MAX_NOISE_COUNT"
        }
        require(settings.minSize in MIN_NOISE_SIZE..MAX_NOISE_SIZE) {
            "Minimum size must be between $MIN_NOISE_SIZE and $MAX_NOISE_SIZE"
        }
        require(settings.maxSize in MIN_NOISE_SIZE..MAX_NOISE_SIZE) {
            "Maximum size must be between $MIN_NOISE_SIZE and $MAX_NOISE_SIZE"
        }
        require(settings.minSize <= settings.maxSize) { "Minimum size cannot exceed maximum size" }
        return settings
    }

    fun isValidNoise(settings: AmneziaNoiseSettings): Boolean =
        runCatching { validateNoise(settings) }.isSuccess

    fun echCapable(type: String, tlsEnabled: Boolean, realityEnabled: Boolean): Boolean {
        if (realityEnabled) return false
        return when (type.lowercase()) {
            "trojan", "anytls", "trusttunnel", "tuic", "hysteria", "hysteria2" -> true
            "vless", "vmess" -> tlsEnabled
            else -> false
        }
    }

    fun applyTo(profile: ConnectionProfile, options: MihomoConnectionOptions): ConnectionProfile {
        return profile.copy(
            amneziaNoise = if (options.amneziaNoiseEnabled && profile.type.equals("wireguard", true)) {
                options.amneziaNoise
            } else {
                profile.amneziaNoise
            },
        )
    }
}

class MihomoConnectionOptionsPreferenceStore(context: Context) {
    private val prefs = context.getSharedPreferences("white_dns_connection_options", Context.MODE_PRIVATE)

    fun read(): MihomoConnectionOptions {
        val noise = AmneziaNoiseSettings(
            count = prefs.getInt(KEY_NOISE_COUNT, MihomoConnectionOptionsPolicy.DEFAULT_NOISE.count),
            minSize = prefs.getInt(KEY_NOISE_MIN_SIZE, MihomoConnectionOptionsPolicy.DEFAULT_NOISE.minSize),
            maxSize = prefs.getInt(KEY_NOISE_MAX_SIZE, MihomoConnectionOptionsPolicy.DEFAULT_NOISE.maxSize),
        ).takeIf(MihomoConnectionOptionsPolicy::isValidNoise) ?: MihomoConnectionOptionsPolicy.DEFAULT_NOISE
        return MihomoConnectionOptions(
            amneziaNoiseEnabled = prefs.getBoolean(KEY_AMNEZIA_NOISE_ENABLED, false),
            amneziaNoise = noise,
        )
    }

    fun saveAmneziaNoise(enabled: Boolean, settings: AmneziaNoiseSettings) {
        val valid = MihomoConnectionOptionsPolicy.validateNoise(settings)
        prefs.edit()
            .putBoolean(KEY_AMNEZIA_NOISE_ENABLED, enabled)
            .putInt(KEY_NOISE_COUNT, valid.count)
            .putInt(KEY_NOISE_MIN_SIZE, valid.minSize)
            .putInt(KEY_NOISE_MAX_SIZE, valid.maxSize)
            .apply()
    }

    private companion object {
        const val KEY_AMNEZIA_NOISE_ENABLED = "amnezia_noise_enabled"
        const val KEY_NOISE_COUNT = "noise_count"
        const val KEY_NOISE_MIN_SIZE = "noise_min_size"
        const val KEY_NOISE_MAX_SIZE = "noise_max_size"
    }
}

class TlsIntegrityException(cause: Throwable) : IOException("TLS certificate validation failed", cause)

enum class DnsPrivacyMode(val wireName: String, val labelRes: Int) {
    Automatic("automatic", R.string.dns_mode_automatic),
    DoH("doh", R.string.dns_mode_doh),
    DoT("dot", R.string.dns_mode_dot),
    ;

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
            .getOrElse { throw IllegalArgumentException("DoH باید یک URL معتبر HTTPS باشد") }
        require(uri.scheme.equals("https", ignoreCase = true) && !uri.host.isNullOrBlank()) {
            "DoH باید یک URL معتبر HTTPS باشد"
        }
        require(uri.rawUserInfo == null && uri.rawFragment == null) {
            "URL مربوط به DoH نباید اطلاعات ورود یا fragment داشته باشد"
        }
        require(uri.rawAuthority?.endsWith(':') == false) { "پورت DoH باید عددی بین 1 تا 65535 باشد" }
        require(uri.port == -1 || uri.port in 1..65535) { "پورت DoH باید عددی بین 1 تا 65535 باشد" }
        return uri.toASCIIString()
    }

    fun normalizeDotEndpoint(value: String): String {
        val input = value.trim()
        val uri = runCatching {
            URI(if (input.startsWith("tls://", ignoreCase = true)) input else "tls://$input")
        }.getOrElse { throw IllegalArgumentException("DoT باید یک host[:port] معتبر باشد") }
        require(uri.scheme.equals("tls", ignoreCase = true) && !uri.host.isNullOrBlank()) {
            "DoT باید یک host[:port] معتبر باشد"
        }
        require(
            uri.rawUserInfo == null &&
                uri.rawQuery == null &&
                uri.rawFragment == null &&
                (uri.rawPath.isNullOrEmpty() || uri.rawPath == "/"),
        ) {
            "DoT فقط می‌تواند نام میزبان و پورت اختیاری داشته باشد"
        }
        require(uri.rawAuthority?.endsWith(':') == false) { "پورت DoT باید عددی بین 1 تا 65535 باشد" }
        require(uri.port == -1 || uri.port in 1..65535) { "پورت DoT باید عددی بین 1 تا 65535 باشد" }
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

internal object MihomoGeoDataInstaller {
    val fileNames = listOf("GEOIP.metadb", "GEOIP.dat", "GEOSITE.dat", "ASN.mmdb")

    @Synchronized
    fun install(baseDir: File, openAsset: (String) -> InputStream): List<String> {
        baseDir.mkdirs()
        return fileNames.mapNotNull { fileName ->
            val target = File(baseDir, fileName)
            val existing = baseDir.listFiles()?.firstOrNull { candidate ->
                candidate.name.equals(fileName, ignoreCase = true)
            }
            if (existing?.isFile == true && existing.length() > 0L) return@mapNotNull null

            val temporary = File(baseDir, ".$fileName.tmp")
            try {
                openAsset("data/$fileName").use { input ->
                    temporary.outputStream().buffered().use { output -> input.copyTo(output) }
                }
                listOfNotNull(existing, target.takeIf { it != existing && it.exists() }).forEach { stale ->
                    if (!stale.delete()) {
                        throw IOException("Unable to replace Mihomo geodata file ${stale.name}")
                    }
                }
                if (!temporary.renameTo(target)) {
                    throw IOException("Unable to install Mihomo geodata file $fileName")
                }
                fileName
            } finally {
                temporary.delete()
            }
        }
    }
}

class MihomoRuntimeConfigBuilder(private val context: Context) {
    fun write(
        rawYaml: String,
        splitTunnelPlan: SplitTunnelRuntimePlan,
        dnsPrivacyMode: DnsPrivacyMode = DnsPrivacyMode.Automatic,
        dohUrl: String = DnsPrivacyPolicy.DEFAULT_DOH_URL,
        dotEndpoint: String = DnsPrivacyPolicy.DEFAULT_DOT_ENDPOINT,
        secret: String = MihomoControllerSecret.generate(),
        selectedMap: Map<String, String> = emptyMap(),
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
        setupParams.writeText(setupParamsJson(selectedMap).toString(2))
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
                append("  listen: 127.0.0.1:1053\n")
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
                // Encrypted, IP-addressed resolvers (same privacy mode as `nameserver`) with no proxy
                // suffix, so proxy-hostname resolution isn't exposed to an on-path operator over
                // plaintext UDP:53 and doesn't loop back through the proxy it's resolving.
                append("  proxy-server-nameserver:\n")
                dnsServers.forEach { server ->
                    append("    - ${yamlSingleQuoted(server)}\n")
                }
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

object MihomoConnectionOptionsPatcher {
    fun patch(rawYaml: String, options: MihomoConnectionOptions): String {
        if (!options.amneziaNoiseEnabled) return rawYaml
        val output = mutableListOf<String>()
        var inProxies = false
        var currentProxy = mutableListOf<String>()

        fun flushProxy() {
            if (currentProxy.isEmpty()) return
            output += patchProxyBlock(currentProxy, options)
            currentProxy = mutableListOf()
        }

        rawYaml.replace("\r\n", "\n").replace('\r', '\n').split('\n').forEach { line ->
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
            if (indentation(line) == 2 && line.trimStart().startsWith("- ")) {
                flushProxy()
                currentProxy += line
            } else if (currentProxy.isNotEmpty()) {
                currentProxy += line
            } else {
                output += line
            }
        }
        if (inProxies) flushProxy()
        return output.joinToString("\n")
    }

    private fun patchProxyBlock(
        lines: List<String>,
        options: MihomoConnectionOptions,
    ): List<String> {
        val type = proxyFieldValue(lines, "type").orEmpty()
        var patched = lines
        if (options.amneziaNoiseEnabled && type.equals("wireguard", true)) {
            val noise = options.amneziaNoise
            patched = upsertNestedOptions(
                patched,
                "amnezia-wg-option",
                linkedMapOf(
                    "jc" to noise.count.toString(),
                    "jmin" to noise.minSize.toString(),
                    "jmax" to noise.maxSize.toString(),
                ),
            )
        }
        return patched
    }

    private fun upsertNestedOptions(
        lines: List<String>,
        key: String,
        fields: Map<String, String>,
    ): List<String> {
        if (lines.size == 1 && lines.single().trimStart().startsWith("- {")) {
            return listOf(upsertInlineMap(lines.single(), key, fields))
        }
        val start = lines.indexOfFirst { indentation(it) == 4 && fieldName(it) == key }
        if (start < 0) {
            return lines + "    $key:" + fields.map { (field, value) -> "      $field: $value" }
        }
        if (lines[start].substringAfter(':').trim().startsWith("{")) {
            return lines.toMutableList().also { it[start] = upsertInlineMap(it[start], key, fields) }
        }

        val patched = lines.toMutableList()
        var end = (start + 1 until patched.size)
            .firstOrNull { indentation(patched[it]) <= 4 && patched[it].isNotBlank() }
            ?: patched.size
        fields.forEach { (field, value) ->
            val index = (start + 1 until end).firstOrNull {
                indentation(patched[it]) == 6 && fieldName(patched[it]) == field
            }
            if (index == null) {
                patched.add(end, "      $field: $value")
                end += 1
            } else {
                patched[index] = "      $field: $value"
            }
        }
        return patched
    }

    private fun upsertInlineMap(line: String, key: String, fields: Map<String, String>): String {
        val keyPattern = Regex.escape(key)
        val nestedMap = Regex("""(['\"]?$keyPattern['\"]?\s*:\s*)\{([^}]*)}""")
        val match = nestedMap.find(line)
        if (match != null) {
            val body = patchInlineFields(match.groupValues[2], fields)
            return line.replaceRange(match.range, "${match.groupValues[1]}{$body}")
        }
        val close = line.lastIndexOf('}')
        if (close < 0) return line
        val separator = if (line.substring(0, close).trimEnd().endsWith('{')) "" else ", "
        val body = fields.entries.joinToString(", ") { (field, value) -> "$field: $value" }
        return line.substring(0, close) + "$separator$key: {$body}" + line.substring(close)
    }

    private fun patchInlineFields(body: String, fields: Map<String, String>): String {
        var patched = body.trim()
        fields.forEach { (field, value) ->
            val fieldPattern = Regex("""((?:^|,\s*)['\"]?${Regex.escape(field)}['\"]?\s*:\s*)([^,}]+)""")
            val match = fieldPattern.find(patched)
            patched = if (match == null) {
                patched + if (patched.isBlank()) "$field: $value" else ", $field: $value"
            } else {
                val valueRange = match.groups[2]?.range ?: return@forEach
                patched.replaceRange(valueRange, value)
            }
        }
        return patched
    }

    private fun proxyFieldValue(lines: List<String>, key: String): String? {
        lines.firstOrNull { indentation(it) == 4 && fieldName(it) == key }?.let { line ->
            return yamlScalar(line.substringAfter(':'))
        }
        val inline = lines.singleOrNull()?.trimStart()?.takeIf { it.startsWith("- {") } ?: return null
        return Regex("""(?:^|[,{]\s*)['\"]?${Regex.escape(key)}['\"]?\s*:\s*([^,}]+)""")
            .find(inline)
            ?.groupValues
            ?.get(1)
            ?.let(::yamlScalar)
    }

    private fun fieldName(line: String): String =
        line.trimStart().substringBefore(':').trim().removeSurrounding("\"").removeSurrounding("'")

    private fun yamlScalar(value: String): String =
        value.substringBefore(" #").trim().removeSurrounding("\"").removeSurrounding("'")

    private fun topLevelKey(line: String): String? {
        if (line.isBlank() || line.first().isWhitespace() || line.trimStart().startsWith("#")) return null
        return line.substringBefore(':').trim().takeIf { ':' in line && it.isNotBlank() }
    }

    private fun indentation(line: String): Int =
        line.indexOfFirst { !it.isWhitespace() }.takeIf { it >= 0 } ?: line.length
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

    fun selectorPath(
        response: JSONObject,
        targetName: String,
        preferredRoots: List<String> = emptyList(),
    ): List<MihomoGroupSelection> {
        val proxies = response.optJSONObject("proxies") ?: return emptyList()
        val selectorNames = proxies.keys().asSequence()
            .filter { name ->
                proxies.optJSONObject(name)
                    ?.optString("type")
                    .equals("Selector", ignoreCase = true)
            }
            .toList()
        val roots = (preferredRoots + selectorNames).distinct()
        roots.forEach { root ->
            val path = selectorPathFrom(
                proxies = proxies,
                currentName = root,
                targetName = targetName,
                visited = emptySet(),
            ) ?: return@forEach
            return path.zipWithNext()
                .map { (selector, selected) -> MihomoGroupSelection(selector, selected) }
                .reversed()
        }
        return emptyList()
    }

    private fun selectorPathFrom(
        proxies: JSONObject,
        currentName: String,
        targetName: String,
        visited: Set<String>,
    ): List<String>? {
        if (currentName == targetName) return listOf(targetName)
        if (currentName in visited || visited.size >= MAX_GROUP_DEPTH) return null
        val item = proxies.optJSONObject(currentName) ?: return null
        if (!item.optString("type").equals("Selector", ignoreCase = true)) return null
        val members = item.optJSONArray("all") ?: return null
        for (index in 0 until members.length()) {
            val member = members.optString(index).takeIf(String::isNotBlank) ?: continue
            val childPath = selectorPathFrom(
                proxies = proxies,
                currentName = member,
                targetName = targetName,
                visited = visited + currentName,
            ) ?: continue
            return listOf(currentName) + childPath
        }
        return null
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
