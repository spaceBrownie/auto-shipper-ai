package com.autoshipper.fulfillment.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

@Repository
interface WebhookEventRepository : JpaRepository<WebhookEvent, String> {
    fun existsByEventId(eventId: String): Boolean

    @Modifying
    @Transactional
    fun deleteByProcessedAtBefore(cutoff: Instant): Long
}
