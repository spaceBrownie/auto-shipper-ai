package com.autoshipper.fulfillment.handler.webhook

import com.autoshipper.fulfillment.config.ShopifyWebhookProperties
import com.autoshipper.fulfillment.persistence.WebhookEvent
import com.autoshipper.fulfillment.persistence.WebhookEventPersister
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

@ExtendWith(MockitoExtension::class)
class ShopifyWebhookIntegrationTest {

    @Mock
    lateinit var webhookEventRepository: WebhookEventRepository

    @Mock
    lateinit var webhookEventPersister: WebhookEventPersister

    @Mock
    lateinit var eventPublisher: ApplicationEventPublisher

    @Captor
    lateinit var eventCaptor: ArgumentCaptor<ShopifyOrderReceivedEvent>

    private lateinit var mockMvc: MockMvc
    private val objectMapper = jacksonObjectMapper()

    private val shopifyPayload = """
        {
          "id": 820982911946154508,
          "name": "#1001",
          "currency": "USD",
          "financial_status": "paid",
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
          ]
        }
    """.trimIndent()

    @BeforeEach
    fun setUp() {
        val properties = ShopifyWebhookProperties(
            secrets = listOf("test-webhook-secret-key"),
            replayProtection = ShopifyWebhookProperties.ReplayProtection(enabled = false, maxAgeSeconds = 300)
        )

        val controller = ShopifyWebhookController(
            webhookEventRepository = webhookEventRepository,
            webhookEventPersister = webhookEventPersister,
            eventPublisher = eventPublisher,
            properties = properties,
            objectMapper = objectMapper
        )

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setMessageConverters(StringHttpMessageConverter(), MappingJackson2HttpMessageConverter(objectMapper))
            .build()
    }

    @Test
    fun `full flow - valid webhook publishes ShopifyOrderReceivedEvent with raw payload`() {
        val eventId = "integration-test-evt-001"
        whenever(webhookEventRepository.existsByEventId(eventId)).thenReturn(false)
        whenever(webhookEventPersister.tryPersist(any())).thenReturn(true)

        mockMvc.perform(
            post("/webhooks/shopify/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(shopifyPayload)
                .header("X-Shopify-Topic", "orders/create")
                .header("X-Shopify-Event-Id", eventId)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("accepted"))

        verify(webhookEventPersister).tryPersist(argThat<WebhookEvent> {
            this.eventId == eventId && topic == "orders/create" && channel == "shopify"
        })

        verify(eventPublisher).publishEvent(capture(eventCaptor))
        val published = eventCaptor.value
        assert(published.shopifyEventId == eventId)
        assert(published.rawPayload == shopifyPayload)
    }

    @Test
    fun `full flow - case-insensitive topic accepted`() {
        val eventId = "integration-test-evt-002"
        whenever(webhookEventRepository.existsByEventId(eventId)).thenReturn(false)
        whenever(webhookEventPersister.tryPersist(any())).thenReturn(true)

        mockMvc.perform(
            post("/webhooks/shopify/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(shopifyPayload)
                .header("X-Shopify-Topic", "Orders/Create")
                .header("X-Shopify-Event-Id", eventId)
        ).andExpect(status().isOk).andExpect(jsonPath("$.status").value("accepted"))

        verify(webhookEventPersister).tryPersist(any())
        verify(eventPublisher).publishEvent(any<ShopifyOrderReceivedEvent>())
    }

    @Test
    fun `full flow - missing event ID returns 400`() {
        mockMvc.perform(
            post("/webhooks/shopify/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(shopifyPayload)
                .header("X-Shopify-Topic", "orders/create")
        ).andExpect(status().isBadRequest).andExpect(jsonPath("$.error").value("Missing event ID"))

        verify(webhookEventPersister, never()).tryPersist(any())
        verify(eventPublisher, never()).publishEvent(any())
    }

    @Test
    fun `full flow - missing topic returns 400`() {
        mockMvc.perform(
            post("/webhooks/shopify/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(shopifyPayload)
                .header("X-Shopify-Event-Id", "evt-missing-topic")
        ).andExpect(status().isBadRequest).andExpect(jsonPath("$.error").value("Unexpected topic"))

        verify(webhookEventRepository, never()).existsByEventId(any())
        verify(eventPublisher, never()).publishEvent(any())
    }
}
