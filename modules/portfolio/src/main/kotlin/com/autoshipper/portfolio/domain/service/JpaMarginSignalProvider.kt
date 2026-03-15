package com.autoshipper.portfolio.domain.service

import jakarta.persistence.EntityManager
import jakarta.persistence.PersistenceContext
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.util.UUID

@Service
class JpaMarginSignalProvider(
    @PersistenceContext private val entityManager: EntityManager
) : MarginSignalProvider {

    @Suppress("UNCHECKED_CAST")
    override fun getSkusWithNegativeMarginSince(days: Int): List<UUID> {
        // Find SKUs where the last N snapshots all have net_margin < 0
        // A SKU qualifies if it has at least [days] consecutive negative-margin snapshots
        // ending at the most recent snapshot date
        val query = entityManager.createNativeQuery(
            """
            SELECT DISTINCT ms.sku_id
            FROM margin_snapshots ms
            WHERE ms.sku_id IN (
                SELECT sub.sku_id
                FROM margin_snapshots sub
                WHERE sub.net_margin < 0
                GROUP BY sub.sku_id
                HAVING COUNT(*) >= :days
            )
            AND NOT EXISTS (
                SELECT 1 FROM margin_snapshots pos
                WHERE pos.sku_id = ms.sku_id
                  AND pos.net_margin >= 0
                  AND pos.snapshot_date > (
                      SELECT MAX(neg.snapshot_date) - CAST(:days || ' days' AS INTERVAL)
                      FROM margin_snapshots neg
                      WHERE neg.sku_id = ms.sku_id
                  )
            )
            """
        )
        query.setParameter("days", days)
        return query.resultList as List<UUID>
    }

    override fun getAverageNetMargin(skuId: UUID): BigDecimal {
        val result = entityManager.createNativeQuery(
            "SELECT COALESCE(AVG(net_margin), 0) FROM margin_snapshots WHERE sku_id = :skuId"
        ).setParameter("skuId", skuId)
            .singleResult
        return toBigDecimal(result)
    }

    override fun getConsecutiveHighMarginDays(skuId: UUID, threshold: BigDecimal): Int {
        @Suppress("UNCHECKED_CAST")
        val margins = entityManager.createNativeQuery(
            """
            SELECT net_margin FROM margin_snapshots
            WHERE sku_id = :skuId
            ORDER BY snapshot_date DESC
            """
        ).setParameter("skuId", skuId)
            .resultList as List<Any>

        var count = 0
        for (m in margins) {
            val margin = toBigDecimal(m)
            if (margin >= threshold) {
                count++
            } else {
                break
            }
        }
        return count
    }

    override fun getAverageRevenueVolume(skuId: UUID): BigDecimal {
        val result = entityManager.createNativeQuery(
            "SELECT COALESCE(AVG(revenue_amount), 0) FROM margin_snapshots WHERE sku_id = :skuId"
        ).setParameter("skuId", skuId)
            .singleResult
        return toBigDecimal(result)
    }

    override fun getAverageRefundRate(skuId: UUID): BigDecimal {
        val result = entityManager.createNativeQuery(
            "SELECT COALESCE(AVG(refund_rate), 0) FROM margin_snapshots WHERE sku_id = :skuId"
        ).setParameter("skuId", skuId)
            .singleResult
        return toBigDecimal(result)
    }

    @Suppress("UNCHECKED_CAST")
    override fun getActiveSkuIds(): List<UUID> {
        return entityManager.createNativeQuery(
            "SELECT id FROM skus WHERE current_state IN ('LISTED', 'SCALED')"
        ).resultList as List<UUID>
    }

    override fun getPortfolioAverageRefundRate(): BigDecimal {
        val result = entityManager.createNativeQuery(
            """
            SELECT COALESCE(AVG(ms.refund_rate), 0)
            FROM margin_snapshots ms
            INNER JOIN skus s ON s.id = ms.sku_id
            WHERE s.current_state IN ('LISTED', 'SCALED')
            """
        ).singleResult
        return toBigDecimal(result)
    }

    override fun getTotalProfit(): BigDecimal {
        val result = entityManager.createNativeQuery(
            """
            SELECT COALESCE(SUM(ms.revenue_amount - ms.total_cost_amount), 0)
            FROM margin_snapshots ms
            INNER JOIN skus s ON s.id = ms.sku_id
            WHERE s.current_state IN ('LISTED', 'SCALED')
              AND ms.snapshot_date = (
                  SELECT MAX(ms2.snapshot_date) FROM margin_snapshots ms2 WHERE ms2.sku_id = ms.sku_id
              )
            """
        ).singleResult
        return toBigDecimal(result)
    }

    override fun getBlendedNetMargin(): BigDecimal {
        val result = entityManager.createNativeQuery(
            """
            SELECT COALESCE(AVG(ms.net_margin), 0)
            FROM margin_snapshots ms
            INNER JOIN skus s ON s.id = ms.sku_id
            WHERE s.current_state IN ('LISTED', 'SCALED')
              AND ms.snapshot_date = (
                  SELECT MAX(ms2.snapshot_date) FROM margin_snapshots ms2 WHERE ms2.sku_id = ms.sku_id
              )
            """
        ).singleResult
        return toBigDecimal(result)
    }

    private fun toBigDecimal(value: Any?): BigDecimal {
        return when (value) {
            is BigDecimal -> value
            is Number -> BigDecimal(value.toString())
            else -> BigDecimal.ZERO
        }
    }
}
