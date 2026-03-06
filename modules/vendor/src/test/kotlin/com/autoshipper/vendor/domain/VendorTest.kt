package com.autoshipper.vendor.domain

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class VendorTest {

    @Test
    fun `new vendor starts in PENDING status`() {
        val vendor = Vendor(name = "Test Vendor", contactEmail = "test@vendor.com")
        assertEquals(VendorStatus.PENDING, vendor.currentStatus())
    }

    @Test
    fun `activate succeeds when checklist is complete`() {
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

        vendor.activate()

        assertEquals(VendorStatus.ACTIVE, vendor.currentStatus())
    }

    @Test
    fun `activate throws when checklist is incomplete`() {
        val vendor = Vendor(
            name = "Test Vendor",
            contactEmail = "test@vendor.com",
            checklist = VendorActivationChecklist(slaConfirmed = true)
        )

        val ex = assertThrows<VendorNotActivatedException> {
            vendor.activate()
        }
        assertTrue(ex.message!!.contains("defectRateDocumented"))
        assertTrue(ex.message!!.contains("scalabilityConfirmed"))
    }

    @Test
    fun `suspend sets status to SUSPENDED`() {
        val vendor = Vendor(
            name = "Test Vendor",
            contactEmail = "test@vendor.com",
            status = VendorStatus.ACTIVE.name
        )

        vendor.suspend()

        assertEquals(VendorStatus.SUSPENDED, vendor.currentStatus())
        assertNotNull(vendor.deactivatedAt)
    }
}
