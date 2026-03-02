package com.autoshipper.catalog.domain

import com.autoshipper.shared.identity.SkuId
import com.autoshipper.shared.money.Currency
import com.autoshipper.shared.money.Money
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.Instant

class CostEnvelopeTest {

    private val skuId = SkuId.new()
    private val verifiedAt = Instant.now()

    private fun usd(amount: Double) = Money.of(amount, Currency.USD)
    private fun eur(amount: Double) = Money.of(amount, Currency.EUR)

    private fun buildVerified(
        supplierUnitCost: Money = usd(10.0),
        inboundShipping: Money = usd(2.0),
        outboundShipping: Money = usd(8.0),
        platformFee: Money = usd(3.0),
        processingFee: Money = usd(1.5),
        packagingCost: Money = usd(0.5),
        returnHandlingCost: Money = usd(1.0),
        customerAcquisitionCost: Money = usd(5.0),
        warehousingCost: Money = usd(2.0),
        customerServiceCost: Money = usd(0.75),
        refundAllowance: Money = usd(4.0),
        chargebackAllowance: Money = usd(1.5),
        taxesAndDuties: Money = usd(2.25)
    ): CostEnvelope.Verified = CostEnvelope.Verified.create(
        skuId = skuId,
        supplierUnitCost = supplierUnitCost,
        inboundShipping = inboundShipping,
        outboundShipping = outboundShipping,
        platformFee = platformFee,
        processingFee = processingFee,
        packagingCost = packagingCost,
        returnHandlingCost = returnHandlingCost,
        customerAcquisitionCost = customerAcquisitionCost,
        warehousingCost = warehousingCost,
        customerServiceCost = customerServiceCost,
        refundAllowance = refundAllowance,
        chargebackAllowance = chargebackAllowance,
        taxesAndDuties = taxesAndDuties,
        verifiedAt = verifiedAt
    )

    @Test
    fun `fullyBurdened sums all 13 components correctly`() {
        val envelope = buildVerified()

        // 10 + 2 + 8 + 3 + 1.5 + 0.5 + 1 + 5 + 2 + 0.75 + 4 + 1.5 + 2.25 = 41.5
        val expected = Money.of(BigDecimal("41.5"), Currency.USD)
        assertEquals(expected, envelope.fullyBurdened)
    }

    @Test
    fun `Verified init block throws on currency mismatch between components`() {
        val ex = assertThrows<IllegalArgumentException> {
            buildVerified(inboundShipping = eur(2.0))
        }
        assertTrue(ex.message!!.contains("same currency"))
    }

    @Test
    fun `CostEnvelopeExpiredException message includes skuId and verifiedAt`() {
        val ex = CostEnvelopeExpiredException(skuId, verifiedAt, 24L)
        assertTrue(ex.message!!.contains(skuId.toString()))
        assertTrue(ex.message!!.contains(verifiedAt.toString()))
        assertTrue(ex.message!!.contains("24h"))
    }

    @Test
    fun `Unverified holds skuId and has no cost data`() {
        val unverified = CostEnvelope.Unverified(skuId)
        assertEquals(skuId, unverified.skuId)
    }
}
