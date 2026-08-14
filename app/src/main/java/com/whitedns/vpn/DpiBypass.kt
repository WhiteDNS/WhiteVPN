package com.whitedns.vpn

import android.content.Context
import androidx.annotation.Keep
import com.follow.clash.core.TunInterface

object DpiBypassDefaults {
    const val PROXY_NAME = "WhiteDNS ByeByeDPI"
    const val PROXY_HOST = "127.0.0.1"
    const val FALLBACK_PROXY_PORT = 1080
    private const val PROTECT_PATH_PLACEHOLDER = "android-vpn-protect"

    fun proxyArgs(port: Int): Array<String> {
        require(port in 1..65_535) { "Invalid ByeByeDPI port: $port" }
        return arrayOf(
            "ciadpi",
            "-i$PROXY_HOST",
            "-p$port",
            "-P$PROTECT_PATH_PLACEHOLDER",
            "-Kt,h",
            "-d1",
            "-f-1",
        )
    }
}

@Keep
object ByeDpiProxy {
    init {
        System.loadLibrary("core")
    }

    fun start(port: Int, protect: TunInterface): Int {
        return jniStartProxy(DpiBypassDefaults.proxyArgs(port), protect)
    }

    fun stop(): Int = jniStopProxy()

    private external fun jniStartProxy(args: Array<String>, protect: TunInterface): Int

    private external fun jniStopProxy(): Int
}

class DpiBypassPreferenceStore(context: Context) {
    init {
        context.getSharedPreferences("white_dns_dpi_bypass", Context.MODE_PRIVATE)
            .edit()
            .remove("enabled")
            .apply()
    }

    fun isEnabled(): Boolean = false
}
