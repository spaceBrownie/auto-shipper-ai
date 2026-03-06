package com.autoshipper.vendor.domain.service

import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

@Component
class StubFulfillmentDataProvider : VendorFulfillmentDataProvider {
    override fun countFulfillmentsSince(vendorId: UUID, since: Instant): Long = 0L
    override fun countViolationsSince(vendorId: UUID, since: Instant): Long = 0L
}
