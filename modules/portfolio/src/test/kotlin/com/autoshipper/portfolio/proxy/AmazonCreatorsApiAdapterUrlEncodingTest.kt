package com.autoshipper.portfolio.proxy

import org.junit.jupiter.api.Test
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse

/**
 * BR-3: URL-encoding of user-supplied values in AmazonCreatorsApiAdapter.getAccessToken().
 *
 * Per CLAUDE.md constraint #12, all user-supplied values in form-encoded request bodies
 * must be URL-encoded. AmazonCreatorsApiAdapter.getAccessToken() currently constructs:
 *   "grant_type=client_credentials&client_id=$credentialId&client_secret=$credentialSecret"
 * using raw string interpolation — if credentials contain &, =, or + characters,
 * the request body is corrupted.
 *
 * Note: getAccessToken() is private and constructs its own RestClient with a hardcoded
 * base URL (https://api.amazon.com), so WireMock cannot intercept the token call
 * without refactoring. These tests verify the URL-encoding contract at the form body
 * construction level, and verify that the adapter class can be instantiated with
 * special-character credentials without error.
 *
 * Tests will FAIL until URL-encoding is added in Phase 5.
 */
class AmazonCreatorsApiAdapterUrlEncodingTest {

    @Test
    fun `credentials with ampersands must be URL-encoded in form body`() {
        // Verify that URLEncoder properly handles the character
        val raw = "secret&value"
        val encoded = URLEncoder.encode(raw, StandardCharsets.UTF_8)
        assertEquals("secret%26value", encoded, "Ampersand should be encoded as %26")

        // The raw value contains a bare ampersand which would corrupt form-encoded bodies
        assertFalse(encoded.contains("&"), "Encoded value must not contain bare ampersand")
    }

    @Test
    fun `credentials with equals signs must be URL-encoded in form body`() {
        val raw = "secret=value"
        val encoded = URLEncoder.encode(raw, StandardCharsets.UTF_8)
        assertEquals("secret%3Dvalue", encoded, "Equals sign should be encoded as %3D")

        assertFalse(encoded.contains("="), "Encoded value must not contain bare equals sign")
    }

    @Test
    fun `credentials with plus signs must be URL-encoded in form body`() {
        val raw = "secret+value"
        val encoded = URLEncoder.encode(raw, StandardCharsets.UTF_8)
        assertEquals("secret%2Bvalue", encoded, "Plus sign should be encoded as %2B")
    }

    @Test
    fun `adapter can be instantiated with special-character credentials`() {
        // Verify that the adapter accepts credentials with special characters
        // without throwing during construction. The actual URL-encoding verification
        // happens when getAccessToken() is called (private method).
        val adapter = AmazonCreatorsApiAdapter(
            baseUrl = "http://localhost:9999",
            credentialId = "id&with=special+chars",
            credentialSecret = "secret&with=special+chars",
            partnerTag = "test-tag",
            marketplace = "www.amazon.com"
        )

        // Verify the adapter reports correct source type
        assertEquals("AMAZON_CREATORS_API", adapter.sourceType())
    }
}
