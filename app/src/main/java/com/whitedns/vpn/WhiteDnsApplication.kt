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
        VpnWidgetProvider.refresh(this)
        runCatching {
            contentResolver.registerContentObserver(
                android.provider.Settings.Secure.getUriFor(ALWAYS_ON_VPN_SETTING), false,
                object : android.database.ContentObserver(android.os.Handler(mainLooper)) {
                    override fun onChange(selfChange: Boolean) {
                        VpnWidgetProvider.refresh(this@WhiteDnsApplication)
                    }
                },
            )
        }.onFailure { DiagnosticLogger.warn(this, "widget.settingsObserver.failed", error = it) }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        VpnWidgetProvider.refresh(this)
    }
}
