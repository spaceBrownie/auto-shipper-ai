package com.autoshipper.catalog.handler

import com.autoshipper.catalog.domain.StressTestFailedException
import com.autoshipper.catalog.domain.service.StressTestService
import com.autoshipper.catalog.handler.dto.StressTestRequest
import com.autoshipper.catalog.handler.dto.StressTestResponse
import com.autoshipper.catalog.persistence.StressTestResultRepository
import com.autoshipper.shared.identity.SkuId
import com.autoshipper.shared.money.Currency
import com.autoshipper.shared.money.Money
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/skus")
class StressTestController(
    private val stressTestService: StressTestService,
    private val stressTestResultRepository: StressTestResultRepository
) {

    @PostMapping("/{id}/stress-test")
    fun runStressTest(
        @PathVariable id: String,
        @Valid @RequestBody request: StressTestRequest
    ): ResponseEntity<Any> {
        val skuId = SkuId.of(id)
        val estimatedPrice = Money.of(request.estimatedPriceAmount, Currency.valueOf(request.currency.uppercase()))

        return try {
            stressTestService.run(skuId, estimatedPrice)

            // Fetch the persisted result to build the response
            val results = stressTestResultRepository.findBySkuId(skuId.value)
            val latest = results.maxByOrNull { it.testedAt }
                ?: return ResponseEntity.internalServerError().build()

            ResponseEntity.ok(
                StressTestResponse(
                    skuId = id,
                    passed = latest.passed,
                    grossMarginPercent = latest.grossMarginPercent,
                    netMarginPercent = latest.netMarginPercent,
                    stressedTotalCost = latest.stressedTotalCostAmount,
                    estimatedPrice = latest.estimatedPriceAmount,
                    stressedShipping = latest.stressedShippingAmount,
                    stressedCac = latest.stressedCacAmount,
                    stressedSupplier = latest.stressedSupplierAmount,
                    stressedRefund = latest.stressedRefundAmount,
                    stressedChargeback = latest.stressedChargebackAmount,
                    currency = latest.currency
                )
            )
        } catch (ex: StressTestFailedException) {
            val results = stressTestResultRepository.findBySkuId(skuId.value)
            val latest = results.maxByOrNull { it.testedAt }

            val body = mapOf(
                "error" to "Stress test failed",
                "message" to ex.message,
                "skuId" to id,
                "grossMarginPercent" to latest?.grossMarginPercent,
                "netMarginPercent" to latest?.netMarginPercent
            )
            ResponseEntity.unprocessableEntity().body(body)
        }
    }
}
