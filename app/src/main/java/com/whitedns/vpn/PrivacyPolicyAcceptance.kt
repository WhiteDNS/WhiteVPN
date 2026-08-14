package com.whitedns.vpn

import android.content.Context

object PrivacyPolicyAcceptancePolicy {
    const val CURRENT_VERSION = 1

    fun isAccepted(acceptedVersion: Int, currentVersion: Int = CURRENT_VERSION): Boolean {
        return acceptedVersion == currentVersion
    }
}

class PrivacyPolicyAcceptanceStore(context: Context) {
    private val prefs = context.getSharedPreferences("white_dns_privacy_policy", Context.MODE_PRIVATE)

    fun isAccepted(): Boolean {
        return PrivacyPolicyAcceptancePolicy.isAccepted(
            prefs.getInt(KEY_ACCEPTED_VERSION, VERSION_NOT_ACCEPTED),
        )
    }

    fun acceptCurrentVersion() {
        prefs.edit()
            .putInt(KEY_ACCEPTED_VERSION, PrivacyPolicyAcceptancePolicy.CURRENT_VERSION)
            .apply()
    }

    private companion object {
        const val KEY_ACCEPTED_VERSION = "accepted_policy_version"
        const val VERSION_NOT_ACCEPTED = 0
    }
}
