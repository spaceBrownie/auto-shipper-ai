package com.autoshipper.fulfillment.integration

import com.autoshipper.fulfillment.domain.Order
import com.autoshipper.fulfillment.domain.OrderStatus
import com.autoshipper.fulfillment.domain.ShippingAddress
import com.autoshipper.fulfillment.domain.service.CreateOrderCommand
import com.autoshipper.fulfillment.domain.service.CjTrackingProcessingService
import com.autoshipper.fulfillment.domain.service.OrderService
import com.autoshipper.fulfillment.handler.ShopifyFulfillmentSyncListener
import com.autoshipper.fulfillment.handler.webhook.CjTrackingReceivedEvent
import com.autoshipper.fulfillment.persistence.OrderRepository
import com.autoshipper.fulfillment.proxy.inventory.InventoryChecker
import com.autoshipper.fulfillment.proxy.platform.ShopifyFulfillmentPort
import com.autoshipper.shared.events.OrderShipped
import com.autoshipper.shared.identity.OrderId
import com.autoshipper.shared.identity.SkuId
import com.autoshipper.shared.money.Currency
import com.autoshipper.shared.money.Money
import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.context.ApplicationEventPublisher
import java.math.BigDecimal
import java.util.Optional
import java.util.UUID

/**
 * Unit-level integration test for the full CJ tracking webhook chain:
 * CJ tracking webhook -> CjTrackingProcessingService -> OrderService.markShipped() ->
 * OrderShipped event -> ShopifyFulfillmentSyncListener -> ShopifyFulfillmentPort
 *
 * Tests the logical flow with mocked infrastructure (repo, adapter).
 */
@ExtendWith(MockitoExtension::class)
class CjTrackingWebhookIntegrationTest {

    @Mock
    lateinit var orderRepository: OrderRepository

    @Mock
    lateinit var inventoryChecker: InventoryChecker

    @Mock
    lateinit var eventPublisher: ApplicationEventPublisher

    @Mock
    lateinit var shopifyFulfillmentPort: ShopifyFulfillmentPort

    private val objectMapper = ObjectMapper()

    private val skuId = UUID.randomUUID()
    private val vendorId = UUID.randomUUID()
    private val customerId = UUID.randomUUID()

    /**
     * In-memory store simulating repository persistence so that
     * mutations made by OrderService are visible across the chain.
     */
    private fun setupInMemoryStore(): MutableMap<UUID, Order> {
        val store = mutableMapOf<UUID, Order>()

        whenever(orderRepository.save(any<Order>())).thenAnswer { invocation ->
            val order = invocation.arguments[0] as Order
            store[order.id] = order
            order
        }

        whenever(orderRepository.findById(any<UUID>())).thenAnswer { invocation ->
            val id = invocation.arguments[0] as UUID
            Optional.ofNullable(store[id])
        }

        return store
    }

    private fun buildCjTrackingPayload(orderId: UUID, trackingNumber: String, logisticName: String): String {
        return objectMapper.writeValueAsString(
            mapOf(
                "params" to mapOf(
                    "orderId" to orderId.toString(),
                    "trackingNumber" to trackingNumber,
                    "logisticName" to logisticName
                )
            )
        )
    }

    @Test
    fun `full chain - CJ webhook, order matched, SHIPPED, Shopify fulfillment called`() {
        val store = setupInMemoryStore()

        whenever(orderRepository.findByIdempotencyKey(any())).thenReturn(null)
        whenever(inventoryChecker.isAvailable(skuId)).thenReturn(true)
        whenever(shopifyFulfillmentPort.createFulfillment(any(), any(), any())).thenReturn(true)

        val orderService = OrderService(orderRepository, inventoryChecker, eventPublisher)

        // Step 1: Create and confirm an order (simulating prior lifecycle)
        val command = CreateOrderCommand(
            skuId = skuId,
            vendorId = vendorId,
            customerId = customerId,
            totalAmount = Money.of(BigDecimal("79.99"), Currency.USD),
            paymentIntentId = "pi_integration_cj_1",
            idempotencyKey = "integration-cj-shopify-1",
            quantity = 1,
            shippingAddress = ShippingAddress(
                customerName = "Integration Test User",
                addressLine1 = "100 Test Blvd",
                city = "Testville",
                province = "TX",
                countryCode = "US",
                country = "United States",
                zip = "75001"
            )
        )
        val (order, _) = orderService.create(command)
        orderService.routeToVendor(order.id) // PENDING -> CONFIRMED

        // Set Shopify channel metadata on the order
        store[order.id]!!.also {
            it.channel = "shopify"
            it.channelOrderId = "98765"
            it.channelOrderNumber = "#1001"
        }

        // Step 2: Simulate CjTrackingProcessingService processing a CJ webhook
        val cjTrackingProcessingService = CjTrackingProcessingService(
            orderService, orderRepository, objectMapper
        )

        val payload = buildCjTrackingPayload(order.id, "1Z999AA10123456784", "ups")
        val event = CjTrackingReceivedEvent(rawPayload = payload, dedupKey = "cj:${order.id}:1Z999AA10123456784")

        cjTrackingProcessingService.onCjTrackingReceived(event)

        // Verify: order is SHIPPED
        val finalOrder = store[order.id]!!
        assertThat(finalOrder.status).isEqualTo(OrderStatus.SHIPPED)
        assertThat(finalOrder.shipmentDetails.trackingNumber).isEqualTo("1Z999AA10123456784")
        assertThat(finalOrder.shipmentDetails.carrier).isEqualTo("UPS")

        // Step 3: Capture the OrderShipped event and manually invoke the listener
        val eventCaptor = argumentCaptor<Any>()
        verify(eventPublisher, org.mockito.Mockito.atLeastOnce()).publishEvent(eventCaptor.capture())
        val orderShippedEvent = eventCaptor.allValues.filterIsInstance<OrderShipped>().first()
        assertThat(orderShippedEvent.orderId.value).isEqualTo(order.id)
        assertThat(orderShippedEvent.trackingNumber).isEqualTo("1Z999AA10123456784")
        assertThat(orderShippedEvent.carrier).isEqualTo("UPS")

        // Step 4: Invoke Shopify fulfillment sync listener manually
        val listener = ShopifyFulfillmentSyncListener(shopifyFulfillmentPort, orderRepository)
        listener.onOrderShipped(orderShippedEvent)

        // Verify: Shopify fulfillment adapter called with correct args
        verify(shopifyFulfillmentPort).createFulfillment(
            "98765",
            "1Z999AA10123456784",
            "UPS"
        )
    }

    @Test
    fun `full chain - order without channelOrderId, Shopify sync skipped`() {
        val store = setupInMemoryStore()

        whenever(orderRepository.findByIdempotencyKey(any())).thenReturn(null)
        whenever(inventoryChecker.isAvailable(skuId)).thenReturn(true)

        val orderService = OrderService(orderRepository, inventoryChecker, eventPublisher)

        // Step 1: Create and confirm an order WITHOUT channel metadata
        val command = CreateOrderCommand(
            skuId = skuId,
            vendorId = vendorId,
            customerId = customerId,
            totalAmount = Money.of(BigDecimal("59.99"), Currency.USD),
            paymentIntentId = "pi_integration_no_channel",
            idempotencyKey = "integration-cj-no-channel-1",
            quantity = 1,
            shippingAddress = ShippingAddress(
                customerName = "No Channel User",
                addressLine1 = "200 No Channel Dr",
                city = "Testville",
                province = "TX",
                countryCode = "US",
                country = "United States",
                zip = "75001"
            )
        )
        val (order, _) = orderService.create(command)
        orderService.routeToVendor(order.id) // PENDING -> CONFIRMED
        // Intentionally: no channelOrderId set

        // Step 2: Process CJ tracking
        val cjTrackingProcessingService = CjTrackingProcessingService(
            orderService, orderRepository, objectMapper
        )
        val payload = buildCjTrackingPayload(order.id, "1Z999BB20234567891", "fedex")
        cjTrackingProcessingService.onCjTrackingReceived(
            CjTrackingReceivedEvent(rawPayload = payload, dedupKey = "cj:${order.id}:1Z999BB20234567891")
        )

        // Verify order is SHIPPED
        assertThat(store[order.id]!!.status).isEqualTo(OrderStatus.SHIPPED)

        // Step 3: Capture OrderShipped and invoke listener
        val eventCaptor = argumentCaptor<Any>()
        verify(eventPublisher, org.mockito.Mockito.atLeastOnce()).publishEvent(eventCaptor.capture())
        val orderShippedEvent = eventCaptor.allValues.filterIsInstance<OrderShipped>().first()

        val listener = ShopifyFulfillmentSyncListener(shopifyFulfillmentPort, orderRepository)
        listener.onOrderShipped(orderShippedEvent)

        // Verify: Shopify adapter NOT called (no channelOrderId)
        verify(shopifyFulfillmentPort, never()).createFulfillment(any(), any(), any())
    }

    @Test
    fun `full chain - Shopify failure does not affect order status`() {
        val store = setupInMemoryStore()

        whenever(orderRepository.findByIdempotencyKey(any())).thenReturn(null)
        whenever(inventoryChecker.isAvailable(skuId)).thenReturn(true)
        whenever(shopifyFulfillmentPort.createFulfillment(any(), any(), any()))
            .thenThrow(RuntimeException("Shopify is down"))

        val orderService = OrderService(orderRepository, inventoryChecker, eventPublisher)

        // Step 1: Create and confirm an order with channel metadata
        val command = CreateOrderCommand(
            skuId = skuId,
            vendorId = vendorId,
            customerId = customerId,
            totalAmount = Money.of(BigDecimal("99.99"), Currency.USD),
            paymentIntentId = "pi_integration_shopify_fail",
            idempotencyKey = "integration-cj-shopify-fail-1",
            quantity = 1,
            shippingAddress = ShippingAddress(
                customerName = "Shopify Fail User",
                addressLine1 = "300 Fail Rd",
                city = "Testville",
                province = "TX",
                countryCode = "US",
                country = "United States",
                zip = "75001"
            )
        )
        val (order, _) = orderService.create(command)
        orderService.routeToVendor(order.id) // PENDING -> CONFIRMED

        store[order.id]!!.also {
            it.channel = "shopify"
            it.channelOrderId = "11111"
            it.channelOrderNumber = "#1002"
        }

        // Step 2: Process CJ tracking
        val cjTrackingProcessingService = CjTrackingProcessingService(
            orderService, orderRepository, objectMapper
        )
        val payload = buildCjTrackingPayload(order.id, "1Z999CC30345678902", "dhl")
        cjTrackingProcessingService.onCjTrackingReceived(
            CjTrackingReceivedEvent(rawPayload = payload, dedupKey = "cj:${order.id}:1Z999CC30345678902")
        )

        // Verify order is SHIPPED before Shopify attempt
        assertThat(store[order.id]!!.status).isEqualTo(OrderStatus.SHIPPED)

        // Step 3: Invoke Shopify listener — it should catch the exception
        val eventCaptor = argumentCaptor<Any>()
        verify(eventPublisher, org.mockito.Mockito.atLeastOnce()).publishEvent(eventCaptor.capture())
        val orderShippedEvent = eventCaptor.allValues.filterIsInstance<OrderShipped>().first()

        val listener = ShopifyFulfillmentSyncListener(shopifyFulfillmentPort, orderRepository)
        listener.onOrderShipped(orderShippedEvent) // Should NOT throw

        // Verify: order status still SHIPPED (not rolled back by Shopify failure)
        val finalOrder = store[order.id]!!
        assertThat(finalOrder.status).isEqualTo(OrderStatus.SHIPPED)
        assertThat(finalOrder.shipmentDetails.trackingNumber).isEqualTo("1Z999CC30345678902")
    }
}
