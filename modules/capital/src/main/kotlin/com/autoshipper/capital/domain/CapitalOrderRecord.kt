package com.autoshipper.capital.domain

import com.autoshipper.shared.identity.OrderId
import com.autoshipper.shared.identity.SkuId
import com.autoshipper.shared.money.Currency
import com.autoshipper.shared.money.Money
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "capital_order_records")
class CapitalOrderRecord(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    val id: UUID = UUID.randomUUID(),

    @Column(name = "order_id", nullable = false, unique = true)
    val orderId: UUID,

    @Column(name = "sku_id", nullable = false)
    val skuId: UUID,

    @Column(name = "total_amount", nullable = false, precision = 19, scale = 4)
    val totalAmount: BigDecimal,

    @Enumerated(EnumType.STRING)
    @Column(name = "currency", nullable = false, length = 3)
    val currency: Currency,

    @Column(name = "status", nullable = false, length = 30)
    var status: String,

    @Column(name = "refunded", nullable = false)
    var refunded: Boolean = false,

    @Column(name = "chargebacked", nullable = false)
    var chargebacked: Boolean = false,

    @Column(name = "recorded_at", nullable = false, updatable = false)
    val recordedAt: Instant = Instant.now()
) {
    fun orderId(): OrderId = OrderId(orderId)
    fun skuId(): SkuId = SkuId(skuId)
    fun totalAmount(): Money = Money.of(totalAmount, currency)
}
