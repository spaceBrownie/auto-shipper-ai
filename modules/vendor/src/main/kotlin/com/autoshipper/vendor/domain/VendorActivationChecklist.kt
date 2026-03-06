package com.autoshipper.vendor.domain

import jakarta.persistence.Column
import jakarta.persistence.Embeddable

@Embeddable
class VendorActivationChecklist(
    @Column(name = "sla_confirmed", nullable = false)
    var slaConfirmed: Boolean = false,

    @Column(name = "defect_rate_documented", nullable = false)
    var defectRateDocumented: Boolean = false,

    @Column(name = "scalability_confirmed", nullable = false)
    var scalabilityConfirmed: Boolean = false,

    @Column(name = "fulfillment_times_confirmed", nullable = false)
    var fulfillmentTimesConfirmed: Boolean = false,

    @Column(name = "refund_policy_confirmed", nullable = false)
    var refundPolicyConfirmed: Boolean = false
) {
    fun isComplete(): Boolean =
        slaConfirmed &&
            defectRateDocumented &&
            scalabilityConfirmed &&
            fulfillmentTimesConfirmed &&
            refundPolicyConfirmed
}
