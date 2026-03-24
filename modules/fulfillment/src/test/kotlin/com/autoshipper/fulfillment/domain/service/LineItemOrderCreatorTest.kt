package com.autoshipper.fulfillment.domain.service

import com.autoshipper.fulfillment.domain.Order
import com.autoshipper.fulfillment.domain.OrderStatus
import com.autoshipper.fulfillment.domain.channel.ChannelLineItem
import com.autoshipper.fulfillment.domain.channel.ChannelOrder
import com.autoshipper.fulfillment.proxy.platform.PlatformListingResolver
import com.autoshipper.fulfillment.proxy.platform.VendorSkuResolver
import com.autoshipper.shared.money.Currency
import com.autoshipper.shared.money.Money
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*
import java.math.BigDecimal
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class LineItemOrderCreatorTest {

    @Mock
    lateinit var platformListingResolver: PlatformListingResolver

    @Mock
    lateinit var vendorSkuResolver: VendorSkuResolver

    @Mock
    lateinit var orderService: OrderService

    @InjectMocks
    lateinit var creator: LineItemOrderCreator

    private val skuId = UUID.randomUUID()
    private val vendorId = UUID.randomUUID()
    private val customerId = UUID.nameUUIDFromBytes("customer@example.com".toByteArray())

    private val channelOrder = ChannelOrder(
        channelOrderId = "12345",
        channelOrderNumber = "#1001",
        channelName = "shopify",
        customerEmail = "customer@example.com",
        currencyCode = "USD",
        lineItems = emptyList()
    )

    private val lineItem = ChannelLineItem(
        externalProductId = "7513594",
        externalVariantId = "34505432",
        quantity = 2,
        unitPrice = BigDecimal("29.99"),
        title = "Premium Widget"
    )

    @Test
    fun `creates order with exact Money values when SKU and vendor resolve`() {
        whenever(platformListingResolver.resolveSkuId("7513594", "34505432", "SHOPIFY")).thenReturn(skuId)
        whenever(vendorSkuResolver.resolveVendorId(skuId)).thenReturn(vendorId)

        val expectedTotal = Money.of(BigDecimal("59.98"), Currency.USD)
        val order = Order(
            idempotencyKey = "shopify:order:12345:item:0",
            skuId = skuId, vendorId = vendorId, customerId = customerId,
            totalAmount = expectedTotal.normalizedAmount, totalCurrency = expectedTotal.currency,
            paymentIntentId = "shopify:order:12345", status = OrderStatus.PENDING
        )
        whenever(orderService.create(any<CreateOrderCommand>())).thenReturn(Pair(order, true))
        whenever(orderService.setChannelMetadata(any(), any(), any(), any())).thenReturn(order)

        val result = creator.processLineItem(0, lineItem, channelOrder, customerId, Currency.USD)

        assertTrue(result)
        verify(orderService).create(argThat<CreateOrderCommand> {
            this.skuId == skuId &&
                this.vendorId == vendorId &&
                totalAmount == Money.of(BigDecimal("59.98"), Currency.USD) &&
                paymentIntentId == "shopify:order:12345" &&
                idempotencyKey == "shopify:order:12345:item:0"
        })
        verify(orderService).setChannelMetadata(any(), eq("shopify"), eq("12345"), eq("#1001"))
    }

    @Test
    fun `returns false when SKU not found`() {
        whenever(platformListingResolver.resolveSkuId("7513594", "34505432", "SHOPIFY")).thenReturn(null)

        val result = creator.processLineItem(0, lineItem, channelOrder, customerId, Currency.USD)

        assertFalse(result)
        verify(orderService, never()).create(any<CreateOrderCommand>())
    }

    @Test
    fun `returns false when vendor not found`() {
        whenever(platformListingResolver.resolveSkuId("7513594", "34505432", "SHOPIFY")).thenReturn(skuId)
        whenever(vendorSkuResolver.resolveVendorId(skuId)).thenReturn(null)

        val result = creator.processLineItem(0, lineItem, channelOrder, customerId, Currency.USD)

        assertFalse(result)
        verify(orderService, never()).create(any<CreateOrderCommand>())
    }

    @Test
    fun `returns false when order already exists (idempotent)`() {
        whenever(platformListingResolver.resolveSkuId("7513594", "34505432", "SHOPIFY")).thenReturn(skuId)
        whenever(vendorSkuResolver.resolveVendorId(skuId)).thenReturn(vendorId)

        val existingOrder = Order(
            idempotencyKey = "shopify:order:12345:item:0",
            skuId = skuId, vendorId = vendorId, customerId = customerId,
            totalAmount = BigDecimal("59.98"), totalCurrency = Currency.USD,
            paymentIntentId = "shopify:order:12345", status = OrderStatus.PENDING
        )
        whenever(orderService.create(any<CreateOrderCommand>())).thenReturn(Pair(existingOrder, false))

        val result = creator.processLineItem(0, lineItem, channelOrder, customerId, Currency.USD)

        assertFalse(result)
        verify(orderService, never()).setChannelMetadata(any(), any(), any(), any())
    }
}
