package com.autoshipper.catalog.proxy.platform

import com.autoshipper.catalog.domain.CostEnvelope
import com.autoshipper.catalog.domain.LaunchReadySku
import com.autoshipper.catalog.domain.Sku
import com.autoshipper.catalog.domain.StressTestedMargin
import com.autoshipper.shared.identity.SkuId
import com.autoshipper.shared.money.Currency
import com.autoshipper.shared.money.Money
import com.autoshipper.shared.money.Percentage
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class StubPlatformAdapterTest {

    private val adapter = StubPlatformAdapter()
    private val skuId = SkuId.new()

    private fun buildLaunchReadySku(): LaunchReadySku {
        val sku = Sku(id = skuId.value, name = "Stub Test Product", category = "Gadgets")
        val envelope = CostEnvelope.Verified.create(
            skuId = skuId,
            supplierUnitCost = Money.of(BigDecimal("5.00"), Currency.USD),
            inboundShipping = Money.of(BigDecimal("1.00"), Currency.USD),
            outboundShipping = Money.of(BigDecimal("3.00"), Currency.USD),
            platformFee = Money.of(BigDecimal("1.00"), Currency.USD),
            processingFee = Money.of(BigDecimal("0.50"), Currency.USD),
            packagingCost = Money.of(BigDecimal("0.50"), Currency.USD),
            returnHandlingCost = Money.of(BigDecimal("0.50"), Currency.USD),
            customerAcquisitionCost = Money.of(BigDecimal("2.00"), Currency.USD),
            warehousingCost = Money.of(BigDecimal("0.50"), Currency.USD),
            customerServiceCost = Money.of(BigDecimal("0.50"), Currency.USD),
            refundAllowance = Money.of(BigDecimal("0.50"), Currency.USD),
            chargebackAllowance = Money.of(BigDecimal("0.25"), Currency.USD),
            taxesAndDuties = Money.of(BigDecimal("0.25"), Currency.USD),
            verifiedAt = Instant.now()
        )
        val margin = StressTestedMargin(Percentage.of(BigDecimal("35.00")))
        return LaunchReadySku(sku, envelope, margin)
    }

    @Test
    fun `listSku returns deterministic external IDs`() {
        val launchReadySku = buildLaunchReadySku()
        val price = Money.of(BigDecimal("49.99"), Currency.USD)

        val result1 = adapter.listSku(launchReadySku, price)
        val result2 = adapter.listSku(launchReadySku, price)

        // Deterministic: same SKU always produces same external IDs
        assertEquals(result1.externalListingId, result2.externalListingId)
        assertEquals(result1.externalVariantId, result2.externalVariantId)
        assertNotNull(result1.externalListingId)
        assertNotNull(result1.externalVariantId)
    }

    @Test
    fun `pauseSku does not throw`() {
        assertDoesNotThrow {
            adapter.pauseSku("external-listing-123")
        }
    }

    @Test
    fun `archiveSku does not throw`() {
        assertDoesNotThrow {
            adapter.archiveSku("external-listing-123")
        }
    }

    @Test
    fun `updatePrice does not throw`() {
        assertDoesNotThrow {
            adapter.updatePrice("external-variant-123", Money.of(BigDecimal("59.99"), Currency.USD))
        }
    }

    @Test
    fun `getFees returns 2 percent transaction fee`() {
        val fees = adapter.getFees("Electronics", Money.of(BigDecimal("100.00"), Currency.USD))

        assertEquals(Money.of(BigDecimal("2.0000"), Currency.USD), fees.transactionFee)
        assertEquals(Money.of(BigDecimal.ZERO, Currency.USD), fees.listingFee)
    }
}
