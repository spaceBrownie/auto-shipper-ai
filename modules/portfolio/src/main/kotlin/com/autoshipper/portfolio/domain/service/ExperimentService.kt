package com.autoshipper.portfolio.domain.service

import com.autoshipper.portfolio.domain.Experiment
import com.autoshipper.portfolio.domain.ExperimentStatus
import com.autoshipper.portfolio.persistence.ExperimentRepository
import com.autoshipper.shared.identity.ExperimentId
import com.autoshipper.shared.identity.SkuId
import com.autoshipper.shared.money.Money
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional
class ExperimentService(
    private val experimentRepository: ExperimentRepository
) {
    private val logger = LoggerFactory.getLogger(ExperimentService::class.java)

    fun create(
        name: String,
        hypothesis: String,
        sourceSignal: String?,
        estimatedMarginPerUnit: Money?,
        windowDays: Int
    ): Experiment {
        val experiment = Experiment(
            name = name,
            hypothesisDescription = hypothesis,
            sourceSignal = sourceSignal,
            estimatedMarginPerUnit = estimatedMarginPerUnit?.normalizedAmount,
            estimatedMarginCurrency = estimatedMarginPerUnit?.currency?.name,
            validationWindowDays = windowDays,
            status = ExperimentStatus.ACTIVE
        )
        val saved = experimentRepository.save(experiment)
        logger.info("Created experiment {} ({})", saved.id, saved.name)
        return saved
    }

    fun markValidated(experimentId: ExperimentId, skuId: SkuId): Experiment {
        val experiment = experimentRepository.findById(experimentId.value)
            .orElseThrow { NoSuchElementException("Experiment not found: $experimentId") }

        require(experiment.status == ExperimentStatus.ACTIVE) {
            "Cannot validate experiment in status ${experiment.status}"
        }

        experiment.status = ExperimentStatus.VALIDATED
        experiment.launchedSkuId = skuId.value
        val saved = experimentRepository.save(experiment)
        logger.info("Experiment {} validated, linked to SKU {}", experimentId, skuId)
        return saved
    }

    fun markFailed(experimentId: ExperimentId): Experiment {
        val experiment = experimentRepository.findById(experimentId.value)
            .orElseThrow { NoSuchElementException("Experiment not found: $experimentId") }

        require(experiment.status == ExperimentStatus.ACTIVE) {
            "Cannot fail experiment in status ${experiment.status}"
        }

        experiment.status = ExperimentStatus.FAILED
        val saved = experimentRepository.save(experiment)
        logger.info("Experiment {} marked as failed", experimentId)
        return saved
    }

    @Transactional(readOnly = true)
    fun findAll(): List<Experiment> = experimentRepository.findAll()

    @Transactional(readOnly = true)
    fun findById(experimentId: ExperimentId): Experiment =
        experimentRepository.findById(experimentId.value)
            .orElseThrow { NoSuchElementException("Experiment not found: $experimentId") }

    @Transactional(readOnly = true)
    fun findByStatus(status: ExperimentStatus): List<Experiment> =
        experimentRepository.findByStatus(status)

    @Transactional(readOnly = true)
    fun countByStatus(status: ExperimentStatus): Long =
        experimentRepository.countByStatus(status)
}
