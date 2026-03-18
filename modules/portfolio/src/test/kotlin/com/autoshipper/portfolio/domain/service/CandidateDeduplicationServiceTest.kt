package com.autoshipper.portfolio.domain.service

import com.autoshipper.portfolio.config.DemandScanConfig
import com.autoshipper.portfolio.domain.Experiment
import com.autoshipper.portfolio.domain.ExperimentStatus
import com.autoshipper.portfolio.domain.RawCandidate
import com.autoshipper.portfolio.persistence.DemandCandidateRepository
import com.autoshipper.portfolio.persistence.ExperimentRepository
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CandidateDeduplicationServiceTest {

    @Mock
    private lateinit var demandCandidateRepository: DemandCandidateRepository

    @Mock
    private lateinit var experimentRepository: ExperimentRepository

    private lateinit var service: CandidateDeduplicationService

    private val config = DemandScanConfig(dedupSimilarityThreshold = 0.7)

    @BeforeEach
    fun setUp() {
        service = CandidateDeduplicationService(demandCandidateRepository, experimentRepository, config)
        whenever(demandCandidateRepository.findSimilarByName(any(), any())).thenReturn(emptyList())
        whenever(experimentRepository.findAll()).thenReturn(emptyList())
    }

    private fun candidate(name: String) = RawCandidate(
        productName = name,
        category = "Test",
        description = "Test description",
        sourceType = "TEST",
        supplierUnitCost = null,
        estimatedSellingPrice = null,
        demandSignals = emptyMap()
    )

    @Test
    fun `exact duplicate detected when existing candidate has same name`() {
        val existing = com.autoshipper.portfolio.domain.DemandCandidate(
            scanRunId = java.util.UUID.randomUUID(),
            productName = "Bamboo Kitchen Set",
            category = "Kitchen",
            sourceType = "TEST",
            demandScore = java.math.BigDecimal.ZERO,
            marginPotentialScore = java.math.BigDecimal.ZERO,
            competitionScore = java.math.BigDecimal.ZERO,
            compositeScore = java.math.BigDecimal.ZERO,
            passed = false
        )

        whenever(demandCandidateRepository.findSimilarByName(eq("Bamboo Kitchen Set"), any()))
            .thenReturn(listOf(existing))

        val result = service.isDuplicate(candidate("Bamboo Kitchen Set"))

        assertTrue(result)
    }

    @Test
    fun `no duplicates when completely different product name`() {
        val result = service.isDuplicate(candidate("Completely Unique Product XYZ"))

        assertFalse(result)
    }

    @Test
    fun `batch internal duplicate - only first passes through`() {
        val candidates = listOf(
            candidate("Duplicate Product"),
            candidate("Duplicate Product"),
            candidate("Different Product")
        )

        val result = service.filterDuplicates(candidates)

        assertEquals(2, result.size)
        assertEquals("Duplicate Product", result[0].productName)
        assertEquals("Different Product", result[1].productName)
    }

    @Test
    fun `experiment name match is detected as duplicate`() {
        val experiment = Experiment(
            name = "Bamboo Kitchen Set",
            hypothesisDescription = "Testing demand",
            validationWindowDays = 30,
            status = ExperimentStatus.ACTIVE
        )

        whenever(experimentRepository.findAll()).thenReturn(listOf(experiment))

        val result = service.isDuplicate(candidate("Bamboo Kitchen Set"))

        assertTrue(result)
    }

    @Test
    fun `empty database - no existing candidates or experiments - all pass through`() {
        whenever(demandCandidateRepository.findSimilarByName(any(), any())).thenReturn(emptyList())
        whenever(experimentRepository.findAll()).thenReturn(emptyList())

        val candidates = listOf(
            candidate("Product A"),
            candidate("Product B"),
            candidate("Product C")
        )

        val result = service.filterDuplicates(candidates)

        assertEquals(3, result.size)
    }
}
