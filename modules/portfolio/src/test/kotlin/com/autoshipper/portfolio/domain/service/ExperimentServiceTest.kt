package com.autoshipper.portfolio.domain.service

import com.autoshipper.portfolio.domain.Experiment
import com.autoshipper.portfolio.domain.ExperimentStatus
import com.autoshipper.portfolio.persistence.ExperimentRepository
import com.autoshipper.shared.identity.ExperimentId
import com.autoshipper.shared.identity.SkuId
import com.autoshipper.shared.money.Currency
import com.autoshipper.shared.money.Money
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness
import java.math.BigDecimal
import java.util.Optional
import java.util.UUID

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ExperimentServiceTest {

    @Mock
    private lateinit var experimentRepository: ExperimentRepository

    private lateinit var service: ExperimentService

    @BeforeEach
    fun setUp() {
        service = ExperimentService(experimentRepository)
        whenever(experimentRepository.save(any<Experiment>())).thenAnswer { it.arguments[0] }
    }

    @Test
    fun `create returns experiment in ACTIVE status`() {
        val margin = Money.of(BigDecimal("15"), Currency.USD)
        val experiment = service.create("Test Product", "Will people buy this?", "Google Trends", margin, 14)

        assertEquals("Test Product", experiment.name)
        assertEquals("Will people buy this?", experiment.hypothesisDescription)
        assertEquals("Google Trends", experiment.sourceSignal)
        assertEquals(BigDecimal("15.0000"), experiment.estimatedMarginPerUnit)
        assertEquals("USD", experiment.estimatedMarginCurrency)
        assertEquals(ExperimentStatus.ACTIVE, experiment.status)
        assertEquals(14, experiment.validationWindowDays)
        assertNull(experiment.launchedSkuId)
    }

    @Test
    fun `create with null margin and source signal`() {
        val experiment = service.create("Test Product", "Hypothesis", null, null, 30)

        assertNull(experiment.sourceSignal)
        assertNull(experiment.estimatedMarginPerUnit)
        assertNull(experiment.estimatedMarginCurrency)
    }

    @Test
    fun `markValidated transitions to VALIDATED and links SKU`() {
        val experimentId = ExperimentId.new()
        val skuId = SkuId.new()
        val experiment = Experiment(
            id = experimentId.value,
            name = "Test",
            hypothesisDescription = "Hyp",
            validationWindowDays = 7,
            status = ExperimentStatus.ACTIVE
        )

        whenever(experimentRepository.findById(experimentId.value)).thenReturn(Optional.of(experiment))

        val result = service.markValidated(experimentId, skuId)

        assertEquals(ExperimentStatus.VALIDATED, result.status)
        assertEquals(skuId.value, result.launchedSkuId)
    }

    @Test
    fun `markValidated throws when experiment not ACTIVE`() {
        val experimentId = ExperimentId.new()
        val experiment = Experiment(
            id = experimentId.value,
            name = "Test",
            hypothesisDescription = "Hyp",
            validationWindowDays = 7,
            status = ExperimentStatus.FAILED
        )

        whenever(experimentRepository.findById(experimentId.value)).thenReturn(Optional.of(experiment))

        assertThrows<IllegalArgumentException> {
            service.markValidated(experimentId, SkuId.new())
        }
    }

    @Test
    fun `markFailed transitions to FAILED`() {
        val experimentId = ExperimentId.new()
        val experiment = Experiment(
            id = experimentId.value,
            name = "Test",
            hypothesisDescription = "Hyp",
            validationWindowDays = 7,
            status = ExperimentStatus.ACTIVE
        )

        whenever(experimentRepository.findById(experimentId.value)).thenReturn(Optional.of(experiment))

        val result = service.markFailed(experimentId)

        assertEquals(ExperimentStatus.FAILED, result.status)
    }

    @Test
    fun `markFailed throws when experiment not ACTIVE`() {
        val experimentId = ExperimentId.new()
        val experiment = Experiment(
            id = experimentId.value,
            name = "Test",
            hypothesisDescription = "Hyp",
            validationWindowDays = 7,
            status = ExperimentStatus.VALIDATED
        )

        whenever(experimentRepository.findById(experimentId.value)).thenReturn(Optional.of(experiment))

        assertThrows<IllegalArgumentException> {
            service.markFailed(experimentId)
        }
    }
}
