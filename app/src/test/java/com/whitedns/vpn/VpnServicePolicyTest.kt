package com.whitedns.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VpnServicePolicyTest {
    @Test
    fun systemStartedServiceConnectsWhileAppActionsStayExplicit() {
        assertEquals(Actions.CONNECT, Actions.resolveServiceAction(null, appInitiated = false))
        assertEquals(Actions.DISCONNECT, Actions.resolveServiceAction(Actions.DISCONNECT, appInitiated = true))
    }

    @Test
    fun postConnectRecoveryRequiresConsecutiveFailuresAndCooldown() {
        val lastRecovery = 1_000L

        assertFalse(PostConnectHealthPolicy.shouldRecover(1, Long.MAX_VALUE, 0L))
        assertTrue(PostConnectHealthPolicy.shouldRecover(2, 2_000L, 0L))
        assertFalse(PostConnectHealthPolicy.shouldRecover(2, lastRecovery + 1L, lastRecovery))
        assertTrue(
            PostConnectHealthPolicy.shouldRecover(
                2,
                lastRecovery + PostConnectHealthPolicy.RECOVERY_COOLDOWN_MS,
                lastRecovery,
            ),
        )
    }
}
