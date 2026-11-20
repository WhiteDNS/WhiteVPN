package com.whitedns.vpn

import org.junit.Assert.*
import org.junit.Test

class EngineDiagnosticFailureTest {
    @Test fun nativeConfigurationAndNestedSecretsAreExcludedFromDiagnosticException() {
        val error = IllegalArgumentException("password=secret; key=private; cookie=token", RuntimeException("more-secrets"))
        val sanitized = EngineDiagnosticFailure.sanitize(error)
        assertEquals("Engine connection failed (IllegalArgumentException)", sanitized.message)
        assertNull(sanitized.cause)
        assertFalse(sanitized.stackTraceToString().contains("more-secrets"))
        assertFalse(sanitized.stackTraceToString().contains("cookie=token"))
    }
}
