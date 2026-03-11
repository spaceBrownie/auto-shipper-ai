package com.autoshipper.capital.domain

import com.autoshipper.shared.money.Percentage

sealed class KillRule {
    abstract val name: String

    data class MarginBreach(
        val sustainedDays: Int,
        val floorPercent: Percentage
    ) : KillRule() {
        override val name: String = "MARGIN_BREACH"
    }

    data class RefundRateBreach(
        val windowDays: Int,
        val maxRate: Percentage
    ) : KillRule() {
        override val name: String = "REFUND_RATE_BREACH"
    }

    data class ChargebackRateBreach(
        val windowDays: Int,
        val maxRate: Percentage
    ) : KillRule() {
        override val name: String = "CHARGEBACK_RATE_BREACH"
    }

    data class CacInstability(
        val windowDays: Int,
        val maxVariance: Percentage
    ) : KillRule() {
        override val name: String = "CAC_INSTABILITY"
    }
}
