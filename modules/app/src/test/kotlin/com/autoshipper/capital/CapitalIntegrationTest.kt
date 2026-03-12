package com.autoshipper.capital

import com.autoshipper.capital.config.CapitalConfig
import com.autoshipper.capital.domain.CapitalOrderRecord
import com.autoshipper.capital.domain.CapitalRuleAudit
import com.autoshipper.capital.domain.MarginSnapshot
import com.autoshipper.capital.domain.ReserveAccount
import com.autoshipper.capital.domain.service.ReserveCalcJob
import com.autoshipper.capital.domain.service.ShutdownRuleEngine
import com.autoshipper.capital.persistence.CapitalOrderRecordRepository
import com.autoshipper.capital.persistence.CapitalRuleAuditRepository
import com.autoshipper.capital.persistence.MarginSnapshotRepository
import com.autoshipper.capital.persistence.ReserveAccountRepository
import com.autoshipper.catalog.domain.Sku
import com.autoshipper.catalog.domain.SkuState
import com.autoshipper.catalog.persistence.SkuRepository
import com.autoshipper.shared.events.ShutdownRuleTriggered
import com.autoshipper.shared.identity.SkuId
import com.autoshipper.shared.money.Currency
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * Integration tests for capital module shutdown rules and reserve reconciliation.
 *
 * NOT @Transactional — ShutdownRuleListener uses @TransactionalEventListener(phase = AFTER_COMMIT),
 * which only fires after the transaction commits. A @Transactional test never commits,
 * so the listener would never fire and assertions on SKU state changes would silently pass
 * only when tests are skipped.
 *
 * Uses the running Postgres container (application-test.yml) instead of Testcontainers
 * due to Docker API 1.53 incompatibility (PM-004).
 */
@SpringBootTest
@ActiveProfiles("test")
class CapitalIntegrationTest {

    @Autowired lateinit var shutdownRuleEngine: ShutdownRuleEngine
    @Autowired lateinit var reserveCalcJob: ReserveCalcJob
    @Autowired lateinit var capitalConfig: CapitalConfig
    @Autowired lateinit var snapshotRepository: MarginSnapshotRepository
    @Autowired lateinit var orderRecordRepository: CapitalOrderRecordRepository
    @Autowired lateinit var auditRepository: CapitalRuleAuditRepository
    @Autowired lateinit var reserveAccountRepository: ReserveAccountRepository
    @Autowired lateinit var skuRepository: SkuRepository
    @Autowired lateinit var eventPublisher: ApplicationEventPublisher
    @Autowired lateinit var jdbcTemplate: JdbcTemplate
    @Autowired lateinit var transactionTemplate: TransactionTemplate

    @AfterEach
    fun cleanup() {
        jdbcTemplate.execute("TRUNCATE TABLE capital_rule_audit, margin_snapshots, capital_order_records, reserve_accounts, sku_state_history, skus CASCADE")
    }

    /**
     * Walk a SKU through the state machine to reach LISTED.
     */
    private fun createListedSku(name: String = "Test SKU"): Sku {
        val sku = skuRepository.save(Sku(name = name, category = "Electronics"))
        sku.applyTransition(SkuState.ValidationPending)
        sku.applyTransition(SkuState.CostGating)
        sku.applyTransition(SkuState.StressTesting)
        sku.applyTransition(SkuState.Listed)
        return skuRepository.save(sku)
    }

    /**
     * Walk a SKU through the state machine to reach SCALED.
     */
    private fun createScaledSku(name: String = "Scaled SKU"): Sku {
        val sku = createListedSku(name)
        sku.applyTransition(SkuState.Scaled)
        return skuRepository.save(sku)
    }

    /**
     * Insert margin snapshots for a SKU, one per day going backward from today.
     */
    private fun insertMarginSnapshots(
        skuId: java.util.UUID,
        count: Int,
        netMargin: BigDecimal,
        grossMargin: BigDecimal = BigDecimal("50.00"),
        startDate: LocalDate = LocalDate.now()
    ): List<MarginSnapshot> {
        return (0 until count).map { dayOffset ->
            snapshotRepository.save(
                MarginSnapshot(
                    skuId = skuId,
                    snapshotDate = startDate.minusDays(dayOffset.toLong()),
                    grossMargin = grossMargin,
                    netMargin = netMargin,
                    revenueAmount = BigDecimal("1000.0000"),
                    revenueCurrency = Currency.USD,
                    totalCostAmount = BigDecimal("500.0000"),
                    totalCostCurrency = Currency.USD,
                    refundRate = BigDecimal.ZERO,
                    chargebackRate = BigDecimal.ZERO,
                    cacVariance = BigDecimal.ZERO
                )
            )
        }
    }

    /**
     * Insert order records for a SKU with configurable refund/chargeback flags.
     */
    private fun insertOrderRecords(
        skuId: java.util.UUID,
        total: Int,
        refundedCount: Int = 0,
        chargebackCount: Int = 0,
        amount: BigDecimal = BigDecimal("100.0000")
    ): List<CapitalOrderRecord> {
        return (0 until total).map { i ->
            orderRecordRepository.save(
                CapitalOrderRecord(
                    orderId = java.util.UUID.randomUUID(),
                    skuId = skuId,
                    totalAmount = amount,
                    currency = Currency.USD,
                    status = "DELIVERED",
                    refunded = i < refundedCount,
                    chargebacked = i < chargebackCount
                )
            )
        }
    }

    // ─── Test 1: Margin degradation triggers auto-pause ─────────────────

    @Test
    fun `margin below 30 percent for 7 consecutive days triggers auto-pause`() {
        val sku = createListedSku("Margin Breach SKU")

        // Insert 7 snapshots with net margin below the 30% floor
        val snapshots = insertMarginSnapshots(
            skuId = sku.id,
            count = 7,
            netMargin = BigDecimal("25.00")
        )

        // Evaluate shutdown rules with recent snapshots
        shutdownRuleEngine.evaluate(SkuId(sku.id), snapshots)

        // Verify SKU was auto-paused via the ShutdownRuleTriggered event chain
        val updatedSku = skuRepository.findById(sku.id).orElseThrow()
        assertEquals("PAUSED", updatedSku.currentStateDiscriminator,
            "SKU should be auto-paused when net margin is below 30% for 7+ days")

        // Verify audit record exists
        val audits = auditRepository.findAll().filter { it.skuId == sku.id }
        assertTrue(audits.isNotEmpty(), "A CapitalRuleAudit record should exist")
        assertEquals("MARGIN_BREACH", audits.first().rule)
        assertEquals("PAUSE", audits.first().action)
    }

    // ─── Test 2: Refund rate breach triggers pause ──────────────────────

    @Test
    fun `refund rate above 5 percent triggers auto-pause`() {
        val sku = createListedSku("Refund Breach SKU")

        // Insert 100 orders, 6 refunded => 6% refund rate (> 5% threshold)
        insertOrderRecords(skuId = sku.id, total = 100, refundedCount = 6)

        // Evaluate with empty snapshots (refund rule doesn't use snapshots)
        shutdownRuleEngine.evaluate(SkuId(sku.id), emptyList())

        // Verify SKU was auto-paused
        val updatedSku = skuRepository.findById(sku.id).orElseThrow()
        assertEquals("PAUSED", updatedSku.currentStateDiscriminator,
            "SKU should be auto-paused when refund rate exceeds 5%")

        // Verify audit record
        val audits = auditRepository.findAll().filter { it.skuId == sku.id }
        assertTrue(audits.any { it.rule == "REFUND_RATE_BREACH" },
            "Audit should record REFUND_RATE_BREACH")
        assertEquals("PAUSE", audits.first { it.rule == "REFUND_RATE_BREACH" }.action)
    }

    // ─── Test 3: Chargeback rate breach triggers pause + compliance ─────

    @Test
    fun `chargeback rate above 2 percent triggers auto-pause with compliance action`() {
        val sku = createListedSku("Chargeback Breach SKU")

        // Insert 100 orders, 3 chargebacked => 3% rate (> 2% threshold)
        insertOrderRecords(skuId = sku.id, total = 100, chargebackCount = 3)

        // Evaluate with empty snapshots (chargeback rule doesn't use snapshots)
        shutdownRuleEngine.evaluate(SkuId(sku.id), emptyList())

        // Verify SKU was auto-paused
        val updatedSku = skuRepository.findById(sku.id).orElseThrow()
        assertEquals("PAUSED", updatedSku.currentStateDiscriminator,
            "SKU should be auto-paused when chargeback rate exceeds 2%")

        // Verify audit record with PAUSE_COMPLIANCE action
        val audits = auditRepository.findAll().filter { it.skuId == sku.id }
        assertTrue(audits.any { it.rule == "CHARGEBACK_RATE_BREACH" },
            "Audit should record CHARGEBACK_RATE_BREACH")
        assertEquals("PAUSE_COMPLIANCE",
            audits.first { it.rule == "CHARGEBACK_RATE_BREACH" }.action,
            "Chargeback breach should use PAUSE_COMPLIANCE action")
    }

    // ─── Test 4: Reserve reconciliation corrects balance drift ──────────

    @Test
    fun `reserve reconciliation corrects balance drift`() {
        // Create reserve account with an intentionally wrong balance
        val account = reserveAccountRepository.save(
            ReserveAccount(
                balanceAmount = BigDecimal("999.9999"),
                balanceCurrency = Currency.USD,
                targetRateMin = capitalConfig.reserveRateMinPercent,
                targetRateMax = capitalConfig.reserveRateMaxPercent
            )
        )

        // Insert order records (non-refunded) totaling 5 * $200 = $1000
        val skuId = java.util.UUID.randomUUID()
        (0 until 5).forEach { _ ->
            orderRecordRepository.save(
                CapitalOrderRecord(
                    orderId = java.util.UUID.randomUUID(),
                    skuId = skuId,
                    totalAmount = BigDecimal("200.0000"),
                    currency = Currency.USD,
                    status = "DELIVERED",
                    refunded = false,
                    chargebacked = false
                )
            )
        }

        // Expected balance: $1000 * (reserveRateMinPercent / 100)
        // Default reserve rate min is 10%, so expected = $100
        val reserveRate = capitalConfig.reserveRateMinPercent
            .divide(BigDecimal(100), 4, java.math.RoundingMode.HALF_UP)
        val expectedBalance = BigDecimal("1000.0000").multiply(reserveRate)
            .setScale(4, java.math.RoundingMode.HALF_UP)

        // Run reconciliation
        reserveCalcJob.reconcile(Instant.now())

        // Verify balance was corrected
        val updated = reserveAccountRepository.findById(account.id).orElseThrow()
        assertEquals(0, expectedBalance.compareTo(updated.balanceAmount),
            "Reserve balance should be corrected from 999.9999 to $expectedBalance")
        assertNotEquals(0, BigDecimal("999.9999").compareTo(updated.balanceAmount),
            "Balance should no longer be the incorrect initial value")
    }

    // ─── Test 5: ShutdownRuleListener only pauses LISTED/SCALED SKUs ───

    @Test
    fun `ShutdownRuleTriggered event does not pause SKU in IDEATION state`() {
        val sku = skuRepository.save(Sku(name = "Ideation SKU", category = "Test"))
        assertEquals("IDEATION", sku.currentStateDiscriminator)

        // Publish inside a transaction so the AFTER_COMMIT listener fires
        transactionTemplate.execute {
            eventPublisher.publishEvent(
                ShutdownRuleTriggered(
                    skuId = SkuId(sku.id),
                    rule = "MARGIN_BREACH",
                    conditionValue = "25.00%",
                    action = "PAUSE"
                )
            )
        }

        // Verify SKU state unchanged — still IDEATION
        val updatedSku = skuRepository.findById(sku.id).orElseThrow()
        assertEquals("IDEATION", updatedSku.currentStateDiscriminator,
            "SKU in IDEATION should not be paused by shutdown rule")
    }

    @Test
    fun `ShutdownRuleTriggered event pauses SKU in SCALED state`() {
        val sku = createScaledSku("Scaled Shutdown SKU")
        assertEquals("SCALED", sku.currentStateDiscriminator)

        // Publish inside a transaction so the AFTER_COMMIT listener fires
        transactionTemplate.execute {
            eventPublisher.publishEvent(
                ShutdownRuleTriggered(
                    skuId = SkuId(sku.id),
                    rule = "REFUND_RATE_BREACH",
                    conditionValue = "6.00%",
                    action = "PAUSE"
                )
            )
        }

        val updatedSku = skuRepository.findById(sku.id).orElseThrow()
        assertEquals("PAUSED", updatedSku.currentStateDiscriminator,
            "SKU in SCALED state should be paused by shutdown rule")
    }
}
