package com.autoshipper.vendor.domain

import com.autoshipper.shared.identity.VendorId
import jakarta.persistence.*
import org.springframework.data.domain.Persistable
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "vendors")
class Vendor(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    @get:JvmName("_internalId")
    val id: UUID = UUID.randomUUID(),

    @Column(name = "name", nullable = false)
    val name: String,

    @Column(name = "contact_email", nullable = false)
    val contactEmail: String,

    @Column(name = "status", nullable = false)
    var status: String = "PENDING",

    @Embedded
    var checklist: VendorActivationChecklist = VendorActivationChecklist(),

    @Column(name = "deactivated_at")
    var deactivatedAt: Instant? = null,

    @Version
    var version: Long = 0,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
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

    fun vendorId(): VendorId = VendorId(id)

    fun currentStatus(): VendorStatus = VendorStatus.valueOf(status)

    fun activate() {
        if (!checklist.isComplete()) {
            val missing = buildList {
                if (!checklist.slaConfirmed) add("slaConfirmed")
                if (!checklist.defectRateDocumented) add("defectRateDocumented")
                if (!checklist.scalabilityConfirmed) add("scalabilityConfirmed")
                if (!checklist.fulfillmentTimesConfirmed) add("fulfillmentTimesConfirmed")
                if (!checklist.refundPolicyConfirmed) add("refundPolicyConfirmed")
            }
            throw VendorNotActivatedException(missing)
        }
        status = VendorStatus.ACTIVE.name
        updatedAt = Instant.now()
    }

    fun suspend() {
        status = VendorStatus.SUSPENDED.name
        deactivatedAt = Instant.now()
        updatedAt = Instant.now()
    }
}
