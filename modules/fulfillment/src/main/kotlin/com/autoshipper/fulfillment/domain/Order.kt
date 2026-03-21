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

    @Column(name = "payment_intent_id", nullable = false)
    val paymentIntentId: String,

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    var status: OrderStatus = OrderStatus.PENDING,

    @Embedded
    var shipmentDetails: ShipmentDetails = ShipmentDetails(),

    @Version
    var version: Long = 0,

    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),

    @Column(name = "channel")
    var channel: String? = null,

    @Column(name = "channel_order_id")
    var channelOrderId: String? = null,

    @Column(name = "channel_order_number")
    var channelOrderNumber: String? = null,

    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now()
) {
    fun orderId(): OrderId = OrderId(id)
    fun skuId(): SkuId = SkuId(skuId)
    fun vendorId(): VendorId = VendorId(vendorId)
    fun totalAmount(): Money = Money.of(totalAmount, totalCurrency)

    fun updateStatus(newStatus: OrderStatus) {
        val allowed = VALID_TRANSITIONS[status]
            ?: error("No transitions defined from status $status")
        require(newStatus in allowed) {
            "Invalid order transition: $status → $newStatus (allowed: $allowed)"
        }
        status = newStatus
        updatedAt = Instant.now()
    }

    companion object {
        private val VALID_TRANSITIONS: Map<OrderStatus, Set<OrderStatus>> = mapOf(
            OrderStatus.PENDING to setOf(OrderStatus.CONFIRMED, OrderStatus.REFUNDED),
            OrderStatus.CONFIRMED to setOf(OrderStatus.SHIPPED, OrderStatus.REFUNDED),
            OrderStatus.SHIPPED to setOf(OrderStatus.DELIVERED, OrderStatus.REFUNDED),
            OrderStatus.DELIVERED to setOf(OrderStatus.RETURNED, OrderStatus.REFUNDED),
            OrderStatus.REFUNDED to emptySet(),
            OrderStatus.RETURNED to emptySet()
        )
    }
}
