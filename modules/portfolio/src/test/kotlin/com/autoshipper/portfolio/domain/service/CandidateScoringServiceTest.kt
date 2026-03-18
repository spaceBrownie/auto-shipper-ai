package com.autoshipper.portfolio.domain.service

import com.autoshipper.portfolio.config.DemandScanConfig
import com.autoshipper.portfolio.config.ScoringWeights
import com.autoshipper.portfolio.domain.RawCandidate
import com.autoshipper.shared.money.Currency
import com.autoshipper.shared.money.Money
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.quality.Strictness
import java.math.BigDecimal

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CandidateScoringServiceTest {

    private lateinit var service: CandidateScoringService

    private val defaultConfig = DemandScanConfig(
        enabled = true,
        cooldownHours = 20,
        validationWindowDays = 30,
        scoringWeights = ScoringWeights(demand = 0.4, marginPotential = 0.35, competition = 0.25),
        scoringThreshold = 0.6,
        dedupSimilarityThreshold = 0.7
    )

    @BeforeEach
    fun setUp() {
        service = CandidateScoringService(defaultConfig)
    }

    @Test
    fun `scoring with default weights - high demand good margin low competition passes`() {
        val candidate = RawCandidate(
            productName = "Trending Product",
            category = "Electronics",
            description = "A hot product",
            sourceType = "GOOGLE_TRENDS",
            supplierUnitCost = Money.of(BigDecimal("5"), Currency.USD),
            estimatedSellingPrice = Money.of(BigDecimal("25"), Currency.USD),
            demandSignals = mapOf("approx_traffic" to "50000+", "seller_count" to "5")
        )

        val result = service.score(candidate)

        // demand: 50000+ -> 1.0, margin: (25-5)/25 = 0.80 -> 1.0, competition: 5 sellers -> 0.8
        // composite = 1.0*0.4 + 1.0*0.35 + 0.8*0.25 = 0.4 + 0.35 + 0.2 = 0.95
        assertTrue(result.compositeScore.toDouble() > 0.6)
        assertTrue(result.passed)
    }

    @Test
    fun `scoring at threshold boundary passes`() {
        // We need composite score = exactly 0.6
        // With default weights (0.4, 0.35, 0.25):
        // demand=0.6, margin=0.6, competition=0.6 => 0.6*0.4 + 0.6*0.35 + 0.6*0.25 = 0.24+0.21+0.15 = 0.6
        val candidate = RawCandidate(
            productName = "Boundary Product",
            category = "Kitchen",
            description = "Exactly at the threshold",
            sourceType = "TEST",
            supplierUnitCost = Money.of(BigDecimal("10"), Currency.USD),
            estimatedSellingPrice = Money.of(BigDecimal("20"), Currency.USD),
            // margin: (20-10)/20 = 0.5 -> score 0.6
            demandSignals = mapOf(
                "approx_traffic" to "5000",  // 5000 -> score 0.6
                "seller_count" to "25"        // 25 -> score 0.6
            )
        )

        val result = service.score(candidate)

        assertEquals(BigDecimal("0.6000"), result.compositeScore)
        assertTrue(result.passed)
    }

    @Test
    fun `scoring below threshold fails`() {
        val candidate = RawCandidate(
            productName = "Low Signal Product",
            category = "Misc",
            description = "Weak signals",
            sourceType = "TEST",
            supplierUnitCost = Money.of(BigDecimal("15"), Currency.USD),
            estimatedSellingPrice = Money.of(BigDecimal("18"), Currency.USD),
            // margin: (18-15)/18 = 0.1667 -> score 0.1
            demandSignals = mapOf(
                "approx_traffic" to "50",   // < 200 -> score 0.1
                "seller_count" to "200"      // > 100 -> score 0.1
            )
        )

        val result = service.score(candidate)

        assertTrue(result.compositeScore.toDouble() < 0.6)
        assertFalse(result.passed)
    }

    @Test
    fun `missing demand signals defaults demand score to 0_3`() {
        val candidate = RawCandidate(
            productName = "No Demand Signals",
            category = "Test",
            description = "No traffic or bsr",
            sourceType = "TEST",
            supplierUnitCost = Money.of(BigDecimal("5"), Currency.USD),
            estimatedSellingPrice = Money.of(BigDecimal("25"), Currency.USD),
            demandSignals = emptyMap()
        )

        val result = service.score(candidate)

        assertEquals(BigDecimal("0.3000"), result.demandScore)
    }

    @Test
    fun `missing cost and price data defaults margin score to 0_3`() {
        val candidate = RawCandidate(
            productName = "No Cost Data",
            category = "Test",
            description = "No prices",
            sourceType = "TEST",
            supplierUnitCost = null,
            estimatedSellingPrice = null,
            demandSignals = mapOf("approx_traffic" to "10000")
        )

        val result = service.score(candidate)

        assertEquals(BigDecimal("0.3000"), result.marginPotentialScore)
    }

    @Test
    fun `zero selling price defaults margin score to 0_3`() {
        val candidate = RawCandidate(
            productName = "Zero Price Product",
            category = "Test",
            description = "Zero price",
            sourceType = "TEST",
            supplierUnitCost = Money.of(BigDecimal("5"), Currency.USD),
            estimatedSellingPrice = Money.of(BigDecimal("0"), Currency.USD),
            demandSignals = mapOf("approx_traffic" to "10000")
        )

        val result = service.score(candidate)

        assertEquals(BigDecimal("0.3000"), result.marginPotentialScore)
    }

    @Test
    fun `BSR-based demand scoring uses correct tier`() {
        val candidate = RawCandidate(
            productName = "BSR Product",
            category = "Test",
            description = "BSR-based scoring",
            sourceType = "AMAZON",
            supplierUnitCost = null,
            estimatedSellingPrice = null,
            demandSignals = mapOf("bsr" to "2500")
        )

        val result = service.score(candidate)

        // bsr 2500 -> <= 5000 -> score 0.8
        assertEquals(BigDecimal("0.8000"), result.demandScore)
    }

    @Test
    fun `batch scoring returns correct number of results`() {
        val candidates = listOf(
            RawCandidate(
                productName = "Product A",
                category = "Cat A",
                description = "Desc A",
                sourceType = "TEST",
                supplierUnitCost = null,
                estimatedSellingPrice = null,
                demandSignals = emptyMap()
            ),
            RawCandidate(
                productName = "Product B",
                category = "Cat B",
                description = "Desc B",
                sourceType = "TEST",
                supplierUnitCost = null,
                estimatedSellingPrice = null,
                demandSignals = emptyMap()
            ),
            RawCandidate(
                productName = "Product C",
                category = "Cat C",
                description = "Desc C",
                sourceType = "TEST",
                supplierUnitCost = null,
                estimatedSellingPrice = null,
                demandSignals = emptyMap()
            )
        )

        val results = service.score(candidates)

        assertEquals(3, results.size)
    }
}
