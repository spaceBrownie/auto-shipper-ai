package com.autoshipper.fulfillment.handler

import com.autoshipper.fulfillment.domain.service.SupplierOrderPlacementService
import com.autoshipper.shared.events.OrderConfirmed
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

@Component
class SupplierOrderPlacementListener(
    private val supplierOrderPlacementService: SupplierOrderPlacementService
) {
    private val logger = LoggerFactory.getLogger(SupplierOrderPlacementListener::class.java)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun onOrderConfirmed(event: OrderConfirmed) {
        logger.info("OrderConfirmed received for order {}, placing supplier order", event.orderId)
        supplierOrderPlacementService.placeSupplierOrder(event.orderId.value)
    }
}
