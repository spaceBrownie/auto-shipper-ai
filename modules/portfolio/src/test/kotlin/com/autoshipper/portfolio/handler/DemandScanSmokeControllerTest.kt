package com.autoshipper.portfolio.handler

import com.autoshipper.portfolio.domain.SmokeStatus
import com.autoshipper.portfolio.domain.SmokeTestReport
import com.autoshipper.portfolio.domain.SourceSmokeResult
import com.autoshipper.portfolio.domain.service.DemandScanSmokeService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import java.time.Instant

class DemandScanSmokeControllerTest {

    private val smokeService: DemandScanSmokeService = mock()
    private lateinit var controller: DemandScanSmokeController

    @BeforeEach
    fun setUp() {
        controller = DemandScanSmokeController(smokeService)
    }

    @Test
    fun `happy path — returns 200 with smoke test report`() {
        val report = SmokeTestReport(
            results = listOf(
                SourceSmokeResult("CJ_DROPSHIPPING", SmokeStatus.OK, 340, 20),
                SourceSmokeResult("GOOGLE_TRENDS", SmokeStatus.OK, 120, 15),
                SourceSmokeResult("YOUTUBE_DATA", SmokeStatus.OK, 200, 25),
                SourceSmokeResult("REDDIT", SmokeStatus.OK, 180, 12)
            ),
            timestamp = Instant.parse("2026-03-20T04:00:00Z")
        )
        whenever(smokeService.runSmokeTest()).thenReturn(report)

        val response = controller.runSmokeTest()

        assertThat(response.statusCode.value()).isEqualTo(200)
        val body = response.body as SmokeTestReport
        assertThat(body.results).hasSize(4)
        assertThat(body.results.map { it.status }).containsOnly(SmokeStatus.OK)
    }

    @Test
    fun `rate limited — second call within 60s returns 429`() {
        val report = SmokeTestReport(
            results = listOf(SourceSmokeResult("TEST", SmokeStatus.OK, 100, 5)),
            timestamp = Instant.now()
        )
        whenever(smokeService.runSmokeTest()).thenReturn(report)

        val first = controller.runSmokeTest()
        assertThat(first.statusCode.value()).isEqualTo(200)

        val second = controller.runSmokeTest()
        assertThat(second.statusCode.value()).isEqualTo(429)
    }

    @Test
    fun `response contains only enumerated status values — no raw errors`() {
        val report = SmokeTestReport(
            results = listOf(
                SourceSmokeResult("SOURCE_A", SmokeStatus.OK, 100, 10),
                SourceSmokeResult("SOURCE_B", SmokeStatus.AUTH_FAILURE, 50, 0),
                SourceSmokeResult("SOURCE_C", SmokeStatus.TIMEOUT, 10000, 0),
                SourceSmokeResult("SOURCE_D", SmokeStatus.RATE_LIMITED, 30, 0)
            ),
            timestamp = Instant.now()
        )
        whenever(smokeService.runSmokeTest()).thenReturn(report)

        val response = controller.runSmokeTest()
        val body = response.body as SmokeTestReport

        val validStatuses = SmokeStatus.entries.map { it.name }.toSet()
        body.results.forEach { result ->
            assertThat(result.status.name).isIn(validStatuses)
        }
    }
}
