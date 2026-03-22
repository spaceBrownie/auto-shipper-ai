package com.autoshipper.portfolio.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.PostLoad
import jakarta.persistence.PostPersist
import jakarta.persistence.Table
import jakarta.persistence.Transient
import org.springframework.data.domain.Persistable
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "kill_recommendations")
class KillRecommendation(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    @get:JvmName("_internalId")
    val id: UUID = UUID.randomUUID(),

    @Column(name = "sku_id", nullable = false)
    val skuId: UUID,

    @Column(name = "days_negative", nullable = false)
    val daysNegative: Int,

    @Column(name = "avg_net_margin", nullable = false, precision = 5, scale = 2)
    val avgNetMargin: BigDecimal,

    @Column(name = "detected_at", nullable = false, updatable = false)
    val detectedAt: Instant = Instant.now(),

    @Column(name = "confirmed_at")
    var confirmedAt: Instant? = null
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
