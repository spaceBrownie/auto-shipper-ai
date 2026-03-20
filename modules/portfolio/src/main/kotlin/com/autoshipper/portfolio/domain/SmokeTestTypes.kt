package com.autoshipper.portfolio.domain

import java.time.Instant

enum class SmokeStatus {
    OK, AUTH_FAILURE, TIMEOUT, RATE_LIMITED, PARSE_ERROR, UNKNOWN_ERROR
}

data class SourceSmokeResult(
    val source: String,
    val status: SmokeStatus,
    val responseTimeMs: Long,
    val sampleCount: Int
)

data class SmokeTestReport(
    val results: List<SourceSmokeResult>,
    val timestamp: Instant
)
