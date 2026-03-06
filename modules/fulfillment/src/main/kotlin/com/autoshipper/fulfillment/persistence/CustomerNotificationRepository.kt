package com.autoshipper.fulfillment.persistence

import com.autoshipper.fulfillment.domain.CustomerNotification
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository
import java.util.UUID

@Repository
interface CustomerNotificationRepository : JpaRepository<CustomerNotification, UUID> {
    fun findByOrderId(orderId: UUID): List<CustomerNotification>
}
