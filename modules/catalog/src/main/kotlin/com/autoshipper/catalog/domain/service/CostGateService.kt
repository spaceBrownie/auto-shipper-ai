package com.autoshipper.catalog.domain.service

import com.autoshipper.catalog.domain.Address
import com.autoshipper.catalog.domain.CostEnvelope
import com.autoshipper.catalog.domain.PackageDimensions
import com.autoshipper.catalog.domain.SkuState
import com.autoshipper.catalog.persistence.CostEnvelopeEntity
import com.autoshipper.catalog.persistence.CostEnvelopeRepository
import com.autoshipper.catalog.proxy.carrier.CarrierRateProvider
import com.autoshipper.catalog.proxy.payment.StripeProcessingFeeProvider
import com.autoshipper.catalog.proxy.platform.ShopifyPlatformFeeProvider
import com.autoshipper.shared.events.CostEnvelopeVerified
import com.autoshipper.shared.identity.SkuId
import com.autoshipper.shared.money.Money
import com.autoshipper.shared.money.Percentage
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Service
@Transactional
class CostGateService(
    private val skuService: SkuService,
    private val carrierRateProviders: List<CarrierRateProvider>,
    private val stripeProcessingFeeProvider: StripeProcessingFeeProvider,
    private val shopifyPlatformFeeProvider: ShopifyPlatformFeeProvider,
    private val costEnvelopeRepository: CostEnvelopeRepository,
    private val eventPublisher: ApplicationEventPublisher
) {
    private val log = LoggerFactory.getLogger(CostGateService::class.java)

    fun verify(
        skuId: SkuId,
        vendorQuote: Money,
        packageDims: PackageDimensions,
        origin: Address,
        destination: Address,
        cacEstimate: Money,
        jurisdiction: String,
        warehouseCost: Money,
        customerServiceCost: Money,
        packagingCost: Money,
        returnHandlingCost: Money,
        refundAllowanceRate: Percentage,
        chargebackAllowanceRate: Percentage,
        taxesAndDuties: Money,
        estimatedOrderValue: Money
    ): CostEnvelope.Verified {
        log.info("Beginning cost gate verification for SKU $skuId")

        // Fetch cheapest carrier rate
        val outboundShipping = fetchCheapestCarrierRate(skuId, origin, destination, packageDims)

        // Fetch processing fee from Stripe
        val processingFee = stripeProcessingFeeProvider.getFee(estimatedOrderValue)

        // Fetch platform fee from Shopify
        val platformFee = shopifyPlatformFeeProvider.getFee()

        // Compute allowances from rates
        val refundAllowance = estimatedOrderValue * refundAllowanceRate.toDecimalFraction()
        val chargebackAllowance = estimatedOrderValue * chargebackAllowanceRate.toDecimalFraction()

        // Construct the verified envelope using internal factory
        val verifiedAt = Instant.now()
        val envelope = CostEnvelope.Verified.create(
            skuId = skuId,
            supplierUnitCost = vendorQuote,
            inboundShipping = Money.of(java.math.BigDecimal.ZERO, vendorQuote.currency),
            outboundShipping = outboundShipping,
            platformFee = platformFee,
            processingFee = processingFee,
            packagingCost = packagingCost,
            returnHandlingCost = returnHandlingCost,
            customerAcquisitionCost = cacEstimate,
            warehousingCost = warehouseCost,
            customerServiceCost = customerServiceCost,
            refundAllowance = refundAllowance,
            chargebackAllowance = chargebackAllowance,
            taxesAndDuties = taxesAndDuties,
            verifiedAt = verifiedAt
        )

        // Persist the envelope
        val entity = CostEnvelopeEntity(
            skuId = skuId.value,
            currency = envelope.fullyBurdened.currency.name,
            supplierUnitCostAmount = envelope.supplierUnitCost.normalizedAmount,
            inboundShippingAmount = envelope.inboundShipping.normalizedAmount,
            outboundShippingAmount = envelope.outboundShipping.normalizedAmount,
            platformFeeAmount = envelope.platformFee.normalizedAmount,
            processingFeeAmount = envelope.processingFee.normalizedAmount,
            packagingCostAmount = envelope.packagingCost.normalizedAmount,
            returnHandlingCostAmount = envelope.returnHandlingCost.normalizedAmount,
            customerAcquisitionCostAmount = envelope.customerAcquisitionCost.normalizedAmount,
            warehousingCostAmount = envelope.warehousingCost.normalizedAmount,
            customerServiceCostAmount = envelope.customerServiceCost.normalizedAmount,
            refundAllowanceAmount = envelope.refundAllowance.normalizedAmount,
            chargebackAllowanceAmount = envelope.chargebackAllowance.normalizedAmount,
            taxesAndDutiesAmount = envelope.taxesAndDuties.normalizedAmount,
            verifiedAt = verifiedAt
        )
        costEnvelopeRepository.save(entity)

        // Transition SKU state: CostGating -> StressTesting
        skuService.transition(skuId, SkuState.StressTesting)

        // Publish domain event
        eventPublisher.publishEvent(
            CostEnvelopeVerified(
                skuId = skuId,
                fullyBurdenedCost = envelope.fullyBurdened
            )
        )

        log.info("Cost gate verification complete for SKU $skuId. Fully burdened: ${envelope.fullyBurdened}")
        return envelope
    }

    private fun fetchCheapestCarrierRate(
        skuId: SkuId,
        origin: Address,
        destination: Address,
        packageDims: PackageDimensions
    ): Money {
        require(carrierRateProviders.isNotEmpty()) { "No carrier rate providers configured" }

        val rates = carrierRateProviders.map { provider ->
            log.debug("Fetching rate from ${provider.carrierName} for SKU $skuId")
            provider.getRate(origin, destination, packageDims)
        }

        return rates.minByOrNull { it.normalizedAmount }
            ?: throw IllegalStateException("No carrier rates returned")
    }
}
