package com.autoshipper.fulfillment.domain.service

import com.autoshipper.fulfillment.domain.Order
import com.autoshipper.fulfillment.domain.OrderStatus
import com.autoshipper.fulfillment.domain.ShipmentDetails
import com.autoshipper.fulfillment.persistence.OrderRepository
import com.autoshipper.fulfillment.proxy.inventory.InventoryChecker
import com.autoshipper.shared.events.OrderConfirmed
import com.autoshipper.shared.events.OrderFulfilled
import com.autoshipper.shared.money.Currency
import com.autoshipper.shared.money.Money
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*
import org.springframework.context.ApplicationEventPublisher
import java.math.BigDecimal
import java.util.Optional
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class OrderServiceTest {

    @Mock
    lateinit var orderRepository: OrderRepository

    @Mock
    lateinit var inventoryChecker: InventoryChecker

    @Mock
    lateinit var eventPublisher: ApplicationEventPublisher

    @InjectMocks
    lateinit var orderService: OrderService

    private val skuId = UUID.randomUUID()
    private val vendorId = UUID.randomUUID()
    private val customerId = UUID.randomUUID()

    private fun createCommand(
        idempotencyKey: String = "idem-${UUID.randomUUID()}",
        quantity: Int = 1,
        shippingAddress: com.autoshipper.fulfillment.domain.ShippingAddress? = null
    ): CreateOrderCommand =
        CreateOrderCommand(
            skuId = skuId,
            vendorId = vendorId,
            customerId = customerId,
            totalAmount = Money.of(BigDecimal("49.99"), Currency.USD),
            paymentIntentId = "pi_test_123",
            idempotencyKey = idempotencyKey,
            quantity = quantity,
            shippingAddress = shippingAddress
        )

    private fun pendingOrder(idempotencyKey: String = "idem-key"): Order = Order(
        idempotencyKey = idempotencyKey,
        skuId = skuId,
        vendorId = vendorId,
        customerId = customerId,
        totalAmount = BigDecimal("49.9900"),
        totalCurrency = Currency.USD,
        paymentIntentId = "pi_test_123",
        status = OrderStatus.PENDING
    )

    @Test
    fun `create with available inventory creates PENDING order`() {
        val command = createCommand()
        whenever(orderRepository.findByIdempotencyKey(command.idempotencyKey)).thenReturn(null)
        whenever(inventoryChecker.isAvailable(command.skuId)).thenReturn(true)
        whenever(orderRepository.save(any<Order>())).thenAnswer { it.arguments[0] }

        val (order, created) = orderService.create(command)

        assert(created) { "Expected order to be newly created" }
        assert(order.status == OrderStatus.PENDING) { "Expected PENDING status" }
        assert(order.skuId == skuId)
        assert(order.vendorId == vendorId)
        assert(order.customerId == customerId)
        verify(orderRepository).save(any<Order>())
    }

    @Test
    fun `create with unavailable inventory throws exception`() {
        val command = createCommand()
        whenever(orderRepository.findByIdempotencyKey(command.idempotencyKey)).thenReturn(null)
        whenever(inventoryChecker.isAvailable(command.skuId)).thenReturn(false)

        assertThrows<IllegalArgumentException> {
            orderService.create(command)
        }

        verify(orderRepository, never()).save(any<Order>())
    }

    @Test
    fun `create with duplicate idempotency key returns existing order`() {
        val idempotencyKey = "duplicate-key"
        val existingOrder = pendingOrder(idempotencyKey)
        val command = createCommand(idempotencyKey)

        whenever(orderRepository.findByIdempotencyKey(idempotencyKey)).thenReturn(existingOrder)

        val (order, created) = orderService.create(command)

        assert(!created) { "Expected order NOT to be newly created" }
        assert(order.id == existingOrder.id) { "Expected same order returned" }
        verify(orderRepository, never()).save(any<Order>())
        verify(inventoryChecker, never()).isAvailable(any())
    }

    @Test
    fun `routeToVendor updates status to CONFIRMED`() {
        val order = pendingOrder()
        whenever(orderRepository.findById(order.id)).thenReturn(Optional.of(order))
        whenever(orderRepository.save(any<Order>())).thenAnswer { it.arguments[0] }

        val result = orderService.routeToVendor(order.id)

        assert(result.status == OrderStatus.CONFIRMED) { "Expected CONFIRMED status" }
        verify(orderRepository).save(argThat<Order> { this.status == OrderStatus.CONFIRMED })
    }

    @Test
    fun `routeToVendor publishes OrderConfirmed event`() {
        val order = pendingOrder()
        whenever(orderRepository.findById(order.id)).thenReturn(Optional.of(order))
        whenever(orderRepository.save(any<Order>())).thenAnswer { it.arguments[0] }

        orderService.routeToVendor(order.id)

        verify(eventPublisher).publishEvent(argThat<OrderConfirmed> {
            this.orderId.value == order.id && this.skuId.value == order.skuId
        })
    }

    @Test
    fun `markShipped sets tracking details and status`() {
        val order = pendingOrder().apply { updateStatus(OrderStatus.CONFIRMED) }
        val trackingNumber = "1Z999AA10123456784"
        val carrier = "UPS"

        whenever(orderRepository.findById(order.id)).thenReturn(Optional.of(order))
        whenever(orderRepository.save(any<Order>())).thenAnswer { it.arguments[0] }

        val result = orderService.markShipped(order.id, trackingNumber, carrier)

        assert(result.status == OrderStatus.SHIPPED) { "Expected SHIPPED status" }
        assert(result.shipmentDetails.trackingNumber == trackingNumber) { "Expected tracking number set" }
        assert(result.shipmentDetails.carrier == carrier) { "Expected carrier set" }
    }

    @Test
    fun `markDelivered publishes OrderFulfilled event`() {
        val order = pendingOrder().apply {
            updateStatus(OrderStatus.CONFIRMED)
            updateStatus(OrderStatus.SHIPPED)
        }

        whenever(orderRepository.findById(order.id)).thenReturn(Optional.of(order))
        whenever(orderRepository.save(any<Order>())).thenAnswer { it.arguments[0] }

        val result = orderService.markDelivered(order.id)

        assert(result.status == OrderStatus.DELIVERED) { "Expected DELIVERED status" }
        verify(eventPublisher).publishEvent(argThat<OrderFulfilled> {
            this.orderId.value == order.id && this.skuId.value == order.skuId
        })
    }

    @Test
    fun `routeToVendor rejects non-PENDING order`() {
        val order = pendingOrder().apply { updateStatus(OrderStatus.CONFIRMED) }
        whenever(orderRepository.findById(order.id)).thenReturn(Optional.of(order))

        assertThrows<IllegalArgumentException> {
            orderService.routeToVendor(order.id)
        }

        verify(orderRepository, never()).save(any<Order>())
    }

    @Test
    fun `markShipped rejects non-CONFIRMED order`() {
        val order = pendingOrder()
        whenever(orderRepository.findById(order.id)).thenReturn(Optional.of(order))

        assertThrows<IllegalArgumentException> {
            orderService.markShipped(order.id, "1Z999", "UPS")
        }

        verify(orderRepository, never()).save(any<Order>())
    }

    @Test
    fun `markDelivered rejects non-SHIPPED order`() {
        val order = pendingOrder().apply { updateStatus(OrderStatus.CONFIRMED) }
        whenever(orderRepository.findById(order.id)).thenReturn(Optional.of(order))

        assertThrows<IllegalArgumentException> {
            orderService.markDelivered(order.id)
        }

        verify(orderRepository, never()).save(any<Order>())
    }

    @Test
    fun `routeToVendor throws for non-existent order`() {
        val unknownId = UUID.randomUUID()
        whenever(orderRepository.findById(unknownId)).thenReturn(Optional.empty())

        assertThrows<IllegalArgumentException> {
            orderService.routeToVendor(unknownId)
        }
    }

    @Test
    fun `findById returns null for unknown order`() {
        val unknownId = UUID.randomUUID()
        whenever(orderRepository.findById(unknownId)).thenReturn(Optional.empty())

        val result = orderService.findById(unknownId)

        assert(result == null) { "Expected null for unknown order" }
    }

    @Test
    fun `findById returns order when found`() {
        val order = pendingOrder()
        whenever(orderRepository.findById(order.id)).thenReturn(Optional.of(order))

        val result = orderService.findById(order.id)

        assert(result != null) { "Expected order to be found" }
        assert(result!!.id == order.id)
    }

    @Test
    fun `setChannelMetadata updates channel fields on existing order`() {
        val order = pendingOrder()
        whenever(orderRepository.findById(order.id)).thenReturn(Optional.of(order))
        whenever(orderRepository.save(any<Order>())).thenAnswer { it.arguments[0] }

        val result = orderService.setChannelMetadata(
            orderId = order.id,
            channel = "shopify",
            channelOrderId = "12345",
            channelOrderNumber = "#1001"
        )

        assert(result.channel == "shopify") { "Expected channel=shopify, got ${result.channel}" }
        assert(result.channelOrderId == "12345") { "Expected channelOrderId=12345, got ${result.channelOrderId}" }
        assert(result.channelOrderNumber == "#1001") { "Expected channelOrderNumber=#1001, got ${result.channelOrderNumber}" }
        verify(orderRepository).save(argThat<Order> {
            channel == "shopify" && channelOrderId == "12345" && channelOrderNumber == "#1001"
        })
    }

    @Test
    fun `setChannelMetadata throws for non-existent order`() {
        val unknownId = UUID.randomUUID()
        whenever(orderRepository.findById(unknownId)).thenReturn(Optional.empty())

        assertThrows<IllegalArgumentException> {
            orderService.setChannelMetadata(unknownId, "shopify", "12345", "#1001")
        }

        verify(orderRepository, never()).save(any<Order>())
    }

    @Test
    fun `create persists quantity from command`() {
        val command = createCommand(quantity = 3)
        whenever(orderRepository.findByIdempotencyKey(command.idempotencyKey)).thenReturn(null)
        whenever(inventoryChecker.isAvailable(command.skuId)).thenReturn(true)
        whenever(orderRepository.save(any<Order>())).thenAnswer { it.arguments[0] }

        val (order, created) = orderService.create(command)

        assert(created) { "Expected order to be newly created" }

        val captor = argumentCaptor<Order>()
        verify(orderRepository).save(captor.capture())
        assert(captor.firstValue.quantity == 3) {
            "Expected quantity=3 but got ${captor.firstValue.quantity}"
        }
    }

    @Test
    fun `create persists shippingAddress from command`() {
        val addr = com.autoshipper.fulfillment.domain.ShippingAddress(
            customerName = "John Doe",
            addressLine1 = "123 Main St",
            addressLine2 = "Apt 4B",
            city = "Los Angeles",
            province = "California",
            provinceCode = "CA",
            country = "United States",
            countryCode = "US",
            zip = "90001",
            phone = "+15551234567"
        )
        val command = createCommand(shippingAddress = addr)
        whenever(orderRepository.findByIdempotencyKey(command.idempotencyKey)).thenReturn(null)
        whenever(inventoryChecker.isAvailable(command.skuId)).thenReturn(true)
        whenever(orderRepository.save(any<Order>())).thenAnswer { it.arguments[0] }

        orderService.create(command)

        val captor = argumentCaptor<Order>()
        verify(orderRepository).save(captor.capture())
        val saved = captor.firstValue.shippingAddress
        assert(saved != null) { "Expected shippingAddress to be non-null" }
        assert(saved!!.customerName == "John Doe") { "Expected customerName='John Doe' but got '${saved.customerName}'" }
        assert(saved.addressLine1 == "123 Main St") { "Expected addressLine1='123 Main St' but got '${saved.addressLine1}'" }
        assert(saved.addressLine2 == "Apt 4B") { "Expected addressLine2='Apt 4B' but got '${saved.addressLine2}'" }
        assert(saved.city == "Los Angeles") { "Expected city='Los Angeles' but got '${saved.city}'" }
        assert(saved.province == "California") { "Expected province='California' but got '${saved.province}'" }
        assert(saved.provinceCode == "CA") { "Expected provinceCode='CA' but got '${saved.provinceCode}'" }
        assert(saved.country == "United States") { "Expected country='United States' but got '${saved.country}'" }
        assert(saved.countryCode == "US") { "Expected countryCode='US' but got '${saved.countryCode}'" }
        assert(saved.zip == "90001") { "Expected zip='90001' but got '${saved.zip}'" }
        assert(saved.phone == "+15551234567") { "Expected phone='+15551234567' but got '${saved.phone}'" }
    }
}
