package com.autoshipper.fulfillment.domain

import com.autoshipper.shared.identity.OrderId
import com.autoshipper.shared.identity.SkuId
import com.autoshipper.shared.identity.VendorId
import com.autoshipper.shared.money.Currency
import com.autoshipper.shared.money.Money
import jakarta.persistence.*
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "orders")
class Order(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    val id: UUID = UUID.randomUUID(),

    @Column(name = "idempotency_key", nullable = false, unique = true)
    val idempotencyKey: String,

    @Column(name = "sku_id", nullable = false)
    val skuId: UUID,

    @Column(name = "vendor_id", nullable = false)
    val vendorId: UUID,

    @Column(name = "customer_id", nullable = false)
    val customerId: UUID,

    @Column(name = "total_amount", nullable = false)
    val totalAmount: BigDecimal,

    @Enumerated(EnumType.STRING)
    @Column(name = "total_currency", nullable = false)
    val totalCurrency: Currency,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: OrderStatus = OrderStatus.PENDING,

    @Embedded
    var shipmentDetails: ShipmentDetails = ShipmentDetails(),

    @Version
    var version: Long = 0,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
) {
    fun orderId(): OrderId = OrderId(id)
    fun skuId(): SkuId = SkuId(skuId)
    fun vendorId(): VendorId = VendorId(vendorId)
    fun totalAmount(): Money = Money.of(totalAmount, totalCurrency)

    fun updateStatus(newStatus: OrderStatus) {
        status = newStatus
        updatedAt = Instant.now()
    }
}
