package com.autoshipper.catalog.persistence

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "sku_state_history")
class SkuStateHistory(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    val id: Long? = null,

    @Column(name = "sku_id", nullable = false)
    val skuId: UUID,

    @Column(name = "from_state", nullable = false)
    val fromState: String,

    @Column(name = "to_state", nullable = false)
    val toState: String,

    @Column(name = "transitioned_at", nullable = false)
    val transitionedAt: Instant = Instant.now()
)
