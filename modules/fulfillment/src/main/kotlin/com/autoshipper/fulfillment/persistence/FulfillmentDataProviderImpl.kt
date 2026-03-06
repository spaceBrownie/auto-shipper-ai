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

    override fun countFulfillmentsSince(vendorId: UUID, since: Instant): Long =
        orderRepository.countByVendorIdAndStatusAndCreatedAtGreaterThanEqual(
            vendorId,
            OrderStatus.DELIVERED,
            since
        )

    /**
     * Counts distinct violations: orders where delay was detected OR status is REFUNDED.
     * Uses a single OR query to avoid double-counting orders that are both delayed and refunded.
     */
    override fun countViolationsSince(vendorId: UUID, since: Instant): Long =
        orderRepository.countViolations(vendorId, since)
}
