package com.whitedns.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class FrontingIpPolicyTest {
    @Test
    fun blankClearsValue() {
        assertNull(FrontingIpPolicy.normalize(""))
        assertNull(FrontingIpPolicy.normalize("   "))
        assertNull(FrontingIpPolicy.normalize(null))
    }

    @Test
    fun validIpv4IsAcceptedAndTrimmed() {
        assertEquals("104.16.0.10", FrontingIpPolicy.normalize(" 104.16.0.10 "))
    }

    @Test
    fun commaSeparatedIpsAreAcceptedAndNormalized() {
        assertEquals(
            "104.16.0.10,104.16.0.11",
            FrontingIpPolicy.normalize(" 104.16.0.10, 104.16.0.11 "),
        )
    }

    @Test
    fun duplicateIpsAreDedupedInOrder() {
        assertEquals(
            "104.16.0.10,104.16.0.11",
            FrontingIpPolicy.normalize("104.16.0.10,104.16.0.11,104.16.0.10"),
        )
    }

    @Test
    fun fiveIpsAreAccepted() {
        assertEquals(
            "104.16.0.1,104.16.0.2,104.16.0.3,104.16.0.4,104.16.0.5",
            FrontingIpPolicy.normalize("104.16.0.1,104.16.0.2,104.16.0.3,104.16.0.4,104.16.0.5"),
        )
    }

    @Test
    fun invalidIpv4IsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            FrontingIpPolicy.normalize("999.16.0.10")
        }
    }

    @Test
    fun whitespaceSeparatedIpsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            FrontingIpPolicy.normalize("104.16.0.10 104.16.0.11")
        }
    }

    @Test
    fun moreThanFiveIpsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            FrontingIpPolicy.normalize("104.16.0.1,104.16.0.2,104.16.0.3,104.16.0.4,104.16.0.5,104.16.0.6")
        }
    }
}
