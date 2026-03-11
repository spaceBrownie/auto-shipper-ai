package com.autoshipper.capital.domain.service

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.any
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.eq
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.LocalDate
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class MarginSweepJobTest {

    @Mock
    private lateinit var skuProcessor: MarginSweepSkuProcessor

    @Mock
    private lateinit var skuProvider: ActiveSkuProvider

    private lateinit var job: MarginSweepJob

    private val today = LocalDate.of(2026, 3, 10)

    @BeforeEach
    fun setUp() {
        job = MarginSweepJob(skuProcessor, skuProvider)
    }

    @Test
    fun `sweep delegates to processor for each active SKU`() {
        val sku1 = UUID.randomUUID()
        val sku2 = UUID.randomUUID()
        whenever(skuProvider.getActiveSkuIds()).thenReturn(listOf(sku1, sku2))

        job.sweep(today)

        verify(skuProcessor).process(sku1, today)
        verify(skuProcessor).process(sku2, today)
    }

    @Test
    fun `sweep continues processing remaining SKUs when one fails`() {
        val sku1 = UUID.randomUUID()
        val sku2 = UUID.randomUUID()
        val sku3 = UUID.randomUUID()
        whenever(skuProvider.getActiveSkuIds()).thenReturn(listOf(sku1, sku2, sku3))
        doThrow(RuntimeException("DB error")).whenever(skuProcessor).process(eq(sku2), any())

        job.sweep(today)

        verify(skuProcessor).process(sku1, today)
        verify(skuProcessor).process(sku2, today)
        verify(skuProcessor).process(sku3, today)
    }

    @Test
    fun `sweep with no active SKUs completes without error`() {
        whenever(skuProvider.getActiveSkuIds()).thenReturn(emptyList())

        job.sweep(today)

        verify(skuProcessor, never()).process(any(), any())
    }
}
