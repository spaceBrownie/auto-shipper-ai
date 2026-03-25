package com.autoshipper.catalog.proxy.payment

import com.autoshipper.catalog.domain.ProviderUnavailableException
import com.autoshipper.shared.money.Currency
import com.autoshipper.shared.money.Money
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.web.client.RestClient
import java.math.BigDecimal

/**
 * BR-2: Blank-credential guard for StripeProcessingFeeProvider.
 *
 * Per CLAUDE.md constraint #13, adapters receiving blank @Value injections must
 * guard with early return that throws ProviderUnavailableException.
 *
 * These tests instantiate StripeProcessingFeeProvider with a blank secret key
 * and verify that getFee() throws ProviderUnavailableException before making
 * any HTTP call. Tests will FAIL until the guard is implemented in Phase 5.
 */
class StripeProcessingFeeProviderBlankCredentialTest {

    private val dummyRestClient: RestClient = RestClient.builder()
        .baseUrl("http://localhost:9999")
        .build()

    @Test
    fun `getFee throws ProviderUnavailableException when secretKey is blank`() {
        val provider = StripeProcessingFeeProvider(
            stripeRestClient = dummyRestClient,
            secretKey = ""
        )

        val orderValue = Money.of(BigDecimal("49.99"), Currency.USD)

        assertThrows<ProviderUnavailableException> {
            provider.getFee(orderValue)
        }
    }
}
