package com.whitedns.vpn

import android.content.Context
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

    fun clear(context: Context) {
        if (!BuildConfig.DEBUG) return
        synchronized(this) {
            logFile(context).writeText("")
            runCatching { mihomoStderrFile(context).writeText("") }
        }
    }

    fun read(context: Context): String {
        if (!BuildConfig.DEBUG) return ""
        return synchronized(this) {
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
                    append(stderr.readText().takeLast(MAX_STDERR_READ_BYTES).sanitizeForLog())
                }
            }
        }
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
        if (!BuildConfig.DEBUG) return
        val line = buildString {
            append(timestamp())
            append(" ")
            append(level)
            append(" ")
            append(event)
            if (message.isNotBlank()) {
                append(" - ")
                append(message.sanitizeForLog())
            }
            if (error != null) {
                append("\n")
                append(error.stackTraceToString())
            }
        }

        when (level) {
            "ERROR" -> Log.e(TAG, line)
            "WARN" -> Log.w(TAG, line)
            else -> Log.i(TAG, line)
        }

        synchronized(this) {
            val file = logFile(context)
            file.parentFile?.mkdirs()
            if (file.exists() && file.length() > MAX_LOG_BYTES) {
                file.writeText(file.readText().takeLast(TRIM_TO_BYTES))
            }
            file.appendText(line)
            file.appendText("\n")
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
    private fun String.sanitizeForLog(): String {
        var sanitized = this
        SECRET_VALUES.forEach { secret ->
            if (secret.isNotBlank()) {
                sanitized = sanitized.replace(secret, "<redacted>")
            }
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

    // Matches `uuid: abc`, `"password":"abc"`, `secret=abc` and friends in YAML, JSON and query
    // strings, keeping the key and separator so the log stays readable.
    private val SECRET_FIELD_REGEX = Regex(
        "(\"?(?:uuid|password|secret|token|psk|auth[_-]?str|private[_-]?key|short[-_]?id)\"?)" +
            "(\\s*[:=]\\s*\"?)([^\\s,\"}&]+)",
        RegexOption.IGNORE_CASE,
    )
}
