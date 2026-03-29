package com.autoshipper.fulfillment.domain.service

import com.autoshipper.fulfillment.domain.Order
import com.autoshipper.fulfillment.domain.OrderStatus
import com.autoshipper.fulfillment.domain.channel.ChannelLineItem
import com.autoshipper.fulfillment.domain.channel.ChannelOrder
import com.autoshipper.fulfillment.domain.channel.ChannelShippingAddress
import com.autoshipper.fulfillment.proxy.platform.PlatformListingResolver
import com.autoshipper.fulfillment.proxy.platform.VendorSkuResolver
import com.autoshipper.shared.money.Currency
import com.autoshipper.shared.money.Money
import org.junit.jupiter.api.Assertions.*
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

    @Test
    fun `quantity flows from lineItem to CreateOrderCommand`() {
        val lineItemQty3 = ChannelLineItem(
            externalProductId = "7513594",
            externalVariantId = "34505432",
            quantity = 3,
            unitPrice = BigDecimal("10.00"),
            title = "Widget"
        )
        whenever(platformListingResolver.resolveSkuId("7513594", "34505432", "SHOPIFY")).thenReturn(skuId)
        whenever(vendorSkuResolver.resolveVendorId(skuId)).thenReturn(vendorId)

        val order = Order(
            idempotencyKey = "shopify:order:12345:item:0",
            skuId = skuId, vendorId = vendorId, customerId = customerId,
            totalAmount = BigDecimal("30.0000"), totalCurrency = Currency.USD,
            paymentIntentId = "shopify:order:12345", status = OrderStatus.PENDING
        )
        whenever(orderService.create(any<CreateOrderCommand>())).thenReturn(Pair(order, true))
        whenever(orderService.setChannelMetadata(any(), any(), any(), any())).thenReturn(order)

        creator.processLineItem(0, lineItemQty3, channelOrder, customerId, Currency.USD)

        val captor = argumentCaptor<CreateOrderCommand>()
        verify(orderService).create(captor.capture())
        val captured = captor.firstValue
        assertEquals(3, captured.quantity, "Expected quantity=3 in CreateOrderCommand")
    }

    @Test
    fun `shippingAddress maps from ChannelShippingAddress to ShippingAddress`() {
        val channelAddr = ChannelShippingAddress(
            firstName = "John",
            lastName = "Doe",
            address1 = "456 Oak Ave",
            address2 = "Apt 2B",
            city = "Portland",
            province = "Oregon",
            provinceCode = "OR",
            country = "United States",
            countryCode = "US",
            zip = "97201",
            phone = "555-1234"
        )
        whenever(platformListingResolver.resolveSkuId("7513594", "34505432", "SHOPIFY")).thenReturn(skuId)
        whenever(vendorSkuResolver.resolveVendorId(skuId)).thenReturn(vendorId)

        val order = Order(
            idempotencyKey = "shopify:order:12345:item:0",
            skuId = skuId, vendorId = vendorId, customerId = customerId,
            totalAmount = BigDecimal("59.9800"), totalCurrency = Currency.USD,
            paymentIntentId = "shopify:order:12345", status = OrderStatus.PENDING
        )
        whenever(orderService.create(any<CreateOrderCommand>())).thenReturn(Pair(order, true))
        whenever(orderService.setChannelMetadata(any(), any(), any(), any())).thenReturn(order)

        creator.processLineItem(0, lineItem, channelOrder, customerId, Currency.USD, channelAddr)

        val captor = argumentCaptor<CreateOrderCommand>()
        verify(orderService).create(captor.capture())
        val addr = captor.firstValue.shippingAddress
        assertNotNull(addr, "Expected ShippingAddress to be non-null")
        assertEquals("John Doe", addr!!.customerName)
        assertEquals("456 Oak Ave", addr.addressLine1)
        assertEquals("Apt 2B", addr.addressLine2)
        assertEquals("Portland", addr.city)
        assertEquals("OR", addr.province, "province should use provinceCode when available")
        assertEquals("OR", addr.provinceCode)
        assertEquals("United States", addr.country)
        assertEquals("US", addr.countryCode)
        assertEquals("97201", addr.zip)
        assertEquals("555-1234", addr.phone)
    }

    @Test
    fun `routeToVendor called after order creation when order is new`() {
        whenever(platformListingResolver.resolveSkuId("7513594", "34505432", "SHOPIFY")).thenReturn(skuId)
        whenever(vendorSkuResolver.resolveVendorId(skuId)).thenReturn(vendorId)

        val order = Order(
            idempotencyKey = "shopify:order:12345:item:0",
            skuId = skuId, vendorId = vendorId, customerId = customerId,
            totalAmount = BigDecimal("59.9800"), totalCurrency = Currency.USD,
            paymentIntentId = "shopify:order:12345", status = OrderStatus.PENDING
        )
        whenever(orderService.create(any<CreateOrderCommand>())).thenReturn(Pair(order, true))
        whenever(orderService.setChannelMetadata(any(), any(), any(), any())).thenReturn(order)

        val result = creator.processLineItem(0, lineItem, channelOrder, customerId, Currency.USD)

        assertTrue(result)
        verify(orderService).routeToVendor(order.id)
    }

    @Test
    fun `routeToVendor NOT called when order already existed`() {
        whenever(platformListingResolver.resolveSkuId("7513594", "34505432", "SHOPIFY")).thenReturn(skuId)
        whenever(vendorSkuResolver.resolveVendorId(skuId)).thenReturn(vendorId)

        val existingOrder = Order(
            idempotencyKey = "shopify:order:12345:item:0",
            skuId = skuId, vendorId = vendorId, customerId = customerId,
            totalAmount = BigDecimal("59.9800"), totalCurrency = Currency.USD,
            paymentIntentId = "shopify:order:12345", status = OrderStatus.PENDING
        )
        whenever(orderService.create(any<CreateOrderCommand>())).thenReturn(Pair(existingOrder, false))

        val result = creator.processLineItem(0, lineItem, channelOrder, customerId, Currency.USD)

        assertFalse(result)
        verify(orderService, never()).routeToVendor(any())
    }

    @Test
    fun `null shippingAddress passes through as null in CreateOrderCommand`() {
        whenever(platformListingResolver.resolveSkuId("7513594", "34505432", "SHOPIFY")).thenReturn(skuId)
        whenever(vendorSkuResolver.resolveVendorId(skuId)).thenReturn(vendorId)

        val order = Order(
            idempotencyKey = "shopify:order:12345:item:0",
            skuId = skuId, vendorId = vendorId, customerId = customerId,
            totalAmount = BigDecimal("59.9800"), totalCurrency = Currency.USD,
            paymentIntentId = "shopify:order:12345", status = OrderStatus.PENDING
        )
        whenever(orderService.create(any<CreateOrderCommand>())).thenReturn(Pair(order, true))
        whenever(orderService.setChannelMetadata(any(), any(), any(), any())).thenReturn(order)

        creator.processLineItem(0, lineItem, channelOrder, customerId, Currency.USD, null)

        val captor = argumentCaptor<CreateOrderCommand>()
        verify(orderService).create(captor.capture())
        assertNull(captor.firstValue.shippingAddress, "Expected shippingAddress to be null")
    }
}
