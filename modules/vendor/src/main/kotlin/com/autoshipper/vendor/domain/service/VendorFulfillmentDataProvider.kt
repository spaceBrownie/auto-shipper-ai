package com.autoshipper.vendor.domain.service

import java.time.Instant
import java.util.UUID

interface VendorFulfillmentDataProvider {
    fun countFulfillmentsSince(vendorId: UUID, since: Instant): Long
    fun countViolationsSince(vendorId: UUID, since: Instant): Long
}
