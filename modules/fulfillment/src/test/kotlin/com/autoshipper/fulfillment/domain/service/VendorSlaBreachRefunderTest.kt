package com.autoshipper.fulfillment.domain.service

import com.autoshipper.fulfillment.domain.Order
import com.autoshipper.fulfillment.domain.OrderStatus
import com.autoshipper.fulfillment.persistence.OrderRepository
import com.autoshipper.fulfillment.proxy.notification.NotificationSender
import com.autoshipper.fulfillment.proxy.payment.RefundProvider
import com.autoshipper.fulfillment.proxy.payment.RefundResult
import com.autoshipper.shared.events.VendorSlaBreached
import com.autoshipper.shared.identity.SkuId
import com.autoshipper.shared.identity.VendorId
import com.autoshipper.shared.money.Currency
import com.autoshipper.shared.money.Money
import com.autoshipper.shared.money.Percentage
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*
import java.math.BigDecimal
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class VendorSlaBreachRefunderTest {

    @Mock
    lateinit var orderRepository: OrderRepository

    @Mock
    lateinit var refundProvider: RefundProvider

    @Mock
    lateinit var notificationSender: NotificationSender

    @InjectMocks
    lateinit var refunder: VendorSlaBreachRefunder

    private val vendorUUID = UUID.randomUUID()
    private val vendorId = VendorId(vendorUUID)

    private fun breachEvent(): VendorSlaBreached = VendorSlaBreached(
        vendorId = vendorId,
        skuIds = listOf(SkuId(UUID.randomUUID())),
        breachRate = Percentage.of(15)
    )

    private fun activeOrder(status: OrderStatus = OrderStatus.CONFIRMED): Order = Order(
        idempotencyKey = "idem-${UUID.randomUUID()}",
        skuId = UUID.randomUUID(),
        vendorId = vendorUUID,
        customerId = UUID.randomUUID(),
        totalAmount = BigDecimal("39.9900"),
        totalCurrency = Currency.USD,
        status = status
    )

    @Test
    fun `onVendorSlaBreached finds active orders and triggers refunds`() {
        val order = activeOrder(OrderStatus.CONFIRMED)
        val event = breachEvent()

        whenever(orderRepository.findByVendorIdAndStatusIn(
            eq(vendorUUID),
            eq(listOf(OrderStatus.SHIPPED, OrderStatus.CONFIRMED))
        )).thenReturn(listOf(order))
        whenever(refundProvider.refund(eq(order.id), any<Money>(), any())).thenReturn(
            RefundResult(refundId = "ref-123", status = "succeeded")
        )
        whenever(orderRepository.save(any<Order>())).thenAnswer { it.arguments[0] }

        refunder.onVendorSlaBreached(event)

        verify(refundProvider).refund(eq(order.id), any<Money>(), argThat { startsWith("sla_breach_refund_") })
    }

    @Test
    fun `onVendorSlaBreached updates order status to REFUNDED`() {
        val order = activeOrder(OrderStatus.SHIPPED)
        val event = breachEvent()

        whenever(orderRepository.findByVendorIdAndStatusIn(
            eq(vendorUUID),
            eq(listOf(OrderStatus.SHIPPED, OrderStatus.CONFIRMED))
        )).thenReturn(listOf(order))
        whenever(refundProvider.refund(eq(order.id), any<Money>(), any())).thenReturn(
            RefundResult(refundId = "ref-456", status = "succeeded")
        )
        whenever(orderRepository.save(any<Order>())).thenAnswer { it.arguments[0] }

        refunder.onVendorSlaBreached(event)

        verify(orderRepository).save(argThat<Order> { this.status == OrderStatus.REFUNDED })
    }

    @Test
    fun `onVendorSlaBreached sends customer notifications`() {
        val order = activeOrder(OrderStatus.CONFIRMED)
        val event = breachEvent()

        whenever(orderRepository.findByVendorIdAndStatusIn(
            eq(vendorUUID),
            eq(listOf(OrderStatus.SHIPPED, OrderStatus.CONFIRMED))
        )).thenReturn(listOf(order))
        whenever(refundProvider.refund(eq(order.id), any<Money>(), any())).thenReturn(
            RefundResult(refundId = "ref-789", status = "succeeded")
        )
        whenever(orderRepository.save(any<Order>())).thenAnswer { it.arguments[0] }

        refunder.onVendorSlaBreached(event)

        verify(notificationSender).send(
            eq(order.id),
            eq("SLA_BREACH_REFUND"),
            argThat { contains("ref-789") && contains("refunded") }
        )
    }

    @Test
    fun `onVendorSlaBreached with no active orders does nothing`() {
        val event = breachEvent()

        whenever(orderRepository.findByVendorIdAndStatusIn(
            eq(vendorUUID),
            eq(listOf(OrderStatus.SHIPPED, OrderStatus.CONFIRMED))
        )).thenReturn(emptyList())

        refunder.onVendorSlaBreached(event)

        verify(refundProvider, never()).refund(any(), any<Money>(), any())
        verify(notificationSender, never()).send(any(), any(), any())
        verify(orderRepository, never()).save(any<Order>())
    }

    @Test
    fun `onVendorSlaBreached processes multiple orders`() {
        val order1 = activeOrder(OrderStatus.CONFIRMED)
        val order2 = activeOrder(OrderStatus.SHIPPED)
        val event = breachEvent()

        whenever(orderRepository.findByVendorIdAndStatusIn(
            eq(vendorUUID),
            eq(listOf(OrderStatus.SHIPPED, OrderStatus.CONFIRMED))
        )).thenReturn(listOf(order1, order2))
        whenever(refundProvider.refund(any(), any<Money>(), any())).thenReturn(
            RefundResult(refundId = "ref-multi", status = "succeeded")
        )
        whenever(orderRepository.save(any<Order>())).thenAnswer { it.arguments[0] }

        refunder.onVendorSlaBreached(event)

        verify(refundProvider, times(2)).refund(any(), any<Money>(), any())
        verify(orderRepository, times(2)).save(any<Order>())
        verify(notificationSender, times(2)).send(any(), eq("SLA_BREACH_REFUND"), any())
    }

    @Test
    fun `onVendorSlaBreached continues processing remaining orders when one refund fails`() {
        val order1 = activeOrder(OrderStatus.CONFIRMED)
        val order2 = activeOrder(OrderStatus.SHIPPED)
        val event = breachEvent()

        whenever(orderRepository.findByVendorIdAndStatusIn(
            eq(vendorUUID),
            eq(listOf(OrderStatus.SHIPPED, OrderStatus.CONFIRMED))
        )).thenReturn(listOf(order1, order2))
        whenever(refundProvider.refund(eq(order1.id), any<Money>(), any()))
            .thenThrow(RuntimeException("Stripe error"))
        whenever(refundProvider.refund(eq(order2.id), any<Money>(), any())).thenReturn(
            RefundResult(refundId = "ref-ok", status = "succeeded")
        )
        whenever(orderRepository.save(any<Order>())).thenAnswer { it.arguments[0] }

        refunder.onVendorSlaBreached(event)

        // First order failed, second succeeded
        verify(orderRepository, times(1)).save(argThat<Order> { this.status == OrderStatus.REFUNDED })
        verify(notificationSender, times(1)).send(any(), eq("SLA_BREACH_REFUND"), any())
    }
}
