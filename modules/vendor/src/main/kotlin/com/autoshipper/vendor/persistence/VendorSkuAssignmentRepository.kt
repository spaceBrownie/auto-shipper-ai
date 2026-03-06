package com.autoshipper.vendor.persistence

import com.autoshipper.vendor.domain.VendorSkuAssignment
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface VendorSkuAssignmentRepository : JpaRepository<VendorSkuAssignment, UUID> {
    fun findByVendorIdAndActiveTrue(vendorId: UUID): List<VendorSkuAssignment>
    fun findBySkuIdAndActiveTrue(skuId: UUID): List<VendorSkuAssignment>
}
