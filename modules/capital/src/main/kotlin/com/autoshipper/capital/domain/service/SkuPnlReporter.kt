package com.autoshipper.capital.domain.service

import com.autoshipper.capital.persistence.MarginSnapshotRepository
import com.autoshipper.shared.identity.SkuId
import com.autoshipper.shared.money.Currency
import com.autoshipper.shared.money.Money
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDate

@Service
class SkuPnlReporter(
    private val snapshotRepository: MarginSnapshotRepository
) {

    @Transactional(readOnly = true)
    fun report(skuId: SkuId, from: LocalDate, to: LocalDate): SkuPnlReport {
        val snapshots = snapshotRepository.findBySkuIdAndSnapshotDateBetweenOrderBySnapshotDateAsc(
            skuId.value, from, to
        )

        if (snapshots.isEmpty()) {
            return SkuPnlReport(
                skuId = skuId,
                from = from,
                to = to,
                totalRevenue = Money.of(BigDecimal.ZERO, Currency.USD),
                totalCost = Money.of(BigDecimal.ZERO, Currency.USD),
                averageGrossMarginPercent = BigDecimal.ZERO,
                averageNetMarginPercent = BigDecimal.ZERO,
                snapshotCount = 0
            )
        }

        val currency = snapshots.first().revenueCurrency
        val totalRevenue = snapshots.fold(BigDecimal.ZERO) { acc, s -> acc.add(s.revenueAmount) }
        val totalCost = snapshots.fold(BigDecimal.ZERO) { acc, s -> acc.add(s.totalCostAmount) }

        val avgGrossMargin = snapshots.map { it.grossMargin }
            .fold(BigDecimal.ZERO) { acc, m -> acc.add(m) }
            .divide(BigDecimal(snapshots.size), 2, RoundingMode.HALF_UP)

        val avgNetMargin = snapshots.map { it.netMargin }
            .fold(BigDecimal.ZERO) { acc, m -> acc.add(m) }
            .divide(BigDecimal(snapshots.size), 2, RoundingMode.HALF_UP)

        return SkuPnlReport(
            skuId = skuId,
            from = from,
            to = to,
            totalRevenue = Money.of(totalRevenue, currency),
            totalCost = Money.of(totalCost, currency),
            averageGrossMarginPercent = avgGrossMargin,
            averageNetMarginPercent = avgNetMargin,
            snapshotCount = snapshots.size
        )
    }
}

data class SkuPnlReport(
    val skuId: SkuId,
    val from: LocalDate,
    val to: LocalDate,
    val totalRevenue: Money,
    val totalCost: Money,
    val averageGrossMarginPercent: BigDecimal,
    val averageNetMarginPercent: BigDecimal,
    val snapshotCount: Int
)
