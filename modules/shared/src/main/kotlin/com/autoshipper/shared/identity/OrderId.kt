package com.autoshipper.shared.identity

import java.util.UUID

@JvmInline
value class OrderId(val value: UUID) {
    companion object {
        fun new(): OrderId = OrderId(UUID.randomUUID())
        fun of(value: String): OrderId = OrderId(UUID.fromString(value))
    }

    override fun toString(): String = value.toString()
}
