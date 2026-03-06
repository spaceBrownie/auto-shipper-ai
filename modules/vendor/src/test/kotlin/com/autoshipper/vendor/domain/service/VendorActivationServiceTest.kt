package com.autoshipper.vendor.domain.service

import com.autoshipper.shared.identity.VendorId
import com.autoshipper.vendor.domain.Vendor
import com.autoshipper.vendor.domain.VendorActivationChecklist
import com.autoshipper.vendor.domain.VendorNotActivatedException
import com.autoshipper.vendor.domain.VendorStatus
import com.autoshipper.vendor.persistence.VendorFulfillmentRecordRepository
import com.autoshipper.vendor.persistence.VendorRepository
import com.autoshipper.vendor.persistence.VendorSkuAssignmentRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*
import java.util.*

@ExtendWith(MockitoExtension::class)
class VendorActivationServiceTest {

    @Mock
    lateinit var vendorRepository: VendorRepository

    @Mock
    lateinit var assignmentRepository: VendorSkuAssignmentRepository

    @Mock
    lateinit var fulfillmentRecordRepository: VendorFulfillmentRecordRepository

    @InjectMocks
    lateinit var service: VendorActivationService

    @Test
    fun `register creates vendor in PENDING status`() {
        whenever(vendorRepository.save(any<Vendor>())).thenAnswer { it.arguments[0] }

        val vendor = service.register("Test Vendor", "test@vendor.com")

        assertEquals("Test Vendor", vendor.name)
        assertEquals(VendorStatus.PENDING, vendor.currentStatus())
        verify(vendorRepository).save(any())
    }

    @Test
    fun `activate succeeds when all checklist items are true`() {
        val vendor = Vendor(
            name = "Test Vendor",
            contactEmail = "test@vendor.com",
            checklist = VendorActivationChecklist(
                slaConfirmed = true,
                defectRateDocumented = true,
                scalabilityConfirmed = true,
                fulfillmentTimesConfirmed = true,
                refundPolicyConfirmed = true
            )
        )
        whenever(vendorRepository.findById(vendor.id)).thenReturn(Optional.of(vendor))
        whenever(vendorRepository.save(any<Vendor>())).thenAnswer { it.arguments[0] }

        val result = service.activate(VendorId(vendor.id))

        assertEquals(VendorStatus.ACTIVE, result.currentStatus())
    }

    @Test
    fun `activate fails when checklist is incomplete`() {
        val vendor = Vendor(
            name = "Test Vendor",
            contactEmail = "test@vendor.com",
            checklist = VendorActivationChecklist(slaConfirmed = true)
        )
        whenever(vendorRepository.findById(vendor.id)).thenReturn(Optional.of(vendor))

        assertThrows<VendorNotActivatedException> {
            service.activate(VendorId(vendor.id))
        }
    }

    @Test
    fun `activate throws when vendor not found`() {
        val id = UUID.randomUUID()
        whenever(vendorRepository.findById(id)).thenReturn(Optional.empty())

        assertThrows<NoSuchElementException> {
            service.activate(VendorId(id))
        }
    }
}
