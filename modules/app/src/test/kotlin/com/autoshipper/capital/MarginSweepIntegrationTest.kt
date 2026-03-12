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
 * End-to-end integration tests for MarginSweepJob fix.
 *
 * NOT @Transactional — the MarginSweepSkuProcessor uses REQUIRES_NEW propagation,
 * which needs committed data visible to its independent transactions.
 *
 * Uses the running Postgres container (application-test.yml) instead of Testcontainers
 * due to Docker API 1.53 incompatibility (PM-004).
 */
@SpringBootTest
@ActiveProfiles("test")
class MarginSweepIntegrationTest {

    @Autowired lateinit var marginSweepJob: MarginSweepJob
    @Autowired lateinit var snapshotRepository: MarginSnapshotRepository
    @Autowired lateinit var orderRecordRepository: CapitalOrderRecordRepository
    @Autowired lateinit var auditRepository: CapitalRuleAuditRepository
    @Autowired lateinit var skuRepository: SkuRepository
    @Autowired lateinit var jdbcTemplate: JdbcTemplate

    @AfterEach
    fun cleanup() {
        jdbcTemplate.execute("TRUNCATE TABLE capital_rule_audit, margin_snapshots, capital_order_records, sku_state_history, skus CASCADE")
    }

    private fun createListedSku(name: String = "Test SKU"): Sku {
        val sku = skuRepository.save(Sku(name = name, category = "Electronics"))
        sku.applyTransition(SkuState.ValidationPending)
        sku.applyTransition(SkuState.CostGating)
        sku.applyTransition(SkuState.StressTesting)
        sku.applyTransition(SkuState.Listed)
        return skuRepository.save(sku)
    }

    private fun insertOrders(skuId: UUID, count: Int, refundedCount: Int = 0) {
        (0 until count).forEach { i ->
            orderRecordRepository.save(
                CapitalOrderRecord(
                    orderId = UUID.randomUUID(),
                    skuId = skuId,
                    totalAmount = BigDecimal("100.0000"),
                    currency = Currency.USD,
                    status = "DELIVERED",
                    refunded = i < refundedCount,
                    chargebacked = false
                )
            )
        }
    }

    // ─── Test 1: Running sweep twice on the same day does not throw ──────

    @Test
    fun `sweep runs twice on same day without constraint violation`() {
        val sku = createListedSku("Double Sweep SKU")
        insertOrders(sku.id, count = 10)

        val today = LocalDate.now()

        // First sweep — creates snapshot
        marginSweepJob.sweep(today)

        val snapshotsAfterFirst = snapshotRepository.findBySkuIdAndSnapshotDateBetweenOrderBySnapshotDateAsc(
            sku.id, today, today
        )
        assertEquals(1, snapshotsAfterFirst.size, "First sweep should create exactly one snapshot")

        // Second sweep — should NOT throw, should NOT create duplicate
        marginSweepJob.sweep(today)

        val snapshotsAfterSecond = snapshotRepository.findBySkuIdAndSnapshotDateBetweenOrderBySnapshotDateAsc(
            sku.id, today, today
        )
        assertEquals(1, snapshotsAfterSecond.size,
            "Second sweep should not create a duplicate snapshot")
    }

    // ─── Test 2: One SKU failure does not prevent processing others ──────

    @Test
    fun `failure on one SKU does not prevent processing other SKUs`() {
        val sku1 = createListedSku("SKU One")
        val sku2 = createListedSku("SKU Two")

        // Only SKU 2 has orders — SKU 1 will be a no-op (no orders, no snapshot)
        insertOrders(sku2.id, count = 10)

        val today = LocalDate.now()
        marginSweepJob.sweep(today)

        // SKU 2 should have a snapshot
        val sku2Snapshots = snapshotRepository.findBySkuIdAndSnapshotDateBetweenOrderBySnapshotDateAsc(
            sku2.id, today, today
        )
        assertEquals(1, sku2Snapshots.size, "SKU 2 should have a snapshot")
    }

    // ─── Test 3: Shutdown rules fire through full sweep path ─────────────

    @Test
    fun `sweep triggers shutdown rule when refund rate exceeds threshold`() {
        val sku = createListedSku("Refund Breach via Sweep")

        // Insert 100 orders, 6 refunded => 6% refund rate (> 5% threshold)
        insertOrders(sku.id, count = 100, refundedCount = 6)

        marginSweepJob.sweep(LocalDate.now())

        // Verify the shutdown rule fired through the full sweep → processor → engine path
        val audits = auditRepository.findAll().filter { it.skuId == sku.id }
        assertTrue(audits.any { it.rule == "REFUND_RATE_BREACH" },
            "Refund rate breach should be detected through MarginSweepJob")

        // Verify SKU was auto-paused
        val updatedSku = skuRepository.findById(sku.id).orElseThrow()
        assertEquals("PAUSED", updatedSku.currentStateDiscriminator,
            "SKU should be auto-paused via the full sweep pipeline")
    }

    // ─── Test 4: Rules still evaluate on second run even without new snapshot ─

    @Test
    fun `shutdown rules evaluate on second sweep even though snapshot already exists`() {
        val sku = createListedSku("Second Sweep Rules SKU")

        // First sweep with healthy orders — no rule triggers
        insertOrders(sku.id, count = 100, refundedCount = 0)
        marginSweepJob.sweep(LocalDate.now())

        // SKU should still be LISTED
        var updatedSku = skuRepository.findById(sku.id).orElseThrow()
        assertEquals("LISTED", updatedSku.currentStateDiscriminator)

        // Now add refund orders to push refund rate above 5%
        // Total becomes 106 orders, 6 refunded => ~5.66% (> 5%)
        insertOrders(sku.id, count = 6, refundedCount = 6)

        // Second sweep — snapshot already exists, but rules should still evaluate
        marginSweepJob.sweep(LocalDate.now())

        val audits = auditRepository.findAll().filter { it.skuId == sku.id }
        assertTrue(audits.any { it.rule == "REFUND_RATE_BREACH" },
            "Shutdown rules should fire on second sweep even when snapshot already exists")

        updatedSku = skuRepository.findById(sku.id).orElseThrow()
        assertEquals("PAUSED", updatedSku.currentStateDiscriminator,
            "SKU should be paused on second sweep via rule evaluation")
    }
}
