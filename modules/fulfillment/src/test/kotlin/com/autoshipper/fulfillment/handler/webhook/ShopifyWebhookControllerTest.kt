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
import java.time.Instant

@ExtendWith(MockitoExtension::class)
class ShopifyWebhookControllerTest {

    @Mock
    lateinit var webhookEventRepository: WebhookEventRepository

    @Mock
    lateinit var eventPublisher: ApplicationEventPublisher

    private lateinit var mockMvc: MockMvc
    private val objectMapper = jacksonObjectMapper()

    private val validPayload = """
        {
          "id": 820982911946154508,
          "name": "#1001",
          "currency": "USD",
          "customer": {
            "email": "customer@example.com"
          },
          "line_items": [
            {
              "product_id": 788032119674292922,
              "variant_id": 788032119674292923,
              "quantity": 2,
              "price": "29.99",
              "title": "Wireless Bluetooth Speaker"
            }
          ]
        }
    """.trimIndent()

    @BeforeEach
    fun setUp() {
        val properties = ShopifyWebhookProperties(
            secrets = listOf("test-secret"),
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
    fun `valid webhook returns 200 with accepted status`() {
        whenever(webhookEventRepository.existsByEventId("evt-123")).thenReturn(false)
        whenever(webhookEventRepository.save(any<WebhookEvent>())).thenAnswer { it.arguments[0] }

        mockMvc.perform(
            post("/webhooks/shopify/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validPayload)
                .header("X-Shopify-Topic", "orders/create")
                .header("X-Shopify-Event-Id", "evt-123")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("accepted"))

        verify(webhookEventRepository).save(argThat<WebhookEvent> {
            eventId == "evt-123" && topic == "orders/create" && channel == "shopify"
        })
        verify(eventPublisher).publishEvent(argThat<ShopifyOrderReceivedEvent> {
            rawPayload == validPayload && shopifyEventId == "evt-123"
        })
    }

    @Test
    fun `duplicate event returns 200 with already_processed status`() {
        whenever(webhookEventRepository.existsByEventId("evt-123")).thenReturn(true)

        mockMvc.perform(
            post("/webhooks/shopify/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validPayload)
                .header("X-Shopify-Topic", "orders/create")
                .header("X-Shopify-Event-Id", "evt-123")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("already_processed"))

        verify(eventPublisher, never()).publishEvent(any())
        verify(webhookEventRepository, never()).save(any<WebhookEvent>())
    }

    @Test
    fun `invalid topic returns 400`() {
        mockMvc.perform(
            post("/webhooks/shopify/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validPayload)
                .header("X-Shopify-Topic", "orders/updated")
                .header("X-Shopify-Event-Id", "evt-123")
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("Unexpected topic"))

        verify(eventPublisher, never()).publishEvent(any())
        verify(webhookEventRepository, never()).save(any<WebhookEvent>())
    }

    @Test
    fun `replay protection enabled rejects stale timestamp`() {
        val properties = ShopifyWebhookProperties(
            secrets = listOf("test-secret"),
            replayProtection = ShopifyWebhookProperties.ReplayProtection(
                enabled = true,
                maxAgeSeconds = 300
            )
        )

        val controller = ShopifyWebhookController(
            webhookEventRepository = webhookEventRepository,
            eventPublisher = eventPublisher,
            properties = properties,
            objectMapper = objectMapper
        )

        val replayMockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setMessageConverters(StringHttpMessageConverter(), MappingJackson2HttpMessageConverter(objectMapper))
            .build()

        whenever(webhookEventRepository.existsByEventId("evt-456")).thenReturn(false)

        val staleTimestamp = Instant.now().minusSeconds(600).toString()

        replayMockMvc.perform(
            post("/webhooks/shopify/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validPayload)
                .header("X-Shopify-Topic", "orders/create")
                .header("X-Shopify-Event-Id", "evt-456")
                .header("X-Shopify-Triggered-At", staleTimestamp)
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.error").value("Event too old"))

        verify(eventPublisher, never()).publishEvent(any())
        verify(webhookEventRepository, never()).save(any<WebhookEvent>())
    }

    @Test
    fun `replay protection disabled allows stale timestamp`() {
        whenever(webhookEventRepository.existsByEventId("evt-789")).thenReturn(false)
        whenever(webhookEventRepository.save(any<WebhookEvent>())).thenAnswer { it.arguments[0] }

        val staleTimestamp = Instant.now().minusSeconds(600).toString()

        mockMvc.perform(
            post("/webhooks/shopify/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validPayload)
                .header("X-Shopify-Topic", "orders/create")
                .header("X-Shopify-Event-Id", "evt-789")
                .header("X-Shopify-Triggered-At", staleTimestamp)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("accepted"))

        verify(webhookEventRepository).save(any<WebhookEvent>())
        verify(eventPublisher).publishEvent(any<ShopifyOrderReceivedEvent>())
    }
}
