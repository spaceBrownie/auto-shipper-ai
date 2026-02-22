package com.autoshipper.shared.events

import java.time.Instant

sealed interface DomainEvent {
    val occurredAt: Instant
}
