package com.autoshipper.portfolio.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "demand_scan_runs")
class DemandScanRun(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    val id: UUID = UUID.randomUUID(),

    @Column(name = "started_at", nullable = false, updatable = false)
    val startedAt: Instant = Instant.now(),

    @Column(name = "completed_at")
    var completedAt: Instant? = null,

    @Column(name = "status", nullable = false, length = 30)
    var status: String = "RUNNING",

    @Column(name = "sources_queried", nullable = false)
    var sourcesQueried: Int = 0,

    @Column(name = "candidates_found", nullable = false)
    var candidatesFound: Int = 0,

    @Column(name = "experiments_created", nullable = false)
    var experimentsCreated: Int = 0,

    @Column(name = "rejections", nullable = false)
    var rejections: Int = 0
)
