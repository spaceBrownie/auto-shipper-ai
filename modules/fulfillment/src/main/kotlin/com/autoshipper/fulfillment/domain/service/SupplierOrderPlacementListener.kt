package com.autoshipper.fulfillment.domain.service

import com.autoshipper.fulfillment.domain.OrderStatus
import com.autoshipper.fulfillment.domain.supplier.SupplierOrderAdapter
import com.autoshipper.fulfillment.domain.supplier.SupplierOrderRequest
import com.autoshipper.fulfillment.domain.supplier.SupplierOrderResult
import com.autoshipper.fulfillment.persistence.OrderRepository
import com.autoshipper.fulfillment.persistence.SupplierProductMappingRepository
import com.autoshipper.shared.events.OrderConfirmed
import io.micrometer.core.instrument.MeterRegistry
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

@Component
class SupplierOrderPlacementListener(
    private val orderRepository: OrderRepository,
    private val supplierProductMappingRepository: SupplierProductMappingRepository,
    private val supplierOrderAdapters: List<SupplierOrderAdapter>,
    private val meterRegistry: MeterRegistry
) {
    private val logger = LoggerFactory.getLogger(SupplierOrderPlacementListener::class.java)

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    fun onOrderConfirmed(event: OrderConfirmed) {
        val orderId = event.orderId.value
        val order = orderRepository.findById(orderId).orElse(null)
        if (order == null) {
            logger.error("Order {} not found for supplier order placement", orderId)
            return
        }

        // Status guard
        if (order.status != OrderStatus.CONFIRMED) {
            logger.warn("Order {} is not CONFIRMED (status: {}), skipping supplier placement", orderId, order.status)
            return
        }

        // Idempotency check
        if (order.supplierOrderId != null) {
            logger.warn("Order {} already has supplier order ID {}, skipping duplicate placement", orderId, order.supplierOrderId)
            return
        }

        // Resolve supplier product mapping
        val mapping = supplierProductMappingRepository.findBySkuId(order.skuId)
        if (mapping == null) {
            logger.error("No supplier product mapping found for SKU {} on order {}", order.skuId, orderId)
            order.updateStatus(OrderStatus.FAILED)
            order.failureReason = "No supplier product mapping found for SKU"
            orderRepository.save(order)
            meterRegistry.counter("supplier.order.placed", "supplier", "unknown", "outcome", "failure").increment()
            return
        }

        // Find matching adapter
        val adapter = supplierOrderAdapters.find { it.supplierName() == mapping.supplier }
        if (adapter == null) {
            logger.error("No adapter found for supplier {} on order {}", mapping.supplier, orderId)
            order.updateStatus(OrderStatus.FAILED)
            order.failureReason = "No adapter found for supplier ${mapping.supplier}"
            orderRepository.save(order)
            meterRegistry.counter("supplier.order.placed", "supplier", mapping.supplier, "outcome", "failure").increment()
            return
        }

        // Validate shipping address
        val addr = order.shippingAddress
        if (addr.customerName.isNullOrBlank() && addr.address.isNullOrBlank() && addr.zip.isNullOrBlank()) {
            logger.error("Order {} has no shipping address, cannot place supplier order", orderId)
            order.updateStatus(OrderStatus.FAILED)
            order.failureReason = "INVALID_ADDRESS"
            orderRepository.save(order)
            meterRegistry.counter("supplier.order.placed", "supplier", mapping.supplier, "outcome", "failure").increment()
            return
        }

        // Build request
        val request = SupplierOrderRequest(
            orderNumber = orderId.toString(),
            customerName = addr.customerName ?: "",
            address = addr.address ?: "",
            city = addr.city ?: "",
            province = addr.province ?: "",
            country = addr.country ?: "",
            countryCode = addr.countryCode ?: "",
            zip = addr.zip ?: "",
            phone = addr.phone ?: "",
            supplierVariantId = mapping.supplierVariantId,
            quantity = order.quantity
        )

        // Place order — let RestClientException propagate to Resilience4j @Retry/@CircuitBreaker,
        // then catch here after retries are exhausted
        val timer = meterRegistry.timer("supplier.order.placement.duration", "supplier", mapping.supplier)
        val result = try {
            timer.recordCallable { adapter.placeOrder(request) }!!
        } catch (e: Exception) {
            logger.error("Supplier order placement failed for order {} after retries: {}", orderId, e.message)
            order.updateStatus(OrderStatus.FAILED)
            order.failureReason = "NETWORK_ERROR"
            orderRepository.save(order)
            meterRegistry.counter("supplier.order.placed", "supplier", mapping.supplier, "outcome", "failure").increment()
            return
        }

        when (result) {
            is SupplierOrderResult.Success -> {
                order.supplierOrderId = result.supplierOrderId
                orderRepository.save(order)
                logger.info("Supplier order placed for order {}: supplierOrderId={}", orderId, result.supplierOrderId)
                meterRegistry.counter("supplier.order.placed", "supplier", mapping.supplier, "outcome", "success").increment()
            }
            is SupplierOrderResult.Failure -> {
                order.updateStatus(OrderStatus.FAILED)
                order.failureReason = result.reason.name
                orderRepository.save(order)
                logger.error("Supplier order failed for order {}: reason={}, message={}", orderId, result.reason, result.message)
                meterRegistry.counter("supplier.order.placed", "supplier", mapping.supplier, "outcome", "failure").increment()
            }
        }
    }
}
