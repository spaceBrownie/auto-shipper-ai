package com.autoshipper.fulfillment.proxy.payment

import com.autoshipper.shared.money.Currency
import com.autoshipper.shared.money.Money
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.util.UUID

/**
 * BR-2: Blank-credential guard for StripeRefundAdapter.
 *
 * Per CLAUDE.md constraint #13, adapters receiving blank @Value injections must
 * guard with early return. Since StripeRefundAdapter does not have
 * ProviderUnavailableException in scope (it's in the catalog module), it should
 * throw IllegalStateException when the Stripe API secret key is blank.
 *
 * Tests will FAIL until the guard is implemented in Phase 5.
 */
class StripeRefundAdapterBlankCredentialTest {

    @Test
    fun `refund throws IllegalStateException when secretKey is blank`() {
        val adapter = StripeRefundAdapter(secretKey = "")

        val orderId = UUID.randomUUID()
        val amount = Money.of(BigDecimal("39.99"), Currency.USD)
        val paymentIntentId = "pi_test_123"
        val idempotencyKey = "idem_test_${UUID.randomUUID()}"

        assertThrows<IllegalStateException> {
            adapter.refund(orderId, amount, paymentIntentId, idempotencyKey)
        }
    }
}
