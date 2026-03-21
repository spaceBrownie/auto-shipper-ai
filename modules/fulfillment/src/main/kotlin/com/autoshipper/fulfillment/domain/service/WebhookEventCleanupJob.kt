package com.autoshipper.fulfillment.domain.service

import com.autoshipper.fulfillment.persistence.WebhookEventRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.temporal.ChronoUnit

@Component
class WebhookEventCleanupJob(
    private val webhookEventRepository: WebhookEventRepository
) {
    private val logger = LoggerFactory.getLogger(WebhookEventCleanupJob::class.java)

    @Scheduled(cron = "0 0 3 * * *")
    fun purgeExpiredEvents() {
        val cutoff = Instant.now().minus(24, ChronoUnit.HOURS)
        val deleted = webhookEventRepository.deleteByProcessedAtBefore(cutoff)
        logger.info("Webhook event cleanup: purged {} events older than {}", deleted, cutoff)
    }
}
