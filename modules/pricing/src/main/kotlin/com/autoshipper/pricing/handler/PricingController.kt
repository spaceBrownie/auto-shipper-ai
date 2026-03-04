package com.autoshipper.pricing.handler

import com.autoshipper.pricing.handler.dto.PricingHistoryEntry
import com.autoshipper.pricing.handler.dto.PricingResponse
import com.autoshipper.pricing.persistence.SkuPriceRepository
import com.autoshipper.pricing.persistence.SkuPricingHistoryRepository
import com.autoshipper.shared.identity.SkuId
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/skus")
class PricingController(
    private val skuPriceRepository: SkuPriceRepository,
    private val pricingHistoryRepository: SkuPricingHistoryRepository
) {

    @GetMapping("/{id}/pricing")
    fun getPricing(@PathVariable id: String): ResponseEntity<PricingResponse> {
        val skuId = SkuId.of(id)

        val priceEntity = skuPriceRepository.findBySkuId(skuId.value)
            ?: return ResponseEntity.notFound().build()

        val history = pricingHistoryRepository.findBySkuIdOrderByRecordedAtDesc(skuId.value)

        return ResponseEntity.ok(
            PricingResponse(
                skuId = skuId.value.toString(),
                currency = priceEntity.currency,
                currentPrice = priceEntity.currentPriceAmount,
                currentMarginPercent = priceEntity.currentMarginPercent,
                updatedAt = priceEntity.updatedAt.toString(),
                history = history.map { entry ->
                    PricingHistoryEntry(
                        price = entry.priceAmount,
                        marginPercent = entry.marginPercent,
                        signalType = entry.signalType,
                        decisionType = entry.decisionType,
                        decisionReason = entry.decisionReason,
                        recordedAt = entry.recordedAt.toString()
                    )
                }
            )
        )
    }
}
