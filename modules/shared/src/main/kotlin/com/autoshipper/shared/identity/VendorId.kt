package com.autoshipper.shared.identity

import java.util.UUID

@JvmInline
value class VendorId(val value: UUID) {
    companion object {
        fun new(): VendorId = VendorId(UUID.randomUUID())
        fun of(value: String): VendorId = VendorId(UUID.fromString(value))
    }

    override fun toString(): String = value.toString()
}
