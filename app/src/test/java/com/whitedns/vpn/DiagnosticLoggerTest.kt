package com.whitedns.vpn

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticLoggerTest {
    @Test
    fun copiedDiagnosticsRedactCredentialsFromMessagesAndErrors() {
        val raw = """
            vless://user-secret@example.com:443?token=query-secret
            https://alice:url-password-secret@example.com/path?api_key=query-secret
            Authorization: Bearer header-secret
            Authorization: Digest username="digest-user", response="digest-secret"
            private-key: private-secret
            client_secret=json-secret
            password: "two word, quoted-secret"
            token = 'single quoted secret'
        """.trimIndent()

        val sanitized = DiagnosticLogger.run { raw.sanitizeForLog() }

        listOf(
            "user-secret",
            "url-password-secret",
            "query-secret",
            "header-secret",
            "digest-user",
            "digest-secret",
            "private-secret",
            "json-secret",
            "two word, quoted-secret",
            "single quoted secret",
        ).forEach { secret -> assertFalse(sanitized.contains(secret)) }
        assertTrue(sanitized.contains("<redacted-uri>"))
        assertTrue(sanitized.contains("private-key: <redacted>"))
    }
}
