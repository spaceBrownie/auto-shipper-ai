package com.autoshipper.catalog.persistence

import jakarta.persistence.*
import org.springframework.data.domain.Persistable
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "sku_cost_envelopes")
class CostEnvelopeEntity(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    @get:JvmName("_internalId")
    val id: UUID = UUID.randomUUID(),

    @Column(name = "sku_id", nullable = false, updatable = false)
    val skuId: UUID,

    @Column(name = "currency", nullable = false, length = 3)
    val currency: String,

    @Column(name = "supplier_unit_cost_amount", nullable = false, precision = 19, scale = 4)
    val supplierUnitCostAmount: BigDecimal,

    @Column(name = "inbound_shipping_amount", nullable = false, precision = 19, scale = 4)
    val inboundShippingAmount: BigDecimal,

    @Column(name = "outbound_shipping_amount", nullable = false, precision = 19, scale = 4)
    val outboundShippingAmount: BigDecimal,

    @Column(name = "platform_fee_amount", nullable = false, precision = 19, scale = 4)
    val platformFeeAmount: BigDecimal,

    @Column(name = "processing_fee_amount", nullable = false, precision = 19, scale = 4)
    val processingFeeAmount: BigDecimal,

    @Column(name = "packaging_cost_amount", nullable = false, precision = 19, scale = 4)
    val packagingCostAmount: BigDecimal,

    @Column(name = "return_handling_cost_amount", nullable = false, precision = 19, scale = 4)
    val returnHandlingCostAmount: BigDecimal,

    @Column(name = "customer_acquisition_cost_amount", nullable = false, precision = 19, scale = 4)
    val customerAcquisitionCostAmount: BigDecimal,

    @Column(name = "warehousing_cost_amount", nullable = false, precision = 19, scale = 4)
    val warehousingCostAmount: BigDecimal,

    @Column(name = "customer_service_cost_amount", nullable = false, precision = 19, scale = 4)
    val customerServiceCostAmount: BigDecimal,

    @Column(name = "refund_allowance_amount", nullable = false, precision = 19, scale = 4)
    val refundAllowanceAmount: BigDecimal,

    @Column(name = "chargeback_allowance_amount", nullable = false, precision = 19, scale = 4)
    val chargebackAllowanceAmount: BigDecimal,

    @Column(name = "taxes_and_duties_amount", nullable = false, precision = 19, scale = 4)
    val taxesAndDutiesAmount: BigDecimal,

    @Column(name = "verified_at", nullable = false)
    val verifiedAt: Instant,

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
