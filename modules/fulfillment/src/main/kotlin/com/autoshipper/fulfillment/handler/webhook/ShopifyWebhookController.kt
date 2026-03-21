package com.autoshipper.fulfillment.handler.webhook

import com.autoshipper.fulfillment.config.ShopifyWebhookProperties
import com.autoshipper.fulfillment.persistence.WebhookEvent
import com.autoshipper.fulfillment.persistence.WebhookEventRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.*
import java.time.Instant

@RestController
@RequestMapping("/webhooks/shopify")
class ShopifyWebhookController(
    private val webhookEventRepository: WebhookEventRepository,
    private val eventPublisher: ApplicationEventPublisher,
    private val properties: ShopifyWebhookProperties,
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(ShopifyWebhookController::class.java)

    @PostMapping("/orders")
    @Transactional
    fun receiveOrder(
        @RequestBody body: String,
        @RequestHeader headers: HttpHeaders
    ): ResponseEntity<Map<String, String>> {
        val topic = headers.getFirst("X-Shopify-Topic")
        if (topic == null || !topic.equals("orders/create", ignoreCase = true)) {
            logger.warn("Unexpected Shopify webhook topic: {}", topic)
            return ResponseEntity.badRequest().body(mapOf("error" to "Unexpected topic"))
        }

        val eventId = headers.getFirst("X-Shopify-Event-Id")
        if (eventId == null) {
            logger.warn("Missing X-Shopify-Event-Id header")
            return ResponseEntity.badRequest().body(mapOf("error" to "Missing event ID"))
        }

        if (webhookEventRepository.existsByEventId(eventId)) {
            logger.info("Duplicate Shopify webhook event: {}", eventId)
            return ResponseEntity.ok(mapOf("status" to "already_processed"))
        }

        if (properties.replayProtection.enabled) {
            val triggeredAt = headers.getFirst("X-Shopify-Triggered-At")
            if (triggeredAt != null) {
                val eventTime = try {
                    Instant.parse(triggeredAt)
                } catch (e: java.time.format.DateTimeParseException) {
                    logger.warn("Malformed X-Shopify-Triggered-At header '{}', skipping replay protection", triggeredAt)
                    null
                }
                if (eventTime != null) {
                    val maxAge = Instant.now().minusSeconds(properties.replayProtection.maxAgeSeconds)
                    if (eventTime.isBefore(maxAge)) {
                        logger.warn("Shopify webhook event {} is too old: {}", eventId, triggeredAt)
                        return ResponseEntity.badRequest().body(mapOf("error" to "Event too old"))
                    }
                }
            }
        }

        webhookEventRepository.save(
            WebhookEvent(
                eventId = eventId,
                topic = topic,
                channel = "shopify"
            )
        )

        eventPublisher.publishEvent(
            ShopifyOrderReceivedEvent(
                rawPayload = body,
                shopifyEventId = eventId
            )
        )

        logger.info("Accepted Shopify webhook event: {}", eventId)
        return ResponseEntity.ok(mapOf("status" to "accepted"))
    }
}
