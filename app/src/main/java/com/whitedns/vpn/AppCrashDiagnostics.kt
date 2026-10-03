package com.whitedns.vpn

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import kotlinx.coroutines.*

/** Android retains previous process exits even when a native crash bypasses Java exception handling. */
internal object AppCrashDiagnostics {
    fun record(context: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        val app = context.applicationContext
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching {
                val prefs = app.getSharedPreferences("whitevpn_crash_reports", Context.MODE_PRIVATE)
                val previous = prefs.getLong("reported_until", 0L)
                val exits = app.getSystemService(ActivityManager::class.java)
                    .getHistoricalProcessExitReasons(app.packageName, 0, 8)
                exits.filter { it.timestamp > previous && it.reason in listOf(ApplicationExitInfo.REASON_CRASH, ApplicationExitInfo.REASON_CRASH_NATIVE) }
                    .sortedBy { it.timestamp }.forEach { exit ->
                        val process = if (exit.processName.endsWith(":enginecore")) "enginecore" else "app"
                        DiagnosticLogger.warn(app, "app.previousCrash",
                            "process=$process reason=${exit.reason} status=${exit.status} timestamp=${exit.timestamp}")
                    }
                exits.maxOfOrNull { it.timestamp }?.let { prefs.edit().putLong("reported_until", it).apply() }
            }
        }
    }
}
