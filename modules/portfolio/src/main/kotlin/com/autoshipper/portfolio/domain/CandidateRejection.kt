package com.autoshipper.portfolio.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "candidate_rejections")
class CandidateRejection(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    val id: UUID = UUID.randomUUID(),

    @Column(name = "scan_run_id", nullable = false)
    val scanRunId: UUID,

    @Column(name = "product_name", nullable = false, length = 500)
    val productName: String,

    @Column(name = "category", nullable = false, length = 255)
    val category: String,

    @Column(name = "source_type", nullable = false, length = 50)
    val sourceType: String,

    @Column(name = "rejection_reason", nullable = false, length = 255)
    val rejectionReason: String,

    @Column(name = "demand_score", precision = 5, scale = 4)
    val demandScore: BigDecimal? = null,

    @Column(name = "margin_potential_score", precision = 5, scale = 4)
    val marginPotentialScore: BigDecimal? = null,

    @Column(name = "competition_score", precision = 5, scale = 4)
    val competitionScore: BigDecimal? = null,

    @Column(name = "composite_score", precision = 5, scale = 4)
    val compositeScore: BigDecimal? = null,

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    val metadata: String? = null,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now()
)
