package com.autoshipper.portfolio.domain.service

import com.autoshipper.portfolio.config.PortfolioConfig
import com.autoshipper.portfolio.domain.KillRecommendation
import com.autoshipper.portfolio.persistence.KillRecommendationRepository
import com.autoshipper.shared.events.KillWindowBreached
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.ArgumentCaptor
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.junit.jupiter.MockitoSettings
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.mockito.quality.Strictness
import org.springframework.context.ApplicationEventPublisher
import java.math.BigDecimal
import java.util.UUID

@ExtendWith(MockitoExtension::class)
@MockitoSettings(strictness = Strictness.LENIENT)
class KillWindowMonitorTest {

    @Mock
    private lateinit var marginSignalProvider: MarginSignalProvider

    @Mock
    private lateinit var killRecommendationRepository: KillRecommendationRepository

    @Mock
    private lateinit var eventPublisher: ApplicationEventPublisher

    private lateinit var monitor: KillWindowMonitor

    private val skuId = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        whenever(killRecommendationRepository.save(any<KillRecommendation>())).thenAnswer { it.arguments[0] }
    }

    @Test
    fun `flag OFF - SKU with 31 day negative signal creates recommendation but no event`() {
        val config = PortfolioConfig(killWindowDays = 30, autoTerminateEnabled = false)
        monitor = KillWindowMonitor(config, marginSignalProvider, killRecommendationRepository, eventPublisher)

        whenever(marginSignalProvider.getSkusWithNegativeMarginSince(30)).thenReturn(listOf(skuId))
        whenever(marginSignalProvider.getAverageNetMargin(skuId)).thenReturn(BigDecimal("-5.00"))

        monitor.scan()

        val captor = ArgumentCaptor.forClass(KillRecommendation::class.java)
        verify(killRecommendationRepository).save(captor.capture())
        assertEquals(skuId, captor.value.skuId)
        assertEquals(30, captor.value.daysNegative)
        assertEquals(BigDecimal("-5.00"), captor.value.avgNetMargin)
        assertNull(captor.value.confirmedAt)

        verify(eventPublisher, never()).publishEvent(any())
    }

    @Test
    fun `flag OFF - SKU with 29 day signal does not create recommendation`() {
        val config = PortfolioConfig(killWindowDays = 30, autoTerminateEnabled = false)
        monitor = KillWindowMonitor(config, marginSignalProvider, killRecommendationRepository, eventPublisher)

        whenever(marginSignalProvider.getSkusWithNegativeMarginSince(30)).thenReturn(emptyList())

        monitor.scan()

        verify(killRecommendationRepository, never()).save(any<KillRecommendation>())
        verify(eventPublisher, never()).publishEvent(any())
    }

    @Test
    fun `flag ON - qualifying SKU creates recommendation AND publishes KillWindowBreached`() {
        val config = PortfolioConfig(killWindowDays = 30, autoTerminateEnabled = true)
        monitor = KillWindowMonitor(config, marginSignalProvider, killRecommendationRepository, eventPublisher)

        whenever(marginSignalProvider.getSkusWithNegativeMarginSince(30)).thenReturn(listOf(skuId))
        whenever(marginSignalProvider.getAverageNetMargin(skuId)).thenReturn(BigDecimal("-8.00"))

        monitor.scan()

        verify(killRecommendationRepository).save(any<KillRecommendation>())

        val eventCaptor = ArgumentCaptor.forClass(Any::class.java)
        verify(eventPublisher).publishEvent(eventCaptor.capture())

        val event = eventCaptor.value as KillWindowBreached
        assertEquals(skuId, event.skuId.value)
        assertEquals(30, event.daysNegative)
        assertEquals(BigDecimal("-8.00"), event.avgNetMargin)
    }

    @Test
    fun `flag ON - no qualifying SKUs means no recommendations or events`() {
        val config = PortfolioConfig(killWindowDays = 30, autoTerminateEnabled = true)
        monitor = KillWindowMonitor(config, marginSignalProvider, killRecommendationRepository, eventPublisher)

        whenever(marginSignalProvider.getSkusWithNegativeMarginSince(30)).thenReturn(emptyList())

        monitor.scan()

        verify(killRecommendationRepository, never()).save(any<KillRecommendation>())
        verify(eventPublisher, never()).publishEvent(any())
    }

    @Test
    fun `multiple qualifying SKUs each get a recommendation`() {
        val config = PortfolioConfig(killWindowDays = 30, autoTerminateEnabled = false)
        monitor = KillWindowMonitor(config, marginSignalProvider, killRecommendationRepository, eventPublisher)

        val sku1 = UUID.randomUUID()
        val sku2 = UUID.randomUUID()
        whenever(marginSignalProvider.getSkusWithNegativeMarginSince(30)).thenReturn(listOf(sku1, sku2))
        whenever(marginSignalProvider.getAverageNetMargin(sku1)).thenReturn(BigDecimal("-3.00"))
        whenever(marginSignalProvider.getAverageNetMargin(sku2)).thenReturn(BigDecimal("-10.00"))

        monitor.scan()

        verify(killRecommendationRepository, org.mockito.kotlin.times(2)).save(any<KillRecommendation>())
        verify(eventPublisher, never()).publishEvent(any())
    }
}
