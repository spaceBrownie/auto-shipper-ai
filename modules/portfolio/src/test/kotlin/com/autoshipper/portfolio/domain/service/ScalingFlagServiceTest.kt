package com.autoshipper.portfolio.domain.service

import com.autoshipper.portfolio.domain.ScalingFlag
import com.autoshipper.portfolio.persistence.ScalingFlagRepository
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness
import java.math.BigDecimal
import java.util.UUID

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ScalingFlagServiceTest {

    @Mock
    private lateinit var marginSignalProvider: MarginSignalProvider

    @Mock
    private lateinit var scalingFlagRepository: ScalingFlagRepository

    private lateinit var service: ScalingFlagService

    private val skuId = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        service = ScalingFlagService(marginSignalProvider, scalingFlagRepository)
        whenever(scalingFlagRepository.save(any<ScalingFlag>())).thenAnswer { it.arguments[0] }
    }

    @Test
    fun `SKU with 3 consecutive high-margin snapshots gets flagged`() {
        whenever(marginSignalProvider.getActiveSkuIds()).thenReturn(listOf(skuId))
        whenever(scalingFlagRepository.findBySkuIdAndResolvedAtIsNull(skuId)).thenReturn(null)
        whenever(marginSignalProvider.getConsecutiveHighMarginDays(skuId, BigDecimal("50"))).thenReturn(3)

        service.scan()

        verify(scalingFlagRepository).save(any<ScalingFlag>())
    }

    @Test
    fun `SKU with 2 consecutive high-margin snapshots does not get flagged`() {
        whenever(marginSignalProvider.getActiveSkuIds()).thenReturn(listOf(skuId))
        whenever(scalingFlagRepository.findBySkuIdAndResolvedAtIsNull(skuId)).thenReturn(null)
        whenever(marginSignalProvider.getConsecutiveHighMarginDays(skuId, BigDecimal("50"))).thenReturn(2)

        service.scan()

        verify(scalingFlagRepository, never()).save(any<ScalingFlag>())
    }

    @Test
    fun `SKU already flagged is skipped`() {
        whenever(marginSignalProvider.getActiveSkuIds()).thenReturn(listOf(skuId))
        whenever(scalingFlagRepository.findBySkuIdAndResolvedAtIsNull(skuId)).thenReturn(ScalingFlag(skuId = skuId))

        service.scan()

        verify(scalingFlagRepository, never()).save(any<ScalingFlag>())
    }

    @Test
    fun `SKU with 5 consecutive high-margin snapshots gets flagged`() {
        whenever(marginSignalProvider.getActiveSkuIds()).thenReturn(listOf(skuId))
        whenever(scalingFlagRepository.findBySkuIdAndResolvedAtIsNull(skuId)).thenReturn(null)
        whenever(marginSignalProvider.getConsecutiveHighMarginDays(skuId, BigDecimal("50"))).thenReturn(5)

        service.scan()

        verify(scalingFlagRepository).save(any<ScalingFlag>())
    }
}
