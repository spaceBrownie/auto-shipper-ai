package com.autoshipper.shared.identity

import java.util.UUID

@JvmInline
value class PlatformListingId(val value: UUID) {
    companion object {
        fun new(): PlatformListingId = PlatformListingId(UUID.randomUUID())
        fun of(value: String): PlatformListingId = PlatformListingId(UUID.fromString(value))
    }

    override fun toString(): String = value.toString()
}
