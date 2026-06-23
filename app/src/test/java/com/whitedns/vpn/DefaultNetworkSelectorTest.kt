package com.whitedns.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DefaultNetworkSelectorTest {
    @Test
    fun selectorIgnoresVpnAndChoosesValidatedWifi() {
        val selected = DefaultNetworkSelector.choose(
            listOf(
                candidate(name = "tun0", index = 9, isVpn = true, isValidated = true),
                candidate(name = "rmnet0", index = 6, isCellular = true, isValidated = true),
                candidate(name = "wlan0", index = 4, isWifi = true, isValidated = true),
            ),
        )

        assertEquals("wlan0", selected?.name)
    }

    @Test
    fun selectorFallsBackToAnyNonVpnInternetNetwork() {
        val selected = DefaultNetworkSelector.choose(
            listOf(
                candidate(name = "tun0", index = 9, isVpn = true, isValidated = true),
                candidate(name = "eth0", index = 5, isEthernet = true, isValidated = false),
            ),
        )

        assertEquals("eth0", selected?.name)
    }

    @Test
    fun selectorReturnsNullWhenOnlyVpnOrInvalidNetworksExist() {
        val selected = DefaultNetworkSelector.choose(
            listOf(
                candidate(name = "tun0", index = 9, isVpn = true, isValidated = true),
                candidate(name = "", index = -1, isWifi = true, isValidated = true),
            ),
        )

        assertNull(selected)
    }

    @Test
    fun selectorReturnsNullWhenNetworksDoNotHaveInternetCapability() {
        val selected = DefaultNetworkSelector.choose(
            listOf(
                candidate(name = "wlan0", index = 4, isWifi = true, isValidated = true, hasInternet = false),
            ),
        )

        assertNull(selected)
    }

    private fun candidate(
        name: String,
        index: Int,
        hasInternet: Boolean = true,
        isVpn: Boolean = false,
        isValidated: Boolean = false,
        isWifi: Boolean = false,
        isEthernet: Boolean = false,
        isCellular: Boolean = false,
    ): DefaultNetworkCandidate {
        return DefaultNetworkCandidate(
            name = name,
            index = index,
            hasInternet = hasInternet,
            isVpn = isVpn,
            isValidated = isValidated,
            isWifi = isWifi,
            isEthernet = isEthernet,
            isCellular = isCellular,
            isExpensive = false,
            isConstrained = false,
            hasIpv6 = false,
        )
    }
}
