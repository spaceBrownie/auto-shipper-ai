package com.autoshipper.pricing.domain.service

import com.autoshipper.catalog.persistence.CostEnvelopeEntity
import com.autoshipper.catalog.persistence.CostEnvelopeRepository
import com.autoshipper.pricing.config.PricingConfig
import com.autoshipper.pricing.persistence.SkuPriceEntity
import com.autoshipper.pricing.persistence.SkuPriceRepository
import com.autoshipper.pricing.persistence.SkuPricingHistoryEntity
import com.autoshipper.pricing.persistence.SkuPricingHistoryRepository
import com.autoshipper.shared.events.PricingDecision
import com.autoshipper.shared.events.PricingSignal
import com.autoshipper.shared.identity.SkuId
import com.autoshipper.shared.money.Currency
import com.autoshipper.shared.money.Money
import com.autoshipper.shared.money.Percentage
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant

@Service
@Transactional
class PricingEngine(
    private val skuPriceRepository: SkuPriceRepository,
    private val costEnvelopeRepository: CostEnvelopeRepository,
    private val pricingHistoryRepository: SkuPricingHistoryRepository,
    private val pricingConfig: PricingConfig,
    private val eventPublisher: ApplicationEventPublisher
) {
    private val log = LoggerFactory.getLogger(PricingEngine::class.java)

    @EventListener
    fun onPricingSignal(signal: PricingSignal) {
        val skuId = signal.skuId()
        val delta = signal.delta()
        val signalType = signal.signalType()

        log.info("Processing {} for SKU {} with delta {}", signalType, skuId, delta)

        val priceEntity = skuPriceRepository.findBySkuId(skuId.value)
        if (priceEntity == null) {
            log.warn("No price record for SKU {}; ignoring signal", skuId)
            return
        }

        val envelopeEntity = costEnvelopeRepository.findBySkuId(skuId.value)
        if (envelopeEntity == null) {
            log.warn("No cost envelope for SKU {}; ignoring signal", skuId)
            return
        }

        val currency = Currency.valueOf(priceEntity.currency)
        val currentPrice = Money.of(priceEntity.currentPriceAmount, currency)
        val currentFullyBurdened = computeFullyBurdened(envelopeEntity)
        val newFullyBurdened = currentFullyBurdened + delta

        val marginFloor = Percentage.of(pricingConfig.marginFloorPercent)
        val marginAboveFloor = isMarginAboveFloor(newFullyBurdened, currentPrice, marginFloor)
        val newMargin = safeMargin(newFullyBurdened, currentPrice)

        val decision = when {
            marginAboveFloor -> {
                // Margin still healthy — adjust (price stays the same, margin changes)
                priceEntity.currentMarginPercent = newMargin.value
                priceEntity.updatedAt = Instant.now()
                skuPriceRepository.save(priceEntity)

                PricingDecision.Adjusted(skuId = skuId, newPrice = currentPrice)
            }

            canFindViablePrice(newFullyBurdened, currentPrice, marginFloor) -> {
                // Margin breached but a viable price exists — check conversion impact
                val minViablePrice = computeMinViablePrice(newFullyBurdened, marginFloor)
                val priceIncreasePct = computePriceChangePct(currentPrice, minViablePrice)
                val conversionThreshold = Percentage.of(pricingConfig.conversionThresholdPercent)

                if (priceIncreasePct.value <= conversionThreshold.value) {
                    // Price increase within conversion tolerance — adjust price
                    val adjustedMargin = newFullyBurdened.marginAgainst(minViablePrice)
                    priceEntity.currentPriceAmount = minViablePrice.normalizedAmount
                    priceEntity.currentMarginPercent = adjustedMargin.value
                    priceEntity.currency = minViablePrice.currency.name
                    priceEntity.updatedAt = Instant.now()
                    skuPriceRepository.save(priceEntity)

                    PricingDecision.Adjusted(skuId = skuId, newPrice = minViablePrice)
                } else {
                    // Price increase would harm conversion too much
                    PricingDecision.PauseRequired(
                        skuId = skuId,
                        reason = "Price increase of ${priceIncreasePct.value}% exceeds conversion threshold of ${conversionThreshold.value}%"
                    )
                }
            }

            else -> {
                // No viable price exists at all
                PricingDecision.TerminateRequired(
                    skuId = skuId,
                    reason = "No price can maintain ${marginFloor.value}% margin with fully burdened cost of $newFullyBurdened"
                )
            }
        }

        persistHistory(skuId, currentPrice, newMargin, signalType, decision)
        eventPublisher.publishEvent(decision)

        log.info("Emitted {} for SKU {}", decision::class.simpleName, skuId)
    }

    fun setInitialPrice(skuId: SkuId, price: Money, margin: Percentage) {
        val entity = SkuPriceEntity(
            skuId = skuId.value,
            currency = price.currency.name,
            currentPriceAmount = price.normalizedAmount,
            currentMarginPercent = margin.value
        )
        skuPriceRepository.save(entity)

        pricingHistoryRepository.save(
            SkuPricingHistoryEntity(
                skuId = skuId.value,
                currency = price.currency.name,
                priceAmount = price.normalizedAmount,
                marginPercent = margin.value,
                signalType = "INITIAL",
                decisionType = "ADJUSTED",
                decisionReason = "Initial price set via backward induction"
            )
        )
    }

    private fun isMarginAboveFloor(cost: Money, revenue: Money, floor: Percentage): Boolean {
        if (cost.normalizedAmount >= revenue.normalizedAmount) return false
        return cost.marginAgainst(revenue).value >= floor.value
    }

    private fun safeMargin(cost: Money, revenue: Money): Percentage {
        if (cost.normalizedAmount >= revenue.normalizedAmount) return Percentage.of(0)
        return cost.marginAgainst(revenue)
    }

    private fun computeFullyBurdened(entity: CostEnvelopeEntity): Money {
        val currency = Currency.valueOf(entity.currency)
        val total = entity.supplierUnitCostAmount
            .add(entity.inboundShippingAmount)
            .add(entity.outboundShippingAmount)
            .add(entity.platformFeeAmount)
            .add(entity.processingFeeAmount)
            .add(entity.packagingCostAmount)
            .add(entity.returnHandlingCostAmount)
            .add(entity.customerAcquisitionCostAmount)
            .add(entity.warehousingCostAmount)
            .add(entity.customerServiceCostAmount)
            .add(entity.refundAllowanceAmount)
            .add(entity.chargebackAllowanceAmount)
            .add(entity.taxesAndDutiesAmount)
        return Money.of(total, currency)
    }

    private fun canFindViablePrice(fullyBurdened: Money, currentPrice: Money, marginFloor: Percentage): Boolean {
        val minPrice = computeMinViablePrice(fullyBurdened, marginFloor)
        val maxAcceptablePrice = currentPrice.normalizedAmount.multiply(pricingConfig.maxPriceMultiplier)
        return minPrice.normalizedAmount <= maxAcceptablePrice
    }

    private fun computeMinViablePrice(fullyBurdened: Money, marginFloor: Percentage): Money {
        val divisor = BigDecimal.ONE.subtract(marginFloor.toDecimalFraction())
        if (divisor.compareTo(BigDecimal.ZERO) <= 0) {
            return Money.of(BigDecimal("999999"), fullyBurdened.currency)
        }
        val minPrice = fullyBurdened.normalizedAmount.divide(divisor, 4, RoundingMode.HALF_UP)
        return Money.of(minPrice, fullyBurdened.currency)
    }

    private fun computePriceChangePct(currentPrice: Money, newPrice: Money): Percentage {
        val diff = newPrice.normalizedAmount.subtract(currentPrice.normalizedAmount).abs()
        val pct = diff.divide(currentPrice.normalizedAmount, 4, RoundingMode.HALF_UP)
            .multiply(BigDecimal(100))
        return if (pct > BigDecimal(100)) Percentage.of(100) else Percentage.of(pct)
    }

    private fun persistHistory(
        skuId: SkuId,
        price: Money,
        margin: Percentage,
        signalType: String,
        decision: PricingDecision
    ) {
        val (decisionType, reason) = when (decision) {
            is PricingDecision.Adjusted -> "ADJUSTED" to null
            is PricingDecision.PauseRequired -> "PAUSE_REQUIRED" to decision.reason
            is PricingDecision.TerminateRequired -> "TERMINATE_REQUIRED" to decision.reason
        }

        val historyPrice = when (decision) {
            is PricingDecision.Adjusted -> decision.newPrice
            else -> price
        }

        pricingHistoryRepository.save(
            SkuPricingHistoryEntity(
                skuId = skuId.value,
                currency = historyPrice.currency.name,
                priceAmount = historyPrice.normalizedAmount,
                marginPercent = margin.value,
                signalType = signalType,
                decisionType = decisionType,
                decisionReason = reason
            )
        )
    }
}

private fun PricingSignal.skuId(): SkuId = when (this) {
    is PricingSignal.ShippingCostChanged -> skuId
    is PricingSignal.VendorCostChanged -> skuId
    is PricingSignal.CacChanged -> skuId
    is PricingSignal.PlatformFeeChanged -> skuId
}

private fun PricingSignal.delta(): Money = when (this) {
    is PricingSignal.ShippingCostChanged -> delta
    is PricingSignal.VendorCostChanged -> delta
    is PricingSignal.CacChanged -> delta
    is PricingSignal.PlatformFeeChanged -> delta
}

private fun PricingSignal.signalType(): String = when (this) {
    is PricingSignal.ShippingCostChanged -> "SHIPPING_COST_CHANGED"
    is PricingSignal.VendorCostChanged -> "VENDOR_COST_CHANGED"
    is PricingSignal.CacChanged -> "CAC_CHANGED"
    is PricingSignal.PlatformFeeChanged -> "PLATFORM_FEE_CHANGED"
}
