package com.autoshipper.fulfillment.persistence

import com.autoshipper.fulfillment.domain.Order
import com.autoshipper.fulfillment.domain.OrderStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
interface OrderRepository : JpaRepository<Order, UUID> {
    fun findByIdempotencyKey(idempotencyKey: String): Order?
    fun findBySkuId(skuId: UUID): List<Order>
    fun findByVendorId(vendorId: UUID): List<Order>
    fun findByStatus(status: OrderStatus): List<Order>
    fun findByVendorIdAndStatusIn(vendorId: UUID, statuses: List<OrderStatus>): List<Order>
    fun countByVendorIdAndStatusAndCreatedAtGreaterThanEqual(
        vendorId: UUID,
        status: OrderStatus,
        since: Instant
    ): Long
    fun countByVendorIdAndShipmentDetailsDelayDetectedAndCreatedAtGreaterThanEqual(
        vendorId: UUID,
        delayDetected: Boolean,
        since: Instant
    ): Long
    fun countByVendorIdAndStatusInAndCreatedAtGreaterThanEqual(
        vendorId: UUID,
        statuses: List<OrderStatus>,
        since: Instant
    ): Long
}
