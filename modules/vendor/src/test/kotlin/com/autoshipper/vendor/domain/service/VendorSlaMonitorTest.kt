package com.autoshipper.vendor.domain.service

import com.autoshipper.shared.events.VendorSlaBreached
import com.autoshipper.vendor.domain.Vendor
import com.autoshipper.vendor.domain.VendorActivationChecklist
import com.autoshipper.vendor.domain.VendorSkuAssignment
import com.autoshipper.vendor.domain.VendorStatus
import com.autoshipper.vendor.persistence.VendorBreachLogRepository
import com.autoshipper.vendor.persistence.VendorRepository
import com.autoshipper.vendor.persistence.VendorSkuAssignmentRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*
import org.springframework.context.ApplicationEventPublisher
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class VendorSlaMonitorTest {

    @Mock
    lateinit var vendorRepository: VendorRepository

    @Mock
    lateinit var assignmentRepository: VendorSkuAssignmentRepository

    @Mock
    lateinit var breachLogRepository: VendorBreachLogRepository

    @Mock
    lateinit var fulfillmentDataProvider: VendorFulfillmentDataProvider

    @Mock
    lateinit var eventPublisher: ApplicationEventPublisher

    @InjectMocks
    lateinit var monitor: VendorSlaMonitor

    private fun activeVendor(): Vendor = Vendor(
        name = "Test Vendor",
        contactEmail = "test@vendor.com",
        status = VendorStatus.ACTIVE.name,
        checklist = VendorActivationChecklist(
            slaConfirmed = true,
            defectRateDocumented = true,
            scalabilityConfirmed = true,
            fulfillmentTimesConfirmed = true,
            refundPolicyConfirmed = true
        )
    )

    @Test
    fun `does not emit event when violation rate below threshold`() {
        val vendor = activeVendor()
        whenever(vendorRepository.findByStatus(VendorStatus.ACTIVE.name)).thenReturn(listOf(vendor))
        whenever(fulfillmentDataProvider.countFulfillmentsSince(eq(vendor.id), any<Instant>())).thenReturn(100L)
        whenever(fulfillmentDataProvider.countViolationsSince(eq(vendor.id), any<Instant>())).thenReturn(5L)

        monitor.runCheck(BigDecimal("10"))

        verify(eventPublisher, never()).publishEvent(any<VendorSlaBreached>())
    }

    @Test
    fun `does not emit event when no fulfillment records exist`() {
        val vendor = activeVendor()
        whenever(vendorRepository.findByStatus(VendorStatus.ACTIVE.name)).thenReturn(listOf(vendor))
        whenever(fulfillmentDataProvider.countFulfillmentsSince(eq(vendor.id), any<Instant>())).thenReturn(0L)

        monitor.runCheck(BigDecimal("10"))

        verify(eventPublisher, never()).publishEvent(any<VendorSlaBreached>())
    }

    @Test
    fun `emits VendorSlaBreached when violation rate meets threshold`() {
        val vendor = activeVendor()
        val skuId = UUID.randomUUID()
        val assignment = VendorSkuAssignment(vendorId = vendor.id, skuId = skuId)

        whenever(vendorRepository.findByStatus(VendorStatus.ACTIVE.name)).thenReturn(listOf(vendor))
        whenever(fulfillmentDataProvider.countFulfillmentsSince(eq(vendor.id), any<Instant>())).thenReturn(100L)
        whenever(fulfillmentDataProvider.countViolationsSince(eq(vendor.id), any<Instant>())).thenReturn(15L)
        whenever(assignmentRepository.findByVendorIdAndActiveTrue(vendor.id)).thenReturn(listOf(assignment))
        whenever(vendorRepository.save(any<Vendor>())).thenAnswer { it.arguments[0] }
        whenever(breachLogRepository.save(any())).thenAnswer { it.arguments[0] }

        monitor.runCheck(BigDecimal("10"))

        verify(eventPublisher).publishEvent(argThat<VendorSlaBreached> {
            this.vendorId.value == vendor.id && this.skuIds.size == 1
        })
    }

    @Test
    fun `suspends vendor when breach threshold exceeded`() {
        val vendor = activeVendor()

        whenever(vendorRepository.findByStatus(VendorStatus.ACTIVE.name)).thenReturn(listOf(vendor))
        whenever(fulfillmentDataProvider.countFulfillmentsSince(eq(vendor.id), any<Instant>())).thenReturn(50L)
        whenever(fulfillmentDataProvider.countViolationsSince(eq(vendor.id), any<Instant>())).thenReturn(10L)
        whenever(assignmentRepository.findByVendorIdAndActiveTrue(vendor.id)).thenReturn(emptyList())
        whenever(vendorRepository.save(any<Vendor>())).thenAnswer { it.arguments[0] }
        whenever(breachLogRepository.save(any())).thenAnswer { it.arguments[0] }

        monitor.runCheck(BigDecimal("10"))

        verify(vendorRepository).save(argThat<Vendor> {
            this.currentStatus() == VendorStatus.SUSPENDED
        })
    }

    @Test
    fun `does not emit event when no SKUs assigned to breached vendor`() {
        val vendor = activeVendor()

        whenever(vendorRepository.findByStatus(VendorStatus.ACTIVE.name)).thenReturn(listOf(vendor))
        whenever(fulfillmentDataProvider.countFulfillmentsSince(eq(vendor.id), any<Instant>())).thenReturn(20L)
        whenever(fulfillmentDataProvider.countViolationsSince(eq(vendor.id), any<Instant>())).thenReturn(10L)
        whenever(assignmentRepository.findByVendorIdAndActiveTrue(vendor.id)).thenReturn(emptyList())
        whenever(vendorRepository.save(any<Vendor>())).thenAnswer { it.arguments[0] }
        whenever(breachLogRepository.save(any())).thenAnswer { it.arguments[0] }

        monitor.runCheck(BigDecimal("10"))

        verify(eventPublisher, never()).publishEvent(any<VendorSlaBreached>())
    }

    @Test
    fun `breach rate calculated as violations over total fulfillments percentage`() {
        val vendor = activeVendor()

        whenever(vendorRepository.findByStatus(VendorStatus.ACTIVE.name)).thenReturn(listOf(vendor))
        // 9 violations out of 100 = 9% — below 10% threshold
        whenever(fulfillmentDataProvider.countFulfillmentsSince(eq(vendor.id), any<Instant>())).thenReturn(100L)
        whenever(fulfillmentDataProvider.countViolationsSince(eq(vendor.id), any<Instant>())).thenReturn(9L)

        monitor.runCheck(BigDecimal("10"))

        verify(eventPublisher, never()).publishEvent(any<VendorSlaBreached>())
        verify(vendorRepository, never()).save(any<Vendor>())
    }
}
