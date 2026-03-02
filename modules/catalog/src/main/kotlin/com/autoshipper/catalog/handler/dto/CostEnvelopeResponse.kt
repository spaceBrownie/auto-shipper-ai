package com.autoshipper.catalog.handler.dto

import com.autoshipper.catalog.domain.CostEnvelope
import java.math.BigDecimal
import java.time.Instant

data class CostEnvelopeResponse(
    val skuId: String,
    val currency: String,
    val supplierUnitCost: BigDecimal,
    val inboundShipping: BigDecimal,
    val outboundShipping: BigDecimal,
    val platformFee: BigDecimal,
    val processingFee: BigDecimal,
    val packagingCost: BigDecimal,
    val returnHandlingCost: BigDecimal,
    val customerAcquisitionCost: BigDecimal,
    val warehousingCost: BigDecimal,
    val customerServiceCost: BigDecimal,
    val refundAllowance: BigDecimal,
    val chargebackAllowance: BigDecimal,
    val taxesAndDuties: BigDecimal,
    val fullyBurdened: BigDecimal,
    val verifiedAt: Instant
) {
    companion object {
        fun from(envelope: CostEnvelope.Verified): CostEnvelopeResponse = CostEnvelopeResponse(
            skuId = envelope.skuId.toString(),
            currency = envelope.fullyBurdened.currency.name,
            supplierUnitCost = envelope.supplierUnitCost.normalizedAmount,
            inboundShipping = envelope.inboundShipping.normalizedAmount,
            outboundShipping = envelope.outboundShipping.normalizedAmount,
            platformFee = envelope.platformFee.normalizedAmount,
            processingFee = envelope.processingFee.normalizedAmount,
            packagingCost = envelope.packagingCost.normalizedAmount,
            returnHandlingCost = envelope.returnHandlingCost.normalizedAmount,
            customerAcquisitionCost = envelope.customerAcquisitionCost.normalizedAmount,
            warehousingCost = envelope.warehousingCost.normalizedAmount,
            customerServiceCost = envelope.customerServiceCost.normalizedAmount,
            refundAllowance = envelope.refundAllowance.normalizedAmount,
            chargebackAllowance = envelope.chargebackAllowance.normalizedAmount,
            taxesAndDuties = envelope.taxesAndDuties.normalizedAmount,
            fullyBurdened = envelope.fullyBurdened.normalizedAmount,
            verifiedAt = envelope.verifiedAt
        )
    }
}
