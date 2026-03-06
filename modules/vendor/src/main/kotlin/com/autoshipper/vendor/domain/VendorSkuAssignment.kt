package com.autoshipper.vendor.domain

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "vendor_sku_assignments")
class VendorSkuAssignment(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    val id: UUID = UUID.randomUUID(),

    @Column(name = "vendor_id", nullable = false)
    val vendorId: UUID,

    @Column(name = "sku_id", nullable = false)
    val skuId: UUID,

    @Column(name = "assigned_at", nullable = false)
    val assignedAt: Instant = Instant.now(),

    @Column(name = "active", nullable = false)
    var active: Boolean = true
)
