package com.autoshipper.catalog.proxy.carrier

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.junit5.WireMockExtension
import com.autoshipper.catalog.domain.Address
import com.autoshipper.catalog.domain.PackageDimensions
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.web.client.RestClient
import java.math.BigDecimal
import java.net.URLEncoder
import java.net.http.HttpClient
import java.nio.charset.StandardCharsets

/**
 * BR-3: URL-encoding of user-supplied values in FedExRateAdapter.fetchBearerToken().
 *
 * Per CLAUDE.md constraint #12, all user-supplied values in form-encoded request bodies
 * must be URL-encoded. This test verifies that credentials containing special characters
 * (&, =, +) are properly encoded in the OAuth token request body.
 *
 * Tests will FAIL until URL-encoding is added in Phase 5 — the current implementation
 * uses raw string interpolation.
 */
class FedExRateAdapterUrlEncodingTest {

    companion object {
        @JvmField
        @RegisterExtension
        val wireMock: WireMockExtension = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build()
    }

    private val origin = Address(
        street = "123 Main St",
        city = "New York",
        stateOrProvince = "NY",
        postalCode = "10001",
        countryCode = "US"
    )

    private val destination = Address(
        street = "456 Oak Ave",
        city = "Los Angeles",
        stateOrProvince = "CA",
        postalCode = "90001",
        countryCode = "US"
    )

    private val dims = PackageDimensions(
        lengthCm = BigDecimal("30"),
        widthCm = BigDecimal("20"),
        heightCm = BigDecimal("15"),
        weightKg = BigDecimal("2.5")
    )

    private fun createAdapter(clientId: String, clientSecret: String): FedExRateAdapter {
        val httpClient = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build()
        val requestFactory = JdkClientHttpRequestFactory(httpClient)
        val restClient = RestClient.builder()
            .baseUrl(wireMock.baseUrl())
            .requestFactory(requestFactory)
            .build()
        return FedExRateAdapter(
            fedexRestClient = restClient,
            clientId = clientId,
            clientSecret = clientSecret
        )
    }

    @Test
    fun `fetchBearerToken URL-encodes client credentials containing ampersand`() {
        val clientId = "id&with&ampersands"
        val clientSecret = "secret&value"

        // Stub token endpoint
        wireMock.stubFor(
            post(urlEqualTo("/oauth/token"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"access_token": "test-token", "expires_in": 3600}""")
                )
        )

        // Stub rate endpoint
        wireMock.stubFor(
            post(urlEqualTo("/rate/v1/rates/quotes"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {
                                "output": {
                                    "rateReplyDetails": [{
                                        "ratedShipmentDetails": [{
                                            "totalNetCharge": {"amount": "15.50"}
                                        }]
                                    }]
                                }
                            }
                        """.trimIndent())
                )
        )

        // Act — this calls fetchBearerToken() internally
        try {
            createAdapter(clientId, clientSecret).getRate(origin, destination, dims)
        } catch (_: Exception) {
            // May fail for other reasons, but we only care about the token request body
        }

        // Verify the token request body contains URL-encoded values
        val encodedId = URLEncoder.encode(clientId, StandardCharsets.UTF_8)
        val encodedSecret = URLEncoder.encode(clientSecret, StandardCharsets.UTF_8)

        wireMock.verify(
            postRequestedFor(urlEqualTo("/oauth/token"))
                .withRequestBody(containing("client_id=$encodedId"))
                .withRequestBody(containing("client_secret=$encodedSecret"))
        )
    }

    @Test
    fun `fetchBearerToken URL-encodes client credentials containing equals sign`() {
        val clientId = "id=with=equals"
        val clientSecret = "secret=value"

        wireMock.stubFor(
            post(urlEqualTo("/oauth/token"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"access_token": "test-token", "expires_in": 3600}""")
                )
        )

        wireMock.stubFor(
            post(urlEqualTo("/rate/v1/rates/quotes"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {
                                "output": {
                                    "rateReplyDetails": [{
                                        "ratedShipmentDetails": [{
                                            "totalNetCharge": {"amount": "15.50"}
                                        }]
                                    }]
                                }
                            }
                        """.trimIndent())
                )
        )

        try {
            createAdapter(clientId, clientSecret).getRate(origin, destination, dims)
        } catch (_: Exception) {
            // May fail for other reasons
        }

        val encodedId = URLEncoder.encode(clientId, StandardCharsets.UTF_8)
        val encodedSecret = URLEncoder.encode(clientSecret, StandardCharsets.UTF_8)

        wireMock.verify(
            postRequestedFor(urlEqualTo("/oauth/token"))
                .withRequestBody(containing("client_id=$encodedId"))
                .withRequestBody(containing("client_secret=$encodedSecret"))
        )
    }
}
