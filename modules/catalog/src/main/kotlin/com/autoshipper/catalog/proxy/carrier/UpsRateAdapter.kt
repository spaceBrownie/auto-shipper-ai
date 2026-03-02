package com.autoshipper.catalog.proxy.carrier

import com.autoshipper.catalog.domain.Address
import com.autoshipper.catalog.domain.PackageDimensions
import com.autoshipper.catalog.domain.ProviderUnavailableException
import com.autoshipper.shared.money.Currency
import com.autoshipper.shared.money.Money
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import io.github.resilience4j.retry.annotation.Retry
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.math.BigDecimal

@Component
class UpsRateAdapter(
    @Qualifier("upsRestClient") private val upsRestClient: RestClient,
    @Value("\${ups.api.client-id}") private val clientId: String,
    @Value("\${ups.api.client-secret}") private val clientSecret: String
) : CarrierRateProvider {

    override val carrierName: String = "UPS"

    @CircuitBreaker(name = "ups-rate")
    @Retry(name = "ups-rate")
    override fun getRate(origin: Address, destination: Address, dims: PackageDimensions): Money {
        try {
            val token = fetchBearerToken()
            val response = upsRestClient.post()
                .uri("/rating/v1/Shop")
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/json")
                .body(buildRateRequest(origin, destination, dims))
                .retrieve()
                .body(Map::class.java)
                ?: throw RuntimeException("Empty response from UPS Rating API")

            return extractCheapestRate(response)
        } catch (ex: Exception) {
            throw ProviderUnavailableException("UPS", ex)
        }
    }

    private fun fetchBearerToken(): String {
        val credentials = java.util.Base64.getEncoder()
            .encodeToString("$clientId:$clientSecret".toByteArray())

        @Suppress("UNCHECKED_CAST")
        val tokenResponse = upsRestClient.post()
            .uri("/security/v1/oauth/token")
            .header("Authorization", "Basic $credentials")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .body("grant_type=client_credentials")
            .retrieve()
            .body(Map::class.java) as Map<String, Any>?
            ?: throw RuntimeException("Empty token response from UPS")

        return tokenResponse["access_token"] as? String
            ?: throw RuntimeException("No access_token in UPS token response")
    }

    private fun buildRateRequest(origin: Address, destination: Address, dims: PackageDimensions): Map<String, Any> =
        mapOf(
            "RateRequest" to mapOf(
                "Shipment" to mapOf(
                    "Shipper" to mapOf(
                        "Address" to mapOf(
                            "PostalCode" to origin.postalCode,
                            "CountryCode" to origin.countryCode
                        )
                    ),
                    "ShipTo" to mapOf(
                        "Address" to mapOf(
                            "PostalCode" to destination.postalCode,
                            "CountryCode" to destination.countryCode
                        )
                    ),
                    "Package" to mapOf(
                        "PackagingType" to mapOf("Code" to "02"),
                        "Dimensions" to mapOf(
                            "UnitOfMeasurement" to mapOf("Code" to "CM"),
                            "Length" to dims.lengthCm.toPlainString(),
                            "Width" to dims.widthCm.toPlainString(),
                            "Height" to dims.heightCm.toPlainString()
                        ),
                        "PackageWeight" to mapOf(
                            "UnitOfMeasurement" to mapOf("Code" to "KGS"),
                            "Weight" to dims.weightKg.toPlainString()
                        )
                    )
                )
            )
        )

    @Suppress("UNCHECKED_CAST")
    private fun extractCheapestRate(response: Map<*, *>): Money {
        val rateResponse = response["RateResponse"] as? Map<String, Any>
            ?: throw RuntimeException("Missing RateResponse in UPS response")
        val ratedShipments = rateResponse["RatedShipment"]
            ?: throw RuntimeException("Missing RatedShipment in UPS response")

        val shipments = when (ratedShipments) {
            is List<*> -> ratedShipments as List<Map<String, Any>>
            is Map<*, *> -> listOf(ratedShipments as Map<String, Any>)
            else -> throw RuntimeException("Unexpected RatedShipment format")
        }

        val cheapest = shipments.minByOrNull { shipment ->
            val totalCharges = shipment["TotalCharges"] as? Map<String, Any>
                ?: throw RuntimeException("Missing TotalCharges in shipment")
            (totalCharges["MonetaryValue"] as? String)?.toBigDecimalOrNull() ?: BigDecimal.valueOf(Long.MAX_VALUE)
        } ?: throw RuntimeException("No rated shipments returned by UPS")

        val totalCharges = cheapest["TotalCharges"] as Map<String, Any>
        val amount = (totalCharges["MonetaryValue"] as String).toBigDecimal()
        return Money.of(amount, Currency.USD)
    }
}
