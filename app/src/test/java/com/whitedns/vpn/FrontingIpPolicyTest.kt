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
    fun ipv4WithPortIsAcceptedAndPreserved() {
        assertEquals("104.16.0.10:8443", FrontingIpPolicy.normalize(" 104.16.0.10:8443 "))
        assertEquals(8443, FrontingIpPolicy.parseEndpoint("104.16.0.10:8443").port)
    }

    @Test
    fun explicitPortMatchTakesPriorityOverIpOnlyFallback() {
        val values = listOf("104.16.0.10", "104.16.0.10:8443")

        assertEquals("104.16.0.10:8443", FrontingIpPolicy.matchingValue(values, "104.16.0.10", 8443))
        assertEquals(8443, FrontingIpPolicy.explicitPortFor(values, "104.16.0.10", 8443))
    }

    @Test
    fun bracketedIpv6WithPortIsAcceptedAndPreserved() {
        assertEquals("[2606:4700:4700::1111]:443", FrontingIpPolicy.normalize("[2606:4700:4700::1111]:443"))
    }

    @Test
    fun invalidPortIsRejected() {
        listOf("104.16.0.10:", "104.16.0.10:0", "104.16.0.10:65536").forEach { value ->
            assertThrows(IllegalArgumentException::class.java) { FrontingIpPolicy.normalize(value) }
        }
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
