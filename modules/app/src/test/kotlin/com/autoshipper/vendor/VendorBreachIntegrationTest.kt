package com.autoshipper.vendor

import com.autoshipper.catalog.domain.Sku
import com.autoshipper.catalog.domain.SkuState
import com.autoshipper.catalog.persistence.SkuRepository
import com.autoshipper.shared.events.VendorSlaBreached
import com.autoshipper.shared.identity.SkuId
import com.autoshipper.shared.identity.VendorId
import com.autoshipper.shared.money.Percentage
import com.autoshipper.vendor.domain.Vendor
import com.autoshipper.vendor.domain.VendorActivationChecklist
import com.autoshipper.vendor.domain.VendorBreachLog
import com.autoshipper.vendor.domain.VendorSkuAssignment
import com.autoshipper.vendor.domain.VendorStatus
import com.autoshipper.vendor.persistence.VendorBreachLogRepository
import com.autoshipper.vendor.persistence.VendorRepository
import com.autoshipper.vendor.persistence.VendorSkuAssignmentRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationEventPublisher
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal

/**
 * Integration tests for vendor breach event handling.
 *
 * NOT @Transactional — VendorBreachListener uses @TransactionalEventListener(phase = AFTER_COMMIT),
 * which only fires after the transaction commits. A @Transactional test never commits,
 * so the listener would never fire and assertions on SKU state changes would be false positives.
 *
 * Uses the running PostgreSQL instance configured in application-test.yml.
 * Migrated from Testcontainers and fixed @Transactional false-positive bug (RAT-18, PM-006 pattern).
 */
@SpringBootTest
@ActiveProfiles("test")
class VendorBreachIntegrationTest {

    @Autowired lateinit var vendorRepository: VendorRepository
    @Autowired lateinit var skuRepository: SkuRepository
    @Autowired lateinit var assignmentRepository: VendorSkuAssignmentRepository
    @Autowired lateinit var breachLogRepository: VendorBreachLogRepository
    @Autowired lateinit var eventPublisher: ApplicationEventPublisher
    @Autowired lateinit var jdbcTemplate: JdbcTemplate
    @Autowired lateinit var transactionTemplate: TransactionTemplate

    @AfterEach
    fun cleanup() {
        jdbcTemplate.execute("TRUNCATE TABLE vendor_breach_log, vendor_sku_assignments, vendors, sku_state_history, skus CASCADE")
    }

    private fun createListedSku(name: String = "Test Product"): Sku {
        val sku = skuRepository.save(Sku(name = name, category = "Electronics"))
        sku.applyTransition(SkuState.ValidationPending)
        sku.applyTransition(SkuState.CostGating)
        sku.applyTransition(SkuState.StressTesting)
        sku.applyTransition(SkuState.Listed)
        return skuRepository.save(sku)
    }

    @Test
    fun `VendorSlaBreached event auto-pauses linked SKUs in Listed state`() {
        // Create a vendor
        val vendor = vendorRepository.save(
            Vendor(
                name = "Breach Vendor",
                contactEmail = "breach@test.com",
                status = VendorStatus.ACTIVE.name,
                checklist = VendorActivationChecklist(
                    slaConfirmed = true,
                    defectRateDocumented = true,
                    scalabilityConfirmed = true,
                    fulfillmentTimesConfirmed = true,
                    refundPolicyConfirmed = true
                )
            )
        )

        // Create a SKU in Listed state
        val sku = createListedSku()

        // Link vendor to SKU
        assignmentRepository.save(
            VendorSkuAssignment(vendorId = vendor.id, skuId = sku.id)
        )

        // Publish inside a transaction so the AFTER_COMMIT listener fires after commit
        transactionTemplate.execute {
            eventPublisher.publishEvent(
                VendorSlaBreached(
                    vendorId = VendorId(vendor.id),
                    skuIds = listOf(SkuId(sku.id)),
                    breachRate = Percentage.of(15.0)
                )
            )
        }

        // Verify SKU was auto-paused
        val updatedSku = skuRepository.findById(sku.id).orElseThrow()
        assertEquals("PAUSED", updatedSku.currentStateDiscriminator)
    }

    @Test
    fun `VendorSlaBreached event auto-pauses multiple linked SKUs`() {
        val vendor = vendorRepository.save(
            Vendor(
                name = "Multi-SKU Vendor",
                contactEmail = "multi@test.com",
                status = VendorStatus.ACTIVE.name,
                checklist = VendorActivationChecklist(
                    slaConfirmed = true,
                    defectRateDocumented = true,
                    scalabilityConfirmed = true,
                    fulfillmentTimesConfirmed = true,
                    refundPolicyConfirmed = true
                )
            )
        )

        // Create two SKUs in Listed state
        val sku1 = createListedSku("Product A")
        val sku2 = createListedSku("Product B")

        // Link both SKUs to vendor
        assignmentRepository.save(VendorSkuAssignment(vendorId = vendor.id, skuId = sku1.id))
        assignmentRepository.save(VendorSkuAssignment(vendorId = vendor.id, skuId = sku2.id))

        // Publish inside a transaction so the AFTER_COMMIT listener fires after commit
        transactionTemplate.execute {
            eventPublisher.publishEvent(
                VendorSlaBreached(
                    vendorId = VendorId(vendor.id),
                    skuIds = listOf(SkuId(sku1.id), SkuId(sku2.id)),
                    breachRate = Percentage.of(20.0)
                )
            )
        }

        // Both SKUs should be paused
        val updated1 = skuRepository.findById(sku1.id).orElseThrow()
        val updated2 = skuRepository.findById(sku2.id).orElseThrow()
        assertEquals("PAUSED", updated1.currentStateDiscriminator)
        assertEquals("PAUSED", updated2.currentStateDiscriminator)
    }

    @Test
    fun `breach log persists correctly`() {
        val vendor = vendorRepository.save(
            Vendor(name = "Log Vendor", contactEmail = "log@test.com")
        )

        breachLogRepository.save(
            VendorBreachLog(
                vendorId = vendor.id,
                breachRate = BigDecimal("15.00"),
                threshold = BigDecimal("10.00")
            )
        )

        val logs = breachLogRepository.findByVendorId(vendor.id)
        assertEquals(1, logs.size)
        assertEquals(BigDecimal("15.00"), logs[0].breachRate)
    }

    @Test
    fun `vendor sku assignment persists and queries correctly`() {
        val vendor = vendorRepository.save(
            Vendor(name = "Assign Vendor", contactEmail = "assign@test.com")
        )
        val sku = skuRepository.save(Sku(name = "Assign Product", category = "Test"))

        assignmentRepository.save(VendorSkuAssignment(vendorId = vendor.id, skuId = sku.id))

        val byVendor = assignmentRepository.findByVendorIdAndActiveTrue(vendor.id)
        assertEquals(1, byVendor.size)

        val bySku = assignmentRepository.findBySkuIdAndActiveTrue(sku.id)
        assertEquals(1, bySku.size)
    }
}
