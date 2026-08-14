package com.whitedns.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class MihomoConfigParserTest {
    @Test
    fun extractsProfilesAndGroupsFromMihomoYaml() {
        val snapshot = MihomoConfigParser.parse(FIXTURE, fetchedAt = 123L)

        assertEquals(2, snapshot.catalog.profiles.size)
        assertEquals(6, snapshot.summary.groups.size)
        assertEquals("\uD83C\uDDFA\uD83C\uDDF8 | @WhiteDNS | US1|10MB/s", snapshot.catalog.profiles[0].tag)
        assertEquals("vless", snapshot.catalog.profiles[0].type)
        assertEquals("203.0.113.1", snapshot.catalog.profiles[0].server)
        assertEquals(443, snapshot.catalog.profiles[0].port)
        assertEquals(true, snapshot.catalog.profiles[0].echEnabled)
        assertEquals(true, snapshot.catalog.profiles[0].echCapable)
        assertEquals(true, snapshot.catalog.profiles[1].echEnabled)
        assertEquals(true, snapshot.catalog.profiles[1].echCapable)
        assertEquals("US", ConnectionLocationPolicy.countryForProfile(snapshot.catalog.profiles[0])?.code)
        assertEquals(123L, snapshot.catalog.fetchedAt)
    }

    @Test
    fun mapsAutoAndCountryToMihomoGroups() {
        val summary = MihomoConfigParser.parseSummary(FIXTURE)

        val auto = MihomoSelectionPolicy.desiredSelection(summary, selectedCountryCode = null)
        val unitedStates = MihomoSelectionPolicy.desiredSelection(summary, selectedCountryCode = "US")

        assertNotNull(auto)
        assertEquals("\uD83D\uDE80 Proxy Select", auto?.selectorGroup)
        assertEquals("\u267B\uFE0F Auto Select", auto?.selectedGroup)
        assertEquals("\uD83D\uDE80 Proxy Select", unitedStates?.selectorGroup)
        assertEquals("\uD83C\uDDFA\uD83C\uDDF8 United States Nodes", unitedStates?.selectedGroup)
    }

    @Test
    fun selectedCountryAlsoTargetsWhiteDnsTrafficGroup() {
        val summary = MihomoConfigParser.parseSummary(FIXTURE)

        val selections = MihomoSelectionPolicy.desiredSelections(summary, selectedCountryCode = "US")

        assertEquals(
            listOf(
                MihomoGroupSelection("\uD83D\uDE80 Proxy Select", "\uD83C\uDDFA\uD83C\uDDF8 United States Nodes"),
                MihomoGroupSelection("\uD83D\uDE80 WhiteDNS Proxy", "\uD83C\uDFC1Countries"),
                MihomoGroupSelection("\uD83C\uDFC1Countries", "\uD83C\uDDFA\uD83C\uDDF8 United States Nodes"),
            ),
            selections,
        )
        assertEquals("\uD83D\uDE80 WhiteDNS Proxy", MihomoSelectionPolicy.trafficProbeGroup(summary)?.name)
    }

    private companion object {
        private val FIXTURE = """
            proxies:
              - name: "\U0001F1FA\U0001F1F8 | @WhiteDNS | US1|10MB/s"
                type: vless
                server: 203.0.113.1
                port: 443
                tls: true
                ech-opts:
                  enable: true
              - name: "\U0001F1E9\U0001F1EA | @WhiteDNS | DE1|9MB/s"
                type: trojan
                server: de.example.com
                port: 8443
                ech-opts: {'enable': true}
            proxy-groups:
              - name: "\U0001F680 Proxy Select"
                type: select
                proxies:
                  - "\U0001F504 Auto Select"
              - name: "\U0000267B\U0000FE0F Auto Select"
                type: url-test
              - name: "\U0001F1FA\U0001F1F8 United States Nodes"
                type: url-test
              - name: "\U0001F1E9\U0001F1EA Germany Nodes"
                type: url-test
              - name: "\U0001F680 WhiteDNS Proxy"
                type: select
                proxies:
                  - "\U0001F3C1Countries"
              - name: "\U0001F3C1Countries"
                type: select
                proxies:
                  - "\U0001F1FA\U0001F1F8 United States Nodes"
        """.trimIndent()
    }
}
