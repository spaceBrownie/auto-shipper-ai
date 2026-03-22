package com.autoshipper.capital.domain

import com.autoshipper.shared.identity.SkuId
import com.autoshipper.shared.money.Currency
import com.autoshipper.shared.money.Money
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.PostLoad
import jakarta.persistence.PostPersist
import jakarta.persistence.Table
import jakarta.persistence.Transient
import jakarta.persistence.UniqueConstraint
import org.springframework.data.domain.Persistable
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Entity
@Table(
    name = "margin_snapshots",
    uniqueConstraints = [UniqueConstraint(columnNames = ["sku_id", "snapshot_date"])]
)
class MarginSnapshot(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    @get:JvmName("_internalId")
    val id: UUID = UUID.randomUUID(),

    @Column(name = "sku_id", nullable = false)
    val skuId: UUID,

    @Column(name = "snapshot_date", nullable = false)
    val snapshotDate: LocalDate,

    @Column(name = "gross_margin", nullable = false, precision = 5, scale = 2)
    val grossMargin: BigDecimal,

    @Column(name = "net_margin", nullable = false, precision = 5, scale = 2)
    val netMargin: BigDecimal,

    @Column(name = "revenue_amount", nullable = false, precision = 19, scale = 4)
    val revenueAmount: BigDecimal,

    @Enumerated(EnumType.STRING)
    @Column(name = "revenue_currency", nullable = false, length = 3)
    val revenueCurrency: Currency,

    @Column(name = "total_cost_amount", nullable = false, precision = 19, scale = 4)
    val totalCostAmount: BigDecimal,

    @Enumerated(EnumType.STRING)
    @Column(name = "total_cost_currency", nullable = false, length = 3)
    val totalCostCurrency: Currency,

    @Column(name = "refund_rate", nullable = false, precision = 5, scale = 2)
    val refundRate: BigDecimal,

    @Column(name = "chargeback_rate", nullable = false, precision = 5, scale = 2)
    val chargebackRate: BigDecimal,

    @Column(name = "cac_variance", nullable = false, precision = 5, scale = 2)
    val cacVariance: BigDecimal,

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

    fun skuId(): SkuId = SkuId(skuId)
    fun revenue(): Money = Money.of(revenueAmount, revenueCurrency)
    fun totalCost(): Money = Money.of(totalCostAmount, totalCostCurrency)
    fun grossMarginPercent(): BigDecimal = grossMargin
    fun netMarginPercent(): BigDecimal = netMargin
    fun refundRatePercent(): BigDecimal = refundRate
    fun chargebackRatePercent(): BigDecimal = chargebackRate
    fun cacVariancePercent(): BigDecimal = cacVariance
}
