package com.autoshipper.fulfillment.proxy.supplier

import com.fasterxml.jackson.databind.ObjectMapper
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import io.github.resilience4j.retry.annotation.Retry
import jakarta.annotation.PostConstruct
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

@Component
@Profile("!local")
class CjSupplierOrderAdapter(
    @Value("\${cj-dropshipping.api.base-url:}") private val baseUrl: String,
    @Value("\${cj-dropshipping.api.access-token:}") private val accessToken: String,
    @Value("\${cj-dropshipping.default-logistic-name:}") private val logisticName: String,
    private val objectMapper: ObjectMapper
) : SupplierOrderAdapter {

    private val logger = LoggerFactory.getLogger(CjSupplierOrderAdapter::class.java)

    @PostConstruct
    private fun warnIfLogisticNameBlank() {
        if (logisticName.isBlank()) {
            logger.warn("cj-dropshipping.default-logistic-name is not configured — orders will omit logisticName")
        }
    }

    @CircuitBreaker(name = "cj-supplier-order")
    @Retry(name = "cj-supplier-order")
    override fun placeOrder(request: SupplierOrderRequest): SupplierOrderResult {
        if (baseUrl.isBlank() || accessToken.isBlank()) {
            logger.warn("CJ Dropshipping API credentials blank — cannot place order")
            return SupplierOrderResult.Failure("CJ API credentials not configured")
        }

        val address = request.shippingAddress
        val shippingAddressLine = if (address != null) {
            listOfNotNull(address.addressLine1, address.addressLine2)
                .filter { it.isNotBlank() }
                .joinToString(" ")
        } else {
            ""
        }

        val fromCountryCode = request.warehouseCountryCode ?: "CN"

        val body = mutableMapOf<String, Any>(
            "orderNumber" to request.orderNumber,
            "shippingCountryCode" to (address?.countryCode ?: "US"),
            "shippingCountry" to (address?.country ?: "United States"),
            "shippingCustomerName" to (address?.customerName ?: ""),
            "shippingAddress" to shippingAddressLine,
            "shippingCity" to (address?.city ?: ""),
            "shippingProvince" to (address?.province ?: ""),
            "shippingZip" to (address?.zip ?: ""),
            "shippingPhone" to (address?.phone ?: ""),
            "fromCountryCode" to fromCountryCode,
            "products" to listOf(
                mapOf(
                    "vid" to request.supplierVariantId,
                    "quantity" to request.quantity
                )
            )
        )

        if (logisticName.isNotBlank()) {
            body["logisticName"] = logisticName
        }

        logger.info(
            "CJ order {}: fromCountryCode={}, logisticName={}",
            request.orderNumber, fromCountryCode, logisticName.ifBlank { "(not configured)" }
        )

        val restClient = RestClient.builder()
            .baseUrl(baseUrl)
            .build()

        // RestClientException propagates to Resilience4j — do NOT catch
        val responseBody = restClient.post()
            .uri("/api2.0/v1/shopping/order/createOrderV2")
            .header("CJ-Access-Token", accessToken)
            .header("Content-Type", "application/json")
            .body(objectMapper.writeValueAsString(body))
            .retrieve()
            .body(String::class.java)
            ?: return SupplierOrderResult.Failure("Empty response from CJ API")

        val root = objectMapper.readTree(responseBody)

        // NullNode guard per CLAUDE.md #17: get()?.let { if (!it.isNull) it.asText() else null }
        val result = root.get("result")?.let { if (!it.isNull) it.asBoolean() else null }
        val code = root.get("code")?.let { if (!it.isNull) it.asInt() else null }
        val message = root.get("message")?.let { if (!it.isNull) it.asText() else null }

        if (result != true || code != 200) {
            val reason = message ?: "CJ API error (code=$code)"
            logger.warn("CJ order placement failed: {}", reason)
            return SupplierOrderResult.Failure(reason)
        }

        val data = root.get("data")
        val orderId = data?.get("orderId")?.let { if (!it.isNull) it.asText() else null }
            ?: return SupplierOrderResult.Failure("Missing orderId in CJ success response")

        logger.info("CJ order placed successfully: orderId={}", orderId)
        return SupplierOrderResult.Success(supplierOrderId = orderId)
    }
}
