package com.autoshipper.fulfillment.handler.webhook

import com.autoshipper.fulfillment.config.ShopifyWebhookProperties
import com.autoshipper.fulfillment.persistence.WebhookEvent
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

/**
 * Deduplication test: verifies that sending the same Shopify event ID twice
 * results in only one WebhookEvent persisted and only one ShopifyOrderReceivedEvent published.
 */
@ExtendWith(MockitoExtension::class)
class ShopifyWebhookDeduplicationTest {

    @Mock
    lateinit var webhookEventRepository: WebhookEventRepository

    @Mock
    lateinit var eventPublisher: ApplicationEventPublisher

    private lateinit var mockMvc: MockMvc
    private val objectMapper = jacksonObjectMapper()

    private val payload = """
        {
          "id": 820982911946154508,
          "name": "#1001",
          "currency": "USD",
          "customer": {
            "email": "buyer@example.com"
          },
          "line_items": [
            {
              "product_id": 788032119674292922,
              "variant_id": 788032119674292923,
              "quantity": 1,
              "price": "49.99",
              "title": "Test Product"
            }
          ]
        }
    """.trimIndent()

    @BeforeEach
    fun setUp() {
        val properties = ShopifyWebhookProperties(
            secrets = listOf("dedup-test-secret"),
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
    fun `same event ID sent twice - first creates event, second is deduplicated`() {
        val eventId = "dedup-test-evt-001"

        // First call: event does not exist yet
        whenever(webhookEventRepository.existsByEventId(eventId)).thenReturn(false)
        whenever(webhookEventRepository.save(any<WebhookEvent>())).thenAnswer { it.arguments[0] }

        mockMvc.perform(
            post("/webhooks/shopify/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("X-Shopify-Topic", "orders/create")
                .header("X-Shopify-Event-Id", eventId)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("accepted"))

        // Verify first call persisted the event and published
        verify(webhookEventRepository, times(1)).save(argThat<WebhookEvent> {
            this.eventId == eventId
        })
        verify(eventPublisher, times(1)).publishEvent(argThat<ShopifyOrderReceivedEvent> {
            shopifyEventId == eventId
        })

        // Second call: event now exists (simulate DB state after first save)
        reset(webhookEventRepository, eventPublisher)
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

        // Verify second call did NOT persist a new event or publish
        verify(webhookEventRepository, never()).save(any<WebhookEvent>())
        // eventPublisher was not reset, so total publishEvent calls should still be 1
        // (the mock was not reset, but we verify on the repository mock which was reset)
        verifyNoInteractions(eventPublisher)
    }

    @Test
    fun `different event IDs are processed independently`() {
        val eventId1 = "dedup-test-evt-002"
        val eventId2 = "dedup-test-evt-003"

        whenever(webhookEventRepository.existsByEventId(eventId1)).thenReturn(false)
        whenever(webhookEventRepository.existsByEventId(eventId2)).thenReturn(false)
        whenever(webhookEventRepository.save(any<WebhookEvent>())).thenAnswer { it.arguments[0] }

        // First event
        mockMvc.perform(
            post("/webhooks/shopify/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("X-Shopify-Topic", "orders/create")
                .header("X-Shopify-Event-Id", eventId1)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("accepted"))

        // Second event (different ID)
        mockMvc.perform(
            post("/webhooks/shopify/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("X-Shopify-Topic", "orders/create")
                .header("X-Shopify-Event-Id", eventId2)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("accepted"))

        // Both should have been saved and published
        verify(webhookEventRepository, times(2)).save(any<WebhookEvent>())
        verify(eventPublisher, times(2)).publishEvent(any<ShopifyOrderReceivedEvent>())
    }
}
