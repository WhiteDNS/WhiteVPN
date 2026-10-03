package com.whitedns.vpn

import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import java.io.File
import java.text.Collator
import java.util.Locale

/** Public, non-secret metadata. Cached notices are read afresh across the engine/UI processes. */
internal object PsiphonRegions {
    // RegionListPreference.java, Psiphon-Inc/psiphon-android revision
    // c2c043da1208b34d28c3fdeef72b43dcd470dacb. Availability is updated by the core.
    val knownCodes = listOf("AE", "AR", "AT", "AU", "BE", "BG", "BR", "CA", "CH", "CL", "CO", "CZ", "DE", "DK", "EE", "ES", "FI", "FR", "GB", "GR", "HK", "HU", "HR", "ID", "IE", "IN", "IS", "IT", "JP", "KE", "KR", "LT", "LV", "MX", "MY", "NL", "NO", "NZ", "PL", "PT", "RO", "RS", "SE", "SG", "SK", "TW", "UA", "US", "ZA")
    fun normalize(regions: Collection<*>): List<String> = regions.take(256).mapNotNull {
        (it as? String)?.trim()?.uppercase(Locale.US)?.takeIf { code -> code.matches(Regex("[A-Z]{2}")) }
    }.distinct().sorted()
    private fun cache(context: Context) = AtomicFile(File(context.noBackupFilesDir, "psiphon-egress-regions.json"))
    fun record(context: Context, regions: Collection<*>) {
        val codes = normalize(regions)
        if (codes.isEmpty()) return
        val file = cache(context)
        val stream = file.startWrite()
        try { stream.write(JSONArray(codes).toString().toByteArray(Charsets.UTF_8)); file.finishWrite(stream) }
        catch (error: Exception) { file.failWrite(stream); throw error }
    }
    fun options(context: Context, selected: String): List<String> {
        val available = runCatching {
            val bytes = cache(context).openRead().use { it.readAtMost(4097) }
            require(bytes.size <= 4096)
            val data = JSONArray(bytes.toString(Charsets.UTF_8))
            require(data.length() <= 256)
            normalize((0 until data.length()).map { data.opt(it) }).takeIf { it.isNotEmpty() }
        }.getOrNull() ?: knownCodes
        return options(available, selected, context.resources.configuration.locales[0])
    }
    fun options(available: Collection<String>, selected: String, locale: Locale): List<String> {
        val countries = normalize(available + selected).toMutableList()
        val collator = Collator.getInstance(locale)
        countries.sortWith { a, b -> collator.compare(name(a, locale), name(b, locale)) }
        return listOf("") + countries
    }
    fun name(code: String, locale: Locale): String = Locale("", code).getDisplayCountry(locale).ifBlank { code }
}
