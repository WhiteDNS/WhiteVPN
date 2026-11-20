package com.whitedns.vpn

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class PsiphonRegionsTest {
    @Test fun officialSeedHasUniqueCountryCodesAndNoAutomaticSentinel() {
        assertEquals(49, PsiphonRegions.knownCodes.size)
        assertEquals(49, PsiphonRegions.knownCodes.toSet().size)
        assertTrue(PsiphonRegions.knownCodes.all { it.matches(Regex("[A-Z]{2}")) })
        assertTrue(PsiphonRegions.knownCodes.containsAll(listOf("US", "GB", "DE", "CA")))
    }
    @Test fun noticesNormalizeCodesAndDiscardInvalidMetadata() {
        assertEquals(listOf("DE", "GB", "US"), PsiphonRegions.normalize(listOf(" us ", "gb", "DE", "US", "", "USA", "../", 123, null)))
    }
    @Test fun automaticIsAlwaysFirstAndStoredUnknownRegionIsPreserved() {
        val options = PsiphonRegions.options(listOf("US", "DE"), "XX", Locale.ENGLISH)
        assertEquals("", options.first())
        assertEquals(setOf("", "US", "DE", "XX"), options.toSet())
    }
    @Test fun legacyLowercaseSelectionIsCanonicalizedWithoutDuplicateOptions() {
        assertEquals(listOf("", "US"), PsiphonRegions.options(listOf("US"), " us ", Locale.ENGLISH))
    }
    @Test fun countryLabelsFollowTheApplicationLanguage() {
        assertEquals("Germany", PsiphonRegions.name("DE", Locale.ENGLISH))
        assertNotEquals("DE", PsiphonRegions.name("DE", Locale.forLanguageTag("fa")))
        assertNotEquals(PsiphonRegions.name("DE", Locale.ENGLISH), PsiphonRegions.name("DE", Locale.forLanguageTag("fa")))
    }
    @Test fun countriesAreSortedByDisplayNameInTheCurrentLanguage() {
        val locale = Locale.ENGLISH
        val options = PsiphonRegions.options(listOf("US", "DE", "CA"), "", locale)
        assertEquals(listOf("", "CA", "DE", "US"), options)
    }
}
