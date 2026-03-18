package com.autoshipper.vendor

import com.autoshipper.catalog.domain.Sku
import com.autoshipper.catalog.domain.SkuState
import com.autoshipper.catalog.domain.SkuStateMachine
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
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.ApplicationEventPublisher
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal

@SpringBootTest
@ActiveProfiles("test")
@Testcontainers
@Transactional
class VendorBreachIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("autoshipper_test")
            .withUsername("autoshipper")
            .withPassword("autoshipper")

        @JvmStatic
        @DynamicPropertySource
        fun configureDatasource(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.flyway.url", postgres::getJdbcUrl)
            registry.add("spring.flyway.user", postgres::getUsername)
            registry.add("spring.flyway.password", postgres::getPassword)
        }
    }

    @Autowired
    lateinit var vendorRepository: VendorRepository

    @Autowired
    lateinit var skuRepository: SkuRepository

    @Autowired
    lateinit var assignmentRepository: VendorSkuAssignmentRepository

    @Autowired
    lateinit var breachLogRepository: VendorBreachLogRepository

    @Autowired
    lateinit var eventPublisher: ApplicationEventPublisher

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

        // Create a SKU in Listed state (need to walk through state machine)
        val sku = skuRepository.save(Sku(name = "Test Product", category = "Electronics"))
        // Walk through valid transitions to reach Listed
        sku.applyTransition(SkuState.ValidationPending)
        sku.applyTransition(SkuState.CostGating)
        sku.applyTransition(SkuState.StressTesting)
        sku.applyTransition(SkuState.Listed)
        skuRepository.save(sku)

        // Link vendor to SKU
        assignmentRepository.save(
            VendorSkuAssignment(vendorId = vendor.id, skuId = sku.id)
        )

        // Publish VendorSlaBreached event
        eventPublisher.publishEvent(
            VendorSlaBreached(
                vendorId = VendorId(vendor.id),
                skuIds = listOf(SkuId(sku.id)),
                breachRate = Percentage.of(15.0)
            )
        )

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
        val sku1 = skuRepository.save(Sku(name = "Product A", category = "Electronics"))
        sku1.applyTransition(SkuState.ValidationPending)
        sku1.applyTransition(SkuState.CostGating)
        sku1.applyTransition(SkuState.StressTesting)
        sku1.applyTransition(SkuState.Listed)
        skuRepository.save(sku1)

        val sku2 = skuRepository.save(Sku(name = "Product B", category = "Home"))
        sku2.applyTransition(SkuState.ValidationPending)
        sku2.applyTransition(SkuState.CostGating)
        sku2.applyTransition(SkuState.StressTesting)
        sku2.applyTransition(SkuState.Listed)
        skuRepository.save(sku2)

        // Link both SKUs to vendor
        assignmentRepository.save(VendorSkuAssignment(vendorId = vendor.id, skuId = sku1.id))
        assignmentRepository.save(VendorSkuAssignment(vendorId = vendor.id, skuId = sku2.id))

        // Publish breach event
        eventPublisher.publishEvent(
            VendorSlaBreached(
                vendorId = VendorId(vendor.id),
                skuIds = listOf(SkuId(sku1.id), SkuId(sku2.id)),
                breachRate = Percentage.of(20.0)
            )
        )

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
