package com.autoshipper.pricing

import com.autoshipper.catalog.domain.Sku
import com.autoshipper.catalog.domain.SkuState
import com.autoshipper.catalog.domain.service.SkuService
import com.autoshipper.catalog.persistence.CostEnvelopeEntity
import com.autoshipper.catalog.persistence.CostEnvelopeRepository
import com.autoshipper.catalog.persistence.SkuRepository
import com.autoshipper.catalog.persistence.StressTestResultEntity
import com.autoshipper.catalog.persistence.StressTestResultRepository
import com.autoshipper.pricing.persistence.SkuPriceRepository
import com.autoshipper.shared.identity.SkuId
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.math.BigDecimal
import java.time.Instant

/**
 * Integration test for PricingInitializer event-driven pricing initialization.
 *
 * NOT @Transactional — PricingInitializer uses @TransactionalEventListener(phase = AFTER_COMMIT)
 * + @Transactional(propagation = REQUIRES_NEW). A @Transactional test never commits,
 * so the listener would never fire.
 *
 * Verifies: when a SKU transitions to LISTED, the PricingInitializer fires via SkuStateChanged
 * and persists a SkuPriceEntity with correct price and margin values derived from the
 * stress test result and cost envelope.
 */
@SpringBootTest
@ActiveProfiles("test")
class PricingInitializerIntegrationTest {

    @Autowired lateinit var skuService: SkuService
    @Autowired lateinit var skuRepository: SkuRepository
    @Autowired lateinit var skuPriceRepository: SkuPriceRepository
    @Autowired lateinit var stressTestResultRepository: StressTestResultRepository
    @Autowired lateinit var costEnvelopeRepository: CostEnvelopeRepository
    @Autowired lateinit var jdbcTemplate: JdbcTemplate

    @AfterEach
    fun cleanup() {
        jdbcTemplate.execute(
            "TRUNCATE TABLE sku_pricing_history, sku_prices, sku_stress_test_results, sku_cost_envelopes, sku_state_history, skus CASCADE"
        )
    }

    /**
     * Walk a SKU to STRESS_TESTING state (the state before LISTED).
     */
    private fun createStressTestingSku(name: String = "Pricing Test SKU"): Sku {
        val sku = skuRepository.save(Sku(name = name, category = "Electronics"))
        sku.applyTransition(SkuState.ValidationPending)
        sku.applyTransition(SkuState.CostGating)
        sku.applyTransition(SkuState.StressTesting)
        return skuRepository.save(sku)
    }

    /**
     * Insert a CostEnvelopeEntity for the given SKU. Each component set to $1 for
     * predictable totals: 13 components * $1 = $13 total cost.
     */
    private fun insertCostEnvelope(skuId: java.util.UUID): CostEnvelopeEntity {
        return costEnvelopeRepository.save(
            CostEnvelopeEntity(
                skuId = skuId,
                currency = "USD",
                supplierUnitCostAmount = BigDecimal("5.0000"),
                inboundShippingAmount = BigDecimal("1.0000"),
                outboundShippingAmount = BigDecimal("2.0000"),
                platformFeeAmount = BigDecimal("1.5000"),
                processingFeeAmount = BigDecimal("0.5000"),
                packagingCostAmount = BigDecimal("0.5000"),
                returnHandlingCostAmount = BigDecimal("0.5000"),
                customerAcquisitionCostAmount = BigDecimal("2.0000"),
                warehousingCostAmount = BigDecimal("0.5000"),
                customerServiceCostAmount = BigDecimal("0.5000"),
                refundAllowanceAmount = BigDecimal("0.5000"),
                chargebackAllowanceAmount = BigDecimal("0.2500"),
                taxesAndDutiesAmount = BigDecimal("0.7500"),
                verifiedAt = Instant.now()
            )
        )
    }

    /**
     * Insert a StressTestResultEntity for the given SKU. Uses a stressed total cost
     * and estimated price that yield a known margin.
     *
     * estimatedPriceAmount = $100, stressedTotalCostAmount = $40
     * Expected margin = (100 - 40) / 100 * 100 = 60%
     */
    private fun insertStressTestResult(skuId: java.util.UUID): StressTestResultEntity {
        return stressTestResultRepository.save(
            StressTestResultEntity(
                skuId = skuId,
                currency = "USD",
                stressedShippingAmount = BigDecimal("4.0000"),
                stressedCacAmount = BigDecimal("6.0000"),
                stressedSupplierAmount = BigDecimal("11.0000"),
                stressedRefundAmount = BigDecimal("5.0000"),
                stressedChargebackAmount = BigDecimal("2.0000"),
                stressedTotalCostAmount = BigDecimal("40.0000"),
                estimatedPriceAmount = BigDecimal("100.0000"),
                grossMarginPercent = BigDecimal("60.0000"),
                netMarginPercent = BigDecimal("55.0000"),
                passed = true,
                shippingMultiplierUsed = BigDecimal("2.0000"),
                cacIncreasePercentUsed = BigDecimal("15.0000"),
                supplierIncreasePercentUsed = BigDecimal("10.0000"),
                refundRatePercentUsed = BigDecimal("5.0000"),
                chargebackRatePercentUsed = BigDecimal("2.0000"),
                testedAt = Instant.now()
            )
        )
    }

    @Test
    fun `transitioning SKU to LISTED triggers PricingInitializer and persists SkuPriceEntity`() {
        // Arrange: create SKU in STRESS_TESTING state with required cost envelope and stress test result
        val sku = createStressTestingSku()
        insertCostEnvelope(sku.id)
        insertStressTestResult(sku.id)

        // Act: transition to LISTED — this is @Transactional, commits, and publishes SkuStateChanged
        skuService.transition(SkuId(sku.id), SkuState.Listed)

        // Wait for AFTER_COMMIT listener to fire (runs in REQUIRES_NEW transaction)
        Thread.sleep(1000)

        // Assert: SkuPriceEntity was created by PricingInitializer
        val priceEntity = skuPriceRepository.findBySkuId(sku.id)
        assertNotNull(priceEntity, "SkuPriceEntity should be created by PricingInitializer after transition to LISTED")

        // Price should match the estimatedPriceAmount from the stress test result
        assertEquals(0, BigDecimal("100.0000").compareTo(priceEntity!!.currentPriceAmount),
            "Price should match stress test estimated price ($100)")

        // Currency should be USD
        assertEquals("USD", priceEntity.currency)

        // Margin = (100 - 40) / 100 * 100 = 60.0000%
        assertEquals(0, BigDecimal("60.0000").compareTo(priceEntity.currentMarginPercent),
            "Margin should be (price - stressedCost) / price * 100 = 60%")

        // Fully burdened cost should be the stressed total cost from the stress test result
        assertNotNull(priceEntity.currentFullyBurdenedAmount)
        assertEquals(0, BigDecimal("40.0000").compareTo(priceEntity.currentFullyBurdenedAmount!!),
            "Fully burdened cost should match stress test stressed total cost ($40)")
    }

    @Test
    fun `PricingInitializer is idempotent — second transition to LISTED does not create duplicate`() {
        // Arrange: create SKU in STRESS_TESTING with prerequisites
        val sku = createStressTestingSku()
        insertCostEnvelope(sku.id)
        insertStressTestResult(sku.id)

        // First transition to LISTED
        skuService.transition(SkuId(sku.id), SkuState.Listed)
        Thread.sleep(1000)

        // Verify price was created
        val firstPrice = skuPriceRepository.findBySkuId(sku.id)
        assertNotNull(firstPrice, "Price should exist after first LISTED transition")

        // Transition back to PAUSED, then back to LISTED
        skuService.transition(SkuId(sku.id), SkuState.Paused)
        Thread.sleep(500)
        skuService.transition(SkuId(sku.id), SkuState.Listed)
        Thread.sleep(1000)

        // Assert: still only one price record (idempotency guard in PricingInitializer)
        val allPrices = skuPriceRepository.findAll().filter { it.skuId == sku.id }
        assertEquals(1, allPrices.size,
            "PricingInitializer should be idempotent — only one price record per SKU")
    }
}
