package com.whitedns.vpn

import android.app.Application
import java.io.File
import java.security.SecureRandom

internal object MihomoControllerSecret {
    fun generate(random: SecureRandom = SecureRandom()): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }
}

class WhiteDnsApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        File(filesDir, "mihomo").mkdirs()
        File(cacheDir, "mihomo").mkdirs()
        DiagnosticLogger.info(this, "mihomo.app.ready", "basePath=${filesDir.absolutePath}")
    }
}
