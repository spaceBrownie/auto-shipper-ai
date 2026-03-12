package com.autoshipper.capital.listener

import com.autoshipper.capital.domain.CapitalOrderRecord
import com.autoshipper.capital.domain.service.OrderAmountProvider
import com.autoshipper.capital.domain.service.ReserveAccountService
import com.autoshipper.capital.persistence.CapitalOrderRecordRepository
import com.autoshipper.shared.events.OrderFulfilled
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

@Component
class OrderEventListener(
    private val orderRecordRepository: CapitalOrderRecordRepository,
    private val reserveAccountService: ReserveAccountService,
    private val orderAmountProvider: OrderAmountProvider
) {
    private val logger = LoggerFactory.getLogger(OrderEventListener::class.java)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun onOrderFulfilled(event: OrderFulfilled) {
        if (orderRecordRepository.findByOrderId(event.orderId.value) != null) {
            logger.debug("Order {} already recorded, skipping", event.orderId)
            return
        }

        val orderAmount = orderAmountProvider.getOrderAmount(event.orderId.value)
        if (orderAmount == null) {
            logger.warn(
                "Could not find order amount for order {}, skipping capital recording",
                event.orderId
            )
            return
        }

        val record = CapitalOrderRecord(
            orderId = event.orderId.value,
            skuId = event.skuId.value,
            totalAmount = orderAmount.normalizedAmount,
            currency = orderAmount.currency,
            status = "DELIVERED"
        )
        orderRecordRepository.save(record)

        reserveAccountService.creditFromOrder(event.orderId, orderAmount)

        logger.info(
            "Recorded order {} (amount: {}) for SKU {} and credited reserve",
            event.orderId, orderAmount, event.skuId
        )
    }
}
