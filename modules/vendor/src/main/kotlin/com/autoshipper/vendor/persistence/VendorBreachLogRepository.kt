package com.autoshipper.vendor.persistence

import com.autoshipper.vendor.domain.VendorBreachLog
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface VendorBreachLogRepository : JpaRepository<VendorBreachLog, UUID> {
    fun findByVendorId(vendorId: UUID): List<VendorBreachLog>
    fun countByVendorId(vendorId: UUID): Long
}
