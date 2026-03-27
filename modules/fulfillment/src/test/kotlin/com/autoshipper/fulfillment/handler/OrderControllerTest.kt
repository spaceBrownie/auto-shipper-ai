package com.autoshipper.fulfillment.handler

import com.autoshipper.fulfillment.domain.Order
import com.autoshipper.fulfillment.domain.OrderStatus
import com.autoshipper.fulfillment.domain.ShipmentDetails
import com.autoshipper.fulfillment.domain.service.CreateOrderCommand
import com.autoshipper.fulfillment.domain.service.OrderService
import com.autoshipper.fulfillment.handler.dto.ShipOrderRequest
import com.autoshipper.shared.money.Currency
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith
import org.mockito.Mock
import org.mockito.junit.jupiter.MockitoExtension
import org.mockito.kotlin.*
import org.springframework.http.MediaType
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

@ExtendWith(MockitoExtension::class)
class OrderControllerTest {

    @Mock
    lateinit var orderService: OrderService

    private lateinit var mockMvc: MockMvc
    private val objectMapper = jacksonObjectMapper()
        .registerModule(JavaTimeModule())

    private val skuId = UUID.randomUUID()
    private val vendorId = UUID.randomUUID()
    private val customerId = UUID.randomUUID()

    @BeforeEach
    fun setUp() {
        val controller = OrderController(orderService)
        val converter = MappingJackson2HttpMessageConverter(objectMapper)
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
            .setMessageConverters(converter)
            .build()
    }

    private fun testOrder(status: OrderStatus = OrderStatus.PENDING): Order = Order(
        idempotencyKey = "idem-key-1",
        skuId = skuId,
        vendorId = vendorId,
        customerId = customerId,
        totalAmount = BigDecimal("49.9900"),
        totalCurrency = Currency.USD,
        quantity = 1,
        paymentIntentId = "pi_test_abc123",
        status = status,
        shipmentDetails = ShipmentDetails(
            trackingNumber = "1Z999",
            carrier = "UPS",
            estimatedDelivery = Instant.parse("2026-03-10T12:00:00Z"),
            lastKnownLocation = "In transit",
            delayDetected = false
        )
    )

    @Test
    fun `POST creates order and returns 201`() {
        val order = testOrder()
        whenever(orderService.create(any<CreateOrderCommand>())).thenReturn(Pair(order, true))

        val requestBody = mapOf(
            "skuId" to skuId.toString(),
            "vendorId" to vendorId.toString(),
            "customerId" to customerId.toString(),
            "totalAmount" to "49.99",
            "totalCurrency" to "USD",
            "paymentIntentId" to "pi_test_abc123",
            "idempotencyKey" to "idem-key-1"
        )

        mockMvc.perform(
            post("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(requestBody))
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.id").value(order.id.toString()))
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.skuId").value(skuId.toString()))
    }

    @Test
    fun `POST with existing idempotency key returns 200`() {
        val order = testOrder()
        whenever(orderService.create(any<CreateOrderCommand>())).thenReturn(Pair(order, false))

        val requestBody = mapOf(
            "skuId" to skuId.toString(),
            "vendorId" to vendorId.toString(),
            "customerId" to customerId.toString(),
            "totalAmount" to "49.99",
            "totalCurrency" to "USD",
            "paymentIntentId" to "pi_test_abc123",
            "idempotencyKey" to "idem-key-1"
        )

        mockMvc.perform(
            post("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(requestBody))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(order.id.toString()))
    }

    @Test
    fun `GET returns order by id`() {
        val order = testOrder()
        whenever(orderService.findById(order.id)).thenReturn(order)

        mockMvc.perform(get("/api/orders/{id}", order.id.toString()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(order.id.toString()))
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.vendorId").value(vendorId.toString()))
    }

    @Test
    fun `GET returns 404 for unknown order`() {
        val unknownId = UUID.randomUUID()
        whenever(orderService.findById(unknownId)).thenReturn(null)

        mockMvc.perform(get("/api/orders/{id}", unknownId.toString()))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `GET tracking returns tracking details`() {
        val order = testOrder(OrderStatus.SHIPPED)
        whenever(orderService.findById(order.id)).thenReturn(order)

        mockMvc.perform(get("/api/orders/{id}/tracking", order.id.toString()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.orderId").value(order.id.toString()))
            .andExpect(jsonPath("$.trackingNumber").value("1Z999"))
            .andExpect(jsonPath("$.carrier").value("UPS"))
            .andExpect(jsonPath("$.delayDetected").value(false))
            .andExpect(jsonPath("$.status").value("SHIPPED"))
    }

    @Test
    fun `GET tracking returns 404 for unknown order`() {
        val unknownId = UUID.randomUUID()
        whenever(orderService.findById(unknownId)).thenReturn(null)

        mockMvc.perform(get("/api/orders/{id}/tracking", unknownId.toString()))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `POST confirm returns CONFIRMED order`() {
        val order = testOrder(OrderStatus.CONFIRMED)
        whenever(orderService.routeToVendor(order.id)).thenReturn(order)

        mockMvc.perform(post("/api/orders/{id}/confirm", order.id.toString()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(order.id.toString()))
            .andExpect(jsonPath("$.status").value("CONFIRMED"))
    }

    @Test
    fun `POST ship returns SHIPPED order with tracking`() {
        val order = testOrder(OrderStatus.SHIPPED)
        whenever(orderService.markShipped(order.id, "TRK123456", "UPS")).thenReturn(order)

        val requestBody = ShipOrderRequest(trackingNumber = "TRK123456", carrier = "UPS")

        mockMvc.perform(
            post("/api/orders/{id}/ship", order.id.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(requestBody))
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(order.id.toString()))
            .andExpect(jsonPath("$.status").value("SHIPPED"))
            .andExpect(jsonPath("$.trackingNumber").value("1Z999"))
            .andExpect(jsonPath("$.carrier").value("UPS"))
    }

    @Test
    fun `POST deliver returns DELIVERED order`() {
        val order = testOrder(OrderStatus.DELIVERED)
        whenever(orderService.markDelivered(order.id)).thenReturn(order)

        mockMvc.perform(post("/api/orders/{id}/deliver", order.id.toString()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(order.id.toString()))
            .andExpect(jsonPath("$.status").value("DELIVERED"))
    }

    @Test
    fun `POST confirm returns 400 for invalid transition`() {
        val orderId = UUID.randomUUID()
        whenever(orderService.routeToVendor(orderId)).thenThrow(
            IllegalArgumentException("Cannot route order $orderId: expected PENDING but was SHIPPED")
        )

        mockMvc.perform(post("/api/orders/{id}/confirm", orderId.toString()))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `POST deliver returns 404 for unknown order`() {
        val unknownId = UUID.randomUUID()
        whenever(orderService.markDelivered(unknownId)).thenThrow(
            IllegalArgumentException("Order $unknownId not found")
        )

        mockMvc.perform(post("/api/orders/{id}/deliver", unknownId.toString()))
            .andExpect(status().isNotFound)
    }
}
