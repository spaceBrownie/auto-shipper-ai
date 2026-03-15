package com.autoshipper.portfolio.handler

import com.autoshipper.portfolio.domain.service.ExperimentService
import com.autoshipper.portfolio.domain.service.PortfolioReporter
import com.autoshipper.portfolio.domain.service.PriorityRanker
import com.autoshipper.portfolio.domain.service.RefundPatternAnalyzer
import com.autoshipper.portfolio.persistence.KillRecommendationRepository
import com.autoshipper.shared.identity.ExperimentId
import com.autoshipper.shared.identity.SkuId
import com.autoshipper.shared.money.Currency
import com.autoshipper.shared.money.Money
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

// --- Request/Response DTOs ---

data class CreateExperimentRequest(
    val name: String,
    val hypothesis: String,
    val sourceSignal: String? = null,
    val estimatedMarginPerUnitAmount: BigDecimal? = null,
    val estimatedMarginPerUnitCurrency: String? = null,
    val validationWindowDays: Int
)

data class ExperimentResponse(
    val id: UUID,
    val name: String,
    val hypothesis: String,
    val sourceSignal: String?,
    val estimatedMarginPerUnit: BigDecimal?,
    val estimatedMarginCurrency: String?,
    val validationWindowDays: Int,
    val status: String,
    val launchedSkuId: UUID?,
    val createdAt: Instant
)

data class PortfolioSummaryResponse(
    val totalExperiments: Long,
    val activeExperiments: Long,
    val activeSkus: Int,
    val terminatedSkus: Long,
    val blendedNetMargin: BigDecimal,
    val totalProfit: BigDecimal
)

data class KillRecommendationResponse(
    val id: UUID,
    val skuId: UUID,
    val daysNegative: Int,
    val avgNetMargin: BigDecimal,
    val detectedAt: Instant,
    val confirmedAt: Instant?
)

data class PriorityRankingResponse(
    val skuId: UUID,
    val avgNetMargin: BigDecimal,
    val revenueVolume: BigDecimal,
    val riskFactor: BigDecimal,
    val riskAdjustedReturn: BigDecimal
)

data class RefundAlertResponse(
    val skuIds: List<UUID>,
    val portfolioAvgRefundRate: BigDecimal,
    val elevatedSkuCount: Int
)

data class ValidateExperimentRequest(
    val skuId: UUID
)

// --- Controller ---

@RestController
@RequestMapping("/api/portfolio")
class PortfolioController(
    private val experimentService: ExperimentService,
    private val portfolioReporter: PortfolioReporter,
    private val priorityRanker: PriorityRanker,
    private val refundPatternAnalyzer: RefundPatternAnalyzer,
    private val killRecommendationRepository: KillRecommendationRepository
) {

    @GetMapping("/summary")
    fun getSummary(): ResponseEntity<PortfolioSummaryResponse> {
        val summary = portfolioReporter.summary()
        return ResponseEntity.ok(
            PortfolioSummaryResponse(
                totalExperiments = summary.totalExperiments,
                activeExperiments = summary.activeExperiments,
                activeSkus = summary.activeSkus,
                terminatedSkus = summary.terminatedSkus,
                blendedNetMargin = summary.blendedNetMargin,
                totalProfit = summary.totalProfit
            )
        )
    }

    @GetMapping("/experiments")
    fun getExperiments(): ResponseEntity<List<ExperimentResponse>> {
        val experiments = experimentService.findAll().map { it.toResponse() }
        return ResponseEntity.ok(experiments)
    }

    @PostMapping("/experiments")
    fun createExperiment(@RequestBody request: CreateExperimentRequest): ResponseEntity<ExperimentResponse> {
        val estimatedMargin = if (request.estimatedMarginPerUnitAmount != null && request.estimatedMarginPerUnitCurrency != null) {
            Money.of(request.estimatedMarginPerUnitAmount, Currency.valueOf(request.estimatedMarginPerUnitCurrency))
        } else null

        val experiment = experimentService.create(
            name = request.name,
            hypothesis = request.hypothesis,
            sourceSignal = request.sourceSignal,
            estimatedMarginPerUnit = estimatedMargin,
            windowDays = request.validationWindowDays
        )

        return ResponseEntity.status(HttpStatus.CREATED).body(experiment.toResponse())
    }

    @PostMapping("/experiments/{id}/validate")
    fun validateExperiment(
        @PathVariable id: UUID,
        @RequestBody request: ValidateExperimentRequest
    ): ResponseEntity<ExperimentResponse> {
        val experiment = experimentService.markValidated(ExperimentId(id), SkuId(request.skuId))
        return ResponseEntity.ok(experiment.toResponse())
    }

    @PostMapping("/experiments/{id}/fail")
    fun failExperiment(@PathVariable id: UUID): ResponseEntity<ExperimentResponse> {
        val experiment = experimentService.markFailed(ExperimentId(id))
        return ResponseEntity.ok(experiment.toResponse())
    }

    @GetMapping("/reallocation")
    fun getReallocationRecommendation(): ResponseEntity<List<PriorityRankingResponse>> {
        val rankings = priorityRanker.rank().map { ranking ->
            PriorityRankingResponse(
                skuId = ranking.skuId,
                avgNetMargin = ranking.avgNetMargin,
                revenueVolume = ranking.revenueVolume,
                riskFactor = ranking.riskFactor,
                riskAdjustedReturn = ranking.riskAdjustedReturn
            )
        }
        return ResponseEntity.ok(rankings)
    }

    @GetMapping("/kill-recommendations")
    fun getKillRecommendations(): ResponseEntity<List<KillRecommendationResponse>> {
        val recommendations = killRecommendationRepository.findByConfirmedAtIsNull().map { rec ->
            KillRecommendationResponse(
                id = rec.id,
                skuId = rec.skuId,
                daysNegative = rec.daysNegative,
                avgNetMargin = rec.avgNetMargin,
                detectedAt = rec.detectedAt,
                confirmedAt = rec.confirmedAt
            )
        }
        return ResponseEntity.ok(recommendations)
    }

    @PostMapping("/kill-recommendations/{id}/confirm")
    fun confirmKillRecommendation(@PathVariable id: UUID): ResponseEntity<KillRecommendationResponse> {
        val recommendation = killRecommendationRepository.findById(id)
            .orElseThrow { NoSuchElementException("Kill recommendation not found: $id") }

        require(recommendation.confirmedAt == null) {
            "Kill recommendation $id already confirmed"
        }

        recommendation.confirmedAt = Instant.now()
        val saved = killRecommendationRepository.save(recommendation)

        return ResponseEntity.ok(
            KillRecommendationResponse(
                id = saved.id,
                skuId = saved.skuId,
                daysNegative = saved.daysNegative,
                avgNetMargin = saved.avgNetMargin,
                detectedAt = saved.detectedAt,
                confirmedAt = saved.confirmedAt
            )
        )
    }

    @GetMapping("/refund-alerts")
    fun getRefundAlerts(): ResponseEntity<RefundAlertResponse> {
        val alert = refundPatternAnalyzer.analyze()
        return ResponseEntity.ok(
            RefundAlertResponse(
                skuIds = alert.skuIds,
                portfolioAvgRefundRate = alert.portfolioAvgRefundRate,
                elevatedSkuCount = alert.elevatedSkuCount
            )
        )
    }

    private fun com.autoshipper.portfolio.domain.Experiment.toResponse() = ExperimentResponse(
        id = id,
        name = name,
        hypothesis = hypothesisDescription,
        sourceSignal = sourceSignal,
        estimatedMarginPerUnit = estimatedMarginPerUnit,
        estimatedMarginCurrency = estimatedMarginCurrency,
        validationWindowDays = validationWindowDays,
        status = status.name,
        launchedSkuId = launchedSkuId,
        createdAt = createdAt
    )
}
