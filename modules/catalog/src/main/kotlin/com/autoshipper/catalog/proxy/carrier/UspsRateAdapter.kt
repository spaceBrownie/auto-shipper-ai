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
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.math.BigDecimal
import java.math.RoundingMode

@Component
@Profile("!local")
class UspsRateAdapter(
    @Qualifier("uspsRestClient") private val uspsRestClient: RestClient,
    @Value("\${usps.api.oauth-token}") private val oauthToken: String
) : CarrierRateProvider {

    override val carrierName: String = "USPS"

    @CircuitBreaker(name = "usps-rate")
    @Retry(name = "usps-rate")
    override fun getRate(origin: Address, destination: Address, dims: PackageDimensions): Money {
        try {
            val response = uspsRestClient.post()
                .uri("/prices/v3/total-rates/search")
                .header("Authorization", "Bearer $oauthToken")
                .header("Content-Type", "application/json")
                .body(buildRateRequest(origin, destination, dims))
                .retrieve()
                .body(Map::class.java)
                ?: throw RuntimeException("Empty response from USPS Price API")

            return extractCheapestRate(response)
        } catch (ex: Exception) {
            throw ProviderUnavailableException("USPS", ex)
        }
    }

    private fun buildRateRequest(origin: Address, destination: Address, dims: PackageDimensions): Map<String, Any> =
        mapOf(
            "originZIPCode" to origin.postalCode,
            "destinationZIPCode" to destination.postalCode,
            "weight" to dims.weightKg.multiply(BigDecimal("2.20462")).toPlainString(),
            "length" to dims.lengthCm.divide(BigDecimal("2.54"), 2, RoundingMode.HALF_UP).toPlainString(),
            "width" to dims.widthCm.divide(BigDecimal("2.54"), 2, RoundingMode.HALF_UP).toPlainString(),
            "height" to dims.heightCm.divide(BigDecimal("2.54"), 2, RoundingMode.HALF_UP).toPlainString(),
            "mailClass" to "PARCEL_SELECT",
            "processingCategory" to "MACHINABLE",
            "destinationEntryFacilityType" to "NONE",
            "rateIndicator" to "DR"
        )

    @Suppress("UNCHECKED_CAST")
    private fun extractCheapestRate(response: Map<*, *>): Money {
        val totalBasePrice = response["totalBasePrice"] as? String
            ?: (response["rates"] as? List<Map<String, Any>>)
                ?.minByOrNull { (it["totalBasePrice"] as? String)?.toBigDecimalOrNull() ?: BigDecimal.valueOf(Long.MAX_VALUE) }
                ?.get("totalBasePrice") as? String
            ?: throw RuntimeException("Cannot extract rate from USPS response")

        return Money.of(totalBasePrice.toBigDecimal(), Currency.USD)
    }
}
