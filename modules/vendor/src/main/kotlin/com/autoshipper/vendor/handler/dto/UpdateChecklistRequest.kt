package com.autoshipper.vendor.handler.dto

data class UpdateChecklistRequest(
    val slaConfirmed: Boolean? = null,
    val defectRateDocumented: Boolean? = null,
    val scalabilityConfirmed: Boolean? = null,
    val fulfillmentTimesConfirmed: Boolean? = null,
    val refundPolicyConfirmed: Boolean? = null
)
