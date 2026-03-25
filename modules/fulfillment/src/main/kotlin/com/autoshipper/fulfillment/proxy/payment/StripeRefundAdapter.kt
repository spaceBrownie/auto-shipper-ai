package com.autoshipper.fulfillment.proxy.payment

import com.autoshipper.shared.money.Money
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import io.github.resilience4j.retry.annotation.Retry
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.net.URLEncoder
import java.util.UUID

interface RefundProvider {
    fun refund(orderId: UUID, amount: Money, paymentIntentId: String, idempotencyKey: String): RefundResult
}

data class RefundResult(val refundId: String, val status: String)

@Component
@Profile("!local")
class StripeRefundAdapter(
    @Value("\${stripe.api.secret-key:}") private val secretKey: String
) : RefundProvider {

    private val logger = LoggerFactory.getLogger(StripeRefundAdapter::class.java)

    private val restClient: RestClient = RestClient.builder()
        .baseUrl("https://api.stripe.com")
        .defaultHeader("Authorization", "Bearer $secretKey")
        .build()

    @CircuitBreaker(name = "stripe-refund")
    @Retry(name = "stripe-refund")
    override fun refund(orderId: UUID, amount: Money, paymentIntentId: String, idempotencyKey: String): RefundResult {
        if (secretKey.isBlank()) {
            logger.warn("Stripe API secret-key is blank — cannot process refund")
            throw IllegalStateException("Stripe API secret key not configured")
        }
        val amountInCents = amount.normalizedAmount.movePointRight(2).toLong()
        val encodedPi = URLEncoder.encode(paymentIntentId, Charsets.UTF_8)
        val encodedOrderId = URLEncoder.encode(orderId.toString(), Charsets.UTF_8)

        @Suppress("UNCHECKED_CAST")
        val response = restClient.post()
            .uri("/v1/refunds")
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Idempotency-Key", idempotencyKey)
            .body("payment_intent=$encodedPi&amount=$amountInCents&currency=${amount.currency.name.lowercase()}&metadata[order_id]=$encodedOrderId")
            .retrieve()
            .body(Map::class.java) as? Map<String, Any>
            ?: throw RuntimeException("Empty response from Stripe Refund API")

        val refundId = response["id"] as? String
            ?: throw RuntimeException("Missing refund id in Stripe response")
        val status = response["status"] as? String ?: "unknown"

        return RefundResult(refundId = refundId, status = status)
    }
}

@Configuration
@Profile("local")
class StubRefundConfiguration {

    @Bean
    fun stubRefundProvider(): RefundProvider = object : RefundProvider {
        override fun refund(orderId: UUID, amount: Money, paymentIntentId: String, idempotencyKey: String): RefundResult =
            RefundResult(
                refundId = "stub_refund_${UUID.randomUUID()}",
                status = "succeeded"
            )
    }
}
