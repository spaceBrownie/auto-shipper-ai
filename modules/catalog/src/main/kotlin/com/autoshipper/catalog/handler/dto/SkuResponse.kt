package com.autoshipper.catalog.handler.dto

import com.autoshipper.catalog.domain.Sku
import java.time.Instant
import java.util.UUID

data class SkuResponse(
    val id: UUID,
    val name: String,
    val category: String,
    val currentState: String,
    val terminationReason: String?,
    val createdAt: Instant,
    val updatedAt: Instant
) {
    companion object {
        fun from(sku: Sku) = SkuResponse(
            id = sku.id,
            name = sku.name,
            category = sku.category,
            currentState = sku.currentStateDiscriminator,
            terminationReason = sku.terminationReasonName,
            createdAt = sku.createdAt,
            updatedAt = sku.updatedAt
        )
    }
}
