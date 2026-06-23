package com.whitedns.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PrivacyPolicyAcceptancePolicyTest {
    @Test
    fun policyIsNotAcceptedByDefault() {
        assertFalse(PrivacyPolicyAcceptancePolicy.isAccepted(0))
    }

    @Test
    fun policyIsAcceptedForCurrentVersion() {
        assertTrue(
            PrivacyPolicyAcceptancePolicy.isAccepted(
                PrivacyPolicyAcceptancePolicy.CURRENT_VERSION,
            ),
        )
    }

    @Test
    fun policyBecomesUnacceptedWhenVersionChanges() {
        assertFalse(
            PrivacyPolicyAcceptancePolicy.isAccepted(
                acceptedVersion = 1,
                currentVersion = 2,
            ),
        )
    }
}
