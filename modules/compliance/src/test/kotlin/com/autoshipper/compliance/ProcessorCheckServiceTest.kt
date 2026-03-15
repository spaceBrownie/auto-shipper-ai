package com.autoshipper.compliance

import com.autoshipper.compliance.domain.ComplianceCheckResult
import com.autoshipper.compliance.domain.ComplianceFailureReason
import com.autoshipper.compliance.domain.service.ProcessorCheckService
import com.autoshipper.shared.identity.SkuId
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class ProcessorCheckServiceTest {

    private val service = ProcessorCheckService()
    private val skuId = SkuId.new()

    @Test
    fun `allowed category passes processor check`() {
        val result = service.check(skuId, "home_goods")
        assertTrue(result is ComplianceCheckResult.Cleared)
    }

    @Test
    fun `prohibited category fails processor check`() {
        val result = service.check(skuId, "gambling")
        assertTrue(result is ComplianceCheckResult.Failed)
        val failed = result as ComplianceCheckResult.Failed
        assertEquals(ComplianceFailureReason.PROCESSOR_PROHIBITED, failed.reason)
        assertTrue(failed.detail.contains("Stripe"))
    }

    @Test
    fun `category normalization with spaces`() {
        val result = service.check(skuId, "adult content")
        assertTrue(result is ComplianceCheckResult.Failed)
    }

    @Test
    fun `category normalization with hyphens`() {
        val result = service.check(skuId, "adult-content")
        assertTrue(result is ComplianceCheckResult.Failed)
    }

    @Test
    fun `case insensitive category matching`() {
        val result = service.check(skuId, "FIREARMS")
        assertTrue(result is ComplianceCheckResult.Failed)
    }

    @Test
    fun `electronics category passes`() {
        val result = service.check(skuId, "electronics")
        assertTrue(result is ComplianceCheckResult.Cleared)
    }
}
