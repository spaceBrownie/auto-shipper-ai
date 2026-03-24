package com.autoshipper.catalog.persistence

import jakarta.persistence.*
import org.springframework.data.domain.Persistable
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "sku_stress_test_results")
class StressTestResultEntity(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    @get:JvmName("_internalId")
    val id: UUID = UUID.randomUUID(),

    @Column(name = "sku_id", nullable = false, updatable = false)
    val skuId: UUID,

    @Column(name = "currency", nullable = false, length = 3)
    val currency: String,

    @Column(name = "stressed_shipping_amount", nullable = false, precision = 19, scale = 4)
    val stressedShippingAmount: BigDecimal,

    @Column(name = "stressed_cac_amount", nullable = false, precision = 19, scale = 4)
    val stressedCacAmount: BigDecimal,

    @Column(name = "stressed_supplier_amount", nullable = false, precision = 19, scale = 4)
    val stressedSupplierAmount: BigDecimal,

    @Column(name = "stressed_refund_amount", nullable = false, precision = 19, scale = 4)
    val stressedRefundAmount: BigDecimal,

    @Column(name = "stressed_chargeback_amount", nullable = false, precision = 19, scale = 4)
    val stressedChargebackAmount: BigDecimal,

    @Column(name = "stressed_total_cost_amount", nullable = false, precision = 19, scale = 4)
    val stressedTotalCostAmount: BigDecimal,

    @Column(name = "estimated_price_amount", nullable = false, precision = 19, scale = 4)
    val estimatedPriceAmount: BigDecimal,

    @Column(name = "gross_margin_percent", nullable = false, precision = 8, scale = 4)
    val grossMarginPercent: BigDecimal,

    @Column(name = "net_margin_percent", nullable = false, precision = 8, scale = 4)
    val netMarginPercent: BigDecimal,

    @Column(name = "passed", nullable = false)
    val passed: Boolean,

    @Column(name = "shipping_multiplier_used", nullable = false, precision = 8, scale = 4)
    val shippingMultiplierUsed: BigDecimal,

    @Column(name = "cac_increase_percent_used", nullable = false, precision = 8, scale = 4)
    val cacIncreasePercentUsed: BigDecimal,

    @Column(name = "supplier_increase_percent_used", nullable = false, precision = 8, scale = 4)
    val supplierIncreasePercentUsed: BigDecimal,

    @Column(name = "refund_rate_percent_used", nullable = false, precision = 8, scale = 4)
    val refundRatePercentUsed: BigDecimal,

    @Column(name = "chargeback_rate_percent_used", nullable = false, precision = 8, scale = 4)
    val chargebackRatePercentUsed: BigDecimal,

    @Column(name = "tested_at", nullable = false)
    val testedAt: Instant,

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
