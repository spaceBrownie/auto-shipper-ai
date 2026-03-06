package com.autoshipper.fulfillment.persistence

import com.autoshipper.fulfillment.domain.OrderStatus
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*
import java.time.Instant
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class FulfillmentDataProviderImplTest {

    @Mock
    lateinit var orderRepository: OrderRepository

    @InjectMocks
    lateinit var provider: FulfillmentDataProviderImpl

    private val vendorId = UUID.randomUUID()
    private val since = Instant.now().minusSeconds(2_592_000) // 30 days ago

    @Test
    fun `countFulfillmentsSince counts DELIVERED orders`() {
        whenever(
            orderRepository.countByVendorIdAndStatusAndCreatedAtGreaterThanEqual(
                vendorId, OrderStatus.DELIVERED, since
            )
        ).thenReturn(42L)

        val count = provider.countFulfillmentsSince(vendorId, since)

        assert(count == 42L) { "Expected 42 fulfilled orders, got $count" }
        verify(orderRepository).countByVendorIdAndStatusAndCreatedAtGreaterThanEqual(
            vendorId, OrderStatus.DELIVERED, since
        )
    }

    @Test
    fun `countViolationsSince counts delayed and refunded orders`() {
        whenever(
            orderRepository.countByVendorIdAndShipmentDetailsDelayDetectedAndCreatedAtGreaterThanEqual(
                vendorId, true, since
            )
        ).thenReturn(5L)
        whenever(
            orderRepository.countByVendorIdAndStatusAndCreatedAtGreaterThanEqual(
                vendorId, OrderStatus.REFUNDED, since
            )
        ).thenReturn(3L)

        val count = provider.countViolationsSince(vendorId, since)

        assert(count == 8L) { "Expected 8 violations (5 delays + 3 refunds), got $count" }
    }

    @Test
    fun `countViolationsSince returns zero when no violations`() {
        whenever(
            orderRepository.countByVendorIdAndShipmentDetailsDelayDetectedAndCreatedAtGreaterThanEqual(
                vendorId, true, since
            )
        ).thenReturn(0L)
        whenever(
            orderRepository.countByVendorIdAndStatusAndCreatedAtGreaterThanEqual(
                vendorId, OrderStatus.REFUNDED, since
            )
        ).thenReturn(0L)

        val count = provider.countViolationsSince(vendorId, since)

        assert(count == 0L) { "Expected 0 violations, got $count" }
    }

    @Test
    fun `countFulfillmentsSince returns zero when no delivered orders`() {
        whenever(
            orderRepository.countByVendorIdAndStatusAndCreatedAtGreaterThanEqual(
                vendorId, OrderStatus.DELIVERED, since
            )
        ).thenReturn(0L)

        val count = provider.countFulfillmentsSince(vendorId, since)

        assert(count == 0L) { "Expected 0 fulfillments, got $count" }
    }
}
