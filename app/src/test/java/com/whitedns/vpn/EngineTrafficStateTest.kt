package com.whitedns.vpn

import org.junit.Assert.*
import org.junit.Test

class EngineTrafficStateTest {
    @Test fun parsesNativeProxyRatesInBytesPerSecond() {
        assertEquals(EngineTrafficRate(4096, 8192), EngineTrafficRate.parse("{\"up\":4096,\"down\":8192}"))
    }
    @Test fun rejectsInvalidAndNegativeCounters() {
        for (json in listOf("{}", "{\"up\":-1,\"down\":0}", "{\"up\":\"wrong\",\"down\":0}")) {
            assertThrows(Exception::class.java) { EngineTrafficRate.parse(json) }
        }
    }
    @Test fun oldLeaseCannotPublishOrClearReplacementMetrics() {
        val state = EngineTrafficRegistry()
        state.begin("old", "first")
        state.begin("new", "second")
        assertFalse(state.publish("old", EngineTrafficRate(1, 2), 10))
        assertTrue(state.publish("new", EngineTrafficRate(3, 4), 10))
        state.clear("old")
        assertEquals(EngineTrafficRate(3, 4), state.read("second", 11))
        assertNull(state.read("first", 11))
    }
    @Test fun expiredOutOfOrderAndDisconnectedSamplesCannotShowSpeed() {
        val state = EngineTrafficRegistry()
        state.begin("lease", "profile")
        assertNull(state.read("profile", 1))
        assertTrue(state.publish("lease", EngineTrafficRate(3, 4), 100))
        assertFalse(state.publish("lease", EngineTrafficRate(100, 200), 99))
        assertNull(state.read("profile", 5101))
        assertNull(state.read("profile", 99))
        state.clear("lease")
        assertNull(state.read("profile", 101))
    }
}
