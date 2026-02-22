package com.autoshipper.catalog.domain.service

import com.autoshipper.catalog.domain.*
import com.autoshipper.catalog.persistence.CostEnvelopeRepository
import com.autoshipper.catalog.persistence.SkuRepository
import com.autoshipper.catalog.persistence.StressTestResultEntity
import com.autoshipper.catalog.persistence.StressTestResultRepository
import com.autoshipper.shared.events.SkuTerminated
import com.autoshipper.shared.identity.SkuId
import com.autoshipper.shared.money.Currency
import com.autoshipper.shared.money.Money
import com.autoshipper.shared.money.Percentage
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.Instant

@Service
@Transactional
class StressTestService(
    private val skuService: SkuService,
    private val skuRepository: SkuRepository,
    private val costEnvelopeRepository: CostEnvelopeRepository,
    private val stressTestResultRepository: StressTestResultRepository,
    private val config: StressTestConfig,
    private val eventPublisher: ApplicationEventPublisher
) {
    private val log = LoggerFactory.getLogger(StressTestService::class.java)

    fun run(skuId: SkuId, estimatedPrice: Money): LaunchReadySku {
        log.info("Running stress test for SKU $skuId with estimated price $estimatedPrice")

        val envelopeEntity = costEnvelopeRepository.findBySkuId(skuId.value)
            ?: throw IllegalStateException("No verified cost envelope found for SKU $skuId")

        val currency = Currency.valueOf(envelopeEntity.currency)

        fun money(amount: BigDecimal) = Money.of(amount, currency)

        val envelope = CostEnvelope.Verified.create(
            skuId = skuId,
            supplierUnitCost = money(envelopeEntity.supplierUnitCostAmount),
            inboundShipping = money(envelopeEntity.inboundShippingAmount),
            outboundShipping = money(envelopeEntity.outboundShippingAmount),
            platformFee = money(envelopeEntity.platformFeeAmount),
            processingFee = money(envelopeEntity.processingFeeAmount),
            packagingCost = money(envelopeEntity.packagingCostAmount),
            returnHandlingCost = money(envelopeEntity.returnHandlingCostAmount),
            customerAcquisitionCost = money(envelopeEntity.customerAcquisitionCostAmount),
            warehousingCost = money(envelopeEntity.warehousingCostAmount),
            customerServiceCost = money(envelopeEntity.customerServiceCostAmount),
            refundAllowance = money(envelopeEntity.refundAllowanceAmount),
            chargebackAllowance = money(envelopeEntity.chargebackAllowanceAmount),
            taxesAndDuties = money(envelopeEntity.taxesAndDutiesAmount),
            verifiedAt = envelopeEntity.verifiedAt
        )

        // Apply stress multipliers
        val stressedShipping = envelope.outboundShipping * config.shippingMultiplier
        val stressedCac = envelope.customerAcquisitionCost *
            (BigDecimal.ONE + config.cacIncreasePercent.divide(BigDecimal(100)))
        val stressedSupplier = envelope.supplierUnitCost *
            (BigDecimal.ONE + config.supplierIncreasePercent.divide(BigDecimal(100)))
        val stressedRefund = estimatedPrice * (config.refundRatePercent.divide(BigDecimal(100)))
        val stressedChargeback = estimatedPrice * (config.chargebackRatePercent.divide(BigDecimal(100)))

        // Compute stressed total: replace original components with stressed versions
        val stressedTotal = envelope.fullyBurdened -
            envelope.outboundShipping + stressedShipping -
            envelope.customerAcquisitionCost + stressedCac -
            envelope.supplierUnitCost + stressedSupplier -
            envelope.refundAllowance + stressedRefund -
            envelope.chargebackAllowance + stressedChargeback

        // Compute raw margin as BigDecimal to safely handle negative values
        val rawGrossMarginBd = estimatedPrice.normalizedAmount
            .subtract(stressedTotal.normalizedAmount)
            .divide(estimatedPrice.normalizedAmount, 4, java.math.RoundingMode.HALF_UP)
            .multiply(BigDecimal(100))

        // Clamp to 0 for Percentage construction; negative margin is always a fail
        val grossMarginBd = rawGrossMarginBd.max(BigDecimal.ZERO)
        val grossMargin = Percentage.of(grossMarginBd)
        val netMargin = grossMargin

        val testedAt = Instant.now()
        val grossPassed = rawGrossMarginBd >= config.grossMarginFloorPercent
        val netPassed = rawGrossMarginBd >= config.netMarginFloorPercent
        val passed = grossPassed && netPassed

        val entity = StressTestResultEntity(
            skuId = skuId.value,
            currency = currency.name,
            stressedShippingAmount = stressedShipping.normalizedAmount,
            stressedCacAmount = stressedCac.normalizedAmount,
            stressedSupplierAmount = stressedSupplier.normalizedAmount,
            stressedRefundAmount = stressedRefund.normalizedAmount,
            stressedChargebackAmount = stressedChargeback.normalizedAmount,
            stressedTotalCostAmount = stressedTotal.normalizedAmount,
            estimatedPriceAmount = estimatedPrice.normalizedAmount,
            grossMarginPercent = grossMargin.value,
            netMarginPercent = netMargin.value,
            passed = passed,
            shippingMultiplierUsed = config.shippingMultiplier,
            cacIncreasePercentUsed = config.cacIncreasePercent,
            supplierIncreasePercentUsed = config.supplierIncreasePercent,
            refundRatePercentUsed = config.refundRatePercent,
            chargebackRatePercentUsed = config.chargebackRatePercent,
            testedAt = testedAt
        )
        stressTestResultRepository.save(entity)

        return if (passed) {
            val stressTestedMargin = StressTestedMargin(netMargin)
            skuService.transition(skuId, SkuState.Listed)
            val sku = skuRepository.findById(skuId.value)
                .orElseThrow { IllegalStateException("SKU not found after transition: $skuId") }
            log.info("Stress test passed for SKU $skuId. Gross margin: $grossMargin, Net margin: $netMargin")
            LaunchReadySku(sku = sku, envelope = envelope, stressTestedMargin = stressTestedMargin)
        } else {
            skuService.transition(skuId, SkuState.Terminated(TerminationReason.STRESS_TEST_FAILED))
            eventPublisher.publishEvent(
                SkuTerminated(skuId = skuId, reason = TerminationReason.STRESS_TEST_FAILED.name)
            )
            log.warn("Stress test failed for SKU $skuId. Gross margin: $grossMargin, Net margin: $netMargin")
            throw StressTestFailedException(skuId, grossMargin, netMargin)
        }
    }
}
