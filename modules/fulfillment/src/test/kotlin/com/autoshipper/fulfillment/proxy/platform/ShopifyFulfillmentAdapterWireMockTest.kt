package com.autoshipper.fulfillment.proxy.platform

import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.containing
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.github.tomakehurst.wiremock.junit5.WireMockExtension
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.RegisterExtension
import org.springframework.web.client.HttpClientErrorException
import org.springframework.web.client.RestClient

class ShopifyFulfillmentAdapterWireMockTest {

    companion object {
        @JvmField
        @RegisterExtension
        val wireMock: WireMockExtension = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build()

        private const val GRAPHQL_ENDPOINT = "/admin/api/2024-01/graphql.json"
    }

    private fun loadFixture(path: String): String =
        this::class.java.classLoader
            .getResource(path)
            ?.readText()
            ?: throw IllegalArgumentException("Fixture not found: $path")

    private fun adapter(
        baseUrl: String = wireMock.baseUrl(),
        accessToken: String = "test-shopify-access-token"
    ): ShopifyFulfillmentAdapter {
        val restClient = RestClient.builder()
            .baseUrl(baseUrl)
            .build()
        return ShopifyFulfillmentAdapter(
            shopifyRestClient = restClient,
            accessToken = accessToken
        )
    }

    @Test
    fun `successful fulfillment creation returns true`() {
        wireMock.stubFor(
            post(urlEqualTo(GRAPHQL_ENDPOINT))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(loadFixture("wiremock/shopify/fulfillment-create-success.json"))
                )
        )

        val result = adapter().createFulfillment(
            shopifyOrderGid = "gid://shopify/Order/12345",
            trackingNumber = "1Z999AA10123456784",
            carrier = "UPS"
        )

        assertThat(result).isTrue()
    }

    @Test
    fun `request body contains fulfillmentCreateV2 mutation with correct variables`() {
        wireMock.stubFor(
            post(urlEqualTo(GRAPHQL_ENDPOINT))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(loadFixture("wiremock/shopify/fulfillment-create-success.json"))
                )
        )

        adapter().createFulfillment(
            shopifyOrderGid = "gid://shopify/Order/67890",
            trackingNumber = "1Z999AA10123456784",
            carrier = "UPS"
        )

        wireMock.verify(
            postRequestedFor(urlEqualTo(GRAPHQL_ENDPOINT))
                .withHeader("X-Shopify-Access-Token", equalTo("test-shopify-access-token"))
                .withHeader("Content-Type", containing("application/json"))
                .withRequestBody(containing("fulfillmentCreateV2"))
                .withRequestBody(containing("1Z999AA10123456784"))
                .withRequestBody(containing("UPS"))
                .withRequestBody(containing("gid://shopify/Order/67890"))
                .withRequestBody(containing("notifyCustomer"))
        )
    }

    @Test
    fun `request includes X-Shopify-Access-Token header`() {
        wireMock.stubFor(
            post(urlEqualTo(GRAPHQL_ENDPOINT))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(loadFixture("wiremock/shopify/fulfillment-create-success.json"))
                )
        )

        adapter().createFulfillment(
            shopifyOrderGid = "gid://shopify/Order/12345",
            trackingNumber = "1Z999AA10123456784",
            carrier = "UPS"
        )

        wireMock.verify(
            postRequestedFor(urlEqualTo(GRAPHQL_ENDPOINT))
                .withHeader("X-Shopify-Access-Token", equalTo("test-shopify-access-token"))
        )
    }

    @Test
    fun `userErrors response returns false`() {
        wireMock.stubFor(
            post(urlEqualTo(GRAPHQL_ENDPOINT))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(loadFixture("wiremock/shopify/fulfillment-create-user-errors.json"))
                )
        )

        val result = adapter().createFulfillment(
            shopifyOrderGid = "gid://shopify/Order/12345",
            trackingNumber = "1Z999AA10123456784",
            carrier = "UPS"
        )

        assertThat(result).isFalse()
    }

    @Test
    fun `blank access token returns false without HTTP call`() {
        val blankAdapter = adapter(accessToken = "")

        val result = blankAdapter.createFulfillment(
            shopifyOrderGid = "gid://shopify/Order/12345",
            trackingNumber = "1Z999AA10123456784",
            carrier = "UPS"
        )

        assertThat(result).isFalse()

        // Verify no HTTP requests were made to WireMock
        wireMock.verify(0, postRequestedFor(urlEqualTo(GRAPHQL_ENDPOINT)))
    }

    @Test
    fun `HTTP 401 throws exception`() {
        wireMock.stubFor(
            post(urlEqualTo(GRAPHQL_ENDPOINT))
                .willReturn(
                    aResponse()
                        .withStatus(401)
                        .withHeader("Content-Type", "application/json")
                        .withBody(loadFixture("wiremock/shopify/fulfillment-create-auth-error.json"))
                )
        )

        assertThrows<HttpClientErrorException.Unauthorized> {
            adapter().createFulfillment(
                shopifyOrderGid = "gid://shopify/Order/12345",
                trackingNumber = "1Z999AA10123456784",
                carrier = "UPS"
            )
        }
    }

    @Test
    fun `null fulfillment with empty userErrors returns false`() {
        wireMock.stubFor(
            post(urlEqualTo(GRAPHQL_ENDPOINT))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(loadFixture("wiremock/shopify/fulfillment-create-null-fulfillment.json"))
                )
        )

        val result = adapter().createFulfillment(
            shopifyOrderGid = "gid://shopify/Order/12345",
            trackingNumber = "1Z999AA10123456784",
            carrier = "UPS"
        )

        assertThat(result).isFalse()
    }

    @Test
    fun `top-level GraphQL errors response returns false`() {
        wireMock.stubFor(
            post(urlEqualTo(GRAPHQL_ENDPOINT))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(loadFixture("wiremock/shopify/fulfillment-create-auth-error.json"))
                )
        )

        val result = adapter().createFulfillment(
            shopifyOrderGid = "gid://shopify/Order/12345",
            trackingNumber = "1Z999AA10123456784",
            carrier = "UPS"
        )

        assertThat(result).isFalse()
    }
}
