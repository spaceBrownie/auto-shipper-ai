package com.autoshipper.compliance

import com.autoshipper.compliance.domain.ComplianceCheckResult
import com.autoshipper.compliance.domain.ComplianceFailureReason
import com.autoshipper.compliance.domain.service.IpCheckService
import com.autoshipper.shared.identity.SkuId
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class IpCheckServiceTest {

    private val service = IpCheckService()
    private val skuId = SkuId.new()

    @Test
    fun `clear product name passes IP check`() {
        val result = service.check(skuId, "Premium Bamboo Water Bottle")
        assertTrue(result is ComplianceCheckResult.Cleared)
    }

    @Test
    fun `product name containing trademarked term fails IP check`() {
        val result = service.check(skuId, "Nike-Style Running Shoes")
        assertTrue(result is ComplianceCheckResult.Failed)
        val failed = result as ComplianceCheckResult.Failed
        assertEquals(ComplianceFailureReason.IP_INFRINGEMENT, failed.reason)
        assertTrue(failed.detail.contains("nike"))
    }

    @Test
    fun `case insensitive trademarked term detection`() {
        val result = service.check(skuId, "APPLE Compatible Charger")
        assertTrue(result is ComplianceCheckResult.Failed)
    }

    @Test
    fun `product name with multiple trademarked terms fails on first`() {
        val result = service.check(skuId, "Nike Adidas Hybrid Sneakers")
        assertTrue(result is ComplianceCheckResult.Failed)
        val failed = result as ComplianceCheckResult.Failed
        assertEquals(ComplianceFailureReason.IP_INFRINGEMENT, failed.reason)
    }

    @Test
    fun `empty product name passes`() {
        val result = service.check(skuId, "")
        assertTrue(result is ComplianceCheckResult.Cleared)
    }
}
