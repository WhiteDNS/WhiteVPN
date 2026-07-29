package com.whitedns.vpn

import java.util.Locale

data class ConnectionCountry(
    val code: String,
    val country: String,
    val flag: String,
) {
    val label: String
        get() = "$flag $country"
}

data class LocationSelectorOption(
    val countryCode: String?,
    val label: String,
)

data class LocationFilterResult(
    val profiles: List<ConnectionProfile>,
    val selectedCountryCode: String?,
    val selectedLabel: String,
    val resetToAuto: Boolean,
)

object ConnectionLocationPolicy {
    fun selectorOptions(
        profiles: List<ConnectionProfile>,
        automaticLabel: String = "Automatic",
        displayLocale: Locale = Locale.getDefault(),
    ): List<LocationSelectorOption> {
        val countries = profiles
            .mapNotNull { profile -> countryForProfile(profile, displayLocale) }
            .distinctBy { it.code }
            .sortedWith(compareBy<ConnectionCountry> { it.country }.thenBy { it.code })
            .map { country -> country.selectorOption() }
        return listOf(LocationSelectorOption(countryCode = null, label = automaticLabel)) + countries
    }

    fun filterProfiles(
        profiles: List<ConnectionProfile>,
        selectedCountryCode: String?,
        automaticLabel: String = "Automatic",
        displayLocale: Locale = Locale.getDefault(),
    ): LocationFilterResult {
        val automatic = LocationSelectorOption(countryCode = null, label = automaticLabel)
        val countryCode = normalizeCountryCode(selectedCountryCode)
            ?: return LocationFilterResult(
                profiles = profiles,
                selectedCountryCode = null,
                selectedLabel = automatic.label,
                resetToAuto = false,
            )
        val selectedCountry = countryFromCode(countryCode, displayLocale)
        val selectedProfiles = profiles.filter { profile ->
            countryForProfile(profile, displayLocale)?.code == countryCode
        }
        if (selectedProfiles.isEmpty()) {
            return LocationFilterResult(
                profiles = profiles,
                selectedCountryCode = null,
                selectedLabel = automatic.label,
                resetToAuto = true,
            )
        }
        return LocationFilterResult(
            profiles = selectedProfiles,
            selectedCountryCode = countryCode,
            selectedLabel = selectedCountry?.label ?: countryCode,
            resetToAuto = false,
        )
    }

    fun optionForCode(
        countryCode: String?,
        displayLocale: Locale = Locale.getDefault(),
    ): LocationSelectorOption? {
        return countryFromCode(countryCode, displayLocale)?.selectorOption()
    }

    fun countryForProfile(
        profile: ConnectionProfile,
        displayLocale: Locale = Locale.getDefault(),
    ): ConnectionCountry? {
        return countryFromText(displayLocale, profile.tag, profile.server)
    }

    fun countryFromText(vararg parts: String): ConnectionCountry? {
        return countryFromText(Locale.getDefault(), *parts)
    }

    private fun countryFromText(displayLocale: Locale, vararg parts: String): ConnectionCountry? {
        val source = parts.joinToString(" ")
        extractRegionalFlagCode(source)?.let { code ->
            countryFromCode(code, displayLocale)?.let { return it }
        }
        extractProfileCodeToken(source)?.let { code ->
            countryFromCode(code, displayLocale)?.let { return it }
        }

        val normalized = source
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9]+"), " ")
            .trim()
        val searchable = " $normalized "
        COUNTRY_MATCHERS.firstOrNull { matcher ->
            matcher.tokens.any { token -> searchable.contains(" $token ") }
        }?.let { matcher ->
            return countryFromCode(matcher.code, displayLocale)
        }

        return null
    }

    fun countryFromCode(
        rawCode: String?,
        displayLocale: Locale = Locale.getDefault(),
    ): ConnectionCountry? {
        val code = normalizeCountryCode(rawCode) ?: return null
        val country = Locale.Builder()
            .setRegion(code)
            .build()
            .getDisplayCountry(displayLocale)
            .ifBlank { return null }
        return ConnectionCountry(
            code = code,
            country = country,
            flag = flagForCode(code),
        )
    }

    fun normalizeCountryCode(rawCode: String?): String? {
        val code = rawCode
            ?.trim()
            ?.uppercase(Locale.US)
            ?.takeIf { it.length == 2 && it.all { char -> char in 'A'..'Z' } }
            ?: return null
        return COUNTRY_CODE_ALIASES[code] ?: code
    }

    private fun ConnectionCountry.selectorOption(): LocationSelectorOption {
        return LocationSelectorOption(countryCode = code, label = label)
    }

    private fun extractRegionalFlagCode(value: String): String? {
        val codePoints = value.codePoints().toArray()
        for (index in 0 until codePoints.lastIndex) {
            val first = codePoints[index]
            val second = codePoints[index + 1]
            if (first in REGIONAL_INDICATOR_START..REGIONAL_INDICATOR_END &&
                second in REGIONAL_INDICATOR_START..REGIONAL_INDICATOR_END
            ) {
                return buildString {
                    append((first - REGIONAL_INDICATOR_START + 'A'.code).toChar())
                    append((second - REGIONAL_INDICATOR_START + 'A'.code).toChar())
                }
            }
        }
        return null
    }

    private fun extractProfileCodeToken(value: String): String? {
        return value
            .split('|')
            .asSequence()
            .map { it.trim() }
            .mapNotNull { token ->
                PROFILE_CODE_TOKEN_REGEX.matchEntire(token)?.groupValues?.getOrNull(1)
            }
            .firstOrNull()
    }

    private fun flagForCode(code: String): String {
        val first = REGIONAL_INDICATOR_START + (code[0].code - 'A'.code)
        val second = REGIONAL_INDICATOR_START + (code[1].code - 'A'.code)
        return String(intArrayOf(first, second), 0, 2)
    }

    private data class CountryMatcher(
        val code: String,
        val tokens: Set<String>,
    )

    private const val REGIONAL_INDICATOR_START = 0x1F1E6
    private const val REGIONAL_INDICATOR_END = 0x1F1FF
    private val PROFILE_CODE_TOKEN_REGEX = Regex("([A-Z]{2})\\d+.*")
    private val COUNTRY_CODE_ALIASES = mapOf("UK" to "GB")

    private val COUNTRY_MATCHERS = listOf(
        CountryMatcher("US", setOf("us", "usa", "united states", "america")),
        CountryMatcher("DE", setOf("de", "deu", "germany")),
        CountryMatcher("GB", setOf("uk", "gb", "gbr", "united kingdom", "england", "london")),
        CountryMatcher("NL", setOf("nl", "nld", "netherlands", "holland")),
        CountryMatcher("FR", setOf("fr", "fra", "france")),
        CountryMatcher("CA", setOf("ca", "can", "canada")),
        CountryMatcher("AU", setOf("au", "aus", "australia", "sydney")),
        CountryMatcher("JP", setOf("jp", "jpn", "japan", "tokyo")),
        CountryMatcher("SG", setOf("sg", "sgp", "singapore")),
        CountryMatcher("TR", setOf("tr", "tur", "turkey")),
        CountryMatcher("IR", setOf("ir", "irn", "iran")),
        CountryMatcher("RU", setOf("ru", "rus", "russia")),
    )
}
