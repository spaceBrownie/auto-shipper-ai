package com.autoshipper.vendor.domain

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "vendor_fulfillment_records")
class VendorFulfillmentRecord(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    val id: UUID = UUID.randomUUID(),

    @Column(name = "vendor_id", nullable = false)
    val vendorId: UUID,

    @Column(name = "order_id", nullable = false)
    val orderId: UUID,

    @Column(name = "is_violation", nullable = false)
    val isViolation: Boolean,

    @Column(name = "violation_type")
    val violationType: String? = null,

    @Column(name = "recorded_at", nullable = false)
    val recordedAt: Instant = Instant.now()
)
