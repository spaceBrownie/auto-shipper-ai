package com.autoshipper.fulfillment.domain.service

import com.autoshipper.fulfillment.domain.Order
import com.autoshipper.fulfillment.domain.OrderStatus
import com.autoshipper.fulfillment.domain.ShipmentDetails
import com.autoshipper.fulfillment.persistence.OrderRepository
import com.autoshipper.fulfillment.proxy.inventory.InventoryChecker
import com.autoshipper.shared.events.OrderConfirmed
import com.autoshipper.shared.events.OrderFulfilled
import com.autoshipper.shared.events.OrderShipped
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class OrderService(
    private val orderRepository: OrderRepository,
    private val inventoryChecker: InventoryChecker,
    private val eventPublisher: ApplicationEventPublisher
) {
    private val logger = LoggerFactory.getLogger(OrderService::class.java)

    /**
     * Creates a new order. Idempotency: if an order with the same idempotency key
     * already exists, returns it without creating a duplicate.
     *
     * @return a Pair of (Order, created) where created is true if a new order was created,
     *         false if an existing order was returned due to idempotency.
     */
    @Transactional
    fun create(request: CreateOrderCommand): Pair<Order, Boolean> {
        val existing = orderRepository.findByIdempotencyKey(request.idempotencyKey)
        if (existing != null) {
            logger.info("Duplicate order request detected for idempotency key {}", request.idempotencyKey)
            return Pair(existing, false)
        }

        require(inventoryChecker.isAvailable(request.skuId)) {
            "SKU ${request.skuId} is not available in inventory"
        }

        val order = Order(
            idempotencyKey = request.idempotencyKey,
            skuId = request.skuId,
            vendorId = request.vendorId,
            customerId = request.customerId,
            totalAmount = request.totalAmount.normalizedAmount,
            totalCurrency = request.totalAmount.currency,
            paymentIntentId = request.paymentIntentId,
            quantity = request.quantity,
            shippingAddress = request.shippingAddress,
            status = OrderStatus.PENDING
        )

        val saved = orderRepository.save(order)
        logger.info("Created order {} for SKU {} (idempotency key: {})", saved.id, saved.skuId, saved.idempotencyKey)
        return Pair(saved, true)
    }

    /**
     * Finds an order by its ID.
     */
    fun findById(orderId: UUID): Order? =
        orderRepository.findById(orderId).orElse(null)

    /**
     * Routes a PENDING order to the vendor by transitioning it to CONFIRMED.
     */
    @Transactional
    fun routeToVendor(orderId: UUID): Order {
        val order = orderRepository.findById(orderId)
            .orElseThrow { IllegalArgumentException("Order $orderId not found") }
        require(order.status == OrderStatus.PENDING) {
            "Cannot route order $orderId: expected PENDING but was ${order.status}"
        }

        order.updateStatus(OrderStatus.CONFIRMED)
        val saved = orderRepository.save(order)

        eventPublisher.publishEvent(
            OrderConfirmed(
                orderId = order.orderId(),
                skuId = order.skuId()
            )
        )
        logger.info("Order {} routed to vendor {}, status -> CONFIRMED, OrderConfirmed event published", orderId, order.vendorId)
        return saved
    }

    /**
     * Marks a CONFIRMED order as SHIPPED with tracking information.
     */
    @Transactional
    fun markShipped(orderId: UUID, trackingNumber: String, carrier: String): Order {
        val order = orderRepository.findById(orderId)
            .orElseThrow { IllegalArgumentException("Order $orderId not found") }
        require(order.status == OrderStatus.CONFIRMED) {
            "Cannot mark order $orderId as shipped: expected CONFIRMED but was ${order.status}"
        }

        order.updateStatus(OrderStatus.SHIPPED)
        order.shipmentDetails = ShipmentDetails(
            trackingNumber = trackingNumber,
            carrier = carrier,
            estimatedDelivery = order.shipmentDetails.estimatedDelivery,
            lastKnownLocation = order.shipmentDetails.lastKnownLocation,
            delayDetected = false
        )

        val saved = orderRepository.save(order)

        eventPublisher.publishEvent(
            OrderShipped(
                orderId = order.orderId(),
                skuId = order.skuId(),
                trackingNumber = trackingNumber,
                carrier = carrier
            )
        )
        logger.info("Order {} marked SHIPPED with tracking {} via {}, OrderShipped event published", orderId, trackingNumber, carrier)
        return saved
    }

    /**
     * Sets channel metadata (channel name, channel order ID, channel order number) on an existing order.
     * Used by webhook processing to associate internal orders with their external channel source.
     */
    @Transactional
    fun setChannelMetadata(orderId: UUID, channel: String, channelOrderId: String, channelOrderNumber: String): Order {
        val order = orderRepository.findById(orderId)
            .orElseThrow { IllegalArgumentException("Order $orderId not found") }
        order.channel = channel
        order.channelOrderId = channelOrderId
        order.channelOrderNumber = channelOrderNumber
        order.updatedAt = Instant.now()
        return orderRepository.save(order)
    }

    /**
     * Marks an order as DELIVERED and publishes an OrderFulfilled event.
     */
    @Transactional
    fun markDelivered(orderId: UUID): Order {
        val order = orderRepository.findById(orderId)
            .orElseThrow { IllegalArgumentException("Order $orderId not found") }
        require(order.status == OrderStatus.SHIPPED) {
            "Cannot mark order $orderId as delivered: expected SHIPPED but was ${order.status}"
        }

        order.updateStatus(OrderStatus.DELIVERED)
        val saved = orderRepository.save(order)

        eventPublisher.publishEvent(
            OrderFulfilled(
                orderId = order.orderId(),
                skuId = order.skuId()
            )
        )
        logger.info("Order {} marked DELIVERED, OrderFulfilled event published", orderId)
        return saved
    }
}
