package com.autoshipper.capital.domain.service

import com.autoshipper.capital.config.CapitalConfig
import com.autoshipper.capital.domain.CapitalRuleAudit
import com.autoshipper.capital.domain.KillRule
import com.autoshipper.capital.domain.MarginSnapshot
import com.autoshipper.capital.persistence.CapitalOrderRecordRepository
import com.autoshipper.capital.persistence.CapitalRuleAuditRepository
import com.autoshipper.shared.events.PricingSignal
import com.autoshipper.shared.events.ShutdownRuleTriggered
import com.autoshipper.shared.identity.SkuId
import com.autoshipper.shared.money.Currency
import com.autoshipper.shared.money.Money
import com.autoshipper.shared.money.Percentage
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant

@Service
class ShutdownRuleEngine(
    private val capitalConfig: CapitalConfig,
    private val auditRepository: CapitalRuleAuditRepository,
    private val orderRecordRepository: CapitalOrderRecordRepository,
    private val eventPublisher: ApplicationEventPublisher
) {
    private val logger = LoggerFactory.getLogger(ShutdownRuleEngine::class.java)

    fun buildRules(): List<KillRule> = listOf(
        KillRule.MarginBreach(
            sustainedDays = capitalConfig.sustainedDays,
            floorPercent = Percentage.of(capitalConfig.netMarginFloorPercent)
        ),
        KillRule.RefundRateBreach(
            windowDays = 30,
            maxRate = Percentage.of(capitalConfig.refundRateMaxPercent)
        ),
        KillRule.ChargebackRateBreach(
            windowDays = 30,
            maxRate = Percentage.of(capitalConfig.chargebackRateMaxPercent)
        ),
        KillRule.CacInstability(
            windowDays = capitalConfig.cacVarianceDays,
            maxVariance = Percentage.of(capitalConfig.cacVarianceMaxPercent)
        )
    )

    @Transactional
    fun evaluate(skuId: SkuId, snapshots: List<MarginSnapshot>) {
        for (rule in buildRules()) {
            when (rule) {
                is KillRule.MarginBreach -> evaluateMarginBreach(skuId, snapshots, rule)
                is KillRule.RefundRateBreach -> evaluateRefundRateBreach(skuId, rule)
                is KillRule.ChargebackRateBreach -> evaluateChargebackRateBreach(skuId, rule)
                is KillRule.CacInstability -> evaluateCacInstability(skuId, snapshots, rule)
            }
        }
    }

    private fun evaluateMarginBreach(
        skuId: SkuId,
        snapshots: List<MarginSnapshot>,
        rule: KillRule.MarginBreach
    ) {
        val recentSnapshots = snapshots
            .sortedByDescending { it.snapshotDate }
            .take(rule.sustainedDays)

        if (recentSnapshots.size < rule.sustainedDays) return

        val allBelowFloor = recentSnapshots.all { it.netMargin < rule.floorPercent.value }

        if (allBelowFloor) {
            val latestMargin = recentSnapshots.first().netMargin
            fireRule(skuId, rule.name, "${latestMargin}%", "PAUSE")
        }
    }

    private fun evaluateRefundRateBreach(skuId: SkuId, rule: KillRule.RefundRateBreach) {
        val since = Instant.now().minus(Duration.ofDays(rule.windowDays.toLong()))
        val totalOrders = orderRecordRepository.countBySkuIdAndRecordedAtAfter(skuId.value, since)
        if (totalOrders == 0L) return

        val refundedOrders = orderRecordRepository.countBySkuIdAndRefundedTrueAndRecordedAtAfter(
            skuId.value, since
        )
        val refundRate = BigDecimal(refundedOrders)
            .multiply(BigDecimal(100))
            .divide(BigDecimal(totalOrders), 2, RoundingMode.HALF_UP)

        if (refundRate > rule.maxRate.value) {
            fireRule(skuId, rule.name, "${refundRate}%", "PAUSE")
        }
    }

    private fun evaluateChargebackRateBreach(skuId: SkuId, rule: KillRule.ChargebackRateBreach) {
        val since = Instant.now().minus(Duration.ofDays(rule.windowDays.toLong()))
        val totalOrders = orderRecordRepository.countBySkuIdAndRecordedAtAfter(skuId.value, since)
        if (totalOrders == 0L) return

        val chargebackOrders = orderRecordRepository.countBySkuIdAndChargebackedTrueAndRecordedAtAfter(
            skuId.value, since
        )
        val chargebackRate = BigDecimal(chargebackOrders)
            .multiply(BigDecimal(100))
            .divide(BigDecimal(totalOrders), 2, RoundingMode.HALF_UP)

        if (chargebackRate > rule.maxRate.value) {
            fireRule(skuId, rule.name, "${chargebackRate}%", "PAUSE_COMPLIANCE")
        }
    }

    private fun evaluateCacInstability(
        skuId: SkuId,
        snapshots: List<MarginSnapshot>,
        rule: KillRule.CacInstability
    ) {
        val recentSnapshots = snapshots
            .sortedByDescending { it.snapshotDate }
            .take(rule.windowDays)

        if (recentSnapshots.isEmpty()) return

        val latestVariance = recentSnapshots.first().cacVariance

        if (latestVariance > rule.maxVariance.value) {
            logger.warn(
                "CAC instability detected for SKU {}: variance {}% > max {}%",
                skuId, latestVariance, rule.maxVariance.value
            )
            auditRepository.save(
                CapitalRuleAudit(
                    skuId = skuId.value,
                    rule = rule.name,
                    conditionValue = "${latestVariance}%",
                    action = "PRICING_RERUN"
                )
            )
            eventPublisher.publishEvent(
                PricingSignal.CacChanged(
                    skuId = skuId,
                    delta = Money.of(BigDecimal.ZERO, Currency.USD)
                )
            )
        }
    }

    private fun fireRule(skuId: SkuId, ruleName: String, conditionValue: String, action: String) {
        logger.warn(
            "Kill rule triggered for SKU {}: {} (value: {}, action: {})",
            skuId, ruleName, conditionValue, action
        )

        auditRepository.save(
            CapitalRuleAudit(
                skuId = skuId.value,
                rule = ruleName,
                conditionValue = conditionValue,
                action = action
            )
        )

        eventPublisher.publishEvent(
            ShutdownRuleTriggered(
                skuId = skuId,
                rule = ruleName,
                conditionValue = conditionValue,
                action = action
            )
        )
    }
}
