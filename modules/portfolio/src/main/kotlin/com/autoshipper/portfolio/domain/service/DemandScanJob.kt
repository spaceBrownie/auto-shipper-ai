package com.autoshipper.portfolio.domain.service

import com.autoshipper.portfolio.config.DemandScanConfig
import com.autoshipper.portfolio.domain.CandidateRejection
import com.autoshipper.portfolio.domain.DemandCandidate
import com.autoshipper.portfolio.domain.DemandScanRun
import com.autoshipper.portfolio.domain.DemandSignalProvider
import com.autoshipper.portfolio.domain.RawCandidate
import com.autoshipper.portfolio.domain.ScoredCandidate
import com.autoshipper.portfolio.persistence.CandidateRejectionRepository
import com.autoshipper.portfolio.persistence.DemandCandidateRepository
import com.autoshipper.portfolio.persistence.DemandScanRunRepository
import com.autoshipper.portfolio.persistence.DiscoveryBlacklistRepository
import com.autoshipper.shared.money.Currency
import com.autoshipper.shared.money.Money
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import java.time.Duration
import java.time.Instant

@Service
class DemandScanJob(
    private val config: DemandScanConfig,
    private val providers: List<DemandSignalProvider>,
    private val scoringService: CandidateScoringService,
    private val deduplicationService: CandidateDeduplicationService,
    private val experimentService: ExperimentService,
    private val scanRunRepository: DemandScanRunRepository,
    private val candidateRepository: DemandCandidateRepository,
    private val rejectionRepository: CandidateRejectionRepository,
    private val blacklistRepository: DiscoveryBlacklistRepository,
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(DemandScanJob::class.java)

    @Scheduled(cron = "0 0 3 * * *")
    fun run() {
        if (!config.enabled) {
            logger.info("DemandScanJob is disabled, skipping")
            return
        }

        if (isWithinCooldown()) {
            logger.info("DemandScanJob skipped — within cooldown window ({}h)", config.cooldownHours)
            return
        }

        logger.info("DemandScanJob started with {} providers", providers.size)
        val scanRun = scanRunRepository.save(DemandScanRun())

        try {
            val allCandidates = collectFromSources(scanRun)
            val deduplicated = deduplicationService.filterDuplicates(allCandidates)
            val filtered = filterBlacklisted(deduplicated)
            val scored = scoringService.score(filtered)

            val passed = scored.filter { it.passed }
            val failed = scored.filter { !it.passed }

            var experimentsCreated = 0
            for (candidate in passed) {
                persistCandidate(candidate, scanRun.id)
                createExperiment(candidate)
                experimentsCreated++
            }

            for (candidate in failed) {
                persistCandidate(candidate, scanRun.id)
                persistRejection(candidate, "Below scoring threshold", scanRun.id)
            }

            scanRun.candidatesFound = scored.size
            scanRun.experimentsCreated = experimentsCreated
            scanRun.rejections = failed.size
            scanRun.status = "COMPLETED"
            scanRun.completedAt = Instant.now()
            scanRunRepository.save(scanRun)

            logger.info(
                "DemandScanJob completed: {} candidates scored, {} experiments created, {} rejected",
                scored.size, experimentsCreated, failed.size
            )
        } catch (e: Exception) {
            scanRun.status = "FAILED"
            scanRun.completedAt = Instant.now()
            scanRunRepository.save(scanRun)
            logger.error("DemandScanJob failed", e)
            throw e
        }
    }

    private fun isWithinCooldown(): Boolean {
        val lastRun = scanRunRepository.findTopByOrderByStartedAtDesc() ?: return false
        if (lastRun.status != "COMPLETED") return false
        val cooldownDuration = Duration.ofHours(config.cooldownHours.toLong())
        return Duration.between(lastRun.startedAt, Instant.now()) < cooldownDuration
    }

    private fun collectFromSources(scanRun: DemandScanRun): List<RawCandidate> {
        val allCandidates = mutableListOf<RawCandidate>()
        var sourcesQueried = 0

        for (provider in providers) {
            try {
                val candidates = provider.fetch()
                allCandidates.addAll(candidates)
                sourcesQueried++
                logger.info("Source '{}' returned {} candidates", provider.sourceType(), candidates.size)
            } catch (e: Exception) {
                logger.warn("Source '{}' failed: {}", provider.sourceType(), e.message)
            }
        }

        scanRun.sourcesQueried = sourcesQueried
        return allCandidates
    }

    private fun filterBlacklisted(candidates: List<RawCandidate>): List<RawCandidate> {
        val blacklist = blacklistRepository.findAll().map { it.keyword.lowercase() }
        if (blacklist.isEmpty()) return candidates

        return candidates.filter { candidate ->
            val nameLC = candidate.productName.lowercase()
            val categoryLC = candidate.category.lowercase()
            val isBlacklisted = blacklist.any { keyword ->
                nameLC.contains(keyword) || categoryLC.contains(keyword)
            }
            if (isBlacklisted) {
                logger.debug("Candidate '{}' blacklisted", candidate.productName)
            }
            !isBlacklisted
        }
    }

    private fun persistCandidate(scored: ScoredCandidate, scanRunId: java.util.UUID) {
        val raw = scored.raw
        candidateRepository.save(
            DemandCandidate(
                scanRunId = scanRunId,
                productName = raw.productName,
                category = raw.category,
                description = raw.description,
                sourceType = raw.sourceType,
                supplierUnitCost = raw.supplierUnitCost?.amount,
                supplierCostCurrency = raw.supplierUnitCost?.currency?.name,
                estimatedSellingPrice = raw.estimatedSellingPrice?.amount,
                sellingPriceCurrency = raw.estimatedSellingPrice?.currency?.name,
                demandScore = scored.demandScore,
                marginPotentialScore = scored.marginPotentialScore,
                competitionScore = scored.competitionScore,
                compositeScore = scored.compositeScore,
                passed = scored.passed,
                demandSignals = objectMapper.writeValueAsString(raw.demandSignals)
            )
        )
    }

    private fun persistRejection(scored: ScoredCandidate, reason: String, scanRunId: java.util.UUID) {
        val raw = scored.raw
        rejectionRepository.save(
            CandidateRejection(
                scanRunId = scanRunId,
                productName = raw.productName,
                category = raw.category,
                sourceType = raw.sourceType,
                rejectionReason = reason,
                demandScore = scored.demandScore,
                marginPotentialScore = scored.marginPotentialScore,
                competitionScore = scored.competitionScore,
                compositeScore = scored.compositeScore,
                metadata = objectMapper.writeValueAsString(raw.demandSignals)
            )
        )
    }

    private fun createExperiment(scored: ScoredCandidate) {
        val raw = scored.raw
        val estimatedMargin = if (raw.supplierUnitCost != null && raw.estimatedSellingPrice != null) {
            val marginAmount = raw.estimatedSellingPrice.amount.subtract(raw.supplierUnitCost.amount)
            Money.of(marginAmount, raw.supplierUnitCost.currency)
        } else null

        val sourceSignal = "${raw.sourceType}: ${raw.demandSignals.entries.take(3).joinToString(", ") { "${it.key}=${it.value}" }}"

        experimentService.create(
            name = raw.productName,
            hypothesis = "Demand signal detected via ${raw.sourceType} — composite score ${scored.compositeScore}. " +
                    "Category: ${raw.category}. Validate demand before sourcing.",
            sourceSignal = sourceSignal,
            estimatedMarginPerUnit = estimatedMargin,
            windowDays = config.validationWindowDays
        )
    }
}
