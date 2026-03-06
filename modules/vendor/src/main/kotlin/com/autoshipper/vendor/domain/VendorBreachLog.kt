package com.autoshipper.vendor.domain

import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "vendor_breach_log")
class VendorBreachLog(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    val id: UUID = UUID.randomUUID(),

    @Column(name = "vendor_id", nullable = false)
    val vendorId: UUID,

    @Column(name = "breach_rate", nullable = false)
    val breachRate: BigDecimal,

    @Column(name = "threshold", nullable = false)
    val threshold: BigDecimal,

    @Column(name = "occurred_at", nullable = false)
    val occurredAt: Instant = Instant.now()
)
