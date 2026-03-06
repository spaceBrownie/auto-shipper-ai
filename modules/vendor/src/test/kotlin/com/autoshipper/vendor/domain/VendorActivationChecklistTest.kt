package com.autoshipper.vendor.domain

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class VendorActivationChecklistTest {

    @Test
    fun `isComplete returns false when all items are false`() {
        val checklist = VendorActivationChecklist()
        assertFalse(checklist.isComplete())
    }

    @Test
    fun `isComplete returns false when one item is missing`() {
        val checklist = VendorActivationChecklist(
            slaConfirmed = true,
            defectRateDocumented = true,
            scalabilityConfirmed = true,
            fulfillmentTimesConfirmed = true,
            refundPolicyConfirmed = false
        )
        assertFalse(checklist.isComplete())
    }

    @Test
    fun `isComplete returns true when all items are true`() {
        val checklist = VendorActivationChecklist(
            slaConfirmed = true,
            defectRateDocumented = true,
            scalabilityConfirmed = true,
            fulfillmentTimesConfirmed = true,
            refundPolicyConfirmed = true
        )
        assertTrue(checklist.isComplete())
    }
}
