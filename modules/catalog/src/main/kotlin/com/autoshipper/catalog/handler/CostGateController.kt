package com.autoshipper.catalog.handler

import com.autoshipper.catalog.domain.Address
import com.autoshipper.catalog.domain.PackageDimensions
import com.autoshipper.catalog.domain.service.CostGateService
import com.autoshipper.catalog.handler.dto.CostEnvelopeResponse
import com.autoshipper.catalog.handler.dto.VerifyCostsRequest
import com.autoshipper.shared.identity.SkuId
import com.autoshipper.shared.money.Currency
import com.autoshipper.shared.money.Money
import com.autoshipper.shared.money.Percentage
import jakarta.validation.Valid
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/api/skus")
class CostGateController(private val costGateService: CostGateService) {

    @PostMapping("/{id}/verify-costs")
    fun verifyCosts(
        @PathVariable id: String,
        @Valid @RequestBody request: VerifyCostsRequest
    ): ResponseEntity<CostEnvelopeResponse> {
        val skuId = SkuId.of(id)

        val envelope = costGateService.verify(
            skuId = skuId,
            vendorQuote = request.vendorQuote.toMoney(),
            packageDims = request.packageDimensions.toDomain(),
            origin = request.origin.toDomain(),
            destination = request.destination.toDomain(),
            cacEstimate = request.cacEstimate.toMoney(),
            jurisdiction = request.jurisdiction,
            warehouseCost = request.warehouseCost.toMoney(),
            customerServiceCost = request.customerServiceCost.toMoney(),
            packagingCost = request.packagingCost.toMoney(),
            returnHandlingCost = request.returnHandlingCost.toMoney(),
            refundAllowanceRate = Percentage.of(request.refundAllowanceRatePercent),
            chargebackAllowanceRate = Percentage.of(request.chargebackAllowanceRatePercent),
            taxesAndDuties = request.taxesAndDuties.toMoney(),
            estimatedOrderValue = request.estimatedOrderValue.toMoney()
        )

        return ResponseEntity.ok(CostEnvelopeResponse.from(envelope))
    }
}

private fun com.autoshipper.catalog.handler.dto.MoneyDto.toMoney(): Money =
    Money.of(amount, Currency.valueOf(currency.uppercase()))

private fun com.autoshipper.catalog.handler.dto.PackageDimensionsDto.toDomain(): PackageDimensions =
    PackageDimensions(lengthCm = lengthCm, widthCm = widthCm, heightCm = heightCm, weightKg = weightKg)

private fun com.autoshipper.catalog.handler.dto.AddressDto.toDomain(): Address =
    Address(
        street = street,
        city = city,
        stateOrProvince = stateOrProvince,
        postalCode = postalCode,
        countryCode = countryCode.uppercase()
    )
