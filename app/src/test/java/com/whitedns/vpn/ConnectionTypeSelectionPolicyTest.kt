package com.whitedns.vpn

import org.junit.Assert.assertEquals
import org.junit.Test

class ConnectionTypeSelectionPolicyTest {
    @Test
    fun filtersOneOrMoreTypesAndTreatsAllTypesAsUnrestricted() {
        val profiles = listOf(
            profile("VLESS", 1),
            profile(" trojan ", 2),
            profile("ss", 3),
        )

        assertEquals(listOf("ss", "trojan", "vless"), ConnectionTypeSelectionPolicy.availableTypes(profiles))
        assertEquals(
            listOf(1, 2),
            ConnectionTypeSelectionPolicy.filterProfiles(profiles, setOf("vless", "TROJAN"))
                .map { it.port },
        )
        assertEquals(
            emptySet<String>(),
            ConnectionTypeSelectionPolicy.restrictedTypes(setOf("vless", "trojan", "ss"), profiles),
        )
    }

    private fun profile(type: String, port: Int) = ConnectionProfile(
        tag = "$type-$port",
        type = type,
        server = "example.com",
        port = port,
        transport = "",
        validationHost = "example.com",
    )
}
