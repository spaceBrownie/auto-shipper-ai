package com.autoshipper.fulfillment.handler

import com.autoshipper.fulfillment.domain.Order
import com.autoshipper.fulfillment.domain.service.CreateOrderCommand
import com.autoshipper.fulfillment.domain.service.OrderService
import com.autoshipper.fulfillment.handler.dto.CreateOrderRequest
import com.autoshipper.fulfillment.handler.dto.OrderResponse
import com.autoshipper.fulfillment.handler.dto.ShipOrderRequest
import com.autoshipper.fulfillment.handler.dto.TrackingResponse
import com.autoshipper.shared.money.Currency
import com.autoshipper.shared.money.Money
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.math.BigDecimal
import java.util.UUID

@RestController
@RequestMapping("/api/orders")
class OrderController(private val orderService: OrderService) {

    @PostMapping
    fun createOrder(@RequestBody request: CreateOrderRequest): ResponseEntity<OrderResponse> {
        val command = CreateOrderCommand(
            skuId = UUID.fromString(request.skuId),
            vendorId = UUID.fromString(request.vendorId),
            customerId = UUID.fromString(request.customerId),
            totalAmount = Money.of(BigDecimal(request.totalAmount), Currency.valueOf(request.totalCurrency)),
            paymentIntentId = request.paymentIntentId,
            idempotencyKey = request.idempotencyKey,
            quantity = request.quantity
        )
        val (order, created) = orderService.create(command)
        val status = if (created) HttpStatus.CREATED else HttpStatus.OK
        return ResponseEntity.status(status).body(order.toResponse())
    }

    @GetMapping("/{id}")
    fun getOrder(@PathVariable id: String): ResponseEntity<OrderResponse> {
        val order = orderService.findById(UUID.fromString(id))
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(order.toResponse())
    }

    @PostMapping("/{id}/confirm")
    fun confirmOrder(@PathVariable id: String): ResponseEntity<OrderResponse> {
        return try {
            val order = orderService.routeToVendor(UUID.fromString(id))
            ResponseEntity.ok(order.toResponse())
        } catch (e: IllegalArgumentException) {
            if (e.message?.contains("not found") == true) {
                ResponseEntity.notFound().build()
            } else {
                ResponseEntity.badRequest().build()
            }
        }
    }

    @PostMapping("/{id}/ship")
    fun shipOrder(
        @PathVariable id: String,
        @RequestBody request: ShipOrderRequest
    ): ResponseEntity<OrderResponse> {
        return try {
            val order = orderService.markShipped(
                UUID.fromString(id),
                request.trackingNumber,
                request.carrier
            )
            ResponseEntity.ok(order.toResponse())
        } catch (e: IllegalArgumentException) {
            if (e.message?.contains("not found") == true) {
                ResponseEntity.notFound().build()
            } else {
                ResponseEntity.badRequest().build()
            }
        }
    }

    @PostMapping("/{id}/deliver")
    fun deliverOrder(@PathVariable id: String): ResponseEntity<OrderResponse> {
        return try {
            val order = orderService.markDelivered(UUID.fromString(id))
            ResponseEntity.ok(order.toResponse())
        } catch (e: IllegalArgumentException) {
            if (e.message?.contains("not found") == true) {
                ResponseEntity.notFound().build()
            } else {
                ResponseEntity.badRequest().build()
            }
        }
    }

    @GetMapping("/{id}/tracking")
    fun getTracking(@PathVariable id: String): ResponseEntity<TrackingResponse> {
        val order = orderService.findById(UUID.fromString(id))
            ?: return ResponseEntity.notFound().build()
        return ResponseEntity.ok(order.toTrackingResponse())
    }

    private fun Order.toResponse(): OrderResponse = OrderResponse(
        id = id.toString(),
        skuId = skuId.toString(),
        vendorId = vendorId.toString(),
        customerId = customerId.toString(),
        totalAmount = totalAmount.toPlainString(),
        totalCurrency = totalCurrency.name,
        status = status.name,
        trackingNumber = shipmentDetails.trackingNumber,
        carrier = shipmentDetails.carrier,
        estimatedDelivery = shipmentDetails.estimatedDelivery?.toString(),
        channel = channel,
        channelOrderId = channelOrderId,
        channelOrderNumber = channelOrderNumber,
        createdAt = createdAt.toString(),
        updatedAt = updatedAt.toString()
    )

    private fun Order.toTrackingResponse(): TrackingResponse = TrackingResponse(
        orderId = id.toString(),
        trackingNumber = shipmentDetails.trackingNumber,
        carrier = shipmentDetails.carrier,
        estimatedDelivery = shipmentDetails.estimatedDelivery?.toString(),
        lastKnownLocation = shipmentDetails.lastKnownLocation,
        delayDetected = shipmentDetails.delayDetected,
        status = status.name
    )
}
