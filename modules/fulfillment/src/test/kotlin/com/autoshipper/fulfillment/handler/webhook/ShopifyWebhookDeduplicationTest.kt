package com.autoshipper.fulfillment.handler.webhook

import com.autoshipper.fulfillment.config.ShopifyWebhookProperties
import com.autoshipper.fulfillment.persistence.WebhookEvent
import com.autoshipper.fulfillment.persistence.WebhookEventPersister
import com.autoshipper.fulfillment.persistence.WebhookEventRepository
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
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
class ShopifyWebhookDeduplicationTest {

    @Mock
    lateinit var webhookEventRepository: WebhookEventRepository

    @Mock
    lateinit var webhookEventPersister: WebhookEventPersister

    @Mock
    lateinit var eventPublisher: ApplicationEventPublisher

    private lateinit var mockMvc: MockMvc
    private val objectMapper = jacksonObjectMapper()

    private val payload = """
        {
          "id": 820982911946154508,
          "name": "#1001",
          "currency": "USD",
          "customer": { "email": "buyer@example.com" },
          "line_items": [
            { "product_id": 788032119674292922, "variant_id": 788032119674292923, "quantity": 1, "price": "49.99", "title": "Test Product" }
          ]
        }
    """.trimIndent()

    @BeforeEach
    fun setUp() {
        val properties = ShopifyWebhookProperties(
            secrets = listOf("dedup-test-secret"),
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
    fun `same event ID sent twice - first creates event, second is deduplicated`() {
        val eventId = "dedup-test-evt-001"

        // First call: event does not exist yet
        whenever(webhookEventRepository.existsByEventId(eventId)).thenReturn(false)
        whenever(webhookEventPersister.tryPersist(any())).thenReturn(true)

        mockMvc.perform(
            post("/webhooks/shopify/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("X-Shopify-Topic", "orders/create")
                .header("X-Shopify-Event-Id", eventId)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("accepted"))

        verify(webhookEventPersister, times(1)).tryPersist(argThat<WebhookEvent> { this.eventId == eventId })
        verify(eventPublisher, times(1)).publishEvent(argThat<ShopifyOrderReceivedEvent> { shopifyEventId == eventId })

        // Second call: event now exists
        reset(webhookEventRepository, webhookEventPersister, eventPublisher)
        whenever(webhookEventRepository.existsByEventId(eventId)).thenReturn(true)

        mockMvc.perform(
            post("/webhooks/shopify/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("X-Shopify-Topic", "orders/create")
                .header("X-Shopify-Event-Id", eventId)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("already_processed"))

        verify(webhookEventPersister, never()).tryPersist(any())
        verifyNoInteractions(eventPublisher)
    }

    @Test
    fun `different event IDs are processed independently`() {
        val eventId1 = "dedup-test-evt-002"
        val eventId2 = "dedup-test-evt-003"

        whenever(webhookEventRepository.existsByEventId(eventId1)).thenReturn(false)
        whenever(webhookEventRepository.existsByEventId(eventId2)).thenReturn(false)
        whenever(webhookEventPersister.tryPersist(any())).thenReturn(true)

        mockMvc.perform(
            post("/webhooks/shopify/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("X-Shopify-Topic", "orders/create")
                .header("X-Shopify-Event-Id", eventId1)
        ).andExpect(status().isOk).andExpect(jsonPath("$.status").value("accepted"))

        mockMvc.perform(
            post("/webhooks/shopify/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("X-Shopify-Topic", "orders/create")
                .header("X-Shopify-Event-Id", eventId2)
        ).andExpect(status().isOk).andExpect(jsonPath("$.status").value("accepted"))

        verify(webhookEventPersister, times(2)).tryPersist(any())
        verify(eventPublisher, times(2)).publishEvent(any<ShopifyOrderReceivedEvent>())
    }
}
