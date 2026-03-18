package com.autoshipper.portfolio.domain.service

import com.autoshipper.portfolio.config.DemandScanConfig
import com.autoshipper.portfolio.domain.RawCandidate
import com.autoshipper.portfolio.domain.ScoredCandidate
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode

@Service
class CandidateScoringService(
    private val config: DemandScanConfig
) {
    private val logger = LoggerFactory.getLogger(CandidateScoringService::class.java)

    fun score(candidates: List<RawCandidate>): List<ScoredCandidate> =
        candidates.map { score(it) }

    fun score(candidate: RawCandidate): ScoredCandidate {
        val demandScore = computeDemandScore(candidate)
        val marginPotentialScore = computeMarginPotentialScore(candidate)
        val competitionScore = computeCompetitionScore(candidate)

        val weights = config.scoringWeights
        val compositeScore = (demandScore.toDouble() * weights.demand +
                marginPotentialScore.toDouble() * weights.marginPotential +
                competitionScore.toDouble() * weights.competition)
            .toBigDecimal().setScale(4, RoundingMode.HALF_UP)

        val passed = compositeScore.toDouble() >= config.scoringThreshold

        logger.debug(
            "Scored '{}': demand={}, margin={}, competition={}, composite={}, passed={}",
            candidate.productName, demandScore, marginPotentialScore, competitionScore, compositeScore, passed
        )

        return ScoredCandidate(
            raw = candidate,
            demandScore = demandScore,
            marginPotentialScore = marginPotentialScore,
            competitionScore = competitionScore,
            compositeScore = compositeScore,
            passed = passed
        )
    }

    internal fun computeDemandScore(candidate: RawCandidate): BigDecimal {
        val traffic = candidate.demandSignals["approx_traffic"]
        if (traffic != null) {
            val numericTraffic = traffic.replace("+", "").replace(",", "").toLongOrNull() ?: 0L
            val score = when {
                numericTraffic >= 50_000 -> 1.0
                numericTraffic >= 10_000 -> 0.8
                numericTraffic >= 5_000 -> 0.6
                numericTraffic >= 1_000 -> 0.4
                numericTraffic >= 200 -> 0.2
                else -> 0.1
            }
            return score.toBigDecimal().setScale(4, RoundingMode.HALF_UP)
        }

        val bsr = candidate.demandSignals["bsr"]?.toLongOrNull()
        if (bsr != null) {
            val score = when {
                bsr <= 1_000 -> 1.0
                bsr <= 5_000 -> 0.8
                bsr <= 20_000 -> 0.6
                bsr <= 50_000 -> 0.4
                bsr <= 100_000 -> 0.2
                else -> 0.1
            }
            return score.toBigDecimal().setScale(4, RoundingMode.HALF_UP)
        }

        return BigDecimal("0.3000")
    }

    internal fun computeMarginPotentialScore(candidate: RawCandidate): BigDecimal {
        val cost = candidate.supplierUnitCost?.amount
        val price = candidate.estimatedSellingPrice?.amount
        if (cost == null || price == null || price.signum() == 0) {
            return BigDecimal("0.3000")
        }

        val margin = price.subtract(cost).divide(price, 4, RoundingMode.HALF_UP)
        val score = when {
            margin >= BigDecimal("0.70") -> 1.0
            margin >= BigDecimal("0.60") -> 0.8
            margin >= BigDecimal("0.50") -> 0.6
            margin >= BigDecimal("0.40") -> 0.4
            margin >= BigDecimal("0.30") -> 0.2
            else -> 0.1
        }
        return score.toBigDecimal().setScale(4, RoundingMode.HALF_UP)
    }

    internal fun computeCompetitionScore(candidate: RawCandidate): BigDecimal {
        val sellerCount = candidate.demandSignals["seller_count"]?.toIntOrNull()
        if (sellerCount != null) {
            val score = when {
                sellerCount <= 3 -> 1.0
                sellerCount <= 10 -> 0.8
                sellerCount <= 25 -> 0.6
                sellerCount <= 50 -> 0.4
                sellerCount <= 100 -> 0.2
                else -> 0.1
            }
            return score.toBigDecimal().setScale(4, RoundingMode.HALF_UP)
        }

        return BigDecimal("0.5000")
    }
}
