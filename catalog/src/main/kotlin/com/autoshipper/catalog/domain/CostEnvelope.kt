package com.autoshipper.catalog.domain

import com.autoshipper.shared.identity.SkuId
import com.autoshipper.shared.money.Money
import java.time.Instant

sealed class CostEnvelope {

    data class Unverified(val skuId: SkuId) : CostEnvelope()

    class Verified internal constructor(
        val skuId: SkuId,
        val supplierUnitCost: Money,
        val inboundShipping: Money,
        val outboundShipping: Money,
        val platformFee: Money,
        val processingFee: Money,
        val packagingCost: Money,
        val returnHandlingCost: Money,
        val customerAcquisitionCost: Money,
        val warehousingCost: Money,
        val customerServiceCost: Money,
        val refundAllowance: Money,
        val chargebackAllowance: Money,
        val taxesAndDuties: Money,
        val verifiedAt: Instant
    ) : CostEnvelope() {

        init {
            val components = listOf(
                supplierUnitCost, inboundShipping, outboundShipping, platformFee,
                processingFee, packagingCost, returnHandlingCost, customerAcquisitionCost,
                warehousingCost, customerServiceCost, refundAllowance, chargebackAllowance,
                taxesAndDuties
            )
            val currencies = components.map { it.currency }.toSet()
            require(currencies.size == 1) {
                "All cost components must share the same currency, found: $currencies"
            }
        }

        val fullyBurdened: Money = supplierUnitCost +
                inboundShipping +
                outboundShipping +
                platformFee +
                processingFee +
                packagingCost +
                returnHandlingCost +
                customerAcquisitionCost +
                warehousingCost +
                customerServiceCost +
                refundAllowance +
                chargebackAllowance +
                taxesAndDuties

        companion object {
            internal fun create(
                skuId: SkuId,
                supplierUnitCost: Money,
                inboundShipping: Money,
                outboundShipping: Money,
                platformFee: Money,
                processingFee: Money,
                packagingCost: Money,
                returnHandlingCost: Money,
                customerAcquisitionCost: Money,
                warehousingCost: Money,
                customerServiceCost: Money,
                refundAllowance: Money,
                chargebackAllowance: Money,
                taxesAndDuties: Money,
                verifiedAt: Instant
            ): Verified = Verified(
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
        }
    }
}
