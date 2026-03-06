package com.autoshipper.vendor.domain.service

import com.autoshipper.shared.events.VendorSlaBreached
import com.autoshipper.shared.identity.SkuId
import com.autoshipper.shared.money.Percentage
import com.autoshipper.vendor.domain.VendorBreachLog
import com.autoshipper.vendor.domain.VendorStatus
import com.autoshipper.vendor.persistence.VendorBreachLogRepository
import com.autoshipper.vendor.persistence.VendorRepository
import com.autoshipper.vendor.persistence.VendorSkuAssignmentRepository
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant

@Service
class VendorSlaMonitor(
    private val vendorRepository: VendorRepository,
    private val assignmentRepository: VendorSkuAssignmentRepository,
    private val breachLogRepository: VendorBreachLogRepository,
    private val fulfillmentDataProvider: VendorFulfillmentDataProvider,
    private val eventPublisher: ApplicationEventPublisher
) {
    private val logger = LoggerFactory.getLogger(VendorSlaMonitor::class.java)

    companion object {
        val DEFAULT_BREACH_THRESHOLD = BigDecimal("10.00")
        val ROLLING_WINDOW: Duration = Duration.ofDays(30)
    }

    @Scheduled(fixedRate = 900_000)
    @Transactional
    fun runCheck() {
        runCheck(DEFAULT_BREACH_THRESHOLD)
    }

    @Transactional
    fun runCheck(breachThreshold: BigDecimal) {
        val activeVendors = vendorRepository.findByStatus(VendorStatus.ACTIVE.name)
        val since = Instant.now().minus(ROLLING_WINDOW)

        for (vendor in activeVendors) {
            val totalFulfillments = fulfillmentDataProvider.countFulfillmentsSince(vendor.id, since)
            if (totalFulfillments == 0L) continue

            val violations = fulfillmentDataProvider.countViolationsSince(vendor.id, since)

            val breachRate = BigDecimal(violations)
                .multiply(BigDecimal(100))
                .divide(BigDecimal(totalFulfillments), 2, RoundingMode.HALF_UP)

            if (breachRate >= breachThreshold) {
                logger.warn(
                    "Vendor {} ({}) exceeded SLA breach threshold: {}% >= {}% ({} violations / {} fulfillments in 30d)",
                    vendor.id, vendor.name, breachRate, breachThreshold, violations, totalFulfillments
                )

                vendor.suspend()
                vendorRepository.save(vendor)

                val assignments = assignmentRepository.findByVendorIdAndActiveTrue(vendor.id)
                val affectedSkuIds = assignments.map { SkuId(it.skuId) }

                breachLogRepository.save(
                    VendorBreachLog(
                        vendorId = vendor.id,
                        breachRate = breachRate,
                        threshold = breachThreshold
                    )
                )

                if (affectedSkuIds.isNotEmpty()) {
                    eventPublisher.publishEvent(
                        VendorSlaBreached(
                            vendorId = vendor.vendorId(),
                            skuIds = affectedSkuIds,
                            breachRate = Percentage.of(breachRate)
                        )
                    )
                }
            }
        }
    }
}
