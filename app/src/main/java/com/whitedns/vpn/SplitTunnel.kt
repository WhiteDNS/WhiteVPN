package com.whitedns.vpn

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.util.Locale

enum class SplitTunnelMode(val wireName: String) {
    Off("off"),
    BypassSelected("bypass_selected"),
    VpnOnlySelected("vpn_only_selected"),
    ;

    companion object {
        fun fromWireName(value: String?): SplitTunnelMode {
            return entries.firstOrNull { it.wireName == value } ?: Off
        }
    }
}

data class SplitTunnelSettings(
    val mode: SplitTunnelMode = SplitTunnelMode.Off,
    val selectedPackages: Set<String> = emptySet(),
)

data class SplitTunnelInstalledApp(
    val packageName: String,
    val label: String,
)

data class SplitTunnelRuntimePlan(
    val mode: SplitTunnelMode,
    val selectedPackages: List<String>,
    val allowedPackages: List<String>,
    val disallowedPackages: List<String>,
    val skippedPackages: List<String>,
) {
    val appliedPackages: List<String>
        get() = allowedPackages + disallowedPackages

    companion object {
        fun off(selectedPackages: List<String> = emptyList()): SplitTunnelRuntimePlan {
            return SplitTunnelRuntimePlan(
                mode = SplitTunnelMode.Off,
                selectedPackages = selectedPackages,
                allowedPackages = emptyList(),
                disallowedPackages = emptyList(),
                skippedPackages = emptyList(),
            )
        }
    }
}

class SplitTunnelNoSelectedAppsException(message: String) : IllegalStateException(message)

object SplitTunnelPolicy {
    fun sanitizeSettings(
        settings: SplitTunnelSettings,
        selfPackageName: String,
    ): SplitTunnelSettings {
        return SplitTunnelSettings(
            mode = settings.mode,
            selectedPackages = normalizePackages(settings.selectedPackages, selfPackageName).toSet(),
        )
    }

    fun normalizePackages(
        packages: Iterable<String>,
        selfPackageName: String,
    ): List<String> {
        return packages
            .map { it.trim() }
            .filter { it.isNotBlank() && it != selfPackageName }
            .distinct()
            .sorted()
    }

    fun runtimePlan(
        settings: SplitTunnelSettings,
        launchablePackages: Iterable<String>,
        selfPackageName: String,
    ): SplitTunnelRuntimePlan {
        val selected = normalizePackages(settings.selectedPackages, selfPackageName)
        if (settings.mode == SplitTunnelMode.Off) {
            return SplitTunnelRuntimePlan.off(selected)
        }

        val launchable = normalizePackages(launchablePackages, selfPackageName).toSet()
        val applied = selected.filter { it in launchable }
        val skipped = selected.filter { it !in launchable }

        return when (settings.mode) {
            SplitTunnelMode.Off -> SplitTunnelRuntimePlan.off(selected)
            SplitTunnelMode.BypassSelected -> SplitTunnelRuntimePlan(
                mode = settings.mode,
                selectedPackages = selected,
                allowedPackages = emptyList(),
                disallowedPackages = applied,
                skippedPackages = skipped,
            )
            SplitTunnelMode.VpnOnlySelected -> {
                if (applied.isEmpty()) {
                    throw SplitTunnelNoSelectedAppsException(
                        "VPN-only split tunneling has no selected launchable apps installed",
                    )
                }
                SplitTunnelRuntimePlan(
                    mode = settings.mode,
                    selectedPackages = selected,
                    allowedPackages = applied,
                    disallowedPackages = emptyList(),
                    skippedPackages = skipped,
                )
            }
        }
    }
}

class SplitTunnelPreferenceStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun readSettings(): SplitTunnelSettings {
        val rawPackages = prefs.getStringSet(KEY_PACKAGES, emptySet()).orEmpty()
        return SplitTunnelPolicy.sanitizeSettings(
            SplitTunnelSettings(
                mode = SplitTunnelMode.fromWireName(prefs.getString(KEY_MODE, null)),
                selectedPackages = rawPackages,
            ),
            appContext.packageName,
        )
    }

    fun saveSettings(settings: SplitTunnelSettings) {
        val sanitized = SplitTunnelPolicy.sanitizeSettings(settings, appContext.packageName)
        prefs.edit()
            .putString(KEY_MODE, sanitized.mode.wireName)
            .putStringSet(KEY_PACKAGES, sanitized.selectedPackages)
            .apply()
    }

    private companion object {
        const val PREFS = "white_dns_split_tunnel"
        const val KEY_MODE = "mode"
        const val KEY_PACKAGES = "packages"
    }
}

class InstalledAppRepository(private val context: Context) {
    fun loadLaunchableApps(): List<SplitTunnelInstalledApp> {
        val packageManager = context.packageManager
        val installed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstalledApplications(0)
        }

        return installed
            .mapNotNull { info ->
                val packageName = info.packageName?.takeIf { it.isNotBlank() }
                    ?: return@mapNotNull null
                if (packageName == context.packageName) return@mapNotNull null
                SplitTunnelInstalledApp(
                    packageName = packageName,
                    label = info.loadLabel(packageManager)?.toString()?.takeIf { it.isNotBlank() }
                        ?: packageName,
                )
            }
            .sortedWith(
                compareBy<SplitTunnelInstalledApp> { it.label.lowercase(Locale.getDefault()) }
                    .thenBy { it.packageName },
            )
    }
}
