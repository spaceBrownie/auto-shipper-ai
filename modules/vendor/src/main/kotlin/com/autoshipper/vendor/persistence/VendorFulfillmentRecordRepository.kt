package com.autoshipper.vendor.persistence

import com.autoshipper.vendor.domain.VendorFulfillmentRecord
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

@Repository
interface VendorFulfillmentRecordRepository : JpaRepository<VendorFulfillmentRecord, UUID> {

    @Query("SELECT COUNT(r) FROM VendorFulfillmentRecord r WHERE r.vendorId = :vendorId AND r.recordedAt >= :since")
    fun countByVendorIdAndRecordedAtAfter(@Param("vendorId") vendorId: UUID, @Param("since") since: Instant): Long

    @Query("SELECT COUNT(r) FROM VendorFulfillmentRecord r WHERE r.vendorId = :vendorId AND r.isViolation = true AND r.recordedAt >= :since")
    fun countViolationsByVendorIdAndRecordedAtAfter(@Param("vendorId") vendorId: UUID, @Param("since") since: Instant): Long
}
