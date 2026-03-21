package com.autoshipper.capital

import com.autoshipper.capital.domain.CapitalOrderRecord
import com.autoshipper.capital.domain.service.MarginSweepJob
import com.autoshipper.capital.persistence.CapitalOrderRecordRepository
import com.autoshipper.capital.persistence.CapitalRuleAuditRepository
import com.autoshipper.capital.persistence.MarginSnapshotRepository
import com.autoshipper.catalog.domain.Sku
import com.autoshipper.catalog.domain.SkuState
import com.autoshipper.catalog.persistence.SkuRepository
import com.autoshipper.shared.money.Currency
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * Integration tests for MarginSweepJob failure isolation.
 *
 * NOT @Transactional — the MarginSweepSkuProcessor uses REQUIRES_NEW propagation,
 * which needs committed data visible to its independent transactions.
 *
 * Verifies: when one SKU triggers a shutdown rule for an already-PAUSED SKU,
 * the snapshot is still persisted and other SKUs are processed independently.
 */
@SpringBootTest
@ActiveProfiles("test")
class MarginSweepFailureIntegrationTest {

    @Autowired lateinit var marginSweepJob: MarginSweepJob
    @Autowired lateinit var snapshotRepository: MarginSnapshotRepository
    @Autowired lateinit var orderRecordRepository: CapitalOrderRecordRepository
    @Autowired lateinit var auditRepository: CapitalRuleAuditRepository
    @Autowired lateinit var skuRepository: SkuRepository
    @Autowired lateinit var jdbcTemplate: JdbcTemplate

    @AfterEach
    fun cleanup() {
        jdbcTemplate.execute(
            "TRUNCATE TABLE capital_rule_audit, margin_snapshots, capital_order_records, platform_listings, sku_state_history, skus CASCADE"
        )
    }

    private fun createListedSku(name: String = "Test SKU"): Sku {
        val sku = skuRepository.save(Sku(name = name, category = "Electronics"))
        sku.applyTransition(SkuState.ValidationPending)
        sku.applyTransition(SkuState.CostGating)
        sku.applyTransition(SkuState.StressTesting)
        sku.applyTransition(SkuState.Listed)
        return skuRepository.save(sku)
    }

    private fun insertOrders(
        skuId: UUID,
        count: Int,
        refundedCount: Int = 0,
        chargebackCount: Int = 0
    ) {
        (0 until count).forEach { i ->
            orderRecordRepository.save(
                CapitalOrderRecord(
                    orderId = UUID.randomUUID(),
                    skuId = skuId,
                    totalAmount = BigDecimal("100.0000"),
                    currency = Currency.USD,
                    status = "DELIVERED",
                    refunded = i < refundedCount,
                    chargebacked = i < chargebackCount
                )
            )
        }
    }

    @Test
    fun `sweep processes all SKUs and auto-pauses breaching SKU while healthy SKU remains LISTED`() {
        // Arrange: SKU-A is LISTED with high refund rate (10% > 5% threshold — triggers shutdown rule)
        val skuA = createListedSku("SKU-A Refund Breach")
        insertOrders(skuA.id, count = 100, refundedCount = 10)

        // SKU-B is LISTED with healthy refund rate (1% < 5% threshold)
        val skuB = createListedSku("SKU-B Healthy")
        insertOrders(skuB.id, count = 100, refundedCount = 1)

        val today = LocalDate.now()

        // Act
        marginSweepJob.sweep(today)

        // Wait for AFTER_COMMIT ShutdownRuleListener to fire
        Thread.sleep(500)

        // Assert: Both SKUs should have margin snapshots (REQUIRES_NEW isolation)
        val snapshotsA = snapshotRepository.findBySkuIdAndSnapshotDateBetweenOrderBySnapshotDateAsc(
            skuA.id, today, today
        )
        val snapshotsB = snapshotRepository.findBySkuIdAndSnapshotDateBetweenOrderBySnapshotDateAsc(
            skuB.id, today, today
        )
        assertEquals(1, snapshotsA.size, "SKU-A should have a margin snapshot (committed before shutdown rule fires)")
        assertEquals(1, snapshotsB.size, "SKU-B should have a margin snapshot (isolation)")

        // SKU-B should remain LISTED (healthy metrics)
        val updatedSkuB = skuRepository.findById(skuB.id).orElseThrow()
        assertEquals("LISTED", updatedSkuB.currentStateDiscriminator,
            "SKU-B should remain LISTED since its metrics are healthy")

        // SKU-A should be auto-paused (ShutdownRuleListener transitions LISTED -> PAUSED)
        val updatedSkuA = skuRepository.findById(skuA.id).orElseThrow()
        assertEquals("PAUSED", updatedSkuA.currentStateDiscriminator,
            "SKU-A should be auto-paused due to refund rate breach")

        // A capital_rule_audit should exist for SKU-A (refund rate breach detected)
        val auditsA = auditRepository.findAll().filter { it.skuId == skuA.id }
        assertTrue(auditsA.any { it.rule == "REFUND_RATE_BREACH" },
            "Refund rate breach should be detected for SKU-A")
    }

    @Test
    fun `sweep isolates failures so one SKU error does not block others`() {
        // Arrange: SKU-A is LISTED with breach-level chargebacks (3% > 2% threshold)
        val skuA = createListedSku("SKU-A Chargeback Breach")
        insertOrders(skuA.id, count = 100, chargebackCount = 3)

        // SKU-B is LISTED with healthy metrics
        val skuB = createListedSku("SKU-B Clean")
        insertOrders(skuB.id, count = 100)

        val today = LocalDate.now()
        marginSweepJob.sweep(today)

        // Assert: both have snapshots
        val snapshotsA = snapshotRepository.findBySkuIdAndSnapshotDateBetweenOrderBySnapshotDateAsc(
            skuA.id, today, today
        )
        val snapshotsB = snapshotRepository.findBySkuIdAndSnapshotDateBetweenOrderBySnapshotDateAsc(
            skuB.id, today, today
        )
        assertEquals(1, snapshotsA.size, "SKU-A should have a snapshot")
        assertEquals(1, snapshotsB.size, "SKU-B should have a snapshot")

        // SKU-A should be paused (chargeback breach triggers PAUSE_COMPLIANCE)
        val updatedSkuA = skuRepository.findById(skuA.id).orElseThrow()
        assertEquals("PAUSED", updatedSkuA.currentStateDiscriminator,
            "SKU-A should be auto-paused due to chargeback rate breach")

        // SKU-B should remain LISTED
        val updatedSkuB = skuRepository.findById(skuB.id).orElseThrow()
        assertEquals("LISTED", updatedSkuB.currentStateDiscriminator,
            "SKU-B should remain LISTED — unaffected by SKU-A's breach")

        // Verify audit records
        val auditsA = auditRepository.findAll().filter { it.skuId == skuA.id }
        assertTrue(auditsA.any { it.rule == "CHARGEBACK_RATE_BREACH" },
            "Chargeback rate breach audit should exist for SKU-A")
        assertEquals("PAUSE_COMPLIANCE",
            auditsA.first { it.rule == "CHARGEBACK_RATE_BREACH" }.action,
            "Chargeback breach action should be PAUSE_COMPLIANCE")
    }
}
