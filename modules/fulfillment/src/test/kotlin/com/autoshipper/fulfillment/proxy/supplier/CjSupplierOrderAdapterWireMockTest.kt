package com.autoshipper.fulfillment.proxy.supplier

import com.autoshipper.fulfillment.domain.ShippingAddress
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

class CjSupplierOrderAdapterWireMockTest {

    companion object {
        @JvmField
        @RegisterExtension
        val wireMock: WireMockExtension = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build()

        private const val ORDER_ENDPOINT = "/api2.0/v1/shopping/order/createOrderV2"
    }

    private fun adapter(
        baseUrl: String = wireMock.baseUrl(),
        accessToken: String = "test-cj-access-token"
    ): CjSupplierOrderAdapter = CjSupplierOrderAdapter(
        baseUrl = baseUrl,
        accessToken = accessToken
    )

    private fun validRequest(
        orderNumber: String = "order-001",
        quantity: Int = 2,
        supplierVariantId: String = "vid-abc-123",
        supplierProductId: String = "pid-xyz-456"
    ): SupplierOrderRequest = SupplierOrderRequest(
        orderNumber = orderNumber,
        shippingAddress = ShippingAddress(
            customerName = "Jane Doe",
            addressLine1 = "456 Oak Ave",
            addressLine2 = "Suite 200",
            city = "Portland",
            province = "Oregon",
            country = "United States",
            countryCode = "US",
            zip = "97201",
            phone = "+1-503-555-0199"
        ),
        supplierProductId = supplierProductId,
        supplierVariantId = supplierVariantId,
        quantity = quantity
    )

    @Test
    fun `successful order placement returns Success with supplierOrderId`() {
        wireMock.stubFor(
            post(urlEqualTo(ORDER_ENDPOINT))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {
                                "result": true,
                                "code": 200,
                                "message": "success",
                                "data": {
                                    "orderId": "cj-order-123"
                                }
                            }
                        """.trimIndent())
                )
        )

        val result = adapter().placeOrder(validRequest())

        assertThat(result).isInstanceOf(SupplierOrderResult.Success::class.java)
        val success = result as SupplierOrderResult.Success
        assertThat(success.supplierOrderId).isEqualTo("cj-order-123")
    }

    @Test
    fun `out of stock error returns Failure with reason`() {
        wireMock.stubFor(
            post(urlEqualTo(ORDER_ENDPOINT))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {
                                "result": false,
                                "code": 1600501,
                                "message": "product out of stock"
                            }
                        """.trimIndent())
                )
        )

        val result = adapter().placeOrder(validRequest())

        assertThat(result).isInstanceOf(SupplierOrderResult.Failure::class.java)
        val failure = result as SupplierOrderResult.Failure
        assertThat(failure.reason).contains("product out of stock")
    }

    @Test
    fun `invalid address error returns Failure with reason`() {
        wireMock.stubFor(
            post(urlEqualTo(ORDER_ENDPOINT))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {
                                "result": false,
                                "code": 1600502,
                                "message": "invalid shipping address"
                            }
                        """.trimIndent())
                )
        )

        val result = adapter().placeOrder(validRequest())

        assertThat(result).isInstanceOf(SupplierOrderResult.Failure::class.java)
        val failure = result as SupplierOrderResult.Failure
        assertThat(failure.reason).contains("invalid shipping address")
    }

    @Test
    fun `auth failure 401 propagates as HttpClientErrorException`() {
        wireMock.stubFor(
            post(urlEqualTo(ORDER_ENDPOINT))
                .willReturn(
                    aResponse()
                        .withStatus(401)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"message": "Unauthorized"}""")
                )
        )

        assertThrows<HttpClientErrorException.Unauthorized> {
            adapter().placeOrder(validRequest())
        }
    }

    @Test
    fun `credential guard - blank credentials return Failure with no HTTP call`() {
        // Adapter with blank baseUrl and accessToken
        val blankAdapter = adapter(baseUrl = "", accessToken = "")

        val result = blankAdapter.placeOrder(validRequest())

        assertThat(result).isInstanceOf(SupplierOrderResult.Failure::class.java)
        val failure = result as SupplierOrderResult.Failure
        assertThat(failure.reason).isEqualTo("CJ API credentials not configured")

        // Verify no HTTP requests were made to WireMock
        wireMock.verify(0, postRequestedFor(urlEqualTo(ORDER_ENDPOINT)))
    }

    @Test
    fun `request body verification - correct headers and body structure`() {
        wireMock.stubFor(
            post(urlEqualTo(ORDER_ENDPOINT))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {
                                "result": true,
                                "code": 200,
                                "message": "success",
                                "data": {
                                    "orderId": "cj-verify-456"
                                }
                            }
                        """.trimIndent())
                )
        )

        val request = validRequest(
            orderNumber = "verify-order-789",
            quantity = 3,
            supplierVariantId = "vid-verify-001",
            supplierProductId = "pid-verify-002"
        )

        adapter().placeOrder(request)

        // Verify CJ-Access-Token header is present with correct value
        wireMock.verify(
            postRequestedFor(urlEqualTo(ORDER_ENDPOINT))
                .withHeader("CJ-Access-Token", equalTo("test-cj-access-token"))
                .withHeader("Content-Type", containing("application/json"))
                .withRequestBody(containing("\"orderNumber\":\"verify-order-789\""))
                .withRequestBody(containing("\"shippingCustomerName\":\"Jane Doe\""))
                .withRequestBody(containing("\"shippingAddress\":\"456 Oak Ave Suite 200\""))
                .withRequestBody(containing("\"shippingCity\":\"Portland\""))
                .withRequestBody(containing("\"shippingProvince\":\"Oregon\""))
                .withRequestBody(containing("\"shippingCountryCode\":\"US\""))
                .withRequestBody(containing("\"shippingZip\":\"97201\""))
                .withRequestBody(containing("\"shippingPhone\":\"+1-503-555-0199\""))
                .withRequestBody(containing("\"vid\":\"vid-verify-001\""))
                .withRequestBody(containing("\"quantity\":3"))
        )
    }

    @Test
    fun `null orderId in success response returns Failure via NullNode guard`() {
        wireMock.stubFor(
            post(urlEqualTo(ORDER_ENDPOINT))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {
                                "result": true,
                                "code": 200,
                                "message": "success",
                                "data": {
                                    "orderId": null
                                }
                            }
                        """.trimIndent())
                )
        )

        val result = adapter().placeOrder(validRequest())

        assertThat(result).isInstanceOf(SupplierOrderResult.Failure::class.java)
        val failure = result as SupplierOrderResult.Failure
        assertThat(failure.reason).contains("Missing orderId")
    }

    @Test
    fun `null data node in response returns Failure`() {
        wireMock.stubFor(
            post(urlEqualTo(ORDER_ENDPOINT))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                            {
                                "result": true,
                                "code": 200,
                                "message": "success",
                                "data": null
                            }
                        """.trimIndent())
                )
        )

        val result = adapter().placeOrder(validRequest())

        assertThat(result).isInstanceOf(SupplierOrderResult.Failure::class.java)
        val failure = result as SupplierOrderResult.Failure
        assertThat(failure.reason).contains("Missing orderId")
    }
}
