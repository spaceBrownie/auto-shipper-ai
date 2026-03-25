package com.autoshipper.pricing.domain.service

import com.autoshipper.catalog.persistence.CostEnvelopeRepository
import com.autoshipper.catalog.persistence.StressTestResultRepository
import com.autoshipper.pricing.persistence.SkuPriceRepository
import com.autoshipper.shared.events.SkuStateChanged
import com.autoshipper.shared.money.Currency
import com.autoshipper.shared.money.Money
import com.autoshipper.shared.money.Percentage
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.math.BigDecimal
import java.math.RoundingMode

@Component
class PricingInitializer(
    private val pricingEngine: PricingEngine,
    private val skuPriceRepository: SkuPriceRepository,
    private val stressTestResultRepository: StressTestResultRepository,
    private val costEnvelopeRepository: CostEnvelopeRepository
) {
    private val log = LoggerFactory.getLogger(PricingInitializer::class.java)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun onSkuStateChanged(event: SkuStateChanged) {
        if (event.toState != "LISTED") return

        val skuId = event.skuId

        // Idempotency guard: skip if price already exists
        if (skuPriceRepository.findBySkuId(skuId.value) != null) {
            log.info("Price record already exists for SKU {}; skipping initialization", skuId)
            return
        }

        val stressResult = stressTestResultRepository.findBySkuId(skuId.value)
            .maxByOrNull { it.testedAt }
        if (stressResult == null) {
            log.warn("No stress test result for SKU {}; cannot initialize pricing", skuId)
            return
        }

        val envelopeEntity = costEnvelopeRepository.findBySkuId(skuId.value)
        if (envelopeEntity == null) {
            log.warn("No cost envelope for SKU {}; cannot initialize pricing", skuId)
            return
        }

        val currency = Currency.valueOf(stressResult.currency)
        val price = Money.of(stressResult.estimatedPriceAmount, currency)
        val fullyBurdenedCost = Money.of(stressResult.stressedTotalCostAmount, currency)

        // margin = (price - cost) / price * 100
        val margin = if (price.normalizedAmount > BigDecimal.ZERO) {
            price.normalizedAmount.subtract(fullyBurdenedCost.normalizedAmount)
                .divide(price.normalizedAmount, 4, RoundingMode.HALF_UP)
                .multiply(BigDecimal(100))
        } else {
            BigDecimal.ZERO
        }

        pricingEngine.setInitialPrice(skuId, price, Percentage.of(margin), fullyBurdenedCost)

        // Post-persist verification: confirm the SkuPriceEntity was actually written
        val persisted = skuPriceRepository.findBySkuId(skuId.value)
        if (persisted != null) {
            log.info(
                "Post-persist verification: SkuPriceEntity persisted for SKU {} — price={} {}, margin={}%",
                skuId, persisted.currentPriceAmount, persisted.currency, persisted.currentMarginPercent
            )
        } else {
            log.error(
                "Post-persist verification FAILED: SkuPriceEntity NOT found for SKU {} after setInitialPrice()",
                skuId
            )
        }
    }
}
