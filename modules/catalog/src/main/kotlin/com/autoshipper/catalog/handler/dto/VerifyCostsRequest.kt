package com.autoshipper.catalog.handler.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.DecimalMin
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.Size
import java.math.BigDecimal

data class VerifyCostsRequest(
    @field:NotNull @field:Valid
    val vendorQuote: MoneyDto,

    @field:NotNull @field:Valid
    val packageDimensions: PackageDimensionsDto,

    @field:NotNull @field:Valid
    val origin: AddressDto,

    @field:NotNull @field:Valid
    val destination: AddressDto,

    @field:NotNull @field:Valid
    val cacEstimate: MoneyDto,

    @field:NotBlank
    val jurisdiction: String,

    @field:NotNull @field:Valid
    val warehouseCost: MoneyDto,

    @field:NotNull @field:Valid
    val customerServiceCost: MoneyDto,

    @field:NotNull @field:Valid
    val packagingCost: MoneyDto,

    @field:NotNull @field:Valid
    val returnHandlingCost: MoneyDto,

    /** Refund allowance as a percentage, e.g. 5.0 means 5% */
    @field:NotNull
    @field:DecimalMin("0.0")
    val refundAllowanceRatePercent: BigDecimal,

    /** Chargeback allowance as a percentage, e.g. 2.0 means 2% */
    @field:NotNull
    @field:DecimalMin("0.0")
    val chargebackAllowanceRatePercent: BigDecimal,

    @field:NotNull @field:Valid
    val taxesAndDuties: MoneyDto,

    @field:NotNull @field:Valid
    val estimatedOrderValue: MoneyDto
)

data class MoneyDto(
    @field:NotNull
    @field:DecimalMin("0.0")
    val amount: BigDecimal,

    @field:NotBlank
    val currency: String
)

data class PackageDimensionsDto(
    @field:NotNull @field:DecimalMin("0.01")
    val lengthCm: BigDecimal,

    @field:NotNull @field:DecimalMin("0.01")
    val widthCm: BigDecimal,

    @field:NotNull @field:DecimalMin("0.01")
    val heightCm: BigDecimal,

    @field:NotNull @field:DecimalMin("0.01")
    val weightKg: BigDecimal
)

data class AddressDto(
    @field:NotBlank
    val street: String,

    @field:NotBlank
    val city: String,

    @field:NotBlank
    val stateOrProvince: String,

    @field:NotBlank
    val postalCode: String,

    @field:NotBlank @field:Size(min = 2, max = 2)
    val countryCode: String
)
