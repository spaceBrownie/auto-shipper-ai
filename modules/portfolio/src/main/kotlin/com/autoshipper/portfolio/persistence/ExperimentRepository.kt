package com.autoshipper.portfolio.persistence

import com.autoshipper.portfolio.domain.Experiment
import com.autoshipper.portfolio.domain.ExperimentStatus
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface ExperimentRepository : JpaRepository<Experiment, UUID> {
    fun findByStatus(status: ExperimentStatus): List<Experiment>
    fun countByStatus(status: ExperimentStatus): Long
}
