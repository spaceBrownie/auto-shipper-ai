package com.autoshipper.fulfillment.handler.webhook

import com.autoshipper.fulfillment.config.ShopifyWebhookProperties
import com.autoshipper.fulfillment.persistence.WebhookEventPersister
import com.autoshipper.fulfillment.persistence.WebhookEventRepository
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

@ExtendWith(MockitoExtension::class)
class ShopifyWebhookHmacIntegrationTest {

    @Mock
    lateinit var webhookEventRepository: WebhookEventRepository

    @Mock
    lateinit var webhookEventPersister: WebhookEventPersister

    @Mock
    lateinit var eventPublisher: ApplicationEventPublisher

    private lateinit var mockMvc: MockMvc
    private val objectMapper = jacksonObjectMapper()
    private val testSecret = "hmac-integration-test-secret"

    private val payload = """{"id":12345,"name":"#1001","currency":"USD","customer":{"email":"test@example.com"},"line_items":[{"product_id":111,"variant_id":222,"quantity":1,"price":"19.99","title":"Test"}]}"""

    @BeforeEach
    fun setUp() {
        val properties = ShopifyWebhookProperties(
            secrets = listOf(testSecret),
            replayProtection = ShopifyWebhookProperties.ReplayProtection(enabled = false)
        )

        val controller = ShopifyWebhookController(
            webhookEventRepository = webhookEventRepository,
            webhookEventPersister = webhookEventPersister,
            eventPublisher = eventPublisher,
            properties = properties,
            objectMapper = objectMapper
        )

        val filter = ShopifyHmacVerificationFilter(listOf(testSecret))

        val builder = MockMvcBuilders.standaloneSetup(controller)
        builder.addFilter<Nothing>(filter, "/webhooks/shopify/*")
        mockMvc = builder.build()
    }

    private fun computeHmac(body: String, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return Base64.getEncoder().encodeToString(mac.doFinal(body.toByteArray(Charsets.UTF_8)))
    }

    @Test
    fun `valid HMAC passes filter and reaches controller - returns 200`() {
        val hmac = computeHmac(payload, testSecret)
        whenever(webhookEventRepository.existsByEventId("evt-hmac-1")).thenReturn(false)
        whenever(webhookEventPersister.tryPersist(any())).thenReturn(true)

        mockMvc.perform(
            post("/webhooks/shopify/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("X-Shopify-Hmac-SHA256", hmac)
                .header("X-Shopify-Topic", "orders/create")
                .header("X-Shopify-Event-Id", "evt-hmac-1")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("accepted"))

        verify(eventPublisher).publishEvent(any<ShopifyOrderReceivedEvent>())
    }

    @Test
    fun `invalid HMAC is rejected by filter - returns 401, controller never reached`() {
        mockMvc.perform(
            post("/webhooks/shopify/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("X-Shopify-Hmac-SHA256", "totally-wrong-hmac")
                .header("X-Shopify-Topic", "orders/create")
                .header("X-Shopify-Event-Id", "evt-hmac-2")
        )
            .andExpect(status().isUnauthorized)

        verify(webhookEventPersister, never()).tryPersist(any())
        verify(eventPublisher, never()).publishEvent(any())
    }

    @Test
    fun `missing HMAC header is rejected by filter - returns 401`() {
        mockMvc.perform(
            post("/webhooks/shopify/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
                .header("X-Shopify-Topic", "orders/create")
                .header("X-Shopify-Event-Id", "evt-hmac-3")
        )
            .andExpect(status().isUnauthorized)

        verify(webhookEventPersister, never()).tryPersist(any())
    }
}
