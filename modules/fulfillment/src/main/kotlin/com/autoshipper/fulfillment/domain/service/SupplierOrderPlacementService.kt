package com.autoshipper.fulfillment.domain.service

import com.autoshipper.fulfillment.domain.OrderStatus
import com.autoshipper.fulfillment.persistence.OrderRepository
import com.autoshipper.fulfillment.proxy.supplier.SupplierOrderAdapter
import com.autoshipper.fulfillment.proxy.supplier.SupplierOrderRequest
import com.autoshipper.fulfillment.proxy.supplier.SupplierOrderResult
import com.autoshipper.fulfillment.proxy.supplier.SupplierProductMappingResolver
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

@Service
class SupplierOrderPlacementService(
    private val orderRepository: OrderRepository,
    private val supplierOrderAdapter: SupplierOrderAdapter,
    private val supplierProductMappingResolver: SupplierProductMappingResolver
) {
    private val logger = LoggerFactory.getLogger(SupplierOrderPlacementService::class.java)

    fun placeSupplierOrder(orderId: UUID) {
        val order = orderRepository.findById(orderId)
            .orElseThrow { IllegalArgumentException("Order $orderId not found") }

        // Idempotency: skip if supplier order already placed
        if (order.supplierOrderId != null) {
            logger.info("Order {} already has supplier order {}, skipping", orderId, order.supplierOrderId)
            return
        }

        // Guard: only place supplier orders for CONFIRMED orders
        if (order.status != OrderStatus.CONFIRMED) {
            logger.info("Order {} is in status {}, not CONFIRMED — skipping supplier placement", orderId, order.status)
            return
        }

        // Resolve supplier variant mapping
        val mapping = supplierProductMappingResolver.resolve(order.skuId)
        if (mapping == null) {
            logger.warn("No supplier product mapping found for SKU {} on order {}", order.skuId, orderId)
            order.updateStatus(OrderStatus.FAILED)
            order.failureReason = "No supplier product mapping found for SKU ${order.skuId}"
            orderRepository.save(order)
            return
        }

        val request = SupplierOrderRequest(
            orderNumber = orderId.toString(),
            shippingAddress = order.shippingAddress,
            supplierProductId = mapping.supplierProductId,
            supplierVariantId = mapping.supplierVariantId,
            quantity = order.quantity,
            warehouseCountryCode = mapping.warehouseCountryCode
        )

        val result = supplierOrderAdapter.placeOrder(request)

        when (result) {
            is SupplierOrderResult.Success -> {
                order.supplierOrderId = result.supplierOrderId
                orderRepository.save(order)
                logger.info("Supplier order {} placed for order {}", result.supplierOrderId, orderId)
            }
            is SupplierOrderResult.Failure -> {
                order.updateStatus(OrderStatus.FAILED)
                order.failureReason = result.reason
                orderRepository.save(order)
                logger.warn("Supplier order placement failed for order {}: {}", orderId, result.reason)
            }
        }
    }
}
