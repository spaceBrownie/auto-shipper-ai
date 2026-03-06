package com.autoshipper.fulfillment.domain

import jakarta.persistence.*
import java.time.Instant
import java.util.UUID

@Entity
@Table(name = "customer_notifications")
class CustomerNotification(
    @Id
    @Column(name = "id", nullable = false, updatable = false)
    val id: UUID = UUID.randomUUID(),

    @Column(name = "order_id", nullable = false)
    val orderId: UUID,

    @Column(name = "notification_type", nullable = false, length = 50)
    val notificationType: String,

    @Column(name = "message", nullable = false, columnDefinition = "TEXT")
    val message: String,

    @Column(name = "sent_at", nullable = false, updatable = false)
    val sentAt: Instant = Instant.now()
)
