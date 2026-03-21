package com.autoshipper.portfolio.domain.service

import com.autoshipper.portfolio.domain.DemandSignalProvider
import com.autoshipper.portfolio.domain.RawCandidate
import com.autoshipper.portfolio.domain.SmokeStatus
import com.fasterxml.jackson.core.JsonParseException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.web.client.HttpClientErrorException
import org.springframework.http.HttpStatus
import org.xml.sax.SAXParseException

class DemandScanSmokeServiceTest {

    private fun stubProvider(name: String, behavior: () -> List<RawCandidate>): DemandSignalProvider {
        return object : DemandSignalProvider {
            override fun sourceType(): String = name
            override fun fetch(): List<RawCandidate> = behavior()
        }
    }

    private fun dummyCandidate(name: String = "Test Product"): RawCandidate = RawCandidate(
        productName = name,
        category = "Test",
        description = "test",
        sourceType = "TEST",
        supplierUnitCost = null,
        estimatedSellingPrice = null,
        demandSignals = mapOf("key" to "value")
    )

    @Test
    fun `all providers OK — returns OK status with correct sample counts`() {
        val providers = listOf(
            stubProvider("SOURCE_A") { listOf(dummyCandidate(), dummyCandidate(), dummyCandidate()) },
            stubProvider("SOURCE_B") { listOf(dummyCandidate()) }
        )
        val service = DemandScanSmokeService(providers, 10)

        val report = service.runSmokeTest()

        assertThat(report.results).hasSize(2)
        val resultA = report.results.first { it.source == "SOURCE_A" }
        assertThat(resultA.status).isEqualTo(SmokeStatus.OK)
        assertThat(resultA.sampleCount).isEqualTo(3)
        val resultB = report.results.first { it.source == "SOURCE_B" }
        assertThat(resultB.status).isEqualTo(SmokeStatus.OK)
        assertThat(resultB.sampleCount).isEqualTo(1)
        assertThat(report.timestamp).isNotNull()
    }

    @Test
    fun `provider timeout — returns TIMEOUT status`() {
        val providers = listOf(
            stubProvider("SLOW_SOURCE") {
                Thread.sleep(15_000) // exceeds 1s timeout
                listOf(dummyCandidate())
            }
        )
        val service = DemandScanSmokeService(providers, 1) // 1 second timeout

        val report = service.runSmokeTest()

        assertThat(report.results).hasSize(1)
        assertThat(report.results[0].status).isEqualTo(SmokeStatus.TIMEOUT)
        assertThat(report.results[0].sampleCount).isEqualTo(0)
    }

    @Test
    fun `provider auth failure (401) — returns AUTH_FAILURE status`() {
        val providers = listOf(
            stubProvider("AUTH_FAIL_SOURCE") {
                throw HttpClientErrorException(HttpStatus.UNAUTHORIZED, "401 Unauthorized")
            }
        )
        val service = DemandScanSmokeService(providers, 10)

        val report = service.runSmokeTest()

        assertThat(report.results).hasSize(1)
        assertThat(report.results[0].source).isEqualTo("AUTH_FAIL_SOURCE")
        assertThat(report.results[0].status).isEqualTo(SmokeStatus.AUTH_FAILURE)
        assertThat(report.results[0].sampleCount).isEqualTo(0)
    }

    @Test
    fun `provider rate limited (429) — returns RATE_LIMITED status`() {
        val providers = listOf(
            stubProvider("RATE_LIMITED_SOURCE") {
                throw HttpClientErrorException(HttpStatus.TOO_MANY_REQUESTS, "429 Too Many Requests")
            }
        )
        val service = DemandScanSmokeService(providers, 10)

        val report = service.runSmokeTest()

        assertThat(report.results).hasSize(1)
        assertThat(report.results[0].source).isEqualTo("RATE_LIMITED_SOURCE")
        assertThat(report.results[0].status).isEqualTo(SmokeStatus.RATE_LIMITED)
        assertThat(report.results[0].sampleCount).isEqualTo(0)
    }

    @Test
    fun `provider parse error (JSON) — returns PARSE_ERROR status`() {
        val providers = listOf(
            stubProvider("PARSE_FAIL_SOURCE") {
                throw JsonParseException(null, "Unexpected character")
            }
        )
        val service = DemandScanSmokeService(providers, 10)

        val report = service.runSmokeTest()

        assertThat(report.results).hasSize(1)
        assertThat(report.results[0].source).isEqualTo("PARSE_FAIL_SOURCE")
        assertThat(report.results[0].status).isEqualTo(SmokeStatus.PARSE_ERROR)
        assertThat(report.results[0].sampleCount).isEqualTo(0)
    }

    @Test
    fun `provider parse error (XML) — returns PARSE_ERROR status`() {
        val providers = listOf(
            stubProvider("XML_FAIL_SOURCE") {
                throw SAXParseException("Malformed XML", null)
            }
        )
        val service = DemandScanSmokeService(providers, 10)

        val report = service.runSmokeTest()

        assertThat(report.results).hasSize(1)
        assertThat(report.results[0].source).isEqualTo("XML_FAIL_SOURCE")
        assertThat(report.results[0].status).isEqualTo(SmokeStatus.PARSE_ERROR)
    }

    @Test
    fun `unknown error — returns UNKNOWN_ERROR status`() {
        val providers = listOf(
            stubProvider("BROKEN_SOURCE") {
                throw RuntimeException("Something unexpected")
            }
        )
        val service = DemandScanSmokeService(providers, 10)

        val report = service.runSmokeTest()

        assertThat(report.results).hasSize(1)
        assertThat(report.results[0].source).isEqualTo("BROKEN_SOURCE")
        assertThat(report.results[0].status).isEqualTo(SmokeStatus.UNKNOWN_ERROR)
        assertThat(report.results[0].sampleCount).isEqualTo(0)
    }

    @Test
    fun `mixed results — each provider gets correct status independently`() {
        val providers = listOf(
            stubProvider("GOOD_SOURCE") { listOf(dummyCandidate(), dummyCandidate()) },
            stubProvider("BAD_AUTH_SOURCE") {
                throw HttpClientErrorException(HttpStatus.UNAUTHORIZED)
            },
            stubProvider("BROKEN_SOURCE") {
                throw RuntimeException("Unexpected failure")
            }
        )
        val service = DemandScanSmokeService(providers, 10)

        val report = service.runSmokeTest()

        assertThat(report.results).hasSize(3)

        val good = report.results.first { it.source == "GOOD_SOURCE" }
        assertThat(good.status).isEqualTo(SmokeStatus.OK)
        assertThat(good.sampleCount).isEqualTo(2)

        val badAuth = report.results.first { it.source == "BAD_AUTH_SOURCE" }
        assertThat(badAuth.status).isEqualTo(SmokeStatus.AUTH_FAILURE)

        val broken = report.results.first { it.source == "BROKEN_SOURCE" }
        assertThat(broken.status).isEqualTo(SmokeStatus.UNKNOWN_ERROR)
    }
}
