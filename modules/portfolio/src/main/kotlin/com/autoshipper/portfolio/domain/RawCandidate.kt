package com.autoshipper.portfolio.domain

import com.autoshipper.shared.money.Money

data class RawCandidate(
    val productName: String,
    val category: String,
    val description: String,
    val sourceType: String,
    val supplierUnitCost: Money?,
    val estimatedSellingPrice: Money?,
    val demandSignals: Map<String, String>
)
