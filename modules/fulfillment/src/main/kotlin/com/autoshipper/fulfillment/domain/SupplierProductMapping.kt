package com.autoshipper.fulfillment.domain

import jakarta.persistence.*
import org.springframework.data.domain.Persistable
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "supplier_product_mappings")
class SupplierProductMapping(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    @get:JvmName("_internalId")
    val id: UUID = UUID.randomUUID(),

    @Column(name = "sku_id", nullable = false)
    val skuId: UUID,

    @Column(name = "supplier", nullable = false)
    val supplier: String,

    @Column(name = "supplier_product_id", nullable = false)
    val supplierProductId: String,

    @Column(name = "supplier_variant_id", nullable = false)
    val supplierVariantId: String,

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
