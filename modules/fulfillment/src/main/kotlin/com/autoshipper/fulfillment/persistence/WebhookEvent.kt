package com.autoshipper.fulfillment.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.PostLoad
import jakarta.persistence.PostPersist
import jakarta.persistence.Table
import jakarta.persistence.Transient
import org.springframework.data.domain.Persistable
import java.time.Instant

@Entity
@Table(name = "webhook_events")
class WebhookEvent(
    @Id
    @Column(name = "event_id", nullable = false, updatable = false, length = 255)
    val eventId: String,

    @Column(name = "topic", nullable = false, length = 100)
    val topic: String,

    @Column(name = "channel", nullable = false, length = 50)
    val channel: String = "shopify",

    @Column(name = "processed_at", nullable = false, updatable = false)
    val processedAt: Instant = Instant.now()
) : Persistable<String> {

    @Transient
    private var isNew: Boolean = true

    override fun getId(): String = eventId

    override fun isNew(): Boolean = isNew

    @PostPersist
    @PostLoad
    fun markNotNew() {
        isNew = false
    }
}
