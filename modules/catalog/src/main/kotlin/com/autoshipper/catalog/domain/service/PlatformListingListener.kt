package com.autoshipper.catalog.domain.service

import com.autoshipper.catalog.domain.CostEnvelope
import com.autoshipper.catalog.domain.LaunchReadySku
import com.autoshipper.catalog.domain.StressTestedMargin
import com.autoshipper.catalog.persistence.CostEnvelopeRepository
import com.autoshipper.catalog.persistence.PlatformListingEntity
import com.autoshipper.catalog.persistence.PlatformListingRepository
import com.autoshipper.catalog.persistence.SkuRepository
import com.autoshipper.catalog.persistence.StressTestResultRepository
import com.autoshipper.catalog.proxy.platform.PlatformAdapter
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
import java.time.Instant

@Component
class PlatformListingListener(
    private val platformAdapter: PlatformAdapter,
    private val platformListingRepository: PlatformListingRepository,
    private val skuRepository: SkuRepository,
    private val stressTestResultRepository: StressTestResultRepository,
    private val costEnvelopeRepository: CostEnvelopeRepository
) {
    private val log = LoggerFactory.getLogger(PlatformListingListener::class.java)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun onSkuStateChanged(event: SkuStateChanged) {
        when (event.toState) {
            "LISTED" -> handleListed(event)
            "PAUSED" -> handlePaused(event)
            "TERMINATED" -> handleTerminated(event)
            else -> return
        }
    }

    private fun handleListed(event: SkuStateChanged) {
        val skuId = event.skuId

        // Idempotency guard
        val existing = platformListingRepository.findBySkuIdAndPlatform(skuId.value, "SHOPIFY")
        if (existing != null) {
            log.info("Platform listing already exists for SKU {} on SHOPIFY; skipping creation", skuId)
            return
        }

        val sku = skuRepository.findById(skuId.value).orElse(null)
        if (sku == null) {
            log.warn("SKU {} not found; cannot create platform listing", skuId)
            return
        }

        val stressResult = stressTestResultRepository.findBySkuId(skuId.value)
            .maxByOrNull { it.testedAt }
        if (stressResult == null) {
            log.warn("No stress test result for SKU {}; cannot create platform listing", skuId)
            return
        }

        val envelopeEntity = costEnvelopeRepository.findBySkuId(skuId.value)
        if (envelopeEntity == null) {
            log.warn("No cost envelope for SKU {}; cannot create platform listing", skuId)
            return
        }

        val currency = Currency.valueOf(stressResult.currency)

        val envelope = CostEnvelope.Verified.create(
            skuId = skuId,
            supplierUnitCost = Money.of(envelopeEntity.supplierUnitCostAmount, currency),
            inboundShipping = Money.of(envelopeEntity.inboundShippingAmount, currency),
            outboundShipping = Money.of(envelopeEntity.outboundShippingAmount, currency),
            platformFee = Money.of(envelopeEntity.platformFeeAmount, currency),
            processingFee = Money.of(envelopeEntity.processingFeeAmount, currency),
            packagingCost = Money.of(envelopeEntity.packagingCostAmount, currency),
            returnHandlingCost = Money.of(envelopeEntity.returnHandlingCostAmount, currency),
            customerAcquisitionCost = Money.of(envelopeEntity.customerAcquisitionCostAmount, currency),
            warehousingCost = Money.of(envelopeEntity.warehousingCostAmount, currency),
            customerServiceCost = Money.of(envelopeEntity.customerServiceCostAmount, currency),
            refundAllowance = Money.of(envelopeEntity.refundAllowanceAmount, currency),
            chargebackAllowance = Money.of(envelopeEntity.chargebackAllowanceAmount, currency),
            taxesAndDuties = Money.of(envelopeEntity.taxesAndDutiesAmount, currency),
            verifiedAt = envelopeEntity.verifiedAt
        )

        val stressTestedMargin = StressTestedMargin(Percentage.of(stressResult.netMarginPercent))
        val launchReadySku = LaunchReadySku(sku, envelope, stressTestedMargin)
        val price = Money.of(stressResult.estimatedPriceAmount, currency)

        val result = platformAdapter.listSku(launchReadySku, price)

        val entity = PlatformListingEntity(
            skuId = skuId.value,
            platform = "SHOPIFY",
            externalListingId = result.externalListingId,
            externalVariantId = result.externalVariantId,
            currentPriceAmount = price.normalizedAmount,
            currency = currency.name,
            status = "ACTIVE",
            shopifyInventoryItemId = result.inventoryItemId
        )
        platformListingRepository.save(entity)

        log.info("Created platform listing for SKU {} on SHOPIFY: externalId={}", skuId, result.externalListingId)
    }

    private fun handlePaused(event: SkuStateChanged) {
        val skuId = event.skuId
        val entity = platformListingRepository.findBySkuId(skuId.value)
        if (entity == null) {
            log.warn("No platform listing found for SKU {}; skipping pause", skuId)
            return
        }

        platformAdapter.pauseSku(entity.externalListingId)
        entity.status = "DRAFT"
        entity.updatedAt = Instant.now()
        platformListingRepository.save(entity)

        log.info("Paused platform listing for SKU {} on {}", skuId, entity.platform)
    }

    private fun handleTerminated(event: SkuStateChanged) {
        val skuId = event.skuId
        val entity = platformListingRepository.findBySkuId(skuId.value)
        if (entity == null) {
            log.warn("No platform listing found for SKU {}; skipping archive", skuId)
            return
        }

        platformAdapter.archiveSku(entity.externalListingId)
        entity.status = "ARCHIVED"
        entity.updatedAt = Instant.now()
        platformListingRepository.save(entity)

        log.info("Archived platform listing for SKU {} on {}", skuId, entity.platform)
    }
}
