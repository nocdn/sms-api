package com.example.smsapi

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackendConfigTest {

    @Test
    fun normalizeBackendBaseUrl_addsHttpsWhenSchemeIsMissing() {
        assertEquals(
            "https://example.com",
            BackendConfig.normalizeBackendBaseUrl("example.com"),
        )
    }

    @Test
    fun normalizeBackendBaseUrl_removesKnownEndpointSuffixesAndRepeatedSlashes() {
        assertEquals(
            "https://example.com/base",
            BackendConfig.normalizeBackendBaseUrl(" https://example.com//base///api/messages/sync/ "),
        )
    }

    @Test
    fun normalizeBackendBaseUrl_removesHealthEndpointSuffix() {
        assertEquals(
            "http://localhost:3000",
            BackendConfig.normalizeBackendBaseUrl("http://localhost:3000/health/"),
        )
    }

    @Test
    fun isValidBackendBaseUrl_acceptsNormalizedUrl() {
        val normalized = BackendConfig.normalizeBackendBaseUrl("example.com/api/messages")

        assertTrue(BackendConfig.isValidBackendBaseUrl(normalized))
    }
}
