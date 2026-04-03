package com.autoshipper.fulfillment.handler.webhook

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
class CjTrackingWebhookControllerTest {

    @Mock
    lateinit var webhookEventRepository: WebhookEventRepository

    @Mock
    lateinit var webhookEventPersister: WebhookEventPersister

    @Mock
    lateinit var eventPublisher: ApplicationEventPublisher

    private lateinit var mockMvc: MockMvc
    private val objectMapper = jacksonObjectMapper()

    private val validPayload = """{"messageId":"msg-cj-tracking-abc123def456","type":"LOGISTIC","messageType":"UPDATE","openId":1234567890,"params":{"orderId":"a1b2c3d4-e5f6-7890-abcd-ef1234567890","logisticName":"UPS","trackingNumber":"1Z999AA10123456784","trackingStatus":1,"logisticsTrackEvents":"[]"}}"""

    @BeforeEach
    fun setUp() {
        val controller = CjTrackingWebhookController(
            webhookEventRepository = webhookEventRepository,
            webhookEventPersister = webhookEventPersister,
            eventPublisher = eventPublisher,
            objectMapper = objectMapper
        )

        val converter = MappingJackson2HttpMessageConverter(objectMapper)
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setMessageConverters(StringHttpMessageConverter(), converter)
            .build()
    }

    @Test
    fun `valid webhook returns 200 with accepted status`() {
        val dedupKey = "cj:a1b2c3d4-e5f6-7890-abcd-ef1234567890:1Z999AA10123456784"
        whenever(webhookEventRepository.existsByEventId(dedupKey)).thenReturn(false)
        whenever(webhookEventPersister.tryPersist(any())).thenReturn(true)

        mockMvc.perform(
            post("/webhooks/cj/tracking")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validPayload)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("accepted"))

        verify(webhookEventPersister).tryPersist(argThat<WebhookEvent> {
            eventId == dedupKey && topic == "tracking/update" && channel == "cj"
        })
        verify(eventPublisher).publishEvent(argThat<CjTrackingReceivedEvent> {
            rawPayload == validPayload && this.dedupKey == dedupKey
        })
    }

    @Test
    fun `duplicate via existsByEventId returns already_processed`() {
        val dedupKey = "cj:a1b2c3d4-e5f6-7890-abcd-ef1234567890:1Z999AA10123456784"
        whenever(webhookEventRepository.existsByEventId(dedupKey)).thenReturn(true)

        mockMvc.perform(
            post("/webhooks/cj/tracking")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validPayload)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("already_processed"))

        verify(eventPublisher, never()).publishEvent(any())
        verify(webhookEventPersister, never()).tryPersist(any())
    }

    @Test
    fun `concurrent duplicate via persister returns already_processed`() {
        val dedupKey = "cj:a1b2c3d4-e5f6-7890-abcd-ef1234567890:1Z999AA10123456784"
        whenever(webhookEventRepository.existsByEventId(dedupKey)).thenReturn(false)
        whenever(webhookEventPersister.tryPersist(any())).thenReturn(false)

        mockMvc.perform(
            post("/webhooks/cj/tracking")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validPayload)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("already_processed"))

        verify(eventPublisher, never()).publishEvent(any())
    }

    @Test
    fun `missing trackingNumber returns ignored`() {
        val payload = """{"messageId":"msg-cj-tracking-no-tracking","type":"LOGISTIC","messageType":"UPDATE","openId":1234567890,"params":{"orderId":"d4e5f6a7-b8c9-0123-defa-234567890123","logisticName":"UPS","trackingNumber":null,"trackingStatus":0,"logisticsTrackEvents":"[]"}}"""

        mockMvc.perform(
            post("/webhooks/cj/tracking")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("ignored"))

        verify(eventPublisher, never()).publishEvent(any())
        verify(webhookEventPersister, never()).tryPersist(any())
        verify(webhookEventRepository, never()).existsByEventId(any())
    }

    @Test
    fun `missing orderId returns ignored`() {
        val payload = """{"messageId":"msg-cj-tracking-no-order","type":"LOGISTIC","messageType":"UPDATE","openId":1234567890,"params":{"orderId":null,"logisticName":"FedEx","trackingNumber":"794644790138","trackingStatus":1,"logisticsTrackEvents":"[]"}}"""

        mockMvc.perform(
            post("/webhooks/cj/tracking")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("ignored"))

        verify(eventPublisher, never()).publishEvent(any())
        verify(webhookEventPersister, never()).tryPersist(any())
        verify(webhookEventRepository, never()).existsByEventId(any())
    }

    @Test
    fun `missing params node returns ignored`() {
        val payload = """{"messageId":"msg-cj-tracking-no-params","type":"LOGISTIC","messageType":"UPDATE","openId":1234567890}"""

        mockMvc.perform(
            post("/webhooks/cj/tracking")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("ignored"))

        verify(eventPublisher, never()).publishEvent(any())
        verify(webhookEventPersister, never()).tryPersist(any())
    }

    @Test
    fun `empty params node returns ignored`() {
        val payload = """{"messageId":"msg-cj-tracking-empty-params","type":"LOGISTIC","messageType":"UPDATE","openId":1234567890,"params":{}}"""

        mockMvc.perform(
            post("/webhooks/cj/tracking")
                .contentType(MediaType.APPLICATION_JSON)
                .content(payload)
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("ignored"))

        verify(eventPublisher, never()).publishEvent(any())
        verify(webhookEventPersister, never()).tryPersist(any())
    }

    @Test
    fun `dedup key format is cj colon orderId colon trackingNumber`() {
        whenever(webhookEventRepository.existsByEventId(any())).thenReturn(false)
        whenever(webhookEventPersister.tryPersist(any())).thenReturn(true)

        mockMvc.perform(
            post("/webhooks/cj/tracking")
                .contentType(MediaType.APPLICATION_JSON)
                .content(validPayload)
        )
            .andExpect(status().isOk)

        verify(webhookEventRepository).existsByEventId("cj:a1b2c3d4-e5f6-7890-abcd-ef1234567890:1Z999AA10123456784")
        verify(webhookEventPersister).tryPersist(argThat<WebhookEvent> {
            eventId == "cj:a1b2c3d4-e5f6-7890-abcd-ef1234567890:1Z999AA10123456784"
        })
    }
}
