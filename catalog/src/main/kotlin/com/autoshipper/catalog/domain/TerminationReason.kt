package com.autoshipper.catalog.domain

enum class TerminationReason {
    STRESS_TEST_FAILED,
    MARGIN_BELOW_FLOOR,
    REFUND_RATE_EXCEEDED,
    CHARGEBACK_RATE_EXCEEDED,
    VENDOR_SLA_BREACH,
    COMPLIANCE_VIOLATION,
    MANUAL_OVERRIDE
}
