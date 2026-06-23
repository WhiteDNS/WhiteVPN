package com.whitedns.vpn

import android.app.Application
import io.nekohasekai.libbox.Libbox
import io.nekohasekai.libbox.SetupOptions
import java.io.File
import java.security.SecureRandom

internal object LibboxCommandServerSecret {
    fun generate(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

class WhiteDnsApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        setupLibbox()
    }

    private fun setupLibbox() {
        val workingDir = File(filesDir, "working").apply { mkdirs() }
        val tempDir = File(cacheDir, "libbox").apply { mkdirs() }
        if (BuildConfig.DEBUG) {
            val stderrFile = DiagnosticLogger.libboxStderrFile(this).apply {
                parentFile?.mkdirs()
            }
            runCatching {
                Libbox.redirectStderr(stderrFile.absolutePath)
            }.onFailure { error ->
                DiagnosticLogger.warn(this, "libbox.stderr.redirect.failed", error = error)
            }
        } else {
            runCatching { DiagnosticLogger.libboxStderrFile(this).delete() }
        }

        runCatching {
            Libbox.setMemoryLimit(true)
        }.onFailure { error ->
            DiagnosticLogger.warn(this, "libbox.memoryLimit.failed", error = error)
        }

        runCatching {
            DiagnosticLogger.info(this, "libbox.version", Libbox.version())
        }.onFailure { error ->
            DiagnosticLogger.warn(this, "libbox.version.failed", error = error)
        }

        runCatching {
            Libbox.setup(
                SetupOptions().apply {
                    basePath = filesDir.absolutePath
                    workingPath = workingDir.absolutePath
                    tempPath = tempDir.absolutePath
                    fixAndroidStack = true
                    commandServerListenPort = 0
                    commandServerSecret = LibboxCommandServerSecret.generate()
                    logMaxLines = 300L
                    debug = BuildConfig.DEBUG
                },
            )
            DiagnosticLogger.info(
                this,
                "libbox.setup.success",
                "basePath=${filesDir.absolutePath} workingPath=${workingDir.absolutePath}",
            )
        }.onFailure { error ->
            DiagnosticLogger.error(this, "libbox.setup.failed", error = error)
        }
    }
}
