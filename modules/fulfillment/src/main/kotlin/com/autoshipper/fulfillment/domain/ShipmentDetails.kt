package com.autoshipper.fulfillment.domain

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import java.time.Instant

@Embeddable
class ShipmentDetails(
    @Column(name = "tracking_number")
    var trackingNumber: String? = null,

    @Column(name = "carrier")
    var carrier: String? = null,

    @Column(name = "estimated_delivery")
    var estimatedDelivery: Instant? = null,

    @Column(name = "last_known_location")
    var lastKnownLocation: String? = null,

    @Column(name = "delay_detected", nullable = false)
    var delayDetected: Boolean = false
)
