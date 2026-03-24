package com.autoshipper.portfolio.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.PostLoad
import jakarta.persistence.PostPersist
import jakarta.persistence.Table
import jakarta.persistence.Transient
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import org.springframework.data.domain.Persistable
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "demand_candidates")
class DemandCandidate(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    @get:JvmName("_internalId")
    val id: UUID = UUID.randomUUID(),

    @Column(name = "scan_run_id", nullable = false)
    val scanRunId: UUID,

    @Column(name = "product_name", nullable = false, length = 500)
    val productName: String,

    @Column(name = "category", nullable = false, length = 255)
    val category: String,

    @Column(name = "description")
    val description: String? = null,

    @Column(name = "source_type", nullable = false, length = 50)
    val sourceType: String,

    @Column(name = "supplier_unit_cost", precision = 19, scale = 4)
    val supplierUnitCost: BigDecimal? = null,

    @Column(name = "supplier_cost_currency", length = 3)
    val supplierCostCurrency: String? = null,

    @Column(name = "estimated_selling_price", precision = 19, scale = 4)
    val estimatedSellingPrice: BigDecimal? = null,

    @Column(name = "selling_price_currency", length = 3)
    val sellingPriceCurrency: String? = null,

    @Column(name = "demand_score", nullable = false, precision = 5, scale = 4)
    val demandScore: BigDecimal,

    @Column(name = "margin_potential_score", nullable = false, precision = 5, scale = 4)
    val marginPotentialScore: BigDecimal,

    @Column(name = "competition_score", nullable = false, precision = 5, scale = 4)
    val competitionScore: BigDecimal,

    @Column(name = "composite_score", nullable = false, precision = 5, scale = 4)
    val compositeScore: BigDecimal,

    @Column(name = "passed", nullable = false)
    val passed: Boolean,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "demand_signals", columnDefinition = "jsonb")
    val demandSignals: String? = null,

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
