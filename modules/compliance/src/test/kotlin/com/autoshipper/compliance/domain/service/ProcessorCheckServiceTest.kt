package com.autoshipper.compliance.domain.service

import com.autoshipper.compliance.domain.ComplianceCheckResult
import com.autoshipper.compliance.domain.ComplianceFailureReason
import com.autoshipper.shared.identity.SkuId
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class ProcessorCheckServiceTest {

    private val service = ProcessorCheckService()
    private val skuId = SkuId(UUID.randomUUID())

    @Test
    fun `allowed category passes`() {
        val result = service.check(skuId, "Home & Kitchen")
        assertInstanceOf(ComplianceCheckResult.Cleared::class.java, result)
    }

    @Test
    fun `firearms category fails`() {
        val result = service.check(skuId, "Firearms & Accessories")
        assertInstanceOf(ComplianceCheckResult.Failed::class.java, result)
        val failed = result as ComplianceCheckResult.Failed
        assertEquals(ComplianceFailureReason.PROCESSOR_PROHIBITED, failed.reason)
        assertTrue(failed.detail!!.contains("firearms"))
    }

    @Test
    fun `gambling category fails`() {
        val result = service.check(skuId, "Online Gambling")
        assertInstanceOf(ComplianceCheckResult.Failed::class.java, result)
    }

    @Test
    fun `check is case insensitive`() {
        val result = service.check(skuId, "ADULT CONTENT")
        assertInstanceOf(ComplianceCheckResult.Failed::class.java, result)
    }

    @Test
    fun `electronics category passes`() {
        val result = service.check(skuId, "Electronics")
        assertInstanceOf(ComplianceCheckResult.Cleared::class.java, result)
    }
}
