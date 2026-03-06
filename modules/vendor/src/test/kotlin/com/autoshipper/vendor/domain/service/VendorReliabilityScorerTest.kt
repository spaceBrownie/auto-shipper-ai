package com.autoshipper.vendor.domain.service

import com.autoshipper.shared.identity.VendorId
import com.autoshipper.vendor.persistence.VendorBreachLogRepository
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class VendorReliabilityScorerTest {

    @Mock
    lateinit var breachLogRepository: VendorBreachLogRepository

    @InjectMocks
    lateinit var scorer: VendorReliabilityScorer

    @Test
    fun `perfect vendor scores 100`() {
        val vendorId = VendorId(UUID.randomUUID())
        whenever(breachLogRepository.countByVendorId(vendorId.value)).thenReturn(0L)

        val score = scorer.compute(
            vendorId = vendorId,
            onTimeRate = BigDecimal("100"),
            defectRate = BigDecimal.ZERO,
            avgResponseTimeHours = BigDecimal.ZERO
        )

        assertEquals(BigDecimal("100.00"), score.overallScore)
        assertEquals(0, score.breachCount)
    }

    @Test
    fun `breaches reduce score`() {
        val vendorId = VendorId(UUID.randomUUID())
        whenever(breachLogRepository.countByVendorId(vendorId.value)).thenReturn(3L)

        val score = scorer.compute(
            vendorId = vendorId,
            onTimeRate = BigDecimal("90"),
            defectRate = BigDecimal("2"),
            avgResponseTimeHours = BigDecimal("12")
        )

        assertTrue(score.overallScore < BigDecimal("100"))
        assertEquals(3, score.breachCount)
    }

    @Test
    fun `high defect rate lowers score significantly`() {
        val vendorId = VendorId(UUID.randomUUID())
        whenever(breachLogRepository.countByVendorId(vendorId.value)).thenReturn(0L)

        val goodScore = scorer.compute(
            vendorId = vendorId,
            onTimeRate = BigDecimal("95"),
            defectRate = BigDecimal.ZERO,
            avgResponseTimeHours = BigDecimal("4")
        )

        val badScore = scorer.compute(
            vendorId = vendorId,
            onTimeRate = BigDecimal("95"),
            defectRate = BigDecimal("8"),
            avgResponseTimeHours = BigDecimal("4")
        )

        assertTrue(goodScore.overallScore > badScore.overallScore)
    }
}
