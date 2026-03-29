package com.autoshipper.fulfillment.domain.service

import com.autoshipper.fulfillment.domain.channel.ChannelLineItem
import com.autoshipper.fulfillment.domain.channel.ChannelOrder
import com.autoshipper.fulfillment.domain.channel.ShopifyOrderAdapter
import com.autoshipper.fulfillment.handler.webhook.ShopifyOrderReceivedEvent
import com.autoshipper.shared.money.Currency
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
    lateinit var lineItemOrderCreator: LineItemOrderCreator

    @InjectMocks
    lateinit var processingService: ShopifyOrderProcessingService

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

    @Test
    fun `happy path - 2 line items both delegated to LineItemOrderCreator`() {
        stubAdapterParse()

        whenever(lineItemOrderCreator.processLineItem(
            any(), any(), any(), any(), any(), anyOrNull()
        )).thenReturn(true)

        processingService.onOrderReceived(createEvent())

        verify(lineItemOrderCreator, times(2)).processLineItem(
            any(), any(), any(), any(), eq(Currency.USD), anyOrNull()
        )
    }

    @Test
    fun `partial resolution - one line item returns false`() {
        stubAdapterParse()

        whenever(lineItemOrderCreator.processLineItem(eq(0), any(), any(), any(), any(), anyOrNull()))
            .thenReturn(true)
        whenever(lineItemOrderCreator.processLineItem(eq(1), any(), any(), any(), any(), anyOrNull()))
            .thenReturn(false)

        processingService.onOrderReceived(createEvent())

        verify(lineItemOrderCreator, times(2)).processLineItem(
            any(), any(), any(), any(), any(), anyOrNull()
        )
    }

    @Test
    fun `line item failure does not prevent processing of remaining items`() {
        stubAdapterParse()

        whenever(lineItemOrderCreator.processLineItem(eq(0), any(), any(), any(), any(), anyOrNull()))
            .thenThrow(RuntimeException("Inventory unavailable"))
        whenever(lineItemOrderCreator.processLineItem(eq(1), any(), any(), any(), any(), anyOrNull()))
            .thenReturn(true)

        processingService.onOrderReceived(createEvent())

        // Both line items attempted despite first one failing
        verify(lineItemOrderCreator, times(2)).processLineItem(
            any(), any(), any(), any(), any(), anyOrNull()
        )
    }

    @Test
    fun `unsupported currency - logs error and returns without processing line items`() {
        val payload = loadPayload()
        val adapter = ShopifyOrderAdapter(com.fasterxml.jackson.databind.ObjectMapper())
        val channelOrder = adapter.parse(payload)
        val unsupportedOrder = channelOrder.copy(currencyCode = "JPY")
        whenever(shopifyOrderAdapter.parse(any())).thenReturn(unsupportedOrder)

        processingService.onOrderReceived(createEvent())

        verify(lineItemOrderCreator, never()).processLineItem(
            any(), any(), any(), any(), any(), anyOrNull()
        )
    }
}
