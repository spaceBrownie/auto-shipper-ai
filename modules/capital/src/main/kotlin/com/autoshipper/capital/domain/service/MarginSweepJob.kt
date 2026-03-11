package com.autoshipper.capital.domain.service

import com.autoshipper.capital.domain.MarginSnapshot
import com.autoshipper.capital.persistence.CapitalOrderRecordRepository
import com.autoshipper.capital.persistence.MarginSnapshotRepository
import com.autoshipper.shared.events.MarginSnapshotTaken
import com.autoshipper.shared.identity.SkuId
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

@Service
class MarginSweepJob(
    private val snapshotRepository: MarginSnapshotRepository,
    private val orderRecordRepository: CapitalOrderRecordRepository,
    private val shutdownRuleEngine: ShutdownRuleEngine,
    private val eventPublisher: ApplicationEventPublisher,
    private val skuProvider: ActiveSkuProvider
) {
    private val logger = LoggerFactory.getLogger(MarginSweepJob::class.java)

    @Scheduled(fixedRate = 21_600_000) // 6 hours
    @Transactional
    fun sweep() {
        sweep(LocalDate.now())
    }

    @Transactional
    fun sweep(today: LocalDate) {
        val activeSkuIds = skuProvider.getActiveSkuIds()
        logger.info("Margin sweep started for {} active SKUs", activeSkuIds.size)

        for (skuId in activeSkuIds) {
            try {
                sweepSku(skuId, today)
            } catch (e: Exception) {
                logger.error("Failed to sweep SKU {}", skuId, e)
            }
        }

        logger.info("Margin sweep complete")
    }

    private fun sweepSku(skuId: UUID, today: LocalDate) {
        val since = Instant.now().minus(Duration.ofDays(30))
        val orders = orderRecordRepository.findBySkuIdAndRecordedAtBetween(
            skuId, since, Instant.now()
        )

        if (orders.isEmpty()) {
            logger.debug("No orders for SKU {} in last 30 days, skipping snapshot", skuId)
            return
        }

        val totalRevenue = orders.fold(BigDecimal.ZERO) { acc, o -> acc.add(o.totalAmount) }
        val currency = orders.first().currency
        val totalOrders = orders.size.toLong()
        val refundedCount = orders.count { it.refunded }
        val chargebackCount = orders.count { it.chargebacked }

        val refundRate = if (totalOrders > 0) {
            BigDecimal(refundedCount).multiply(BigDecimal(100))
                .divide(BigDecimal(totalOrders), 2, RoundingMode.HALF_UP)
        } else BigDecimal.ZERO

        val chargebackRate = if (totalOrders > 0) {
            BigDecimal(chargebackCount).multiply(BigDecimal(100))
                .divide(BigDecimal(totalOrders), 2, RoundingMode.HALF_UP)
        } else BigDecimal.ZERO

        // For cost data, use a simple estimate: cost = revenue * 0.50
        // In production this would come from cost envelope data
        val totalCost = totalRevenue.multiply(BigDecimal("0.50"))

        val grossMargin = if (totalRevenue > BigDecimal.ZERO) {
            totalRevenue.subtract(totalCost)
                .multiply(BigDecimal(100))
                .divide(totalRevenue, 2, RoundingMode.HALF_UP)
        } else BigDecimal.ZERO

        val netMargin = grossMargin.subtract(refundRate).subtract(chargebackRate)

        val cacVariance = computeCacVariance(skuId, today)

        val snapshot = MarginSnapshot(
            skuId = skuId,
            snapshotDate = today,
            grossMargin = grossMargin,
            netMargin = netMargin,
            revenueAmount = totalRevenue,
            revenueCurrency = currency,
            totalCostAmount = totalCost,
            totalCostCurrency = currency,
            refundRate = refundRate,
            chargebackRate = chargebackRate,
            cacVariance = cacVariance
        )

        snapshotRepository.save(snapshot)

        eventPublisher.publishEvent(
            MarginSnapshotTaken(
                skuId = SkuId(skuId),
                netMarginPercent = netMargin,
                grossMarginPercent = grossMargin
            )
        )

        // Evaluate shutdown rules against recent snapshots
        val recentSnapshots = snapshotRepository.findRecentBySkuId(
            skuId, today.minusDays(90)
        )
        shutdownRuleEngine.evaluate(SkuId(skuId), recentSnapshots)
    }

    private fun computeCacVariance(skuId: UUID, today: LocalDate): BigDecimal {
        val recent = snapshotRepository.findRecentBySkuId(skuId, today.minusDays(14))
        if (recent.size < 2) return BigDecimal.ZERO

        val margins = recent.map { it.netMargin }
        val mean = margins.fold(BigDecimal.ZERO) { acc, m -> acc.add(m) }
            .divide(BigDecimal(margins.size), 4, RoundingMode.HALF_UP)

        val variance = margins.fold(BigDecimal.ZERO) { acc, m ->
            val diff = m.subtract(mean)
            acc.add(diff.multiply(diff))
        }.divide(BigDecimal(margins.size), 4, RoundingMode.HALF_UP)

        val stdDev = BigDecimal(Math.sqrt(variance.toDouble()))
            .setScale(2, RoundingMode.HALF_UP)
        return stdDev
    }
}
