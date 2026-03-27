package com.autoshipper.fulfillment.handler

import com.autoshipper.fulfillment.domain.Order
import com.autoshipper.fulfillment.domain.OrderStatus
import com.autoshipper.fulfillment.domain.ShippingAddress
import com.autoshipper.fulfillment.persistence.OrderRepository
import com.autoshipper.fulfillment.proxy.platform.SupplierProductMappingResolver
import com.autoshipper.fulfillment.proxy.supplier.SupplierOrderAdapter
import com.autoshipper.fulfillment.proxy.supplier.SupplierOrderRequest
import com.autoshipper.fulfillment.proxy.supplier.SupplierOrderResult
import com.autoshipper.shared.events.OrderConfirmed
import com.autoshipper.shared.identity.OrderId
import com.autoshipper.shared.identity.SkuId
import com.autoshipper.shared.money.Currency
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.*
import org.mockito.quality.Strictness
import java.math.BigDecimal
import java.util.Optional
import java.util.UUID

/**
 * Tests for SupplierOrderPlacementListener -- the event-driven handler that places
 * purchase orders with CJ Dropshipping when an internal order is confirmed.
 */
@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class SupplierOrderPlacementListenerTest {

    @Mock
    lateinit var orderRepository: OrderRepository

    @Mock
    lateinit var supplierOrderAdapter: SupplierOrderAdapter

    @Mock
    lateinit var supplierProductMappingResolver: SupplierProductMappingResolver

    private val skuId = UUID.randomUUID()
    private val orderId = UUID.randomUUID()

    private fun createListener(): SupplierOrderPlacementListener =
        SupplierOrderPlacementListener(
            orderRepository = orderRepository,
            supplierOrderAdapter = supplierOrderAdapter,
            supplierProductMappingResolver = supplierProductMappingResolver,
            logisticName = "CJPacket",
            fromCountryCode = "CN"
        )

    private fun confirmedOrderWithQuantity(quantity: Int): Order {
        val order = Order(
            id = orderId,
            idempotencyKey = "test-idem-${UUID.randomUUID()}",
            skuId = skuId,
            vendorId = UUID.randomUUID(),
            customerId = UUID.randomUUID(),
            totalAmount = BigDecimal("59.98"),
            totalCurrency = Currency.USD,
            quantity = quantity,
            paymentIntentId = "pi_test_123",
            status = OrderStatus.PENDING,
            shippingAddress = ShippingAddress(
                customerName = "John Doe",
                address = "123 Main St",
                city = "Anytown",
                province = "California",
                zip = "90210",
                country = "United States",
                countryCode = "US",
                phone = "+1-555-123-4567"
            )
        )
        order.updateStatus(OrderStatus.CONFIRMED)
        return order
    }

    private fun orderConfirmedEvent(): OrderConfirmed =
        OrderConfirmed(
            orderId = OrderId(orderId),
            skuId = SkuId(skuId)
        )

    // --- Happy Path ---

    @Test
    fun `onOrderConfirmed places CJ order and stores supplier order ID`() {
        val order = confirmedOrderWithQuantity(3)
        whenever(orderRepository.findById(orderId)).thenReturn(Optional.of(order))
        whenever(orderRepository.save(any<Order>())).thenAnswer { it.arguments[0] }
        whenever(supplierProductMappingResolver.resolveSupplierVariantId(skuId, "CJ_DROPSHIPPING"))
            .thenReturn("CJ-VID-001")
        whenever(supplierOrderAdapter.placeOrder(any())).thenReturn(
            SupplierOrderResult(supplierOrderId = "CJ-ORD-001", status = "CREATED")
        )

        val listener = createListener()
        listener.onOrderConfirmed(orderConfirmedEvent())

        assert(order.supplierOrderId == "CJ-ORD-001") {
            "Expected CJ order ID 'CJ-ORD-001' but got '${order.supplierOrderId}'"
        }
        verify(supplierOrderAdapter).placeOrder(any())
        verify(orderRepository).save(order)
    }

    // --- Idempotency (NFR-2) ---

    @Test
    fun `onOrderConfirmed skips placement when supplierOrderId already set`() {
        val order = confirmedOrderWithQuantity(3)
        order.supplierOrderId = "CJ-ORD-EXISTING"
        whenever(orderRepository.findById(orderId)).thenReturn(Optional.of(order))

        val listener = createListener()
        listener.onOrderConfirmed(orderConfirmedEvent())

        verify(supplierOrderAdapter, never()).placeOrder(any())
    }

    // --- Error Handling (BR-5) ---

    @Test
    fun `onOrderConfirmed transitions to FAILED when adapter throws exception`() {
        val order = confirmedOrderWithQuantity(3)
        whenever(orderRepository.findById(orderId)).thenReturn(Optional.of(order))
        whenever(orderRepository.save(any<Order>())).thenAnswer { it.arguments[0] }
        whenever(supplierProductMappingResolver.resolveSupplierVariantId(skuId, "CJ_DROPSHIPPING"))
            .thenReturn("CJ-VID-001")
        whenever(supplierOrderAdapter.placeOrder(any()))
            .thenThrow(RuntimeException("CJ API unavailable"))

        val listener = createListener()
        listener.onOrderConfirmed(orderConfirmedEvent())

        assert(order.status == OrderStatus.FAILED) {
            "Expected FAILED status after CJ API failure but got ${order.status}"
        }
        assert(order.failureReason == "CJ API unavailable") {
            "Expected failure reason 'CJ API unavailable' but got '${order.failureReason}'"
        }
        verify(orderRepository).save(order)
    }

    @Test
    fun `onOrderConfirmed transitions to FAILED when product mapping not found`() {
        val order = confirmedOrderWithQuantity(3)
        whenever(orderRepository.findById(orderId)).thenReturn(Optional.of(order))
        whenever(orderRepository.save(any<Order>())).thenAnswer { it.arguments[0] }
        whenever(supplierProductMappingResolver.resolveSupplierVariantId(skuId, "CJ_DROPSHIPPING"))
            .thenReturn(null)

        val listener = createListener()
        listener.onOrderConfirmed(orderConfirmedEvent())

        assert(order.status == OrderStatus.FAILED) {
            "Expected FAILED when product mapping is missing but got ${order.status}"
        }
        assert(order.failureReason?.contains("No CJ product mapping") == true) {
            "Expected failure reason to mention missing mapping but got '${order.failureReason}'"
        }
        verify(supplierOrderAdapter, never()).placeOrder(any())
    }

    // --- Quantity Flow-Through (BR-3 / PM-017 prevention) ---

    @Test
    fun `onOrderConfirmed sends correct quantity to adapter - not hardcoded 1`() {
        val order = confirmedOrderWithQuantity(5)
        whenever(orderRepository.findById(orderId)).thenReturn(Optional.of(order))
        whenever(orderRepository.save(any<Order>())).thenAnswer { it.arguments[0] }
        whenever(supplierProductMappingResolver.resolveSupplierVariantId(skuId, "CJ_DROPSHIPPING"))
            .thenReturn("CJ-VID-001")

        val captor = argumentCaptor<SupplierOrderRequest>()
        whenever(supplierOrderAdapter.placeOrder(captor.capture())).thenReturn(
            SupplierOrderResult(supplierOrderId = "CJ-ORD-QTY", status = "CREATED")
        )

        val listener = createListener()
        listener.onOrderConfirmed(orderConfirmedEvent())

        val captured = captor.firstValue
        assert(captured.products[0].quantity == 5) {
            "Expected quantity 5 but got ${captured.products[0].quantity} -- hardcoded-quantity bug detected!"
        }
    }

    // --- Shipping Address Flow-Through (BR-2) ---

    @Test
    fun `onOrderConfirmed passes shipping address from order to adapter`() {
        val order = confirmedOrderWithQuantity(3)
        whenever(orderRepository.findById(orderId)).thenReturn(Optional.of(order))
        whenever(orderRepository.save(any<Order>())).thenAnswer { it.arguments[0] }
        whenever(supplierProductMappingResolver.resolveSupplierVariantId(skuId, "CJ_DROPSHIPPING"))
            .thenReturn("CJ-VID-001")

        val captor = argumentCaptor<SupplierOrderRequest>()
        whenever(supplierOrderAdapter.placeOrder(captor.capture())).thenReturn(
            SupplierOrderResult(supplierOrderId = "CJ-ORD-ADDR", status = "CREATED")
        )

        val listener = createListener()
        listener.onOrderConfirmed(orderConfirmedEvent())

        val captured = captor.firstValue
        assert(captured.shippingAddress.customerName == "John Doe") {
            "Expected customerName 'John Doe'"
        }
        assert(captured.shippingAddress.address == "123 Main St") {
            "Expected address '123 Main St'"
        }
        assert(captured.shippingAddress.city == "Anytown") {
            "Expected city 'Anytown'"
        }
        assert(captured.shippingAddress.province == "California") {
            "Expected province 'California'"
        }
        assert(captured.shippingAddress.zip == "90210") {
            "Expected zip '90210'"
        }
        assert(captured.shippingAddress.country == "United States") {
            "Expected country 'United States'"
        }
        assert(captured.shippingAddress.countryCode == "US") {
            "Expected countryCode 'US'"
        }
        assert(captured.shippingAddress.phone == "+1-555-123-4567") {
            "Expected phone '+1-555-123-4567'"
        }
    }
}
