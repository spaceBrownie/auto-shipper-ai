package com.autoshipper.vendor.persistence

import com.autoshipper.vendor.domain.Vendor
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface VendorRepository : JpaRepository<Vendor, UUID> {
    fun findByStatus(status: String): List<Vendor>
}
