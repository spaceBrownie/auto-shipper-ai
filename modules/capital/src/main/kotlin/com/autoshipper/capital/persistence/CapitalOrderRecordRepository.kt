package com.autoshipper.capital.persistence

import com.autoshipper.capital.domain.CapitalOrderRecord
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
interface CapitalOrderRecordRepository : JpaRepository<CapitalOrderRecord, UUID> {

    fun findBySkuIdAndRecordedAtBetween(
        skuId: UUID,
        from: Instant,
        to: Instant
    ): List<CapitalOrderRecord>

    fun countBySkuIdAndRefundedTrueAndRecordedAtAfter(skuId: UUID, since: Instant): Long

    fun countBySkuIdAndChargebackedTrueAndRecordedAtAfter(skuId: UUID, since: Instant): Long

    fun countBySkuIdAndRecordedAtAfter(skuId: UUID, since: Instant): Long

    fun findByOrderId(orderId: UUID): CapitalOrderRecord?

    fun findAllByRecordedAtAfter(since: Instant): List<CapitalOrderRecord>
}
