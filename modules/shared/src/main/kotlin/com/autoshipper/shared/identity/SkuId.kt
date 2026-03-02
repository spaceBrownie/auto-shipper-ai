package com.autoshipper.shared.identity

import java.util.UUID

@JvmInline
value class SkuId(val value: UUID) {
    companion object {
        fun new(): SkuId = SkuId(UUID.randomUUID())
        fun of(value: String): SkuId = SkuId(UUID.fromString(value))
    }

    override fun toString(): String = value.toString()
}
