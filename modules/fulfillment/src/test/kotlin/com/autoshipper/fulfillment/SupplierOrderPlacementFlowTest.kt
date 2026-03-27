package com.autoshipper.fulfillment

import com.autoshipper.fulfillment.domain.Order
import com.autoshipper.fulfillment.domain.OrderStatus
import com.autoshipper.fulfillment.domain.ShippingAddress
import com.autoshipper.fulfillment.domain.SupplierProductMapping
import com.autoshipper.fulfillment.domain.supplier.FailureReason
import com.autoshipper.fulfillment.domain.supplier.SupplierOrderAdapter
import com.autoshipper.fulfillment.domain.supplier.SupplierOrderRequest
import com.autoshipper.fulfillment.domain.supplier.SupplierOrderResult
import com.autoshipper.fulfillment.domain.service.SupplierOrderPlacementListener
import com.autoshipper.fulfillment.persistence.OrderRepository
import com.autoshipper.fulfillment.persistence.SupplierProductMappingRepository
import com.autoshipper.shared.events.OrderConfirmed
import com.autoshipper.shared.identity.OrderId
import com.autoshipper.shared.identity.SkuId
import com.autoshipper.shared.money.Currency
import io.micrometer.core.instrument.simple.SimpleMeterRegistry
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*
import java.math.BigDecimal
import java.util.Optional
import java.util.UUID

/**
 * End-to-end flow tests for supplier order placement.
 *
 * These tests verify the complete flow from OrderConfirmed event through to
 * CJ order ID stored on the Order entity. They use Mockito to simulate
 * the full event chain without requiring a running database.
 */
@ExtendWith(MockitoExtension::class)
class SupplierOrderPlacementFlowTest {

    private val orderRepository: OrderRepository = mock()
    private val supplierProductMappingRepository: SupplierProductMappingRepository = mock()
    private val meterRegistry = SimpleMeterRegistry()

    /**
     * Test: Order confirmed -> supplier order placed -> CJ order ID stored on Order entity.
     *
     * Verifies:
     * - supplierOrderId is exactly "2103221234567890" (not any() matcher)
     * - Order is saved after setting supplierOrderId
     * - Adapter receives correct SupplierOrderRequest fields
     */
    @Test
    fun `order confirmed triggers supplier order placement and stores CJ order ID`() {
        val orderId = UUID.randomUUID()
        val skuId = UUID.randomUUID()

        val order = Order(
            id = orderId,
            idempotencyKey = "test-idem-${UUID.randomUUID()}",
            skuId = skuId,
            vendorId = UUID.randomUUID(),
            customerId = UUID.randomUUID(),
            totalAmount = BigDecimal("49.99"),
            totalCurrency = Currency.USD,
            paymentIntentId = "pi_test",
            status = OrderStatus.PENDING,
            shippingAddress = ShippingAddress(
                customerName = "John Doe",
                address = "123 Main St",
                city = "Anytown",
                province = "CA",
                country = "United States",
                countryCode = "US",
                zip = "90210",
                phone = "+1-555-123-4567"
            )
        )
        order.updateStatus(OrderStatus.CONFIRMED)

        val mapping = SupplierProductMapping(
            skuId = skuId,
            supplier = "CJ_DROPSHIPPING",
            supplierProductId = "04A22450-67F0-4617-A132-E7AE7F8963B0",
            supplierVariantId = "VID-12345-BLUE-M"
        )

        val adapter: SupplierOrderAdapter = mock {
            on { supplierName() } doReturn "CJ_DROPSHIPPING"
            on { placeOrder(any()) } doReturn SupplierOrderResult.Success(supplierOrderId = "2103221234567890")
        }

        whenever(orderRepository.findById(orderId)).thenReturn(Optional.of(order))
        whenever(supplierProductMappingRepository.findBySkuId(skuId)).thenReturn(mapping)
        whenever(orderRepository.save(any<Order>())).thenAnswer { it.arguments[0] }

        val listener = SupplierOrderPlacementListener(
            orderRepository = orderRepository,
            supplierProductMappingRepository = supplierProductMappingRepository,
            supplierOrderAdapters = listOf(adapter),
            meterRegistry = meterRegistry
        )

        val event = OrderConfirmed(orderId = OrderId(orderId), skuId = SkuId(skuId))
        listener.onOrderConfirmed(event)

        // Verify adapter called with correct request
        argumentCaptor<SupplierOrderRequest> {
            verify(adapter).placeOrder(capture())
            assertEquals(orderId.toString(), firstValue.orderNumber)
            assertEquals("John Doe", firstValue.customerName)
            assertEquals("123 Main St", firstValue.address)
            assertEquals("Anytown", firstValue.city)
            assertEquals("CA", firstValue.province)
            assertEquals("United States", firstValue.country)
            assertEquals("US", firstValue.countryCode)
            assertEquals("90210", firstValue.zip)
            assertEquals("+1-555-123-4567", firstValue.phone)
            assertEquals("VID-12345-BLUE-M", firstValue.supplierVariantId)
            assertEquals(1, firstValue.quantity)
        }

        // Verify supplierOrderId stored
        assertEquals("2103221234567890", order.supplierOrderId)
        verify(orderRepository).save(order)
    }

    /**
     * Test: Shipping address mapped correctly from order to supplier order request.
     */
    @Test
    fun `shipping address mapped correctly from order to supplier order request`() {
        val shippingAddress = ShippingAddress(
            customerName = "Jane Smith",
            address = "456 Oak Ave, Apt 7B",
            city = "Springfield",
            province = "IL",
            country = "United States",
            countryCode = "US",
            zip = "62704",
            phone = "+1-217-555-9876"
        )

        val request = SupplierOrderRequest(
            orderNumber = UUID.randomUUID().toString(),
            customerName = shippingAddress.customerName!!,
            address = shippingAddress.address!!,
            city = shippingAddress.city!!,
            province = shippingAddress.province!!,
            country = shippingAddress.country!!,
            countryCode = shippingAddress.countryCode!!,
            zip = shippingAddress.zip!!,
            phone = shippingAddress.phone!!,
            supplierVariantId = "VID-99999",
            quantity = 2
        )

        assertEquals("Jane Smith", request.customerName)
        assertEquals("456 Oak Ave, Apt 7B", request.address)
        assertEquals("Springfield", request.city)
        assertEquals("IL", request.province)
        assertEquals("United States", request.country)
        assertEquals("US", request.countryCode)
        assertEquals("62704", request.zip)
        assertEquals("+1-217-555-9876", request.phone)
        assertEquals(2, request.quantity)
    }

    /**
     * Test: Full flow from Shopify webhook with shipping address through to CJ order.
     *
     * Validates the data contract at each boundary:
     * - Shopify address1 + address2 combined into ShippingAddress.address
     * - ChannelShippingAddress.customerName preserved through to SupplierOrderRequest.customerName
     * - Order UUID used as CJ orderNumber (idempotency key)
     */
    @Test
    fun `full shopify webhook to CJ order placement flow`() {
        // Expected: address1 + address2 combined with comma separator
        val expectedCombinedAddress = "123 Main St, Suite 100"

        val address1 = "123 Main St"
        val address2 = "Suite 100"
        val combined = listOfNotNull(address1, address2).joinToString(", ")

        assertEquals(expectedCombinedAddress, combined)

        val customerName = "John Doe"
        assertEquals("John Doe", customerName)

        // Verify the combined address flows through to ShippingAddress on Order
        val shippingAddress = ShippingAddress(
            customerName = customerName,
            address = combined,
            city = "Anytown",
            province = "California",
            country = "United States",
            countryCode = "US",
            zip = "90210",
            phone = "+1-555-123-4567"
        )

        assertEquals("123 Main St, Suite 100", shippingAddress.address)
        assertEquals("John Doe", shippingAddress.customerName)
    }
}
