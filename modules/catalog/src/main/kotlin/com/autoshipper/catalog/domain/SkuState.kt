package com.autoshipper.catalog.domain

sealed class SkuState {
    object Ideation : SkuState()
    object ValidationPending : SkuState()
    object CostGating : SkuState()
    object StressTesting : SkuState()
    object Listed : SkuState()
    object Scaled : SkuState()
    object Paused : SkuState()
    data class Terminated(val reason: TerminationReason) : SkuState()

    fun toDiscriminator(): String = when (this) {
        is Ideation -> "IDEATION"
        is ValidationPending -> "VALIDATION_PENDING"
        is CostGating -> "COST_GATING"
        is StressTesting -> "STRESS_TESTING"
        is Listed -> "LISTED"
        is Scaled -> "SCALED"
        is Paused -> "PAUSED"
        is Terminated -> "TERMINATED"
    }

    companion object {
        fun fromDiscriminator(value: String, terminationReason: TerminationReason? = null): SkuState = when (value) {
            "IDEATION" -> Ideation
            "VALIDATION_PENDING" -> ValidationPending
            "COST_GATING" -> CostGating
            "STRESS_TESTING" -> StressTesting
            "LISTED" -> Listed
            "SCALED" -> Scaled
            "PAUSED" -> Paused
            "TERMINATED" -> Terminated(terminationReason ?: TerminationReason.MANUAL_OVERRIDE)
            else -> throw IllegalArgumentException("Unknown SKU state: $value")
        }
    }
}
