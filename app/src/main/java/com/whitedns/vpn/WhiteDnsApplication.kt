package com.whitedns.vpn

import android.app.Application
import android.content.Context
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
    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(AppLocale.wrap(AppTheme.wrap(base)))
    }

    override fun onCreate() {
        super.onCreate()
        initializeWhiteDnsTypefaces(this)
        File(filesDir, "mihomo").mkdirs()
        File(cacheDir, "mihomo").mkdirs()
        DiagnosticLogger.info(this, "mihomo.app.ready", "basePath=${filesDir.absolutePath}")
    }
}
