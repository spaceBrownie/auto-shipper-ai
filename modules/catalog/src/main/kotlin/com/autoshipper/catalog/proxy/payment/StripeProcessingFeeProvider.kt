package com.autoshipper.catalog.proxy.payment

import com.autoshipper.catalog.domain.ProviderUnavailableException
import com.autoshipper.shared.money.Money
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import io.github.resilience4j.retry.annotation.Retry
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * Retrieves the Stripe processing fee for a given order value.
 *
 * Standard Stripe pricing: 2.9% + $0.30 per transaction.
 * This provider calls the Stripe API to confirm connectivity and then applies
 * the standard formula.
 */
@Component
class StripeProcessingFeeProvider(
    @Qualifier("stripeRestClient") private val stripeRestClient: RestClient,
    @Value("\${stripe.api.secret-key}") private val secretKey: String
) {
    companion object {
        private val STRIPE_PERCENTAGE_RATE = BigDecimal("0.029")  // 2.9%
        private val STRIPE_FIXED_FEE = BigDecimal("0.30")          // $0.30
    }

    @CircuitBreaker(name = "stripe-fee")
    @Retry(name = "stripe-fee")
    fun getFee(estimatedOrderValue: Money): Money {
        try {
            // Validate connectivity to Stripe by fetching account info
            stripeRestClient.get()
                .uri("/v1/account")
                .header("Authorization", "Bearer $secretKey")
                .retrieve()
                .toBodilessEntity()

            val percentageFee = estimatedOrderValue.normalizedAmount
                .multiply(STRIPE_PERCENTAGE_RATE)
                .setScale(4, RoundingMode.HALF_UP)

            val totalFeeAmount = percentageFee
                .add(STRIPE_FIXED_FEE)
                .setScale(4, RoundingMode.HALF_UP)

            return Money.of(totalFeeAmount, estimatedOrderValue.currency)
        } catch (ex: Exception) {
            throw ProviderUnavailableException("Stripe", ex)
        }
    }
}
