package com.whitedns.vpn

import java.io.IOException

/** Native exception messages/causes may echo configuration, cookies or credentials. */
internal object EngineDiagnosticFailure {
    fun sanitize(error: Throwable): IOException = IOException("Engine connection failed (" + error.javaClass.simpleName + ")")
}
