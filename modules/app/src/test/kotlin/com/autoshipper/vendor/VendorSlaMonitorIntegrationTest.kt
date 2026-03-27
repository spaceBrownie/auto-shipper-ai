package com.autoshipper.vendor

import com.autoshipper.catalog.domain.Sku
import com.autoshipper.catalog.domain.SkuState
import com.autoshipper.catalog.persistence.SkuRepository
import com.autoshipper.fulfillment.domain.Order
import com.autoshipper.fulfillment.domain.OrderStatus
import com.autoshipper.fulfillment.domain.ShipmentDetails
import com.autoshipper.fulfillment.persistence.OrderRepository
import com.autoshipper.shared.money.Currency
import com.autoshipper.vendor.domain.Vendor
import com.autoshipper.vendor.domain.VendorActivationChecklist
import com.autoshipper.vendor.domain.VendorSkuAssignment
import com.autoshipper.vendor.domain.VendorStatus
import com.autoshipper.vendor.domain.service.VendorSlaMonitor
import com.autoshipper.vendor.persistence.VendorBreachLogRepository
import com.autoshipper.vendor.persistence.VendorRepository
import com.autoshipper.vendor.persistence.VendorSkuAssignmentRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import java.math.BigDecimal
import java.util.UUID

/**
 * Integration test for VendorSlaMonitor with real DB records.
 *
 * NOT @Transactional — VendorSlaMonitor is @Transactional itself and publishes
 * VendorSlaBreached via AFTER_COMMIT listener that auto-pauses linked SKUs.
 *
 * Verifies: VendorSlaMonitor detects breach from real order data,
 * suspends the vendor, logs the breach, and triggers SKU auto-pause.
 */
@SpringBootTest
@ActiveProfiles("test")
class VendorSlaMonitorIntegrationTest {

    @Autowired lateinit var vendorSlaMonitor: VendorSlaMonitor
    @Autowired lateinit var vendorRepository: VendorRepository
    @Autowired lateinit var skuRepository: SkuRepository
    @Autowired lateinit var assignmentRepository: VendorSkuAssignmentRepository
    @Autowired lateinit var breachLogRepository: VendorBreachLogRepository
    @Autowired lateinit var orderRepository: OrderRepository
    @Autowired lateinit var jdbcTemplate: JdbcTemplate

    @AfterEach
    fun cleanup() {
        jdbcTemplate.execute(
            "TRUNCATE TABLE vendor_breach_log, vendor_sku_assignments, orders, vendors, sku_state_history, skus CASCADE"
        )
    }

    private fun createActiveVendor(name: String = "SLA Test Vendor"): Vendor {
        return vendorRepository.save(
            Vendor(
                name = name,
                contactEmail = "$name@test.com".replace(" ", "").lowercase(),
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
    }

    private fun createListedSku(name: String = "SLA Test Product"): Sku {
        val sku = skuRepository.save(Sku(name = name, category = "Electronics"))
        sku.applyTransition(SkuState.ValidationPending)
        sku.applyTransition(SkuState.CostGating)
        sku.applyTransition(SkuState.StressTesting)
        sku.applyTransition(SkuState.Listed)
        return skuRepository.save(sku)
    }

    /**
     * Insert DELIVERED orders for a vendor. Some have delayDetected = true (violations).
     * FulfillmentDataProviderImpl.countFulfillmentsSince counts DELIVERED orders.
     * FulfillmentDataProviderImpl.countViolationsSince counts orders where
     * delayDetected = true OR status = REFUNDED.
     */
    private fun insertOrders(
        vendorId: UUID,
        skuId: UUID,
        totalDelivered: Int,
        delayedCount: Int
    ) {
        (0 until totalDelivered).forEach { i ->
            orderRepository.save(
                Order(
                    idempotencyKey = "sla-test-${UUID.randomUUID()}",
                    skuId = skuId,
                    vendorId = vendorId,
                    customerId = UUID.randomUUID(),
                    totalAmount = BigDecimal("50.0000"),
                    totalCurrency = Currency.USD,
                    quantity = 1,
                    paymentIntentId = "pi_sla_${UUID.randomUUID()}",
                    status = OrderStatus.DELIVERED,
                    shipmentDetails = ShipmentDetails(
                        trackingNumber = "TRK${i}",
                        carrier = "UPS",
                        delayDetected = i < delayedCount
                    )
                )
            )
        }
    }

    @Test
    fun `vendor with breach rate above threshold is suspended and linked SKU is paused`() {
        // Arrange: create active vendor with a LISTED SKU linked
        val vendor = createActiveVendor()
        val sku = createListedSku()

        assignmentRepository.save(
            VendorSkuAssignment(vendorId = vendor.id, skuId = sku.id)
        )

        // Insert 100 DELIVERED orders: 15 with delay detected (15% breach rate >= 10% threshold)
        insertOrders(vendor.id, sku.id, totalDelivered = 100, delayedCount = 15)

        // Act: run the SLA monitor check
        vendorSlaMonitor.runCheck()

        // Wait for AFTER_COMMIT listener (VendorBreachListener) to fire
        Thread.sleep(500)

        // Assert: vendor is now SUSPENDED
        val updatedVendor = vendorRepository.findById(vendor.id).orElseThrow()
        assertEquals(VendorStatus.SUSPENDED.name, updatedVendor.status,
            "Vendor should be SUSPENDED when breach rate >= threshold")
        assertNotNull(updatedVendor.deactivatedAt,
            "Vendor deactivatedAt should be set after suspension")

        // Assert: breach log exists with correct breach rate
        val logs = breachLogRepository.findByVendorId(vendor.id)
        assertEquals(1, logs.size, "Exactly one breach log should be created")
        assertEquals(0, BigDecimal("15.00").compareTo(logs.first().breachRate),
            "Breach rate should be 15%")
        assertEquals(0, BigDecimal("10.00").compareTo(logs.first().threshold),
            "Threshold should be the default 10%")

        // Assert: linked SKU is now PAUSED (via VendorSlaBreached event chain)
        val updatedSku = skuRepository.findById(sku.id).orElseThrow()
        assertEquals("PAUSED", updatedSku.currentStateDiscriminator,
            "Linked SKU should be auto-paused when vendor SLA is breached")
    }

    @Test
    fun `vendor below breach threshold is not suspended`() {
        // Arrange: create active vendor with orders that have a healthy breach rate
        val vendor = createActiveVendor("Healthy Vendor")
        val sku = createListedSku("Healthy Product")

        assignmentRepository.save(
            VendorSkuAssignment(vendorId = vendor.id, skuId = sku.id)
        )

        // Insert 100 DELIVERED orders: only 5 delayed (5% < 10% threshold)
        insertOrders(vendor.id, sku.id, totalDelivered = 100, delayedCount = 5)

        // Act
        vendorSlaMonitor.runCheck()

        // Assert: vendor should remain ACTIVE
        val updatedVendor = vendorRepository.findById(vendor.id).orElseThrow()
        assertEquals(VendorStatus.ACTIVE.name, updatedVendor.status,
            "Vendor should remain ACTIVE when breach rate is below threshold")

        // No breach logs
        val logs = breachLogRepository.findByVendorId(vendor.id)
        assertTrue(logs.isEmpty(), "No breach log should exist for healthy vendor")

        // SKU should remain LISTED
        val updatedSku = skuRepository.findById(sku.id).orElseThrow()
        assertEquals("LISTED", updatedSku.currentStateDiscriminator,
            "SKU should remain LISTED when vendor is healthy")
    }
}
