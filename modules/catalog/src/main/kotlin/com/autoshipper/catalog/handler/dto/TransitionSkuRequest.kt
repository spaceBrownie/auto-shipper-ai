package com.autoshipper.catalog.handler.dto

import jakarta.validation.constraints.NotBlank

data class TransitionSkuRequest(
    @field:NotBlank val state: String
)
