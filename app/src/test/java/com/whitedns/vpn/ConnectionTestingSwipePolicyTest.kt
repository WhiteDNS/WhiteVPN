package com.whitedns.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionTestingSwipePolicyTest {
    @Test
    fun `only a deliberate physical right swipe closes the page`() {
        assertTrue(ConnectionTestingSwipePolicy.shouldClose(220f, 24f, 1080f, 3f))
        assertFalse(ConnectionTestingSwipePolicy.shouldClose(-300f, 0f, 1080f, 3f))
        assertFalse(ConnectionTestingSwipePolicy.shouldClose(220f, 300f, 1080f, 3f))
        assertFalse(ConnectionTestingSwipePolicy.shouldClose(120f, 8f, 1080f, 3f))
        assertTrue(ConnectionTestingSwipePolicy.shouldClose(-220f, 24f, 1080f, 3f, isRtl = true))
        assertFalse(ConnectionTestingSwipePolicy.shouldClose(220f, 24f, 1080f, 3f, isRtl = true))
    }
}
