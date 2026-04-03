package com.autoshipper.fulfillment.handler

import com.autoshipper.fulfillment.domain.Order
import com.autoshipper.fulfillment.domain.OrderStatus
import com.autoshipper.fulfillment.persistence.OrderRepository
import com.autoshipper.fulfillment.proxy.platform.ShopifyFulfillmentPort
import com.autoshipper.shared.events.OrderShipped
import com.autoshipper.shared.identity.OrderId
import com.autoshipper.shared.identity.SkuId
import com.autoshipper.shared.money.Currency
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.web.client.HttpServerErrorException
import java.math.BigDecimal
import java.util.Optional
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class ShopifyFulfillmentSyncListenerTest {

    @Mock
    lateinit var shopifyFulfillmentPort: ShopifyFulfillmentPort

    @Mock
    lateinit var orderRepository: OrderRepository

    @InjectMocks
    lateinit var listener: ShopifyFulfillmentSyncListener

    private val orderId = UUID.randomUUID()
    private val skuId = UUID.randomUUID()
    private val vendorId = UUID.randomUUID()
    private val customerId = UUID.randomUUID()

    private fun shippedEvent(
        trackingNumber: String = "1Z999AA10123456784",
        carrier: String = "UPS"
    ): OrderShipped = OrderShipped(
        orderId = OrderId(orderId),
        skuId = SkuId(skuId),
        trackingNumber = trackingNumber,
        carrier = carrier
    )

    private fun orderWithChannelId(channelOrderId: String?): Order {
        val order = Order(
            id = orderId,
            idempotencyKey = "idem-${orderId}",
            skuId = skuId,
            vendorId = vendorId,
            customerId = customerId,
            totalAmount = BigDecimal("79.99"),
            totalCurrency = Currency.USD,
            paymentIntentId = "pi_test_123",
            status = OrderStatus.SHIPPED
        )
        order.channelOrderId = channelOrderId
        return order
    }

    @Test
    fun `happy path - calls adapter with channelOrderId, trackingNumber, carrier`() {
        val order = orderWithChannelId("820982911946154500")
        whenever(orderRepository.findById(orderId)).thenReturn(Optional.of(order))
        whenever(shopifyFulfillmentPort.createFulfillment(any(), any(), any())).thenReturn(true)

        listener.onOrderShipped(shippedEvent())

        verify(shopifyFulfillmentPort).createFulfillment(
            "820982911946154500",
            "1Z999AA10123456784",
            "UPS"
        )
    }

    @Test
    fun `order not found - adapter never called`() {
        whenever(orderRepository.findById(orderId)).thenReturn(Optional.empty())

        listener.onOrderShipped(shippedEvent())

        verify(shopifyFulfillmentPort, never()).createFulfillment(any(), any(), any())
    }

    @Test
    fun `null channelOrderId - adapter never called`() {
        val order = orderWithChannelId(null)
        whenever(orderRepository.findById(orderId)).thenReturn(Optional.of(order))

        listener.onOrderShipped(shippedEvent())

        verify(shopifyFulfillmentPort, never()).createFulfillment(any(), any(), any())
    }

    @Test
    fun `adapter exception caught and does not propagate`() {
        val order = orderWithChannelId("820982911946154500")
        whenever(orderRepository.findById(orderId)).thenReturn(Optional.of(order))
        whenever(shopifyFulfillmentPort.createFulfillment(any(), any(), any()))
            .thenThrow(RuntimeException("Shopify is down"))

        // Should not throw — exception is caught inside the listener
        listener.onOrderShipped(shippedEvent())

        verify(shopifyFulfillmentPort).createFulfillment(any(), any(), any())
    }

    @Test
    fun `adapter HttpServerErrorException does not propagate`() {
        val order = orderWithChannelId("820982911946154500")
        whenever(orderRepository.findById(orderId)).thenReturn(Optional.of(order))
        whenever(shopifyFulfillmentPort.createFulfillment(any(), any(), any()))
            .thenThrow(HttpServerErrorException.create(
                org.springframework.http.HttpStatusCode.valueOf(502),
                "Bad Gateway",
                org.springframework.http.HttpHeaders.EMPTY,
                ByteArray(0),
                null
            ))

        // Should not throw — exception is caught inside the listener
        listener.onOrderShipped(shippedEvent())

        verify(shopifyFulfillmentPort).createFulfillment(any(), any(), any())
    }
}
