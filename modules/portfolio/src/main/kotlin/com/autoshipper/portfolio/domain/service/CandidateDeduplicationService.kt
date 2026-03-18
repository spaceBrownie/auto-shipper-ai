package com.autoshipper.portfolio.domain.service

import com.autoshipper.portfolio.config.DemandScanConfig
import com.autoshipper.portfolio.domain.RawCandidate
import com.autoshipper.portfolio.persistence.DemandCandidateRepository
import com.autoshipper.portfolio.persistence.ExperimentRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

@Service
class CandidateDeduplicationService(
    private val demandCandidateRepository: DemandCandidateRepository,
    private val experimentRepository: ExperimentRepository,
    private val config: DemandScanConfig
) {
    private val logger = LoggerFactory.getLogger(CandidateDeduplicationService::class.java)

    fun isDuplicate(candidate: RawCandidate): Boolean {
        val threshold = config.dedupSimilarityThreshold

        val similarCandidates = demandCandidateRepository.findSimilarByName(
            candidate.productName, threshold
        )
        if (similarCandidates.isNotEmpty()) {
            logger.debug(
                "Candidate '{}' is a near-duplicate of existing candidate '{}'",
                candidate.productName, similarCandidates.first().productName
            )
            return true
        }

        val existingExperiments = experimentRepository.findAll()
        val isDupOfExperiment = existingExperiments.any { experiment ->
            trigramSimilarity(candidate.productName, experiment.name) > threshold
        }
        if (isDupOfExperiment) {
            logger.debug("Candidate '{}' is a near-duplicate of an existing experiment", candidate.productName)
            return true
        }

        return false
    }

    fun filterDuplicates(candidates: List<RawCandidate>): List<RawCandidate> {
        val seen = mutableSetOf<String>()
        return candidates.filter { candidate ->
            val normalizedName = candidate.productName.lowercase().trim()
            if (normalizedName in seen) {
                logger.debug("Candidate '{}' is a batch-internal duplicate", candidate.productName)
                false
            } else {
                seen.add(normalizedName)
                !isDuplicate(candidate)
            }
        }
    }

    private fun trigramSimilarity(a: String, b: String): Double {
        val trigramsA = trigrams(a.lowercase())
        val trigramsB = trigrams(b.lowercase())
        if (trigramsA.isEmpty() && trigramsB.isEmpty()) return 1.0
        if (trigramsA.isEmpty() || trigramsB.isEmpty()) return 0.0
        val intersection = trigramsA.intersect(trigramsB).size
        val union = trigramsA.union(trigramsB).size
        return intersection.toDouble() / union.toDouble()
    }

    private fun trigrams(s: String): Set<String> {
        val padded = "  $s "
        return (0..padded.length - 3).map { padded.substring(it, it + 3) }.toSet()
    }
}
