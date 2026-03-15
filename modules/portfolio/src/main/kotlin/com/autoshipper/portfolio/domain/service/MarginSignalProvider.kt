package com.autoshipper.portfolio.domain.service

import java.math.BigDecimal
import java.util.UUID

interface MarginSignalProvider {
    /**
     * Returns SKU IDs that have had net margin below zero for at least [days] consecutive days.
     */
    fun getSkusWithNegativeMarginSince(days: Int): List<UUID>

    /**
     * Returns the average net margin for a SKU across all its margin snapshots.
     */
    fun getAverageNetMargin(skuId: UUID): BigDecimal

    /**
     * Returns the count of consecutive recent margin snapshots where net margin >= [threshold]
     * for the given SKU, starting from the most recent snapshot.
     */
    fun getConsecutiveHighMarginDays(skuId: UUID, threshold: BigDecimal): Int

    /**
     * Returns average revenue volume for a SKU.
     */
    fun getAverageRevenueVolume(skuId: UUID): BigDecimal

    /**
     * Returns average refund rate for a SKU.
     */
    fun getAverageRefundRate(skuId: UUID): BigDecimal

    /**
     * Returns all active SKU IDs (LISTED or SCALED).
     */
    fun getActiveSkuIds(): List<UUID>

    /**
     * Returns the average refund rate across all active SKUs.
     */
    fun getPortfolioAverageRefundRate(): BigDecimal

    /**
     * Returns total profit (revenue - cost) across active SKUs from latest snapshots.
     */
    fun getTotalProfit(): BigDecimal

    /**
     * Returns blended net margin across all active SKUs.
     */
    fun getBlendedNetMargin(): BigDecimal
}
