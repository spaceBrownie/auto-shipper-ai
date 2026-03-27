package com.autoshipper.fulfillment.proxy.supplier

import com.autoshipper.fulfillment.domain.supplier.FailureReason
import com.autoshipper.fulfillment.domain.supplier.SupplierOrderAdapter
import com.autoshipper.fulfillment.domain.supplier.SupplierOrderRequest
import com.autoshipper.fulfillment.domain.supplier.SupplierOrderResult
import com.fasterxml.jackson.databind.JsonNode
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import io.github.resilience4j.retry.annotation.Retry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.http.MediaType
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

@Component
@Profile("!local")
class CjOrderAdapter(
    @Value("\${cj-dropshipping.api.base-url:}") private val baseUrl: String,
    @Value("\${cj-dropshipping.api.access-token:}") private val accessToken: String
) : SupplierOrderAdapter {

    private val logger = LoggerFactory.getLogger(CjOrderAdapter::class.java)
    private val restClient by lazy { RestClient.builder().baseUrl(baseUrl).build() }

    override fun supplierName(): String = "CJ_DROPSHIPPING"

    @CircuitBreaker(name = "cj-order")
    @Retry(name = "cj-order")
    override fun placeOrder(request: SupplierOrderRequest): SupplierOrderResult {
        if (baseUrl.isBlank() || accessToken.isBlank()) {
            logger.warn("CJ API credentials not configured, cannot place order")
            return SupplierOrderResult.Failure(FailureReason.API_AUTH_FAILURE, "CJ API credentials not configured")
        }

        val requestBody = mapOf(
            "orderNumber" to request.orderNumber,
            "shippingCustomerName" to request.customerName,
            "shippingAddress" to request.address,
            "shippingCity" to request.city,
            "shippingProvince" to request.province,
            "shippingCountry" to request.country,
            "shippingCountryCode" to request.countryCode,
            "shippingZip" to request.zip,
            "shippingPhone" to request.phone,
            "fromCountryCode" to "CN",
            "logisticName" to "CJPacket Ordinary",
            "products" to listOf(
                mapOf(
                    "vid" to request.supplierVariantId,
                    "quantity" to request.quantity
                )
            )
        )

        val response = restClient.post()
            .uri("/api2.0/v1/shopping/order/createOrderV2")
            .header("CJ-Access-Token", accessToken)
            .contentType(MediaType.APPLICATION_JSON)
            .body(requestBody)
            .retrieve()
            .body(JsonNode::class.java)

        // CJ returns HTTP 200 for ALL responses -- check JSON code field
        val code = response?.get("code")?.asInt() ?: -1
        if (code == 200) {
            val orderId = response?.get("data")?.get("orderId")?.asText()
                ?: return SupplierOrderResult.Failure(FailureReason.UNKNOWN, "CJ returned success but missing orderId")
            return SupplierOrderResult.Success(supplierOrderId = orderId)
        }

        // Map CJ error codes to FailureReason
        val message = response?.get("message")?.asText() ?: "Unknown CJ API error"
        val reason = when (code) {
            1600001, 1600100 -> FailureReason.API_AUTH_FAILURE
            1600200 -> FailureReason.NETWORK_ERROR
            else -> when {
                message.contains("stock", ignoreCase = true) -> FailureReason.OUT_OF_STOCK
                message.contains("address", ignoreCase = true) -> FailureReason.INVALID_ADDRESS
                else -> FailureReason.UNKNOWN
            }
        }
        return SupplierOrderResult.Failure(reason, message)
    }
}
