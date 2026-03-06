package com.autoshipper.vendor.domain.service

import com.autoshipper.shared.identity.VendorId
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class VendorReliabilityScorerTest {

    @Mock
    lateinit var fulfillmentDataProvider: VendorFulfillmentDataProvider

    @InjectMocks
    lateinit var scorer: VendorReliabilityScorer

    @Test
    fun `perfect vendor scores 100`() {
        val vendorId = VendorId(UUID.randomUUID())
        whenever(fulfillmentDataProvider.countViolationsSince(eq(vendorId.value), any())).thenReturn(0L)

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
    fun `violations in rolling window reduce breach score`() {
        val vendorId = VendorId(UUID.randomUUID())
        whenever(fulfillmentDataProvider.countViolationsSince(eq(vendorId.value), any())).thenReturn(3L)

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
        whenever(fulfillmentDataProvider.countViolationsSince(eq(vendorId.value), any())).thenReturn(0L)

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

    @Test
    fun `breach score floors at zero after 10 violations`() {
        val vendorId = VendorId(UUID.randomUUID())
        whenever(fulfillmentDataProvider.countViolationsSince(eq(vendorId.value), any())).thenReturn(10L)

        val score = scorer.compute(
            vendorId = vendorId,
            onTimeRate = BigDecimal("100"),
            defectRate = BigDecimal.ZERO,
            avgResponseTimeHours = BigDecimal.ZERO
        )

        // Breach component is 0, so overall = 100*0.40 + 100*0.25 + 0*0.20 + 100*0.15 = 80.00
        assertEquals(BigDecimal("80.00"), score.overallScore)
        assertEquals(10, score.breachCount)
    }

    @Test
    fun `vendor with past violations can recover when violations leave rolling window`() {
        val vendorId = VendorId(UUID.randomUUID())

        // Vendor had 5 violations previously (simulating all-time count)
        whenever(fulfillmentDataProvider.countViolationsSince(eq(vendorId.value), any())).thenReturn(5L)
        val duringViolations = scorer.compute(
            vendorId = vendorId,
            onTimeRate = BigDecimal("90"),
            defectRate = BigDecimal("2"),
            avgResponseTimeHours = BigDecimal("12")
        )

        // Later, violations have aged out of the 30-day window
        whenever(fulfillmentDataProvider.countViolationsSince(eq(vendorId.value), any())).thenReturn(0L)
        val afterRecovery = scorer.compute(
            vendorId = vendorId,
            onTimeRate = BigDecimal("90"),
            defectRate = BigDecimal("2"),
            avgResponseTimeHours = BigDecimal("12")
        )

        assertTrue(afterRecovery.overallScore > duringViolations.overallScore,
            "Score should improve after violations leave the rolling window")
        assertEquals(0, afterRecovery.breachCount)
    }

    @Test
    fun `more than 10 violations does not push breach score below zero`() {
        val vendorId = VendorId(UUID.randomUUID())
        whenever(fulfillmentDataProvider.countViolationsSince(eq(vendorId.value), any())).thenReturn(15L)

        val score = scorer.compute(
            vendorId = vendorId,
            onTimeRate = BigDecimal("100"),
            defectRate = BigDecimal.ZERO,
            avgResponseTimeHours = BigDecimal.ZERO
        )

        // Same as 10 violations — breach component clamped to 0
        assertEquals(BigDecimal("80.00"), score.overallScore)
        assertEquals(15, score.breachCount)
    }
}
