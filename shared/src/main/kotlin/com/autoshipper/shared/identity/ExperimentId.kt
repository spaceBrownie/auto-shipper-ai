package com.autoshipper.shared.identity

import java.util.UUID

@JvmInline
value class ExperimentId(val value: UUID) {
    companion object {
        fun new(): ExperimentId = ExperimentId(UUID.randomUUID())
        fun of(value: String): ExperimentId = ExperimentId(UUID.fromString(value))
    }

    override fun toString(): String = value.toString()
}
