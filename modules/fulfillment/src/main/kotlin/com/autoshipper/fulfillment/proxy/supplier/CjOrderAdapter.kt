package com.autoshipper.fulfillment.proxy.supplier

import com.autoshipper.fulfillment.domain.ShippingAddress
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import io.github.resilience4j.retry.annotation.Retry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

/**
 * CJ Dropshipping order placement adapter.
 *
 * Uses CJ createOrderV2 API to place purchase orders.
 * CLAUDE.md #13: @Value with empty defaults so bean can instantiate under any profile.
 * CLAUDE.md #18: Does NOT catch RestClientException — lets it propagate for @Retry/@CircuitBreaker.
 */
@Component
@Profile("!local")
class CjOrderAdapter(
    @Value("\${cj-dropshipping.api.base-url:}") private val baseUrl: String,
    @Value("\${cj-dropshipping.api.access-token:}") private val accessToken: String,
    @Value("\${cj-dropshipping.order.logistic-name:}") private val logisticName: String,
    @Value("\${cj-dropshipping.order.from-country-code:}") private val fromCountryCode: String
) : SupplierOrderAdapter {

    private val logger = LoggerFactory.getLogger(CjOrderAdapter::class.java)
    private val objectMapper = ObjectMapper()
    private val restClient by lazy { RestClient.builder().baseUrl(baseUrl).build() }

    @Retry(name = "cj-order")
    @CircuitBreaker(name = "cj-order")
    override fun placeOrder(request: SupplierOrderRequest): SupplierOrderResult {
        if (baseUrl.isBlank() || accessToken.isBlank()) {
            throw IllegalStateException("CJ API credentials not configured")
        }

        val body = buildRequestBody(request)

        val responseBody = restClient.post()
            .uri("/shopping/order/createOrderV2")
            .header("CJ-Access-Token", accessToken)
            .contentType(MediaType.APPLICATION_JSON)
            .body(body)
            .retrieve()
            .body(String::class.java)
            ?: throw IllegalStateException("Empty response from CJ API")

        return parseResponse(responseBody)
        // Do NOT catch RestClientException — let it propagate for @Retry/@CircuitBreaker (CLAUDE.md #18)
    }

    private fun buildRequestBody(request: SupplierOrderRequest): String {
        val requestMap = mapOf(
            "orderNumber" to request.orderNumber,
            "shippingCustomerName" to (request.shippingAddress.customerName ?: ""),
            "shippingAddress" to buildFullAddress(request.shippingAddress),
            "shippingCity" to (request.shippingAddress.city ?: ""),
            "shippingProvince" to (request.shippingAddress.province ?: ""),
            "shippingZip" to (request.shippingAddress.zip ?: ""),
            "shippingCountry" to (request.shippingAddress.country ?: ""),
            "shippingCountryCode" to (request.shippingAddress.countryCode ?: ""),
            "shippingPhone" to (request.shippingAddress.phone ?: ""),
            "products" to request.products.map { product ->
                mapOf(
                    "vid" to product.vid,
                    "quantity" to product.quantity
                )
            },
            "logisticName" to request.logisticName,
            "fromCountryCode" to request.fromCountryCode
        )
        return objectMapper.writeValueAsString(requestMap)
    }

    private fun buildFullAddress(addr: ShippingAddress): String {
        return listOfNotNull(addr.address, addr.address2)
            .filter { it.isNotBlank() }
            .joinToString(", ")
    }

    private fun parseResponse(responseBody: String): SupplierOrderResult {
        val root = objectMapper.readTree(responseBody)
        val code = root.get("code")?.asInt() ?: -1
        val result = root.get("result")?.asBoolean() ?: false

        if (code != 200 || !result) {
            val message = root.get("message")?.asText() ?: "Unknown CJ API error"
            throw IllegalStateException("CJ API error: $message (code=$code)")
        }

        val data = root.get("data")
            ?: throw IllegalStateException("CJ API response missing 'data' field")

        return SupplierOrderResult(
            supplierOrderId = data.get("orderId")?.asText()
                ?: throw IllegalStateException("CJ API response missing 'data.orderId'"),
            status = data.get("status")?.asText() ?: "UNKNOWN"
        )
    }
}
