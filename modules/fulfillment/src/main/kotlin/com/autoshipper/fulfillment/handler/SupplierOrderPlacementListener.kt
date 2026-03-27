package com.autoshipper.fulfillment.handler

import com.autoshipper.fulfillment.domain.OrderStatus
import com.autoshipper.fulfillment.persistence.OrderRepository
import com.autoshipper.fulfillment.proxy.platform.SupplierProductMappingResolver
import com.autoshipper.fulfillment.proxy.supplier.SupplierOrderAdapter
import com.autoshipper.fulfillment.proxy.supplier.SupplierOrderProduct
import com.autoshipper.fulfillment.proxy.supplier.SupplierOrderRequest
import com.autoshipper.shared.events.OrderConfirmed
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

/**
 * Listens for OrderConfirmed events and places purchase orders with CJ Dropshipping.
 *
 * CLAUDE.md #6: @TransactionalEventListener(AFTER_COMMIT) + @Transactional(REQUIRES_NEW)
 * ensures this handler runs in its own transaction after the confirming transaction commits.
 */
@Component
class SupplierOrderPlacementListener(
    private val orderRepository: OrderRepository,
    private val supplierOrderAdapter: SupplierOrderAdapter,
    private val supplierProductMappingResolver: SupplierProductMappingResolver,
    @Value("\${cj-dropshipping.order.logistic-name:}") private val logisticName: String,
    @Value("\${cj-dropshipping.order.from-country-code:}") private val fromCountryCode: String
) {
    private val logger = LoggerFactory.getLogger(SupplierOrderPlacementListener::class.java)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun onOrderConfirmed(event: OrderConfirmed) {
        val order = orderRepository.findById(event.orderId.value).orElse(null) ?: return

        // Idempotency guard (NFR-2)
        if (order.supplierOrderId != null) {
            logger.info("Order {} already has supplier order ID, skipping", order.id)
            return
        }

        // Resolve CJ variant ID
        val vid = supplierProductMappingResolver.resolveSupplierVariantId(order.skuId, "CJ_DROPSHIPPING")
        if (vid == null) {
            order.updateStatus(OrderStatus.FAILED)
            order.failureReason = "No CJ product mapping for SKU ${order.skuId}"
            orderRepository.save(order)
            logger.error("No CJ product mapping for SKU {} on order {}", order.skuId, order.id)
            return
        }

        val shippingAddress = order.shippingAddress
        if (shippingAddress == null) {
            order.updateStatus(OrderStatus.FAILED)
            order.failureReason = "Order ${order.id} has no shipping address"
            orderRepository.save(order)
            logger.error("No shipping address for order {}", order.id)
            return
        }

        val request = SupplierOrderRequest(
            orderNumber = order.id.toString(),
            shippingAddress = shippingAddress,
            products = listOf(SupplierOrderProduct(vid = vid, quantity = order.quantity)),
            logisticName = logisticName,
            fromCountryCode = fromCountryCode
        )

        try {
            val result = supplierOrderAdapter.placeOrder(request)
            order.supplierOrderId = result.supplierOrderId
            orderRepository.save(order)
            logger.info("CJ order placed: internal={}, cj={}", order.id, result.supplierOrderId)
        } catch (e: Exception) {
            logger.error("CJ order failed for order {}: {}", order.id, e.message, e)
            order.updateStatus(OrderStatus.FAILED)
            order.failureReason = e.message ?: "Unknown CJ API error"
            orderRepository.save(order)
        }
    }
}
