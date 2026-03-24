package com.autoshipper.portfolio.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
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
@Table(name = "experiments")
class Experiment(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    @get:JvmName("_internalId")
    val id: UUID = UUID.randomUUID(),

    @Column(name = "name", nullable = false)
    val name: String,

    @Column(name = "hypothesis_description", nullable = false)
    val hypothesisDescription: String,

    @Column(name = "source_signal")
    val sourceSignal: String? = null,

    @Column(name = "estimated_margin_per_unit", precision = 19, scale = 4)
    val estimatedMarginPerUnit: BigDecimal? = null,

    @Column(name = "estimated_margin_currency", length = 3)
    val estimatedMarginCurrency: String? = null,

    @Column(name = "validation_window_days", nullable = false)
    val validationWindowDays: Int,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    var status: ExperimentStatus = ExperimentStatus.ACTIVE,

    @Column(name = "launched_sku_id")
    var launchedSkuId: UUID? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()
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
