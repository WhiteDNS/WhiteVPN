package com.whitedns.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SplitTunnelPolicyTest {
    @Test
    fun unknownModeDefaultsToOff() {
        assertEquals(SplitTunnelMode.Off, SplitTunnelMode.fromWireName(null))
        assertEquals(SplitTunnelMode.Off, SplitTunnelMode.fromWireName("missing"))
    }

    @Test
    fun parsesKnownModeWireNames() {
        assertEquals(SplitTunnelMode.Off, SplitTunnelMode.fromWireName("off"))
        assertEquals(SplitTunnelMode.BypassSelected, SplitTunnelMode.fromWireName("bypass_selected"))
        assertEquals(SplitTunnelMode.VpnOnlySelected, SplitTunnelMode.fromWireName("vpn_only_selected"))
    }

    @Test
    fun sanitizeSettingsDedupesSortsAndRemovesSelfPackage() {
        val settings = SplitTunnelPolicy.sanitizeSettings(
            SplitTunnelSettings(
                mode = SplitTunnelMode.BypassSelected,
                selectedPackages = setOf(
                    " com.example.mail ",
                    "com.whitedns.vpn",
                    "",
                    "com.example.browser",
                    "com.example.mail",
                ),
            ),
            selfPackageName = "com.whitedns.vpn",
        )

        assertEquals(SplitTunnelMode.BypassSelected, settings.mode)
        assertEquals(
            setOf("com.example.browser", "com.example.mail"),
            settings.selectedPackages,
        )
    }

    @Test
    fun offModeDoesNotApplyAllowedOrDisallowedPackages() {
        val plan = SplitTunnelPolicy.runtimePlan(
            settings = SplitTunnelSettings(
                mode = SplitTunnelMode.Off,
                selectedPackages = setOf("com.example.mail"),
            ),
            launchablePackages = setOf("com.example.mail"),
            selfPackageName = "com.whitedns.vpn",
        )

        assertEquals(SplitTunnelMode.Off, plan.mode)
        assertEquals(listOf("com.example.mail"), plan.selectedPackages)
        assertEquals(emptyList<String>(), plan.allowedPackages)
        assertEquals(emptyList<String>(), plan.disallowedPackages)
        assertEquals(emptyList<String>(), plan.skippedPackages)
    }

    @Test
    fun bypassSelectedMapsInstalledSelectionsToDisallowedPackages() {
        val plan = SplitTunnelPolicy.runtimePlan(
            settings = SplitTunnelSettings(
                mode = SplitTunnelMode.BypassSelected,
                selectedPackages = setOf("com.example.mail", "com.example.missing"),
            ),
            launchablePackages = setOf("com.example.mail", "com.example.browser"),
            selfPackageName = "com.whitedns.vpn",
        )

        assertEquals(emptyList<String>(), plan.allowedPackages)
        assertEquals(listOf("com.example.mail"), plan.disallowedPackages)
        assertEquals(listOf("com.example.missing"), plan.skippedPackages)
    }

    @Test
    fun bypassSelectedWithEmptySelectionAppliesNoRestrictions() {
        val plan = SplitTunnelPolicy.runtimePlan(
            settings = SplitTunnelSettings(
                mode = SplitTunnelMode.BypassSelected,
                selectedPackages = emptySet(),
            ),
            launchablePackages = setOf("com.example.mail"),
            selfPackageName = "com.whitedns.vpn",
        )

        assertEquals(emptyList<String>(), plan.allowedPackages)
        assertEquals(emptyList<String>(), plan.disallowedPackages)
        assertEquals(emptyList<String>(), plan.skippedPackages)
    }

    @Test
    fun vpnOnlySelectedMapsInstalledSelectionsToAllowedPackages() {
        val plan = SplitTunnelPolicy.runtimePlan(
            settings = SplitTunnelSettings(
                mode = SplitTunnelMode.VpnOnlySelected,
                selectedPackages = setOf("com.example.mail", "com.example.missing"),
            ),
            launchablePackages = setOf("com.example.mail", "com.example.browser"),
            selfPackageName = "com.whitedns.vpn",
        )

        assertEquals(listOf("com.example.mail"), plan.allowedPackages)
        assertEquals(emptyList<String>(), plan.disallowedPackages)
        assertEquals(listOf("com.example.missing"), plan.skippedPackages)
    }

    @Test
    fun vpnOnlySelectedFailsWhenEverySelectedAppIsMissing() {
        assertThrows(SplitTunnelNoSelectedAppsException::class.java) {
            SplitTunnelPolicy.runtimePlan(
                settings = SplitTunnelSettings(
                    mode = SplitTunnelMode.VpnOnlySelected,
                    selectedPackages = setOf("com.example.missing"),
                ),
                launchablePackages = setOf("com.example.mail"),
                selfPackageName = "com.whitedns.vpn",
            )
        }
    }
}
