package com.autoshipper.fulfillment.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
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
)
