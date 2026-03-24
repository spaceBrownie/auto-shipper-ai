package com.autoshipper.fulfillment.domain.service

import com.autoshipper.fulfillment.persistence.WebhookEventRepository
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.InjectMocks
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.argThat
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.time.Instant
import java.time.temporal.ChronoUnit

@ExtendWith(MockitoExtension::class)
class WebhookEventCleanupJobTest {

    @Mock
    lateinit var webhookEventRepository: WebhookEventRepository

    @InjectMocks
    lateinit var cleanupJob: WebhookEventCleanupJob

    @Test
    fun `purgeExpiredEvents deletes events older than 24 hours`() {
        val before = Instant.now().minus(24, ChronoUnit.HOURS)
        whenever(webhookEventRepository.deleteByProcessedAtBefore(argThat<Instant> {
            // The cutoff should be approximately 24 hours ago (within a few seconds tolerance)
            this.isAfter(before.minusSeconds(5)) && this.isBefore(before.plusSeconds(5))
        })).thenReturn(7)

        cleanupJob.purgeExpiredEvents()

        verify(webhookEventRepository).deleteByProcessedAtBefore(argThat<Instant> {
            this.isAfter(before.minusSeconds(5)) && this.isBefore(before.plusSeconds(5))
        })
    }

    @Test
    fun `purgeExpiredEvents handles zero deleted events gracefully`() {
        whenever(webhookEventRepository.deleteByProcessedAtBefore(argThat<Instant> { true }))
            .thenReturn(0)

        cleanupJob.purgeExpiredEvents()

        verify(webhookEventRepository).deleteByProcessedAtBefore(argThat<Instant> { true })
    }
}
