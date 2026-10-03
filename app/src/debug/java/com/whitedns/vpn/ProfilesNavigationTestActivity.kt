package com.whitedns.vpn

/** Real MainActivity navigation under a non-exported, software-rendered debug host. */
class ProfilesNavigationTestActivity : MainActivity() {
    // UI regressions must be independent of live GitHub releases and startup dialogs.
    override fun checkForUpdates() = Unit
}
