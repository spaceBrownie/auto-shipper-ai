package com.autoshipper.compliance.domain.service

import com.autoshipper.compliance.domain.ComplianceCheckResult
import com.autoshipper.compliance.domain.ComplianceFailureReason
import com.autoshipper.shared.identity.SkuId
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class IpCheckServiceTest {

    private val service = IpCheckService()
    private val skuId = SkuId(UUID.randomUUID())

    @Test
    fun `product name without trademarked terms passes`() {
        val result = service.check(skuId, "Ergonomic Wireless Mouse Pad")
        assertInstanceOf(ComplianceCheckResult.Cleared::class.java, result)
    }

    @Test
    fun `product name containing Nike fails with IP_INFRINGEMENT`() {
        val result = service.check(skuId, "Premium Nike-Style Running Shoes")
        assertInstanceOf(ComplianceCheckResult.Failed::class.java, result)
        val failed = result as ComplianceCheckResult.Failed
        assertEquals(ComplianceFailureReason.IP_INFRINGEMENT, failed.reason)
        assertTrue(failed.detail!!.contains("nike"))
    }

    @Test
    fun `check is case insensitive`() {
        val result = service.check(skuId, "DISNEY Princess Costume")
        assertInstanceOf(ComplianceCheckResult.Failed::class.java, result)
    }

    @Test
    fun `generic product name passes`() {
        val result = service.check(skuId, "Bamboo Cutting Board Set")
        assertInstanceOf(ComplianceCheckResult.Cleared::class.java, result)
    }
}
