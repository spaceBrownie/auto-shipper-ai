package com.autoshipper.portfolio.domain.service

import com.autoshipper.portfolio.config.DemandScanConfig
import com.autoshipper.portfolio.config.ScoringWeights
import com.autoshipper.portfolio.domain.CandidateRejection
import com.autoshipper.portfolio.domain.DemandCandidate
import com.autoshipper.portfolio.domain.DemandScanRun
import com.autoshipper.portfolio.domain.DemandSignalProvider
import com.autoshipper.portfolio.domain.DiscoveryBlacklistEntry
import com.autoshipper.portfolio.domain.Experiment
import com.autoshipper.portfolio.domain.ExperimentStatus
import com.autoshipper.portfolio.domain.RawCandidate
import com.autoshipper.portfolio.domain.ScoredCandidate
import com.autoshipper.portfolio.persistence.CandidateRejectionRepository
import com.autoshipper.portfolio.persistence.DemandCandidateRepository
import com.autoshipper.portfolio.persistence.DemandScanRunRepository
import com.autoshipper.portfolio.persistence.DiscoveryBlacklistRepository
import com.autoshipper.shared.money.Currency
import com.autoshipper.shared.money.Money
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DemandScanJobTest {

    @Mock
    private lateinit var scoringService: CandidateScoringService

    @Mock
    private lateinit var deduplicationService: CandidateDeduplicationService

    @Mock
    private lateinit var experimentService: ExperimentService

    @Mock
    private lateinit var scanRunRepository: DemandScanRunRepository

    @Mock
    private lateinit var candidateRepository: DemandCandidateRepository

    @Mock
    private lateinit var rejectionRepository: CandidateRejectionRepository

    @Mock
    private lateinit var blacklistRepository: DiscoveryBlacklistRepository

    @Mock
    private lateinit var provider1: DemandSignalProvider

    @Mock
    private lateinit var provider2: DemandSignalProvider

    private val objectMapper = ObjectMapper()

    private val enabledConfig = DemandScanConfig(
        enabled = true,
        cooldownHours = 20,
        validationWindowDays = 30,
        scoringWeights = ScoringWeights(),
        scoringThreshold = 0.6,
        dedupSimilarityThreshold = 0.7
    )

    private val disabledConfig = DemandScanConfig(enabled = false)

    private lateinit var job: DemandScanJob

    private fun buildCandidate(name: String, category: String = "Test") = RawCandidate(
        productName = name,
        category = category,
        description = "Description for $name",
        sourceType = "TEST",
        supplierUnitCost = Money.of(BigDecimal("5"), Currency.USD),
        estimatedSellingPrice = Money.of(BigDecimal("25"), Currency.USD),
        demandSignals = mapOf("approx_traffic" to "50000+")
    )

    private fun scored(raw: RawCandidate, passed: Boolean) = ScoredCandidate(
        raw = raw,
        demandScore = BigDecimal("0.8000"),
        marginPotentialScore = BigDecimal("0.8000"),
        competitionScore = BigDecimal("0.5000"),
        compositeScore = if (passed) BigDecimal("0.7500") else BigDecimal("0.4000"),
        passed = passed
    )

    @BeforeEach
    fun setUp() {
        whenever(scanRunRepository.save(any<DemandScanRun>())).thenAnswer { it.arguments[0] }
        whenever(candidateRepository.save(any<DemandCandidate>())).thenAnswer { it.arguments[0] }
        whenever(rejectionRepository.save(any<CandidateRejection>())).thenAnswer { it.arguments[0] }
        whenever(blacklistRepository.findAll()).thenReturn(emptyList())
        whenever(scanRunRepository.findTopByOrderByStartedAtDesc()).thenReturn(null)
        whenever(provider1.sourceType()).thenReturn("SOURCE_1")
        whenever(provider2.sourceType()).thenReturn("SOURCE_2")
        whenever(experimentService.create(any(), any(), any(), any(), any())).thenReturn(
            Experiment(
                name = "Test",
                hypothesisDescription = "Test",
                validationWindowDays = 30,
                status = ExperimentStatus.ACTIVE
            )
        )

        job = DemandScanJob(
            config = enabledConfig,
            providers = listOf(provider1, provider2),
            scoringService = scoringService,
            deduplicationService = deduplicationService,
            experimentService = experimentService,
            scanRunRepository = scanRunRepository,
            candidateRepository = candidateRepository,
            rejectionRepository = rejectionRepository,
            blacklistRepository = blacklistRepository,
            objectMapper = objectMapper
        )
    }

    @Test
    fun `happy path - sources succeed, candidates scored, experiments created, rejections logged`() {
        val candidate1 = buildCandidate("Product A")
        val candidate2 = buildCandidate("Product B")
        val candidate3 = buildCandidate("Product C")

        whenever(provider1.fetch()).thenReturn(listOf(candidate1, candidate2))
        whenever(provider2.fetch()).thenReturn(listOf(candidate3))
        whenever(deduplicationService.filterDuplicates(any())).thenAnswer { it.arguments[0] }
        whenever(scoringService.score(any<List<RawCandidate>>())).thenReturn(
            listOf(scored(candidate1, true), scored(candidate2, false), scored(candidate3, true))
        )

        job.run()

        verify(experimentService, times(2)).create(any(), any(), any(), any(), any())
        verify(rejectionRepository, times(1)).save(any<CandidateRejection>())
        verify(candidateRepository, times(3)).save(any<DemandCandidate>())
        verify(scanRunRepository, times(2)).save(any<DemandScanRun>()) // initial + completion
    }

    @Test
    fun `job disabled returns early without creating scan run`() {
        val disabledJob = DemandScanJob(
            config = disabledConfig,
            providers = listOf(provider1),
            scoringService = scoringService,
            deduplicationService = deduplicationService,
            experimentService = experimentService,
            scanRunRepository = scanRunRepository,
            candidateRepository = candidateRepository,
            rejectionRepository = rejectionRepository,
            blacklistRepository = blacklistRepository,
            objectMapper = objectMapper
        )

        disabledJob.run()

        verify(scanRunRepository, never()).save(any<DemandScanRun>())
        verify(provider1, never()).fetch()
    }

    @Test
    fun `within cooldown skips job execution`() {
        val recentRun = DemandScanRun(
            startedAt = Instant.now().minus(1, ChronoUnit.HOURS),
            status = "COMPLETED"
        )
        whenever(scanRunRepository.findTopByOrderByStartedAtDesc()).thenReturn(recentRun)

        job.run()

        verify(scanRunRepository, never()).save(any<DemandScanRun>())
        verify(provider1, never()).fetch()
    }

    @Test
    fun `single source failure does not prevent other sources from succeeding`() {
        val candidate = buildCandidate("Surviving Product")

        whenever(provider1.fetch()).thenThrow(RuntimeException("API down"))
        whenever(provider2.fetch()).thenReturn(listOf(candidate))
        whenever(deduplicationService.filterDuplicates(any())).thenAnswer { it.arguments[0] }
        whenever(scoringService.score(any<List<RawCandidate>>())).thenReturn(
            listOf(scored(candidate, true))
        )

        job.run()

        verify(experimentService, times(1)).create(any(), any(), any(), any(), any())
    }

    @Test
    fun `all sources fail - job completes with 0 candidates`() {
        whenever(provider1.fetch()).thenThrow(RuntimeException("API down"))
        whenever(provider2.fetch()).thenThrow(RuntimeException("Timeout"))
        whenever(deduplicationService.filterDuplicates(any())).thenReturn(emptyList())
        whenever(scoringService.score(any<List<RawCandidate>>())).thenReturn(emptyList())

        job.run()

        verify(experimentService, never()).create(any(), any(), any(), any(), any())
        verify(scanRunRepository, times(2)).save(any<DemandScanRun>()) // initial + completion
    }

    @Test
    fun `blacklisted candidates are excluded`() {
        val goodCandidate = buildCandidate("Good Product", "Electronics")
        val blacklistedCandidate = buildCandidate("Weapon Product", "Weapons")

        whenever(provider1.fetch()).thenReturn(listOf(goodCandidate, blacklistedCandidate))
        whenever(provider2.fetch()).thenReturn(emptyList())
        whenever(blacklistRepository.findAll()).thenReturn(
            listOf(DiscoveryBlacklistEntry(keyword = "weapon", reason = "prohibited"))
        )
        whenever(deduplicationService.filterDuplicates(any())).thenAnswer { it.arguments[0] }
        // Only the good candidate should reach scoring after blacklist filter
        whenever(scoringService.score(any<List<RawCandidate>>())).thenAnswer { invocation ->
            val candidates = invocation.arguments[0] as List<RawCandidate>
            candidates.map { scored(it, true) }
        }

        job.run()

        // Only 1 experiment created (the good product); the weapon product is filtered out
        verify(experimentService, times(1)).create(any(), any(), any(), any(), any())
    }

    @Test
    fun `experiment creation called with correct params for passing candidate`() {
        val candidate = buildCandidate("Test Product")

        whenever(provider1.fetch()).thenReturn(listOf(candidate))
        whenever(provider2.fetch()).thenReturn(emptyList())
        whenever(deduplicationService.filterDuplicates(any())).thenAnswer { it.arguments[0] }
        whenever(scoringService.score(any<List<RawCandidate>>())).thenReturn(
            listOf(scored(candidate, true))
        )

        job.run()

        verify(experimentService).create(
            any(),  // name
            any(),  // hypothesis
            any(),  // sourceSignal
            any(),  // estimatedMarginPerUnit
            any()   // windowDays
        )
    }

    @Test
    fun `scan run tracking - saved with correct status and counts`() {
        val candidate1 = buildCandidate("Product A")
        val candidate2 = buildCandidate("Product B")

        whenever(provider1.fetch()).thenReturn(listOf(candidate1))
        whenever(provider2.fetch()).thenReturn(listOf(candidate2))
        whenever(deduplicationService.filterDuplicates(any())).thenAnswer { it.arguments[0] }
        whenever(scoringService.score(any<List<RawCandidate>>())).thenReturn(
            listOf(scored(candidate1, true), scored(candidate2, false))
        )

        job.run()

        // Capture the last save call to verify the scan run state
        val captor = org.mockito.ArgumentCaptor.forClass(DemandScanRun::class.java)
        verify(scanRunRepository, times(2)).save(captor.capture())

        val finalSave = captor.allValues.last()
        assertEquals("COMPLETED", finalSave.status)
        assertEquals(2, finalSave.candidatesFound)
        assertEquals(1, finalSave.experimentsCreated)
        assertEquals(1, finalSave.rejections)
    }
}
