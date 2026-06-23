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
    private const val LIBBOX_STDERR_FILE_NAME = "libbox-stderr.log"
    private const val MAX_LOG_BYTES = 512 * 1024
    private const val TRIM_TO_BYTES = 384 * 1024
    private const val MAX_STDERR_READ_BYTES = 128 * 1024

    fun clear(context: Context) {
        if (!BuildConfig.DEBUG) return
        synchronized(this) {
            logFile(context).writeText("")
            runCatching { libboxStderrFile(context).writeText("") }
        }
    }

    fun read(context: Context): String {
        if (!BuildConfig.DEBUG) return ""
        return synchronized(this) {
            val file = logFile(context)
            val debugLog = if (!file.exists()) "" else file.readText()
            val stderr = libboxStderrFile(context)
            if (!stderr.exists() || stderr.length() == 0L) {
                debugLog
            } else {
                buildString {
                    append(debugLog)
                    if (isNotEmpty() && !endsWith("\n")) {
                        append("\n")
                    }
                    append("\n--- libbox stderr ---\n")
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

    fun libboxStderrFile(context: Context): File {
        return File(context.filesDir, LIBBOX_STDERR_FILE_NAME)
    }

    private fun timestamp(): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
    }

    private fun String.sanitizeForLog(): String {
        return replace(Regex("YrOTS%3AP8\\*jo_AVgq"), "<subscription-token>")
            .replace(Regex("YrOTS:P8\\*jo_AVgq"), "<subscription-token>")
    }
}
