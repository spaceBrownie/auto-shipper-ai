package com.autoshipper.vendor.handler.dto

import jakarta.validation.constraints.NotNull
import java.util.UUID

data class RecordFulfillmentRequest(
    @field:NotNull val orderId: UUID,
    val isViolation: Boolean = false,
    val violationType: String? = null
)
