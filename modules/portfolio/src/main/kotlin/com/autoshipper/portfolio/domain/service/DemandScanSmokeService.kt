package com.autoshipper.portfolio.domain.service

import com.autoshipper.portfolio.domain.DemandSignalProvider
import com.autoshipper.portfolio.domain.SmokeStatus
import com.autoshipper.portfolio.domain.SmokeTestReport
import com.autoshipper.portfolio.domain.SourceSmokeResult
import com.fasterxml.jackson.core.JsonProcessingException
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import org.xml.sax.SAXException
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

@Service
class DemandScanSmokeService(
    private val providers: List<DemandSignalProvider>,
    @Value("\${demand-scan.smoke-test-timeout-seconds:10}") private val timeoutSeconds: Long
) {

    private val logger = LoggerFactory.getLogger(DemandScanSmokeService::class.java)

    fun runSmokeTest(): SmokeTestReport {
        logger.info("Running demand scan smoke test across {} providers", providers.size)

        val executor = Executors.newFixedThreadPool(providers.size.coerceAtLeast(1))
        try {
            val futures = providers.map { provider ->
                CompletableFuture.supplyAsync({
                    probeProvider(provider)
                }, executor)
            }

            val results = futures.map { future ->
                try {
                    future.get(timeoutSeconds, TimeUnit.SECONDS)
                } catch (e: TimeoutException) {
                    SourceSmokeResult(
                        source = "UNKNOWN",
                        status = SmokeStatus.TIMEOUT,
                        responseTimeMs = timeoutSeconds * 1000,
                        sampleCount = 0
                    )
                } catch (e: Exception) {
                    SourceSmokeResult(
                        source = "UNKNOWN",
                        status = SmokeStatus.UNKNOWN_ERROR,
                        responseTimeMs = 0,
                        sampleCount = 0
                    )
                }
            }

            return SmokeTestReport(results = results, timestamp = Instant.now())
        } finally {
            executor.shutdownNow()
        }
    }

    private fun probeProvider(provider: DemandSignalProvider): SourceSmokeResult {
        val startTime = System.currentTimeMillis()
        return try {
            val candidates = provider.fetch()
            val elapsed = System.currentTimeMillis() - startTime
            SourceSmokeResult(
                source = provider.sourceType(),
                status = SmokeStatus.OK,
                responseTimeMs = elapsed,
                sampleCount = candidates.size
            )
        } catch (e: Exception) {
            val elapsed = System.currentTimeMillis() - startTime
            val status = classifyException(e)
            logger.warn("Smoke test for {} failed with {}: {}", provider.sourceType(), status, e.message)
            SourceSmokeResult(
                source = provider.sourceType(),
                status = status,
                responseTimeMs = elapsed,
                sampleCount = 0
            )
        }
    }

    private fun classifyException(e: Exception): SmokeStatus {
        val cause = e.cause ?: e
        return when {
            cause is TimeoutException -> SmokeStatus.TIMEOUT
            cause is JsonProcessingException || cause is SAXException -> SmokeStatus.PARSE_ERROR
            isAuthFailure(cause) -> SmokeStatus.AUTH_FAILURE
            isRateLimited(cause) -> SmokeStatus.RATE_LIMITED
            else -> SmokeStatus.UNKNOWN_ERROR
        }
    }

    private fun isAuthFailure(e: Throwable): Boolean {
        val message = e.message ?: return false
        return message.contains("401") || message.contains("403") ||
            message.contains("Unauthorized") || message.contains("Forbidden")
    }

    private fun isRateLimited(e: Throwable): Boolean {
        val message = e.message ?: return false
        return message.contains("429") || message.contains("Too Many Requests") ||
            message.contains("rate limit", ignoreCase = true)
    }
}
