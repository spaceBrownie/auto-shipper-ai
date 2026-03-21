package com.autoshipper.fulfillment.handler.webhook

import com.autoshipper.fulfillment.config.ShopifyWebhookProperties
import com.autoshipper.fulfillment.persistence.WebhookEvent
import com.autoshipper.fulfillment.persistence.WebhookEventRepository
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.Captor
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.MediaType
import org.springframework.http.converter.StringHttpMessageConverter
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders

/**
 * Integration test for the full webhook controller flow:
 * controller -> dedup check -> event persistence -> event publishing.
 *
 * Uses MockMvc with manually wired components. External dependencies
 * (PlatformListingResolver, VendorSkuResolver, InventoryChecker) are not
 * involved at the controller layer — they operate in the downstream
 * ShopifyOrderProcessingService triggered by the published event.
 */
@ExtendWith(MockitoExtension::class)
class ShopifyWebhookIntegrationTest {

    @Mock
    lateinit var webhookEventRepository: WebhookEventRepository

    @Mock
    lateinit var eventPublisher: ApplicationEventPublisher

    @Captor
    lateinit var eventCaptor: ArgumentCaptor<ShopifyOrderReceivedEvent>

    @Captor
    lateinit var webhookEventCaptor: ArgumentCaptor<WebhookEvent>

    private lateinit var mockMvc: MockMvc
    private val objectMapper = jacksonObjectMapper()

    private val testSecret = "test-webhook-secret-key"

    private val shopifyPayload = """
        {
          "id": 820982911946154508,
          "admin_graphql_api_id": "gid://shopify/Order/820982911946154508",
          "name": "#1001",
          "order_number": 1001,
          "currency": "USD",
          "total_price": "89.98",
          "subtotal_price": "89.98",
          "financial_status": "paid",
          "fulfillment_status": null,
          "customer": {
            "id": 115310627314723954,
            "email": "john@example.com",
            "first_name": "John",
            "last_name": "Doe"
          },
          "line_items": [
            {
              "id": 866550311766439020,
              "product_id": 788032119674292922,
              "variant_id": 788032119674292923,
              "title": "Wireless Bluetooth Speaker",
              "quantity": 2,
              "price": "29.99",
              "sku": "BT-SPEAKER-001"
            },
            {
              "id": 141249953214522974,
              "product_id": 632910392,
              "variant_id": 808950810,
              "title": "USB-C Charging Cable",
              "quantity": 1,
              "price": "29.99",
              "sku": "USB-C-CABLE-001"
            }
          ],
          "created_at": "2026-03-21T10:00:00-05:00",
          "updated_at": "2026-03-21T10:00:00-05:00"
        }
    """.trimIndent()

    @BeforeEach
    fun setUp() {
        val properties = ShopifyWebhookProperties(
            secrets = listOf(testSecret),
            replayProtection = ShopifyWebhookProperties.ReplayProtection(
                enabled = false,
                maxAgeSeconds = 300
            )
        )

        val controller = ShopifyWebhookController(
            webhookEventRepository = webhookEventRepository,
            eventPublisher = eventPublisher,
            properties = properties,
            objectMapper = objectMapper
        )

        val converter = MappingJackson2HttpMessageConverter(objectMapper)
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setMessageConverters(StringHttpMessageConverter(), converter)
            .build()
    }

    @Test
    fun `full flow - valid webhook persists event and publishes ShopifyOrderReceivedEvent`() {
        val eventId = "integration-test-evt-001"

        whenever(webhookEventRepository.existsByEventId(eventId)).thenReturn(false)
        whenever(webhookEventRepository.save(any<WebhookEvent>())).thenAnswer { it.arguments[0] }

        mockMvc.perform(
            post("/webhooks/shopify/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(shopifyPayload)
                .header("X-Shopify-Topic", "orders/create")
                .header("X-Shopify-Event-Id", eventId)
                .header("X-Shopify-Hmac-SHA256", "test-hmac") // HMAC checked by filter, not controller
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("accepted"))

        // Verify WebhookEvent was persisted with correct fields
        verify(webhookEventRepository).save(capture(webhookEventCaptor))
        val savedEvent = webhookEventCaptor.value
        assert(savedEvent.eventId == eventId) { "Expected eventId=$eventId, got ${savedEvent.eventId}" }
        assert(savedEvent.topic == "orders/create") { "Expected topic=orders/create, got ${savedEvent.topic}" }
        assert(savedEvent.channel == "shopify") { "Expected channel=shopify, got ${savedEvent.channel}" }

        // Verify ShopifyOrderReceivedEvent was published with the raw payload
        verify(eventPublisher).publishEvent(capture(eventCaptor))
        val publishedEvent = eventCaptor.value
        assert(publishedEvent.shopifyEventId == eventId) {
            "Expected shopifyEventId=$eventId, got ${publishedEvent.shopifyEventId}"
        }
        assert(publishedEvent.rawPayload == shopifyPayload) {
            "Expected raw payload to match the request body"
        }
    }

    @Test
    fun `full flow - valid webhook with case-insensitive topic`() {
        val eventId = "integration-test-evt-002"

        whenever(webhookEventRepository.existsByEventId(eventId)).thenReturn(false)
        whenever(webhookEventRepository.save(any<WebhookEvent>())).thenAnswer { it.arguments[0] }

        mockMvc.perform(
            post("/webhooks/shopify/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(shopifyPayload)
                .header("X-Shopify-Topic", "Orders/Create")
                .header("X-Shopify-Event-Id", eventId)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("accepted"))

        verify(webhookEventRepository).save(any<WebhookEvent>())
        verify(eventPublisher).publishEvent(any<ShopifyOrderReceivedEvent>())
    }

    @Test
    fun `full flow - missing event ID returns 400`() {
        mockMvc.perform(
            post("/webhooks/shopify/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(shopifyPayload)
                .header("X-Shopify-Topic", "orders/create")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("Missing event ID"))

        verify(webhookEventRepository, never()).save(any<WebhookEvent>())
        verify(eventPublisher, never()).publishEvent(any())
    }

    @Test
    fun `full flow - missing topic returns 400`() {
        mockMvc.perform(
            post("/webhooks/shopify/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(shopifyPayload)
                .header("X-Shopify-Event-Id", "evt-missing-topic")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("Unexpected topic"))

        verify(webhookEventRepository, never()).existsByEventId(any())
        verify(eventPublisher, never()).publishEvent(any())
    }
}
