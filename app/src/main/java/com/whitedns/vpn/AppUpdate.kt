package com.whitedns.vpn

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL

data class AppRelease(val version: String, val url: String)

object AppUpdatePolicy {
    fun isNewer(latestVersion: String, currentVersion: String): Boolean {
        val latest = parts(latestVersion) ?: return false
        val current = parts(currentVersion) ?: return false
        for (index in 0 until maxOf(latest.size, current.size)) {
            val comparison = latest.getOrElse(index) { 0 }.compareTo(current.getOrElse(index) { 0 })
            if (comparison != 0) return comparison > 0
        }
        return false
    }

    private fun parts(version: String): List<Int>? {
        val value = version.trim().removePrefix("v").substringBefore('-').substringBefore('+')
        if (value.isBlank()) return null
        return value.split('.').map { it.toIntOrNull() ?: return null }
    }
}

object GitHubReleaseClient {
    suspend fun latest(): AppRelease = withContext(Dispatchers.IO) {
        val connection = URL(LATEST_RELEASE_URL).openConnection() as HttpURLConnection
        connection.connectTimeout = 5_000
        connection.readTimeout = 5_000
        connection.requestMethod = "GET"
        connection.setRequestProperty("Accept", "application/vnd.github+json")
        connection.setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        connection.setRequestProperty("User-Agent", "WhiteVPN/${BuildConfig.VERSION_NAME}")
        try {
            if (connection.responseCode !in 200..299) {
                throw IOException("GitHub release request failed: ${connection.responseCode}")
            }
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val version = json.getString("tag_name").trim()
            val releaseUri = URI(json.getString("html_url"))
            if (version.isBlank() || releaseUri.scheme != "https" || releaseUri.host != "github.com") {
                throw IOException("GitHub release response was invalid")
            }
            AppRelease(version, releaseUri.toString())
        } finally {
            connection.disconnect()
        }
    }

    private const val LATEST_RELEASE_URL =
        "https://api.github.com/repos/WhiteDNS/WhiteVPN/releases/latest"
}
