package com.autoshipper.compliance.domain.service

import com.autoshipper.compliance.domain.ComplianceCheckResult
import com.autoshipper.compliance.domain.ComplianceFailureReason
import com.autoshipper.shared.identity.SkuId
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class ClaimsCheckServiceTest {

    private val service = ClaimsCheckService()
    private val skuId = SkuId(UUID.randomUUID())

    @Test
    fun `description without regulated claims passes`() {
        val result = service.check(skuId, "A comfortable memory foam pillow for better sleep")
        assertInstanceOf(ComplianceCheckResult.Cleared::class.java, result)
    }

    @Test
    fun `description claiming to cure disease fails`() {
        val result = service.check(skuId, "This supplement cures diabetes naturally")
        assertInstanceOf(ComplianceCheckResult.Failed::class.java, result)
        val failed = result as ComplianceCheckResult.Failed
        assertEquals(ComplianceFailureReason.MISLEADING_CLAIMS, failed.reason)
    }

    @Test
    fun `description with FDA approved claim fails`() {
        val result = service.check(skuId, "Our FDA-approved weight loss formula")
        assertInstanceOf(ComplianceCheckResult.Failed::class.java, result)
    }

    @Test
    fun `description with clinically proven fails`() {
        val result = service.check(skuId, "Clinically proven to reduce wrinkles")
        assertInstanceOf(ComplianceCheckResult.Failed::class.java, result)
    }

    @Test
    fun `description with miracle claim fails`() {
        val result = service.check(skuId, "This miracle cream transforms your skin")
        assertInstanceOf(ComplianceCheckResult.Failed::class.java, result)
    }

    @Test
    fun `normal product description passes`() {
        val result = service.check(skuId, "Stainless steel water bottle, 32oz, keeps drinks cold for 24 hours")
        assertInstanceOf(ComplianceCheckResult.Cleared::class.java, result)
    }
}
