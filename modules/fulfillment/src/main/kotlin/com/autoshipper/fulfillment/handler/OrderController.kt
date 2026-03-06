package com.autoshipper.fulfillment.handler

import com.autoshipper.fulfillment.domain.Order
import com.autoshipper.fulfillment.domain.service.CreateOrderCommand
import com.autoshipper.fulfillment.domain.service.OrderService
import com.autoshipper.fulfillment.handler.dto.CreateOrderRequest
import com.autoshipper.fulfillment.handler.dto.OrderResponse
import com.autoshipper.fulfillment.handler.dto.TrackingResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
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
            idempotencyKey = request.idempotencyKey
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
        status = status.name,
        trackingNumber = shipmentDetails.trackingNumber,
        carrier = shipmentDetails.carrier,
        estimatedDelivery = shipmentDetails.estimatedDelivery?.toString(),
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
