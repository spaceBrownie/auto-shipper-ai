package com.autoshipper.catalog.persistence

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "platform_listings")
class PlatformListingEntity(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    val id: UUID = UUID.randomUUID(),

    @Column(name = "sku_id", nullable = false, updatable = false)
    val skuId: UUID,

    @Column(name = "platform", nullable = false, length = 50, updatable = false)
    val platform: String,

    @Column(name = "external_listing_id", nullable = false, length = 255, updatable = false)
    val externalListingId: String,

    @Column(name = "external_variant_id", length = 255, updatable = false)
    val externalVariantId: String? = null,

    @Column(name = "current_price_amount", nullable = false, precision = 19, scale = 4)
    var currentPriceAmount: BigDecimal,

    @Column(name = "currency", nullable = false, length = 3, updatable = false)
    val currency: String,

    @Column(name = "status", nullable = false, length = 30)
    var status: String = "DRAFT",

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
)
