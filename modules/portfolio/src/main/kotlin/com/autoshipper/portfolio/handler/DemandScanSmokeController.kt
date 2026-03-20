package com.autoshipper.portfolio.handler

import com.autoshipper.portfolio.domain.SmokeTestReport
import com.autoshipper.portfolio.domain.service.DemandScanSmokeService
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

@RestController
@RequestMapping("/api/portfolio/demand-scan")
@ConditionalOnProperty(name = ["demand-scan.smoke-test-enabled"], havingValue = "true")
class DemandScanSmokeController(
    private val smokeService: DemandScanSmokeService
) {

    private val logger = LoggerFactory.getLogger(DemandScanSmokeController::class.java)
    private val lastInvocation = AtomicReference<Instant>(Instant.MIN)

    @PostMapping("/smoke-test")
    fun runSmokeTest(): ResponseEntity<Any> {
        val now = Instant.now()
        val last = lastInvocation.get()

        if (java.time.Duration.between(last, now).seconds < 60) {
            return ResponseEntity.status(429)
                .body(mapOf("error" to "Rate limited. Please wait 60 seconds between smoke test invocations."))
        }

        if (!lastInvocation.compareAndSet(last, now)) {
            return ResponseEntity.status(429)
                .body(mapOf("error" to "Rate limited. Concurrent invocation detected."))
        }

        logger.info("Smoke test endpoint invoked")
        val report: SmokeTestReport = smokeService.runSmokeTest()
        return ResponseEntity.ok(report)
    }
}
