package com.autoshipper.portfolio.handler

import com.autoshipper.portfolio.domain.service.DemandScanJob
import com.autoshipper.portfolio.persistence.CandidateRejectionRepository
import com.autoshipper.portfolio.persistence.DemandCandidateRepository
import com.autoshipper.portfolio.persistence.DemandScanRunRepository
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

// --- DTOs ---

data class DemandScanStatusResponse(
    val lastRunId: UUID?,
    val lastRunStartedAt: Instant?,
    val lastRunCompletedAt: Instant?,
    val lastRunStatus: String?,
    val sourcesQueried: Int,
    val candidatesFound: Int,
    val experimentsCreated: Int,
    val rejections: Int
)

data class DemandCandidateResponse(
    val id: UUID,
    val productName: String,
    val category: String,
    val description: String?,
    val sourceType: String,
    val supplierUnitCost: BigDecimal?,
    val supplierCostCurrency: String?,
    val estimatedSellingPrice: BigDecimal?,
    val sellingPriceCurrency: String?,
    val demandScore: BigDecimal,
    val marginPotentialScore: BigDecimal,
    val competitionScore: BigDecimal,
    val compositeScore: BigDecimal,
    val passed: Boolean,
    val createdAt: Instant
)

data class CandidateRejectionResponse(
    val id: UUID,
    val productName: String,
    val category: String,
    val sourceType: String,
    val rejectionReason: String,
    val demandScore: BigDecimal?,
    val marginPotentialScore: BigDecimal?,
    val competitionScore: BigDecimal?,
    val compositeScore: BigDecimal?,
    val createdAt: Instant
)

data class TriggerScanResponse(
    val message: String
)

// --- Controller ---

@RestController
@RequestMapping("/api/portfolio/demand-scan")
class DemandScanController(
    private val scanRunRepository: DemandScanRunRepository,
    private val candidateRepository: DemandCandidateRepository,
    private val rejectionRepository: CandidateRejectionRepository,
    private val demandScanJob: DemandScanJob
) {

    @GetMapping("/status")
    fun getStatus(): ResponseEntity<DemandScanStatusResponse> {
        val lastRun = scanRunRepository.findTopByOrderByStartedAtDesc()
        return ResponseEntity.ok(
            DemandScanStatusResponse(
                lastRunId = lastRun?.id,
                lastRunStartedAt = lastRun?.startedAt,
                lastRunCompletedAt = lastRun?.completedAt,
                lastRunStatus = lastRun?.status,
                sourcesQueried = lastRun?.sourcesQueried ?: 0,
                candidatesFound = lastRun?.candidatesFound ?: 0,
                experimentsCreated = lastRun?.experimentsCreated ?: 0,
                rejections = lastRun?.rejections ?: 0
            )
        )
    }

    @GetMapping("/candidates")
    fun getCandidates(): ResponseEntity<List<DemandCandidateResponse>> {
        val lastRun = scanRunRepository.findTopByOrderByStartedAtDesc()
            ?: return ResponseEntity.ok(emptyList())

        val candidates = candidateRepository.findByScanRunId(lastRun.id).map { c ->
            DemandCandidateResponse(
                id = c.id,
                productName = c.productName,
                category = c.category,
                description = c.description,
                sourceType = c.sourceType,
                supplierUnitCost = c.supplierUnitCost,
                supplierCostCurrency = c.supplierCostCurrency,
                estimatedSellingPrice = c.estimatedSellingPrice,
                sellingPriceCurrency = c.sellingPriceCurrency,
                demandScore = c.demandScore,
                marginPotentialScore = c.marginPotentialScore,
                competitionScore = c.competitionScore,
                compositeScore = c.compositeScore,
                passed = c.passed,
                createdAt = c.createdAt
            )
        }
        return ResponseEntity.ok(candidates)
    }

    @GetMapping("/rejections")
    fun getRejections(): ResponseEntity<List<CandidateRejectionResponse>> {
        val lastRun = scanRunRepository.findTopByOrderByStartedAtDesc()
            ?: return ResponseEntity.ok(emptyList())

        val rejections = rejectionRepository.findByScanRunId(lastRun.id).map { r ->
            CandidateRejectionResponse(
                id = r.id,
                productName = r.productName,
                category = r.category,
                sourceType = r.sourceType,
                rejectionReason = r.rejectionReason,
                demandScore = r.demandScore,
                marginPotentialScore = r.marginPotentialScore,
                competitionScore = r.competitionScore,
                compositeScore = r.compositeScore,
                createdAt = r.createdAt
            )
        }
        return ResponseEntity.ok(rejections)
    }

    @PostMapping("/trigger")
    fun triggerScan(): ResponseEntity<TriggerScanResponse> {
        demandScanJob.run()
        return ResponseEntity.ok(TriggerScanResponse("Demand scan triggered successfully"))
    }
}
