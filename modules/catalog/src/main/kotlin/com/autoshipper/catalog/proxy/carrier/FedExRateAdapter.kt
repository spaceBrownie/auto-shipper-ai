package com.autoshipper.catalog.proxy.carrier

import com.autoshipper.catalog.domain.Address
import com.autoshipper.catalog.domain.PackageDimensions
import com.autoshipper.catalog.domain.ProviderUnavailableException
import com.autoshipper.shared.money.Currency
import com.autoshipper.shared.money.Money
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import io.github.resilience4j.retry.annotation.Retry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.math.BigDecimal
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@Component
@Profile("!local")
class FedExRateAdapter(
    @Qualifier("fedexRestClient") private val fedexRestClient: RestClient,
    @Value("\${fedex.api.client-id:}") private val clientId: String,
    @Value("\${fedex.api.client-secret:}") private val clientSecret: String
) : CarrierRateProvider {

    private val logger = LoggerFactory.getLogger(FedExRateAdapter::class.java)

    override val carrierName: String = "FedEx"

    @CircuitBreaker(name = "fedex-rate")
    @Retry(name = "fedex-rate")
    override fun getRate(origin: Address, destination: Address, dims: PackageDimensions): Money {
        if (clientId.isBlank() || clientSecret.isBlank()) {
            logger.warn("FedEx API credentials are blank — cannot fetch rates")
            throw ProviderUnavailableException("FedEx", IllegalStateException("FedEx API credentials not configured"))
        }
        try {
            val token = fetchBearerToken()
            val response = fedexRestClient.post()
                .uri("/rate/v1/rates/quotes")
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .header("X-locale", "en_US")
                .body(buildRateRequest(origin, destination, dims))
                .retrieve()
                .body(Map::class.java)
                ?: throw RuntimeException("Empty response from FedEx Rate API")

            return extractCheapestRate(response)
        } catch (ex: Exception) {
            throw ProviderUnavailableException("FedEx", ex)
        }
    }

    private fun fetchBearerToken(): String {
        @Suppress("UNCHECKED_CAST")
        val tokenResponse = fedexRestClient.post()
            .uri("/oauth/token")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .body("grant_type=client_credentials" +
                "&client_id=${URLEncoder.encode(clientId, StandardCharsets.UTF_8)}" +
                "&client_secret=${URLEncoder.encode(clientSecret, StandardCharsets.UTF_8)}")
            .retrieve()
            .body(Map::class.java) as Map<String, Any>?
            ?: throw RuntimeException("Empty token response from FedEx")

        return tokenResponse["access_token"] as? String
            ?: throw RuntimeException("No access_token in FedEx token response")
    }

    private fun buildRateRequest(origin: Address, destination: Address, dims: PackageDimensions): Map<String, Any> =
        mapOf(
            "accountNumber" to mapOf("value" to ""),
            "requestedShipment" to mapOf(
                "shipper" to mapOf(
                    "address" to mapOf(
                        "postalCode" to origin.postalCode,
                        "countryCode" to origin.countryCode
                    )
                ),
                "recipient" to mapOf(
                    "address" to mapOf(
                        "postalCode" to destination.postalCode,
                        "countryCode" to destination.countryCode
                    )
                ),
                "pickupType" to "DROPOFF_AT_FEDEX_LOCATION",
                "requestedPackageLineItems" to listOf(
                    mapOf(
                        "weight" to mapOf(
                            "units" to "KG",
                            "value" to dims.weightKg.toPlainString()
                        ),
                        "dimensions" to mapOf(
                            "length" to dims.lengthCm.toInt(),
                            "width" to dims.widthCm.toInt(),
                            "height" to dims.heightCm.toInt(),
                            "units" to "CM"
                        )
                    )
                )
            )
        )

    @Suppress("UNCHECKED_CAST")
    private fun extractCheapestRate(response: Map<*, *>): Money {
        val output = response["output"] as? Map<String, Any>
            ?: throw RuntimeException("Missing output in FedEx response")
        val rateReplyDetails = output["rateReplyDetails"] as? List<Map<String, Any>>
            ?: throw RuntimeException("Missing rateReplyDetails in FedEx response")

        val cheapest = rateReplyDetails.minByOrNull { detail ->
            val ratedShipments = detail["ratedShipmentDetails"] as? List<Map<String, Any>>
            val first = ratedShipments?.firstOrNull()
            val totalNet = first?.get("totalNetCharge") as? Map<String, Any>
            (totalNet?.get("amount") as? String)?.toBigDecimalOrNull() ?: BigDecimal.valueOf(Long.MAX_VALUE)
        } ?: throw RuntimeException("No rate reply details from FedEx")

        val ratedShipmentDetails = cheapest["ratedShipmentDetails"] as List<Map<String, Any>>
        val totalNetCharge = ratedShipmentDetails.first()["totalNetCharge"] as Map<String, Any>
        val amount = (totalNetCharge["amount"] as String).toBigDecimal()
        return Money.of(amount, Currency.USD)
    }
}
