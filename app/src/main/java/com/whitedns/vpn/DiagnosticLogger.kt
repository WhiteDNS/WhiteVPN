package com.whitedns.vpn

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DiagnosticLogger {
    private const val TAG = "WhiteDNSDiag"
    private const val LOG_FILE_NAME = "whitedns-debug.log"
    private const val MIHOMO_STDERR_FILE_NAME = "service_error.log"
    private const val MAX_LOG_BYTES = 512 * 1024
    private const val TRIM_TO_BYTES = 384 * 1024
    private const val MAX_STDERR_READ_BYTES = 128 * 1024
    private const val MAX_COPIED_CHARS = 128 * 1024
    @Volatile
    private var captureEnabled = BuildConfig.DEBUG

    fun beginCapture(context: Context) {
        if (captureEnabled) return
        captureEnabled = true
        info(context, "diagnostics.capture.start")
    }

    fun clear(context: Context) {
        runCatching {
            synchronized(this) {
                logFile(context).writeText("")
                mihomoStderrFile(context).writeText("")
            }
        }.onFailure { error ->
            if (BuildConfig.DEBUG) Log.w(TAG, "Unable to clear diagnostics", error)
        }
    }

    fun read(context: Context): String {
        val body = runCatching {
            synchronized(this) {
                val file = logFile(context)
                val debugLog = if (!file.exists()) "" else file.readText()
                val stderr = mihomoStderrFile(context)
                if (!stderr.exists() || stderr.length() == 0L) {
                    debugLog
                } else {
                    buildString {
                        append(debugLog)
                        if (isNotEmpty() && !endsWith("\n")) {
                            append("\n")
                        }
                        append("\n--- mihomo stderr ---\n")
                        append(stderr.readText().takeLast(MAX_STDERR_READ_BYTES))
                    }
                }
            }
        }.onFailure { error ->
            if (BuildConfig.DEBUG) Log.w(TAG, "Unable to read diagnostics", error)
        }.getOrDefault("")

        return buildString {
            append("WhiteVPN ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n")
            append("Device ${Build.MANUFACTURER} ${Build.MODEL}; Android ${Build.VERSION.RELEASE} ")
            append("(SDK ${Build.VERSION.SDK_INT}); ABIs ${Build.SUPPORTED_ABIS.joinToString()}\n")
            append("--- events ---\n")
            append(body.takeLast(MAX_COPIED_CHARS))
        }.sanitizeForLog()
    }

    fun info(context: Context, event: String, message: String = "") {
        write(context, "INFO", event, message, null)
    }

    fun warn(context: Context, event: String, message: String = "", error: Throwable? = null) {
        write(context, "WARN", event, message, error)
    }

    fun error(context: Context, event: String, message: String = "", error: Throwable? = null) {
        write(context, "ERROR", event, message, error)
    }

    private fun write(
        context: Context,
        level: String,
        event: String,
        message: String,
        error: Throwable?,
    ) {
        if (!captureEnabled) return
        val line = buildString {
            append(timestamp())
            append(" ")
            append(level)
            append(" ")
            append(event)
            if (message.isNotBlank()) {
                append(" - ")
                append(message)
            }
            if (error != null) {
                append("\n")
                append(error.stackTraceToString())
            }
        }.sanitizeForLog()

        if (BuildConfig.DEBUG) {
            when (level) {
                "ERROR" -> Log.e(TAG, line)
                "WARN" -> Log.w(TAG, line)
                else -> Log.i(TAG, line)
            }
        }

        runCatching {
            synchronized(this) {
                val file = logFile(context)
                file.parentFile?.mkdirs()
                if (file.exists() && file.length() > MAX_LOG_BYTES) {
                    file.writeText(file.readText().takeLast(TRIM_TO_BYTES))
                }
                file.appendText(line)
                file.appendText("\n")
            }
        }.onFailure { writeError ->
            if (BuildConfig.DEBUG) Log.w(TAG, "Unable to persist diagnostics", writeError)
        }
    }

    private fun logFile(context: Context): File {
        return File(context.filesDir, LOG_FILE_NAME)
    }

    fun mihomoStderrFile(context: Context): File {
        return File(context.filesDir, MIHOMO_STDERR_FILE_NAME)
    }

    private fun timestamp(): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
    }

    /**
     * Debug logs and the mihomo stderr tail are copied to the clipboard by a long-press on the
     * connect button, so anything that reaches them is likely to end up pasted into a chat. Redact
     * the credential-bearing fields a Mihomo profile carries rather than a single known token.
     */
    internal fun String.sanitizeForLog(): String {
        var sanitized = this
        SECRET_VALUES.forEach { secret ->
            if (secret.isNotBlank()) {
                sanitized = sanitized.replace(secret, "<redacted>")
            }
        }
        sanitized = CREDENTIAL_URI_REGEX.replace(sanitized, "<redacted-uri>")
        sanitized = AUTHORIZATION_REGEX.replace(sanitized) { match ->
            "${match.groupValues[1]}<redacted>"
        }
        return SECRET_FIELD_REGEX.replace(sanitized) { match ->
            "${match.groupValues[1]}${match.groupValues[2]}<redacted>"
        }
    }

    private val SECRET_VALUES: List<String>
        get() = listOf(
            WhiteDnsConfig.MIHOMO_SUBSCRIPTION_KEY,
            WhiteDnsConfig.ENCRYPTED_IP_LIST_KEY,
        )

    private val CREDENTIAL_URI_REGEX = Regex(
        "\\b(?:ss|ssr|vmess|vless|trojan|tuic|hysteria2?|hy2|wireguard|wg|socks5h?|https?)://[^\\s\\\"'<>]+",
        RegexOption.IGNORE_CASE,
    )

    private val AUTHORIZATION_REGEX = Regex(
        "(\\b(?:authorization|proxy-authorization)\\b\\s*[:=]\\s*)[^\\r\\n]+",
        RegexOption.IGNORE_CASE,
    )

    // Keeps field names and separators readable while removing their values.
    private val SECRET_FIELD_REGEX = Regex(
        "(\"?(?:uuid|password|secret|token|psk|auth[_-]?str|private[_-]?key|" +
            "preshared[_-]?key|pre[_-]?shared[_-]?key|client[_-]?(?:id|secret)|api[_-]?key|short[-_]?id)\"?)" +
            "(\\s*[:=]\\s*)" +
            "(\"(?:\\\\.|[^\"\\\\])*\"|'(?:\\\\.|[^'\\\\])*'|[^\\s,\"'}&]+)",
        RegexOption.IGNORE_CASE,
    )
}
