package com.autoshipper.portfolio.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "scaling_flags")
class ScalingFlag(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    val id: UUID = UUID.randomUUID(),

    @Column(name = "sku_id", nullable = false)
    val skuId: UUID,

    @Column(name = "flagged_at", nullable = false, updatable = false)
    val flaggedAt: Instant = Instant.now(),

    @Column(name = "resolved_at")
    var resolvedAt: Instant? = null
)
