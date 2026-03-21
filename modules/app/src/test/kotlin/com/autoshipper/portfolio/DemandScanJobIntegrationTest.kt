package com.autoshipper.portfolio

import com.autoshipper.portfolio.domain.CandidateRejection
import com.autoshipper.portfolio.domain.DemandCandidate
import com.autoshipper.portfolio.domain.DemandScanRun
import com.autoshipper.portfolio.domain.service.CandidateScoringService
import com.autoshipper.portfolio.persistence.CandidateRejectionRepository
import com.autoshipper.portfolio.persistence.DemandCandidateRepository
import com.autoshipper.portfolio.persistence.DemandScanRunRepository
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyList
import org.mockito.Mockito.`when`
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import org.springframework.transaction.support.TransactionTemplate
import java.math.BigDecimal

/**
 * Integration tests for DemandScanJob.
 *
 * NOT @Transactional — DemandScanJob is an orchestrator that should NOT be @Transactional.
 * Each write commits independently, so the FAILED status should persist even after an exception.
 *
 * Uses @MockBean for CandidateScoringService to inject a failure.
 * Uses @TestPropertySource to set demand-scan.cooldown-hours=0 to bypass cooldown.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = [
    "demand-scan.enabled=true",
    "demand-scan.cooldown-hours=0"
])
class DemandScanJobIntegrationTest {

    @Autowired lateinit var demandScanJob: com.autoshipper.portfolio.domain.service.DemandScanJob
    @Autowired lateinit var scanRunRepository: DemandScanRunRepository
    @Autowired lateinit var candidateRepository: DemandCandidateRepository
    @Autowired lateinit var rejectionRepository: CandidateRejectionRepository
    @Autowired lateinit var jdbcTemplate: JdbcTemplate
    @Autowired lateinit var transactionTemplate: TransactionTemplate

    @MockBean
    lateinit var candidateScoringService: CandidateScoringService

    @AfterEach
    fun cleanup() {
        jdbcTemplate.execute(
            "TRUNCATE TABLE candidate_rejections, demand_candidates, demand_scan_runs CASCADE"
        )
    }

    // ─── Task 4.1: Failure status persists ───────────────────────────────

    @Test
    fun `DemandScanJob failure sets status to FAILED and persists the run`() {
        // Arrange: scoring service throws RuntimeException on any input
        `when`(candidateScoringService.score(anyList()))
            .thenThrow(RuntimeException("Scoring service unavailable"))

        // Act: run the job — it catches, saves FAILED status, then rethrows
        try {
            demandScanJob.run()
        } catch (_: RuntimeException) {
            // Expected — DemandScanJob rethrows after saving FAILED status
        }

        // Assert: the DemandScanRun should have status = FAILED
        val runs = scanRunRepository.findByStatus("FAILED")
        assertEquals(1, runs.size, "Exactly one FAILED scan run should exist")
        assertEquals("FAILED", runs.first().status)
        assertNotNull(runs.first().completedAt, "completedAt should be set on FAILED run")
    }

    // ─── Task 4.2: JSONB round-trip for DemandCandidate ─────────────────

    @Test
    fun `DemandCandidate JSONB demandSignals round-trip preserves content`() {
        // Arrange: create a scan run (FK dependency)
        val scanRun = scanRunRepository.save(DemandScanRun())

        val jsonContent = """{"youtube_views":"150000","reddit_mentions":"42"}"""
        val candidate = DemandCandidate(
            scanRunId = scanRun.id,
            productName = "JSONB Test Product",
            category = "Electronics",
            description = "Test description",
            sourceType = "YOUTUBE",
            demandScore = BigDecimal("0.8000"),
            marginPotentialScore = BigDecimal("0.6000"),
            competitionScore = BigDecimal("0.5000"),
            compositeScore = BigDecimal("0.6500"),
            passed = true,
            demandSignals = jsonContent
        )

        // Act: save inside transaction (flush requires active tx), then read back outside
        val savedId = transactionTemplate.execute {
            candidateRepository.saveAndFlush(candidate).id
        }!!

        // Assert: JSONB content preserved (read outside the save transaction to avoid L1 cache)
        val reloaded = candidateRepository.findById(savedId).orElseThrow()
        assertNotNull(reloaded.demandSignals, "demandSignals should not be null after round-trip")

        // Parse both as Jackson nodes to compare content regardless of key order
        val mapper = com.fasterxml.jackson.databind.ObjectMapper()
        val originalNode = mapper.readTree(jsonContent)
        val reloadedNode = mapper.readTree(reloaded.demandSignals)
        assertEquals(originalNode, reloadedNode,
            "JSONB content should be preserved exactly through round-trip")
    }

    // ─── Task 4.3: JSONB round-trip for CandidateRejection ──────────────

    @Test
    fun `CandidateRejection JSONB metadata round-trip preserves content`() {
        // Arrange: create a scan run (FK dependency)
        val scanRun = scanRunRepository.save(DemandScanRun())

        val jsonContent = """{"score":"0.35","reason":"low demand"}"""
        val rejection = CandidateRejection(
            scanRunId = scanRun.id,
            productName = "Rejected Product",
            category = "Home",
            sourceType = "REDDIT",
            rejectionReason = "Below scoring threshold",
            demandScore = BigDecimal("0.2000"),
            marginPotentialScore = BigDecimal("0.3000"),
            competitionScore = BigDecimal("0.5000"),
            compositeScore = BigDecimal("0.3500"),
            metadata = jsonContent
        )

        // Act: save inside transaction (flush requires active tx), then read back outside
        val savedId = transactionTemplate.execute {
            rejectionRepository.saveAndFlush(rejection).id
        }!!

        // Assert: JSONB content preserved (read outside the save transaction to avoid L1 cache)
        val reloaded = rejectionRepository.findById(savedId).orElseThrow()
        assertNotNull(reloaded.metadata, "metadata should not be null after round-trip")

        val mapper = com.fasterxml.jackson.databind.ObjectMapper()
        val originalNode = mapper.readTree(jsonContent)
        val reloadedNode = mapper.readTree(reloaded.metadata)
        assertEquals(originalNode, reloadedNode,
            "JSONB metadata content should be preserved exactly through round-trip")
    }
}
