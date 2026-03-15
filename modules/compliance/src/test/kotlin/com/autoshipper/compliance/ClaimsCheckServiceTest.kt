package com.autoshipper.compliance

import com.autoshipper.compliance.domain.ComplianceCheckResult
import com.autoshipper.compliance.domain.ComplianceFailureReason
import com.autoshipper.compliance.domain.service.ClaimsCheckService
import com.autoshipper.shared.identity.SkuId
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ClaimsCheckServiceTest {

    private val service = ClaimsCheckService()
    private val skuId = SkuId.new()

    @Test
    fun `clean description passes claims check`() {
        val result = service.check(skuId, "A high-quality stainless steel water bottle with insulation.")
        assertTrue(result is ComplianceCheckResult.Cleared)
    }

    @Test
    fun `description with medical claim fails`() {
        val result = service.check(skuId, "This supplement cures headaches and fatigue.")
        assertTrue(result is ComplianceCheckResult.Failed)
        val failed = result as ComplianceCheckResult.Failed
        assertEquals(ComplianceFailureReason.MISLEADING_CLAIMS, failed.reason)
        assertTrue(failed.detail.contains("cures"))
    }

    @Test
    fun `description with FDA claim fails`() {
        val result = service.check(skuId, "Our product is FDA Approved for daily use.")
        assertTrue(result is ComplianceCheckResult.Failed)
    }

    @Test
    fun `description with miracle claim fails`() {
        val result = service.check(skuId, "A miracle solution for all your problems.")
        assertTrue(result is ComplianceCheckResult.Failed)
    }

    @Test
    fun `description with guaranteed results fails`() {
        val result = service.check(skuId, "Guaranteed results in 30 days or your money back.")
        assertTrue(result is ComplianceCheckResult.Failed)
    }

    @Test
    fun `empty description passes`() {
        val result = service.check(skuId, "")
        assertTrue(result is ComplianceCheckResult.Cleared)
    }
}
