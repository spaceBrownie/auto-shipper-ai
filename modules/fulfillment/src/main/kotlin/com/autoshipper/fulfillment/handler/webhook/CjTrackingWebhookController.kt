package com.autoshipper.fulfillment.handler.webhook

import com.autoshipper.fulfillment.persistence.WebhookEvent
import com.autoshipper.fulfillment.persistence.WebhookEventPersister
import com.autoshipper.fulfillment.persistence.WebhookEventRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*

@RestController
@RequestMapping("/webhooks/cj")
class CjTrackingWebhookController(
    private val webhookEventRepository: WebhookEventRepository,
    private val webhookEventPersister: WebhookEventPersister,
    private val eventPublisher: ApplicationEventPublisher,
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(CjTrackingWebhookController::class.java)

    @PostMapping("/tracking")
    @Transactional
    fun receiveTracking(
        @RequestBody body: String
    ): ResponseEntity<Map<String, String>> {
        val root = objectMapper.readTree(body)
        val params = root.get("params")

        val orderId = params?.get("orderId")?.let { if (!it.isNull) it.asText() else null }
        val trackingNumber = params?.get("trackingNumber")?.let { if (!it.isNull) it.asText() else null }

        if (orderId == null || trackingNumber == null) {
            logger.info("CJ tracking webhook ignored — missing orderId or trackingNumber")
            return ResponseEntity.ok(mapOf("status" to "ignored"))
        }

        val dedupKey = "cj:${orderId}:${trackingNumber}"

        if (webhookEventRepository.existsByEventId(dedupKey)) {
            logger.info("Duplicate CJ tracking webhook event: {}", dedupKey)
            return ResponseEntity.ok(mapOf("status" to "already_processed"))
        }

        val persisted = webhookEventPersister.tryPersist(
            WebhookEvent(eventId = dedupKey, topic = "tracking/update", channel = "cj")
        )
        if (!persisted) {
            return ResponseEntity.ok(mapOf("status" to "already_processed"))
        }

        eventPublisher.publishEvent(
            CjTrackingReceivedEvent(
                rawPayload = body,
                dedupKey = dedupKey
            )
        )

        logger.info("Accepted CJ tracking webhook event: {}", dedupKey)
        return ResponseEntity.ok(mapOf("status" to "accepted"))
    }
}
