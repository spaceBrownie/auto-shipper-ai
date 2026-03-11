package com.autoshipper.capital.handler.dto

data class SkuPnlResponse(
    val skuId: String,
    val from: String,
    val to: String,
    val totalRevenueAmount: String,
    val totalRevenueCurrency: String,
    val totalCostAmount: String,
    val totalCostCurrency: String,
    val averageGrossMarginPercent: String,
    val averageNetMarginPercent: String,
    val snapshotCount: Int
)
