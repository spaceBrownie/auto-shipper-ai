package com.autoshipper.pricing.persistence

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "sku_pricing_history")
class SkuPricingHistoryEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long? = null,

    @Column(name = "sku_id", nullable = false, updatable = false)
    val skuId: UUID,

    @Column(name = "currency", nullable = false, length = 3)
    val currency: String,

    @Column(name = "price_amount", nullable = false, precision = 19, scale = 4)
    val priceAmount: BigDecimal,

    @Column(name = "margin_percent", nullable = false, precision = 8, scale = 4)
    val marginPercent: BigDecimal,

    @Column(name = "signal_type", nullable = false, length = 50)
    val signalType: String,

    @Column(name = "decision_type", nullable = false, length = 50)
    val decisionType: String,

    @Column(name = "decision_reason", length = 500)
    val decisionReason: String? = null,

    @Column(name = "recorded_at", nullable = false, updatable = false)
    val recordedAt: Instant = Instant.now()
)
