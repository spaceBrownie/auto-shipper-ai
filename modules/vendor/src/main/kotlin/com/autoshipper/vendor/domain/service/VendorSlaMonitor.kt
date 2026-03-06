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

@Service
class VendorSlaMonitor(
    private val vendorRepository: VendorRepository,
    private val assignmentRepository: VendorSkuAssignmentRepository,
    private val breachLogRepository: VendorBreachLogRepository,
    private val eventPublisher: ApplicationEventPublisher
) {
    private val logger = LoggerFactory.getLogger(VendorSlaMonitor::class.java)

    companion object {
        val DEFAULT_BREACH_THRESHOLD = BigDecimal("10.00")
    }

    @Scheduled(fixedRate = 900_000)
    @Transactional
    fun runCheck() {
        runCheck(DEFAULT_BREACH_THRESHOLD)
    }

    @Transactional
    fun runCheck(breachThreshold: BigDecimal) {
        val activeVendors = vendorRepository.findByStatus(VendorStatus.ACTIVE.name)

        for (vendor in activeVendors) {
            val breachCount = breachLogRepository.countByVendorId(vendor.id)
            val breachRate = BigDecimal(breachCount)

            if (breachRate >= breachThreshold) {
                logger.warn(
                    "Vendor {} ({}) exceeded SLA breach threshold: {} >= {}",
                    vendor.id, vendor.name, breachRate, breachThreshold
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
                            breachRate = Percentage.of(breachRate.coerceAtMost(BigDecimal(100)))
                        )
                    )
                }
            }
        }
    }
}
