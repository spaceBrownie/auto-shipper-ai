package com.autoshipper.pricing.persistence

import jakarta.persistence.*
import org.springframework.data.domain.Persistable
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "sku_prices")
class SkuPriceEntity(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    @get:JvmName("_internalId")
    val id: UUID = UUID.randomUUID(),

    @Column(name = "sku_id", nullable = false, updatable = false)
    val skuId: UUID,

    @Column(name = "currency", nullable = false, length = 3)
    var currency: String,

    @Column(name = "current_price_amount", nullable = false, precision = 19, scale = 4)
    var currentPriceAmount: BigDecimal,

    @Column(name = "current_margin_percent", nullable = false, precision = 8, scale = 4)
    var currentMarginPercent: BigDecimal,

    @Column(name = "current_fully_burdened_amount", precision = 19, scale = 4)
    var currentFullyBurdenedAmount: BigDecimal? = null,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @Version
    @Column(name = "version", nullable = false)
    var version: Long = 0
) : Persistable<UUID> {

    @Transient
    private var isNew: Boolean = true

    override fun getId(): UUID = id

    override fun isNew(): Boolean = isNew

    @PostPersist
    @PostLoad
    fun markNotNew() {
        isNew = false
    }
}
