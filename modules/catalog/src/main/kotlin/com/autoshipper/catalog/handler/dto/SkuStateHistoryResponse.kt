package com.autoshipper.catalog.handler.dto

data class SkuStateHistoryResponse(
    val fromState: String,
    val toState: String,
    val transitionedAt: String
)
