package com.autoshipper.vendor.handler.dto

import com.autoshipper.vendor.domain.Vendor
import java.time.Instant
import java.util.UUID

data class VendorResponse(
    val id: UUID,
    val name: String,
    val contactEmail: String,
    val status: String,
    val checklist: ChecklistResponse,
    val createdAt: Instant,
    val updatedAt: Instant
) {
    companion object {
        fun from(vendor: Vendor) = VendorResponse(
            id = vendor.id,
            name = vendor.name,
            contactEmail = vendor.contactEmail,
            status = vendor.status,
            checklist = ChecklistResponse(
                slaConfirmed = vendor.checklist.slaConfirmed,
                defectRateDocumented = vendor.checklist.defectRateDocumented,
                scalabilityConfirmed = vendor.checklist.scalabilityConfirmed,
                fulfillmentTimesConfirmed = vendor.checklist.fulfillmentTimesConfirmed,
                refundPolicyConfirmed = vendor.checklist.refundPolicyConfirmed
            ),
            createdAt = vendor.createdAt,
            updatedAt = vendor.updatedAt
        )
    }
}

data class ChecklistResponse(
    val slaConfirmed: Boolean,
    val defectRateDocumented: Boolean,
    val scalabilityConfirmed: Boolean,
    val fulfillmentTimesConfirmed: Boolean,
    val refundPolicyConfirmed: Boolean
)
