package com.autoshipper.catalog.domain

object SkuStateMachine {
    private val TRANSITIONS: Map<String, Set<String>> = mapOf(
        "IDEATION" to setOf("VALIDATION_PENDING", "TERMINATED"),
        "VALIDATION_PENDING" to setOf("COST_GATING", "TERMINATED"),
        "COST_GATING" to setOf("STRESS_TESTING", "TERMINATED"),
        "STRESS_TESTING" to setOf("LISTED", "TERMINATED"),
        "LISTED" to setOf("SCALED", "PAUSED", "TERMINATED"),
        "SCALED" to setOf("PAUSED", "TERMINATED"),
        "PAUSED" to setOf("LISTED", "TERMINATED"),
        "TERMINATED" to emptySet()
    )

    fun validate(from: SkuState, to: SkuState) {
        val allowed = TRANSITIONS[from.toDiscriminator()] ?: emptySet()
        if (to.toDiscriminator() !in allowed) {
            throw InvalidSkuTransitionException(from, to)
        }
    }

    fun allowedTransitions(from: SkuState): Set<String> =
        TRANSITIONS[from.toDiscriminator()] ?: emptySet()
}
