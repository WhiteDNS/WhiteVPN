package com.whitedns.vpn

import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LibboxNetworkAddressFormatterTest {
    @Test
    fun stripsIpv6ScopeBeforePassingAddressToLibbox() {
        val address = InetAddress.getByName("fe80::1%1")

        assertEquals(
            "fe80:0:0:0:0:0:0:1/64",
            LibboxNetworkAddressFormatter.format(address, 64),
        )
    }

    @Test
    fun rejectsInvalidPrefixLengths() {
        assertNull(
            LibboxNetworkAddressFormatter.format(
                InetAddress.getByName("192.0.2.1"),
                64,
            ),
        )
        assertNull(
            LibboxNetworkAddressFormatter.format(
                InetAddress.getByName("2001:db8::1"),
                129,
            ),
        )
    }
}
