package com.autoshipper.catalog.domain

import com.autoshipper.shared.identity.SkuId
import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "skus")
class Sku(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    val id: UUID = UUID.randomUUID(),

    @Column(name = "name", nullable = false)
    val name: String,

    @Column(name = "category", nullable = false)
    val category: String,

    @Column(name = "current_state", nullable = false)
    var currentStateDiscriminator: String = "IDEATION",

    @Column(name = "termination_reason")
    var terminationReasonName: String? = null,

    @Version
    var version: Long = 0,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
) {
    fun skuId(): SkuId = SkuId(id)

    fun currentState(): SkuState = SkuState.fromDiscriminator(
        currentStateDiscriminator,
        terminationReasonName?.let { TerminationReason.valueOf(it) }
    )

    fun applyTransition(newState: SkuState) {
        currentStateDiscriminator = newState.toDiscriminator()
        terminationReasonName = if (newState is SkuState.Terminated) newState.reason.name else null
        updatedAt = Instant.now()
    }
}
