package com.whitedns.vpn

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ConnectionLocationPolicyTest {
    @Test
    fun extractsCountryFromScreenshotStyleProfileTags() {
        val france = ConnectionLocationPolicy.countryForProfile(profile("\uD83C\uDDEB\uD83C\uDDF7 | @WhiteDNS | FR6|5.3MB/s|GPT+-F..."))
        val netherlands = ConnectionLocationPolicy.countryForProfile(profile("\uD83C\uDDF3\uD83C\uDDF1 | @WhiteDNS | NL1|16.4MB/s|GPT+-N..."))
        val canada = ConnectionLocationPolicy.countryForProfile(profile("\uD83C\uDDE8\uD83C\uDDE6 | @WhiteDNS | CA2|2.7MB/s|GPT+-C..."))

        assertEquals("FR", france?.code)
        assertEquals("فرانسه", france?.country)
        assertEquals("\uD83C\uDDEB\uD83C\uDDF7 فرانسه", france?.label)
        assertEquals("NL", netherlands?.code)
        assertEquals("هلند", netherlands?.country)
        assertEquals("CA", canada?.code)
        assertEquals("کانادا", canada?.country)
    }

    @Test
    fun extractsCountryFromProfileCodeTokenWithoutFlag() {
        val australia = ConnectionLocationPolicy.countryForProfile(profile("| @WhiteDNS | AU2|1.0MB/s|GPT+-A..."))

        assertEquals("AU", australia?.code)
        assertEquals("استرالیا", australia?.country)
        assertEquals("\uD83C\uDDE6\uD83C\uDDFA استرالیا", australia?.label)
    }

    @Test
    fun selectorLabelsOnlyShowAutoOrFlagAndCountry() {
        val options = ConnectionLocationPolicy.selectorOptions(
            listOf(
                profile("\uD83C\uDDEB\uD83C\uDDF7 | @WhiteDNS | FR6|5.3MB/s|GPT+-F..."),
                profile("\uD83C\uDDEB\uD83C\uDDF7 | @WhiteDNS | FR7|12.9MB/s|GPT+-F..."),
                profile("\uD83C\uDDF3\uD83C\uDDF1 | @WhiteDNS | NL1|16.4MB/s|GPT+-N..."),
                profile("\uD83C\uDDE8\uD83C\uDDE6 | @WhiteDNS | CA2|2.7MB/s|GPT+-C..."),
            ),
        )

        assertEquals(
            listOf(
                "خودکار",
                "\uD83C\uDDEB\uD83C\uDDF7 فرانسه",
                "\uD83C\uDDF3\uD83C\uDDF1 هلند",
                "\uD83C\uDDE8\uD83C\uDDE6 کانادا",
            ),
            options.map { it.label },
        )
        options.forEach { option ->
            assertFalse(option.label.contains("@WhiteDNS"))
            assertFalse(option.label.contains("MB/s"))
            assertFalse(option.label.contains("|"))
            assertFalse(Regex("[A-Z]{2}\\d+").containsMatchIn(option.label))
        }
    }

    @Test
    fun autoKeepsAllProfilesAndSelectedCountryFiltersProfiles() {
        val france = profile("\uD83C\uDDEB\uD83C\uDDF7 | @WhiteDNS | FR6|5.3MB/s|GPT+-F...")
        val netherlands = profile("\uD83C\uDDF3\uD83C\uDDF1 | @WhiteDNS | NL1|16.4MB/s|GPT+-N...")
        val unknown = profile("@WhiteDNS | Fast|5.3MB/s")
        val profiles = listOf(france, netherlands, unknown)

        val auto = ConnectionLocationPolicy.filterProfiles(profiles, selectedCountryCode = null)
        val selectedFrance = ConnectionLocationPolicy.filterProfiles(profiles, selectedCountryCode = "FR")
        val missingAustralia = ConnectionLocationPolicy.filterProfiles(profiles, selectedCountryCode = "AU")

        assertEquals(profiles, auto.profiles)
        assertNull(auto.selectedCountryCode)
        assertFalse(auto.resetToAuto)
        assertEquals(listOf(france), selectedFrance.profiles)
        assertEquals("FR", selectedFrance.selectedCountryCode)
        assertEquals("\uD83C\uDDEB\uD83C\uDDF7 فرانسه", selectedFrance.selectedLabel)
        assertEquals(profiles, missingAustralia.profiles)
        assertNull(missingAustralia.selectedCountryCode)
        assertTrue(missingAustralia.resetToAuto)
    }

    private fun profile(tag: String): ConnectionProfile {
        return ConnectionProfile(
            tag = tag,
            type = "vless",
            server = "example.com",
            port = 443,
            transport = "ws",
            validationHost = "example.com",
            fingerprint = tag,
            outboundJson = "{}",
        )
    }
}
