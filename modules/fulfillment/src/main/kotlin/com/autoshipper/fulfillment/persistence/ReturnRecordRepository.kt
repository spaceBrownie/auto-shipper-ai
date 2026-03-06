package com.autoshipper.fulfillment.persistence

import com.autoshipper.fulfillment.domain.ReturnRecord
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface ReturnRecordRepository : JpaRepository<ReturnRecord, UUID> {
    fun findByOrderId(orderId: UUID): List<ReturnRecord>
}
