package com.autoshipper.fulfillment.persistence

import com.autoshipper.fulfillment.domain.OrderStatus
import com.autoshipper.vendor.domain.service.VendorFulfillmentDataProvider
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

@Component
@Primary
class FulfillmentDataProviderImpl(
    private val orderRepository: OrderRepository
) : VendorFulfillmentDataProvider {

    /**
     * Counts DELIVERED orders for the given vendor since the specified instant.
     */
    override fun countFulfillmentsSince(vendorId: UUID, since: Instant): Long =
        orderRepository.countByVendorIdAndStatusAndCreatedAtGreaterThanEqual(
            vendorId,
            OrderStatus.DELIVERED,
            since
        )

    /**
     * Counts violations for the given vendor since the specified instant.
     * A violation is an order where delay was detected OR the order was refunded.
     */
    override fun countViolationsSince(vendorId: UUID, since: Instant): Long {
        val delayCount = orderRepository.countByVendorIdAndShipmentDetailsDelayDetectedAndCreatedAtGreaterThanEqual(
            vendorId,
            true,
            since
        )
        val refundedCount = orderRepository.countByVendorIdAndStatusAndCreatedAtGreaterThanEqual(
            vendorId,
            OrderStatus.REFUNDED,
            since
        )
        return delayCount + refundedCount
    }
}
