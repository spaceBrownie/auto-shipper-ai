package com.autoshipper.fulfillment.domain.service

import com.autoshipper.fulfillment.domain.Order
import com.autoshipper.fulfillment.domain.OrderStatus
import com.autoshipper.fulfillment.handler.webhook.CjTrackingReceivedEvent
import com.autoshipper.fulfillment.persistence.OrderRepository
import com.autoshipper.shared.money.Currency
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*
import java.math.BigDecimal
import java.util.Optional
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class CjTrackingProcessingServiceTest {

    @Mock
    lateinit var orderService: OrderService

    @Mock
    lateinit var orderRepository: OrderRepository

    private val objectMapper = jacksonObjectMapper()

    private fun service() = CjTrackingProcessingService(orderService, orderRepository, objectMapper)

    private val testOrderId = "a1b2c3d4-e5f6-7890-abcd-ef1234567890"
    private val testOrderUuid = UUID.fromString(testOrderId)

    private fun confirmedOrder(orderId: UUID = testOrderUuid): Order = Order(
        id = orderId,
        idempotencyKey = "idem-${orderId}",
        skuId = UUID.randomUUID(),
        vendorId = UUID.randomUUID(),
        customerId = UUID.randomUUID(),
        totalAmount = BigDecimal("49.9900"),
        totalCurrency = Currency.USD,
        paymentIntentId = "pi_test_123",
        status = OrderStatus.CONFIRMED
    )

    private fun pendingOrder(orderId: UUID = testOrderUuid): Order = Order(
        id = orderId,
        idempotencyKey = "idem-${orderId}",
        skuId = UUID.randomUUID(),
        vendorId = UUID.randomUUID(),
        customerId = UUID.randomUUID(),
        totalAmount = BigDecimal("49.9900"),
        totalCurrency = Currency.USD,
        paymentIntentId = "pi_test_123",
        status = OrderStatus.PENDING
    )

    @Test
    fun `happy path - CONFIRMED order is marked shipped with normalized carrier`() {
        val svc = service()
        val order = confirmedOrder()
        whenever(orderRepository.findById(testOrderUuid)).thenReturn(Optional.of(order))

        val payload = """{"messageId":"msg-1","type":"LOGISTIC","messageType":"UPDATE","openId":1234567890,"params":{"orderId":"$testOrderId","logisticName":"UPS","trackingNumber":"1Z999AA10123456784","trackingStatus":1,"logisticsTrackEvents":"[]"}}"""
        val event = CjTrackingReceivedEvent(rawPayload = payload, dedupKey = "cj:$testOrderId:1Z999AA10123456784")

        svc.onCjTrackingReceived(event)

        verify(orderService).markShipped(testOrderUuid, "1Z999AA10123456784", "UPS")
    }

    @Test
    fun `order not found - skips processing`() {
        val svc = service()
        whenever(orderRepository.findById(testOrderUuid)).thenReturn(Optional.empty())

        val payload = """{"messageId":"msg-1","type":"LOGISTIC","params":{"orderId":"$testOrderId","trackingNumber":"1Z999AA10123456784"}}"""
        val event = CjTrackingReceivedEvent(rawPayload = payload, dedupKey = "cj:$testOrderId:1Z999AA10123456784")

        svc.onCjTrackingReceived(event)

        verify(orderService, never()).markShipped(any(), any(), any())
    }

    @Test
    fun `order not CONFIRMED - skips processing`() {
        val svc = service()
        val order = confirmedOrder().apply {
            // Transition to SHIPPED first
            updateStatus(OrderStatus.SHIPPED)
        }
        whenever(orderRepository.findById(testOrderUuid)).thenReturn(Optional.of(order))

        val payload = """{"messageId":"msg-1","type":"LOGISTIC","params":{"orderId":"$testOrderId","trackingNumber":"1Z999AA10123456784","logisticName":"UPS"}}"""
        val event = CjTrackingReceivedEvent(rawPayload = payload, dedupKey = "cj:$testOrderId:1Z999AA10123456784")

        svc.onCjTrackingReceived(event)

        verify(orderService, never()).markShipped(any(), any(), any())
    }

    @Test
    fun `order in PENDING status - skips processing`() {
        val svc = service()
        val order = pendingOrder()
        whenever(orderRepository.findById(testOrderUuid)).thenReturn(Optional.of(order))

        val payload = """{"messageId":"msg-1","type":"LOGISTIC","params":{"orderId":"$testOrderId","trackingNumber":"1Z999AA10123456784","logisticName":"UPS"}}"""
        val event = CjTrackingReceivedEvent(rawPayload = payload, dedupKey = "cj:$testOrderId:1Z999AA10123456784")

        svc.onCjTrackingReceived(event)

        verify(orderService, never()).markShipped(any(), any(), any())
    }

    @Test
    fun `missing trackingNumber in payload - skips processing`() {
        val svc = service()

        val payload = """{"messageId":"msg-1","type":"LOGISTIC","params":{"orderId":"$testOrderId","logisticName":"UPS"}}"""
        val event = CjTrackingReceivedEvent(rawPayload = payload, dedupKey = "cj:$testOrderId:null")

        svc.onCjTrackingReceived(event)

        verify(orderService, never()).markShipped(any(), any(), any())
        verify(orderRepository, never()).findById(any<UUID>())
    }

    @Test
    fun `missing orderId in payload - skips processing`() {
        val svc = service()

        val payload = """{"messageId":"msg-1","type":"LOGISTIC","params":{"trackingNumber":"1Z999AA10123456784","logisticName":"UPS"}}"""
        val event = CjTrackingReceivedEvent(rawPayload = payload, dedupKey = "cj:null:1Z999AA10123456784")

        svc.onCjTrackingReceived(event)

        verify(orderService, never()).markShipped(any(), any(), any())
        verify(orderRepository, never()).findById(any<UUID>())
    }

    @Test
    fun `invalid UUID orderId - skips processing`() {
        val svc = service()

        val payload = """{"messageId":"msg-cj-tracking-bad-uuid","type":"LOGISTIC","messageType":"UPDATE","openId":1234567890,"params":{"orderId":"not-a-valid-uuid","logisticName":"UPS","trackingNumber":"1Z999AA10123456784","trackingStatus":1,"logisticsTrackEvents":"[]"}}"""
        val event = CjTrackingReceivedEvent(rawPayload = payload, dedupKey = "cj:not-a-valid-uuid:1Z999AA10123456784")

        svc.onCjTrackingReceived(event)

        verify(orderService, never()).markShipped(any(), any(), any())
        verify(orderRepository, never()).findById(any<UUID>())
    }

    @Test
    fun `null logisticName defaults to unknown carrier`() {
        val svc = service()
        val order = confirmedOrder()
        whenever(orderRepository.findById(testOrderUuid)).thenReturn(Optional.of(order))

        val payload = """{"messageId":"msg-cj-tracking-null-carrier","type":"LOGISTIC","messageType":"UPDATE","openId":1234567890,"params":{"orderId":"$testOrderId","logisticName":null,"trackingNumber":"1Z999AA10123456784","trackingStatus":1,"logisticsTrackEvents":"[]"}}"""
        val event = CjTrackingReceivedEvent(rawPayload = payload, dedupKey = "cj:$testOrderId:1Z999AA10123456784")

        svc.onCjTrackingReceived(event)

        verify(orderService).markShipped(testOrderUuid, "1Z999AA10123456784", "unknown")
    }

    @Test
    fun `carrier normalization applies CjCarrierMapper`() {
        val svc = service()
        val order = confirmedOrder()
        whenever(orderRepository.findById(testOrderUuid)).thenReturn(Optional.of(order))

        val payload = """{"messageId":"msg-1","type":"LOGISTIC","params":{"orderId":"$testOrderId","logisticName":"fedex","trackingNumber":"794644790138"}}"""
        val event = CjTrackingReceivedEvent(rawPayload = payload, dedupKey = "cj:$testOrderId:794644790138")

        svc.onCjTrackingReceived(event)

        verify(orderService).markShipped(testOrderUuid, "794644790138", "FedEx")
    }

    @Test
    fun `missing params node - skips processing`() {
        val svc = service()

        val payload = """{"messageId":"msg-cj-tracking-no-params","type":"LOGISTIC","messageType":"UPDATE","openId":1234567890}"""
        val event = CjTrackingReceivedEvent(rawPayload = payload, dedupKey = "cj:null:null")

        svc.onCjTrackingReceived(event)

        verify(orderService, never()).markShipped(any(), any(), any())
        verify(orderRepository, never()).findById(any<UUID>())
    }

    @Test
    fun `NullNode orderId in JSON is treated as null - skips processing`() {
        val svc = service()

        val payload = """{"messageId":"msg-1","type":"LOGISTIC","params":{"orderId":null,"trackingNumber":"1Z999AA10123456784","logisticName":"UPS"}}"""
        val event = CjTrackingReceivedEvent(rawPayload = payload, dedupKey = "cj:null:1Z999AA10123456784")

        svc.onCjTrackingReceived(event)

        verify(orderService, never()).markShipped(any(), any(), any())
        verify(orderRepository, never()).findById(any<UUID>())
    }

    @Test
    fun `NullNode trackingNumber in JSON is treated as null - skips processing`() {
        val svc = service()

        val payload = """{"messageId":"msg-1","type":"LOGISTIC","params":{"orderId":"$testOrderId","trackingNumber":null,"logisticName":"UPS"}}"""
        val event = CjTrackingReceivedEvent(rawPayload = payload, dedupKey = "cj:$testOrderId:null")

        svc.onCjTrackingReceived(event)

        verify(orderService, never()).markShipped(any(), any(), any())
        verify(orderRepository, never()).findById(any<UUID>())
    }
}
