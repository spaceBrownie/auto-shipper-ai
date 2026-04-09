package com.autoshipper.fulfillment.domain.service

import com.autoshipper.fulfillment.domain.OrderStatus
import com.autoshipper.fulfillment.handler.webhook.CjTrackingReceivedEvent
import com.autoshipper.fulfillment.persistence.OrderRepository
import com.autoshipper.fulfillment.proxy.carrier.CjCarrierMapper
import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener
import java.util.UUID

@Component
class CjTrackingProcessingService(
    private val orderService: OrderService,
    private val orderRepository: OrderRepository,
    private val objectMapper: ObjectMapper
) {
    private val logger = LoggerFactory.getLogger(CjTrackingProcessingService::class.java)

    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun onCjTrackingReceived(event: CjTrackingReceivedEvent) {
        val root = objectMapper.readTree(event.rawPayload)
        val params = root.get("params")

        if (params == null) {
            logger.warn("CJ tracking event {} has no params node, skipping", event.dedupKey)
            return
        }

        val orderId = params.get("orderId")?.let { if (!it.isNull) it.asText() else null }
        val trackingNumber = params.get("trackingNumber")?.let { if (!it.isNull) it.asText() else null }
        val logisticName = params.get("logisticName")?.let { if (!it.isNull) it.asText() else null }

        if (orderId == null || trackingNumber == null) {
            logger.warn("CJ tracking event {} missing orderId or trackingNumber, skipping", event.dedupKey)
            return
        }

        val uuid = try {
            UUID.fromString(orderId)
        } catch (e: IllegalArgumentException) {
            logger.warn("CJ tracking event {} has invalid UUID orderId '{}', skipping", event.dedupKey, orderId)
            return
        }

        val order = orderRepository.findById(uuid).orElse(null)
        if (order == null) {
            logger.warn("CJ tracking event {} references unknown order {}, skipping", event.dedupKey, uuid)
            return
        }

        if (order.status != OrderStatus.CONFIRMED) {
            logger.warn(
                "CJ tracking event {} for order {} — expected CONFIRMED but was {}, skipping",
                event.dedupKey, uuid, order.status
            )
            return
        }

        val carrier = CjCarrierMapper.normalize(logisticName ?: "unknown")

        try {
            orderService.markShipped(uuid, trackingNumber, carrier)
            logger.info("CJ tracking processed: order {} marked SHIPPED with tracking {} via {}", uuid, trackingNumber, carrier)
        } catch (e: Exception) {
            // Dedup record is already committed — if this fails, CJ retries will get "already_processed"
            // and the order will be stuck in CONFIRMED. Log at ERROR for alerting/manual intervention.
            logger.error(
                "CJ tracking processing FAILED for order {} (dedupKey={}): {}. Manual intervention required.",
                uuid, event.dedupKey, e.message, e
            )
        }
    }
}
