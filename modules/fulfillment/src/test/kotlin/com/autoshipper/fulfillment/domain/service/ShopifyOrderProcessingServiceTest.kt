package com.autoshipper.fulfillment.domain.service

import com.autoshipper.fulfillment.domain.Order
import com.autoshipper.fulfillment.domain.OrderStatus
import com.autoshipper.fulfillment.domain.channel.ShopifyOrderAdapter
import com.autoshipper.fulfillment.handler.webhook.ShopifyOrderReceivedEvent
import com.autoshipper.fulfillment.proxy.platform.PlatformListingResolver
import com.autoshipper.fulfillment.proxy.platform.VendorSkuResolver
import com.autoshipper.shared.money.Currency
import com.autoshipper.shared.money.Money
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*
import java.math.BigDecimal
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class ShopifyOrderProcessingServiceTest {

    @Mock
    lateinit var shopifyOrderAdapter: ShopifyOrderAdapter

    @Mock
    lateinit var platformListingResolver: PlatformListingResolver

    @Mock
    lateinit var vendorSkuResolver: VendorSkuResolver

    @Mock
    lateinit var orderService: OrderService

    @InjectMocks
    lateinit var processingService: ShopifyOrderProcessingService

    private val skuId1 = UUID.randomUUID()
    private val skuId2 = UUID.randomUUID()
    private val vendorId1 = UUID.randomUUID()
    private val vendorId2 = UUID.randomUUID()

    private fun loadPayload(): String =
        javaClass.classLoader.getResource("shopify/orders-create-webhook.json")!!.readText()

    private fun createEvent(): ShopifyOrderReceivedEvent {
        val payload = loadPayload()
        return ShopifyOrderReceivedEvent(rawPayload = payload, shopifyEventId = "evt-123")
    }

    private fun stubAdapterParse() {
        val payload = loadPayload()
        val adapter = ShopifyOrderAdapter(com.fasterxml.jackson.databind.ObjectMapper())
        val channelOrder = adapter.parse(payload)
        whenever(shopifyOrderAdapter.parse(any())).thenReturn(channelOrder)
    }

    private fun pendingOrder(skuId: UUID, vendorId: UUID, idempotencyKey: String, totalAmount: Money): Order =
        Order(
            idempotencyKey = idempotencyKey,
            skuId = skuId,
            vendorId = vendorId,
            customerId = UUID.nameUUIDFromBytes("customer@example.com".toByteArray()),
            totalAmount = totalAmount.normalizedAmount,
            totalCurrency = totalAmount.currency,
            paymentIntentId = "shopify:order:820982911946154500",
            status = OrderStatus.PENDING
        )

    @Test
    fun `happy path - 2 line items both resolve creates 2 orders`() {
        stubAdapterParse()

        whenever(platformListingResolver.resolveSkuId("7513594", "34505432", "SHOPIFY")).thenReturn(skuId1)
        whenever(platformListingResolver.resolveSkuId("8471210", "44892716", "SHOPIFY")).thenReturn(skuId2)
        whenever(vendorSkuResolver.resolveVendorId(skuId1)).thenReturn(vendorId1)
        whenever(vendorSkuResolver.resolveVendorId(skuId2)).thenReturn(vendorId2)

        val expectedTotal1 = Money.of(BigDecimal("59.98"), Currency.USD)
        val expectedTotal2 = Money.of(BigDecimal("49.99"), Currency.USD)

        val order1 = pendingOrder(skuId1, vendorId1, "shopify:order:820982911946154500:item:0", expectedTotal1)
        val order2 = pendingOrder(skuId2, vendorId2, "shopify:order:820982911946154500:item:1", expectedTotal2)

        whenever(orderService.create(argThat<CreateOrderCommand> {
            idempotencyKey == "shopify:order:820982911946154500:item:0"
        })).thenReturn(Pair(order1, true))

        whenever(orderService.create(argThat<CreateOrderCommand> {
            idempotencyKey == "shopify:order:820982911946154500:item:1"
        })).thenReturn(Pair(order2, true))

        whenever(orderService.setChannelMetadata(any(), any(), any(), any())).thenReturn(order1)

        processingService.onOrderReceived(createEvent())

        // Verify exact Money values for line item 1: quantity 2 * $29.99 = $59.98
        verify(orderService).create(argThat<CreateOrderCommand> {
            skuId == skuId1 &&
                vendorId == vendorId1 &&
                totalAmount == Money.of(BigDecimal("59.98"), Currency.USD) &&
                paymentIntentId == "shopify:order:820982911946154500" &&
                idempotencyKey == "shopify:order:820982911946154500:item:0"
        })

        // Verify exact Money values for line item 2: quantity 1 * $49.99 = $49.99
        verify(orderService).create(argThat<CreateOrderCommand> {
            skuId == skuId2 &&
                vendorId == vendorId2 &&
                totalAmount == Money.of(BigDecimal("49.99"), Currency.USD) &&
                paymentIntentId == "shopify:order:820982911946154500" &&
                idempotencyKey == "shopify:order:820982911946154500:item:1"
        })

        // Verify channel metadata set for both new orders
        verify(orderService, times(2)).setChannelMetadata(
            any(),
            eq("shopify"),
            eq("820982911946154500"),
            eq("#1001")
        )
    }

    @Test
    fun `partial resolution - 1 of 2 resolvable creates 1 order`() {
        stubAdapterParse()

        whenever(platformListingResolver.resolveSkuId("7513594", "34505432", "SHOPIFY")).thenReturn(skuId1)
        whenever(platformListingResolver.resolveSkuId("8471210", "44892716", "SHOPIFY")).thenReturn(null)
        whenever(vendorSkuResolver.resolveVendorId(skuId1)).thenReturn(vendorId1)

        val expectedTotal = Money.of(BigDecimal("59.98"), Currency.USD)
        val order1 = pendingOrder(skuId1, vendorId1, "shopify:order:820982911946154500:item:0", expectedTotal)
        whenever(orderService.create(any<CreateOrderCommand>())).thenReturn(Pair(order1, true))
        whenever(orderService.setChannelMetadata(any(), any(), any(), any())).thenReturn(order1)

        processingService.onOrderReceived(createEvent())

        verify(orderService, times(1)).create(any<CreateOrderCommand>())
        verify(orderService, times(1)).setChannelMetadata(any(), any(), any(), any())
    }

    @Test
    fun `no resolution - both unresolvable creates 0 orders`() {
        stubAdapterParse()

        whenever(platformListingResolver.resolveSkuId("7513594", "34505432", "SHOPIFY")).thenReturn(null)
        whenever(platformListingResolver.resolveSkuId("8471210", "44892716", "SHOPIFY")).thenReturn(null)

        processingService.onOrderReceived(createEvent())

        verify(orderService, never()).create(any<CreateOrderCommand>())
        verify(orderService, never()).setChannelMetadata(any(), any(), any(), any())
    }

    @Test
    fun `idempotent - existing order does not set channel metadata again`() {
        stubAdapterParse()

        whenever(platformListingResolver.resolveSkuId("7513594", "34505432", "SHOPIFY")).thenReturn(skuId1)
        whenever(platformListingResolver.resolveSkuId("8471210", "44892716", "SHOPIFY")).thenReturn(skuId2)
        whenever(vendorSkuResolver.resolveVendorId(skuId1)).thenReturn(vendorId1)
        whenever(vendorSkuResolver.resolveVendorId(skuId2)).thenReturn(vendorId2)

        val expectedTotal1 = Money.of(BigDecimal("59.98"), Currency.USD)
        val expectedTotal2 = Money.of(BigDecimal("49.99"), Currency.USD)
        val existingOrder1 = pendingOrder(skuId1, vendorId1, "shopify:order:820982911946154500:item:0", expectedTotal1)
        val existingOrder2 = pendingOrder(skuId2, vendorId2, "shopify:order:820982911946154500:item:1", expectedTotal2)

        // Both orders already exist (created = false)
        whenever(orderService.create(argThat<CreateOrderCommand> {
            idempotencyKey == "shopify:order:820982911946154500:item:0"
        })).thenReturn(Pair(existingOrder1, false))

        whenever(orderService.create(argThat<CreateOrderCommand> {
            idempotencyKey == "shopify:order:820982911946154500:item:1"
        })).thenReturn(Pair(existingOrder2, false))

        processingService.onOrderReceived(createEvent())

        verify(orderService, times(2)).create(any<CreateOrderCommand>())
        verify(orderService, never()).setChannelMetadata(any(), any(), any(), any())
    }

    @Test
    fun `vendor not found - SKU resolves but vendor does not, skips line item`() {
        stubAdapterParse()

        whenever(platformListingResolver.resolveSkuId("7513594", "34505432", "SHOPIFY")).thenReturn(skuId1)
        whenever(platformListingResolver.resolveSkuId("8471210", "44892716", "SHOPIFY")).thenReturn(skuId2)
        whenever(vendorSkuResolver.resolveVendorId(skuId1)).thenReturn(null)
        whenever(vendorSkuResolver.resolveVendorId(skuId2)).thenReturn(vendorId2)

        val expectedTotal = Money.of(BigDecimal("49.99"), Currency.USD)
        val order2 = pendingOrder(skuId2, vendorId2, "shopify:order:820982911946154500:item:1", expectedTotal)
        whenever(orderService.create(any<CreateOrderCommand>())).thenReturn(Pair(order2, true))
        whenever(orderService.setChannelMetadata(any(), any(), any(), any())).thenReturn(order2)

        processingService.onOrderReceived(createEvent())

        // Only 1 order created (second line item), first skipped due to missing vendor
        verify(orderService, times(1)).create(argThat<CreateOrderCommand> {
            skuId == skuId2 &&
                vendorId == vendorId2 &&
                totalAmount == Money.of(BigDecimal("49.99"), Currency.USD) &&
                idempotencyKey == "shopify:order:820982911946154500:item:1"
        })
        verify(orderService, times(1)).setChannelMetadata(any(), any(), any(), any())
    }
}
