package com.autoshipper.fulfillment.persistence

import org.slf4j.LoggerFactory
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * Persists webhook deduplication records in an isolated REQUIRES_NEW transaction.
 *
 * Separated from the controller so that a PK constraint violation (concurrent
 * duplicate) is contained within its own transaction. Without isolation, the
 * DataIntegrityViolationException poisons the Hibernate session and PostgreSQL
 * marks the transaction as aborted — the controller's @Transactional proxy then
 * throws UnexpectedRollbackException (500) instead of returning 200.
 */
@Component
class WebhookEventPersister(
    private val webhookEventRepository: WebhookEventRepository
) {
    private val logger = LoggerFactory.getLogger(WebhookEventPersister::class.java)

    /**
     * Attempts to persist the webhook event record.
     * @return true if persisted (new event), false if duplicate (PK constraint violation)
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun tryPersist(event: WebhookEvent): Boolean {
        return try {
            webhookEventRepository.saveAndFlush(event)
            true
        } catch (e: DataIntegrityViolationException) {
            logger.info("Concurrent duplicate webhook event: {}", event.eventId)
            false
        }
    }
}
