package com.autoshipper.compliance

import com.autoshipper.compliance.domain.ComplianceCheckResult
import com.autoshipper.compliance.domain.ComplianceFailureReason
import com.autoshipper.compliance.domain.service.SourcingCheckService
import com.autoshipper.compliance.proxy.SanctionsListCache
import com.autoshipper.shared.identity.SkuId
import com.autoshipper.shared.identity.VendorId
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SourcingCheckServiceTest {

    private lateinit var sanctionsListCache: SanctionsListCache
    private lateinit var service: SourcingCheckService
    private val skuId = SkuId.new()

    @BeforeEach
    fun setUp() {
        sanctionsListCache = SanctionsListCache()
        service = SourcingCheckService(sanctionsListCache)
    }

    @Test
    fun `vendor not on sanctions list passes`() {
        val vendorId = VendorId.new()
        val result = service.check(skuId, vendorId)
        assertTrue(result is ComplianceCheckResult.Cleared)
    }

    @Test
    fun `vendor on sanctions list fails`() {
        val vendorId = VendorId.new()
        sanctionsListCache.addSanctionedVendor(vendorId.value.toString())

        val result = service.check(skuId, vendorId)
        assertTrue(result is ComplianceCheckResult.Failed)
        val failed = result as ComplianceCheckResult.Failed
        assertEquals(ComplianceFailureReason.GRAY_MARKET_SOURCE, failed.reason)
    }

    @Test
    fun `sanctions list clear and reload works`() {
        val vendorId = VendorId.new()
        sanctionsListCache.addSanctionedVendor(vendorId.value.toString())

        // Verify vendor is sanctioned
        assertTrue(service.check(skuId, vendorId) is ComplianceCheckResult.Failed)

        // Clear and reload without the vendor
        sanctionsListCache.clearAndLoad(emptyList())

        // Vendor should now pass
        assertTrue(service.check(skuId, vendorId) is ComplianceCheckResult.Cleared)
    }
}
