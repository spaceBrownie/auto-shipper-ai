package com.autoshipper.fulfillment.proxy.carrier

import java.time.Instant

interface CarrierTrackingProvider {
    val carrierName: String
    fun getTrackingStatus(trackingNumber: String): TrackingStatus
}

data class TrackingStatus(
    val currentLocation: String?,
    val estimatedDelivery: Instant?,
    val delivered: Boolean,
    val delayed: Boolean
)
