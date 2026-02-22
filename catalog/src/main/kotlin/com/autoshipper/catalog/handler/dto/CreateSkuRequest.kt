package com.autoshipper.catalog.handler.dto

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Size

data class CreateSkuRequest(
    @field:NotBlank @field:Size(max = 255) val name: String,
    @field:NotBlank @field:Size(max = 100) val category: String
)
