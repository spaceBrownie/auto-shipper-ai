package com.autoshipper.fulfillment.domain.service

import com.autoshipper.fulfillment.domain.Order
import com.autoshipper.fulfillment.domain.OrderStatus
import com.autoshipper.fulfillment.domain.ShippingAddress
import com.autoshipper.fulfillment.persistence.OrderRepository
import com.autoshipper.fulfillment.proxy.supplier.SupplierOrderAdapter
import com.autoshipper.fulfillment.proxy.supplier.SupplierOrderRequest
import com.autoshipper.fulfillment.proxy.supplier.SupplierOrderResult
import com.autoshipper.fulfillment.proxy.supplier.SupplierProductMapping
import com.autoshipper.fulfillment.proxy.supplier.SupplierProductMappingResolver
import com.autoshipper.shared.money.Currency
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*
import java.math.BigDecimal
import java.util.Optional
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class SupplierOrderPlacementServiceTest {

    @Mock
    lateinit var orderRepository: OrderRepository

    @Mock
    lateinit var supplierOrderAdapter: SupplierOrderAdapter

    @Mock
    lateinit var supplierProductMappingResolver: SupplierProductMappingResolver

    @InjectMocks
    lateinit var service: SupplierOrderPlacementService

    private val skuId = UUID.randomUUID()
    private val vendorId = UUID.randomUUID()
    private val customerId = UUID.randomUUID()

    private fun confirmedOrder(
        quantity: Int = 2,
        supplierOrderId: String? = null,
        shippingAddress: ShippingAddress? = ShippingAddress(
            customerName = "Jane Doe",
            addressLine1 = "123 Main St",
            city = "Springfield",
            province = "IL",
            zip = "62701",
            country = "US",
            countryCode = "US"
        )
    ): Order = Order(
        idempotencyKey = "idem-${UUID.randomUUID()}",
        skuId = skuId,
        vendorId = vendorId,
        customerId = customerId,
        totalAmount = BigDecimal("49.9900"),
        totalCurrency = Currency.USD,
        paymentIntentId = "pi_test_123",
        quantity = quantity,
        status = OrderStatus.PENDING,
        shippingAddress = shippingAddress
    ).also {
        it.updateStatus(OrderStatus.CONFIRMED)
        if (supplierOrderId != null) {
            it.supplierOrderId = supplierOrderId
        }
    }

    @Test
    fun `happy path - adapter returns Success sets supplierOrderId`() {
        val order = confirmedOrder(quantity = 2)
        val mapping = SupplierProductMapping(supplierProductId = "pid1", supplierVariantId = "vid1")

        whenever(orderRepository.findById(order.id)).thenReturn(Optional.of(order))
        whenever(supplierProductMappingResolver.resolve(order.skuId)).thenReturn(mapping)
        whenever(supplierOrderAdapter.placeOrder(any())).thenReturn(SupplierOrderResult.Success("cj-123"))
        whenever(orderRepository.save(any<Order>())).thenAnswer { it.arguments[0] }

        service.placeSupplierOrder(order.id)

        assert(order.supplierOrderId == "cj-123") {
            "Expected supplierOrderId='cj-123', got '${order.supplierOrderId}'"
        }
        verify(supplierOrderAdapter).placeOrder(argThat<SupplierOrderRequest> {
            orderNumber == order.id.toString() &&
                quantity == 2 &&
                supplierVariantId == "vid1" &&
                supplierProductId == "pid1"
        })
        verify(orderRepository).save(order)
    }

    @Test
    fun `failure path - adapter returns Failure sets order to FAILED with reason`() {
        val order = confirmedOrder()
        val mapping = SupplierProductMapping(supplierProductId = "pid1", supplierVariantId = "vid1")

        whenever(orderRepository.findById(order.id)).thenReturn(Optional.of(order))
        whenever(supplierProductMappingResolver.resolve(order.skuId)).thenReturn(mapping)
        whenever(supplierOrderAdapter.placeOrder(any())).thenReturn(SupplierOrderResult.Failure("out of stock"))
        whenever(orderRepository.save(any<Order>())).thenAnswer { it.arguments[0] }

        service.placeSupplierOrder(order.id)

        assert(order.status == OrderStatus.FAILED) {
            "Expected FAILED status, got ${order.status}"
        }
        assert(order.failureReason == "out of stock") {
            "Expected failureReason='out of stock', got '${order.failureReason}'"
        }
        verify(orderRepository).save(order)
    }

    @Test
    fun `idempotency - order already has supplierOrderId skips adapter call`() {
        val order = confirmedOrder(supplierOrderId = "existing-123")

        whenever(orderRepository.findById(order.id)).thenReturn(Optional.of(order))

        service.placeSupplierOrder(order.id)

        verify(supplierOrderAdapter, never()).placeOrder(any())
        assert(order.supplierOrderId == "existing-123") {
            "Expected supplierOrderId to remain 'existing-123', got '${order.supplierOrderId}'"
        }
    }

    @Test
    fun `missing mapping - resolver returns null sets order to FAILED`() {
        val order = confirmedOrder()

        whenever(orderRepository.findById(order.id)).thenReturn(Optional.of(order))
        whenever(supplierProductMappingResolver.resolve(order.skuId)).thenReturn(null)
        whenever(orderRepository.save(any<Order>())).thenAnswer { it.arguments[0] }

        service.placeSupplierOrder(order.id)

        assert(order.status == OrderStatus.FAILED) {
            "Expected FAILED status, got ${order.status}"
        }
        assert(order.failureReason != null && order.failureReason!!.contains("No supplier product mapping")) {
            "Expected failureReason to contain 'No supplier product mapping', got '${order.failureReason}'"
        }
        verify(supplierOrderAdapter, never()).placeOrder(any())
        verify(orderRepository).save(order)
    }

    @Test
    fun `skips placement when order is FAILED from previous attempt`() {
        val order = confirmedOrder()
        // Simulate a prior failed attempt: transition to FAILED
        order.updateStatus(OrderStatus.FAILED)
        order.failureReason = "previous failure"

        whenever(orderRepository.findById(order.id)).thenReturn(Optional.of(order))

        service.placeSupplierOrder(order.id)

        verify(supplierOrderAdapter, never()).placeOrder(any())
        verify(orderRepository, never()).save(any<Order>())
        assert(order.status == OrderStatus.FAILED) { "Status should remain FAILED" }
        assert(order.failureReason == "previous failure") { "Failure reason should be unchanged" }
    }

    @Test
    fun `skips placement when order is PENDING not yet CONFIRMED`() {
        val order = Order(
            idempotencyKey = "idem-${UUID.randomUUID()}",
            skuId = skuId,
            vendorId = vendorId,
            customerId = customerId,
            totalAmount = BigDecimal("49.9900"),
            totalCurrency = Currency.USD,
            paymentIntentId = "pi_test_123",
            quantity = 1,
            status = OrderStatus.PENDING
        )

        whenever(orderRepository.findById(order.id)).thenReturn(Optional.of(order))

        service.placeSupplierOrder(order.id)

        verify(supplierOrderAdapter, never()).placeOrder(any())
        verify(orderRepository, never()).save(any<Order>())
    }

    @Test
    fun `order not found throws IllegalArgumentException`() {
        val unknownId = UUID.randomUUID()
        whenever(orderRepository.findById(unknownId)).thenReturn(Optional.empty())

        assertThrows<IllegalArgumentException> {
            service.placeSupplierOrder(unknownId)
        }

        verify(supplierOrderAdapter, never()).placeOrder(any())
        verify(orderRepository, never()).save(any<Order>())
    }
}
