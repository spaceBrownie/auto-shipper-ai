package com.autoshipper.fulfillment

import com.autoshipper.catalog.domain.Sku
import com.autoshipper.catalog.domain.SkuState
import com.autoshipper.catalog.persistence.SkuRepository
import com.autoshipper.fulfillment.proxy.inventory.InventoryChecker
import com.autoshipper.vendor.domain.Vendor
import com.autoshipper.vendor.domain.VendorActivationChecklist
import com.autoshipper.vendor.domain.VendorStatus
import com.autoshipper.vendor.persistence.VendorRepository
import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.`when`
import org.mockito.kotlin.any
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.http.MediaType
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import java.util.UUID

/**
 * Integration tests for the full order lifecycle via REST endpoints.
 *
 * NOT @Transactional — OrderService.markDelivered() publishes OrderFulfilled via
 * @TransactionalEventListener(AFTER_COMMIT) which triggers OrderEventListener.
 *
 * Tests the create -> confirm -> ship -> deliver flow and verifies that delivery
 * triggers the OrderFulfilled event chain (capital_order_records creation).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class OrderTransitionIntegrationTest {

    @Autowired lateinit var mockMvc: MockMvc
    @Autowired lateinit var objectMapper: ObjectMapper
    @Autowired lateinit var skuRepository: SkuRepository
    @Autowired lateinit var vendorRepository: VendorRepository
    @Autowired lateinit var jdbcTemplate: JdbcTemplate

    @MockBean
    lateinit var inventoryChecker: InventoryChecker

    @BeforeEach
    fun setup() {
        `when`(inventoryChecker.isAvailable(any())).thenReturn(true)
    }

    @AfterEach
    fun cleanup() {
        jdbcTemplate.execute(
            "TRUNCATE TABLE capital_order_records, reserve_accounts, orders, vendor_sku_assignments, vendors, platform_listings, sku_state_history, skus CASCADE"
        )
    }

    private fun createListedSku(): Sku {
        val sku = skuRepository.save(Sku(name = "Order Test SKU", category = "Electronics"))
        sku.applyTransition(SkuState.ValidationPending)
        sku.applyTransition(SkuState.CostGating)
        sku.applyTransition(SkuState.StressTesting)
        sku.applyTransition(SkuState.Listed)
        return skuRepository.save(sku)
    }

    private fun createActiveVendor(): Vendor {
        return vendorRepository.save(
            Vendor(
                name = "Order Test Vendor",
                contactEmail = "order-test@vendor.com",
                status = VendorStatus.ACTIVE.name,
                checklist = VendorActivationChecklist(
                    slaConfirmed = true,
                    defectRateDocumented = true,
                    scalabilityConfirmed = true,
                    fulfillmentTimesConfirmed = true,
                    refundPolicyConfirmed = true
                )
            )
        )
    }

    private fun createOrder(skuId: UUID, vendorId: UUID): String {
        val customerId = UUID.randomUUID()
        val idempotencyKey = "idem-${UUID.randomUUID()}"

        val result = mockMvc.perform(
            post("/api/orders")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {
                        "skuId": "$skuId",
                        "vendorId": "$vendorId",
                        "customerId": "$customerId",
                        "totalAmount": "99.99",
                        "totalCurrency": "USD",
                        "paymentIntentId": "pi_test_${UUID.randomUUID()}",
                        "idempotencyKey": "$idempotencyKey"
                    }
                """.trimIndent())
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andReturn()

        val body = objectMapper.readTree(result.response.contentAsString)
        return body["id"].asText()
    }

    @Test
    fun `full order lifecycle - create, confirm, ship, deliver`() {
        // Arrange
        val sku = createListedSku()
        val vendor = createActiveVendor()

        // Create order
        val orderId = createOrder(sku.id, vendor.id)

        // Confirm
        mockMvc.perform(post("/api/orders/$orderId/confirm"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("CONFIRMED"))

        // Ship
        mockMvc.perform(
            post("/api/orders/$orderId/ship")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"trackingNumber":"TRK123","carrier":"UPS"}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("SHIPPED"))
            .andExpect(jsonPath("$.trackingNumber").value("TRK123"))
            .andExpect(jsonPath("$.carrier").value("UPS"))

        // Deliver
        mockMvc.perform(post("/api/orders/$orderId/deliver"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("DELIVERED"))

        // Wait for AFTER_COMMIT listener (OrderEventListener) to fire
        Thread.sleep(1000)

        // Verify OrderEventListener created a capital_order_records row
        val capitalRecordCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM capital_order_records WHERE order_id = ?::uuid",
            Long::class.java,
            orderId
        )
        assertEquals(1L, capitalRecordCount,
            "OrderEventListener should create a capital_order_records row after delivery")
    }

    @Test
    fun `invalid transition - confirm on DELIVERED order returns 400`() {
        // Arrange: walk order through full lifecycle
        val sku = createListedSku()
        val vendor = createActiveVendor()
        val orderId = createOrder(sku.id, vendor.id)

        // Walk to DELIVERED
        mockMvc.perform(post("/api/orders/$orderId/confirm"))
            .andExpect(status().isOk)
        mockMvc.perform(
            post("/api/orders/$orderId/ship")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"trackingNumber":"TRK999","carrier":"FedEx"}""")
        )
            .andExpect(status().isOk)
        mockMvc.perform(post("/api/orders/$orderId/deliver"))
            .andExpect(status().isOk)

        // Act: try to confirm a DELIVERED order — invalid transition
        mockMvc.perform(post("/api/orders/$orderId/confirm"))
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `invalid transition - ship on PENDING order returns 400`() {
        // Arrange
        val sku = createListedSku()
        val vendor = createActiveVendor()
        val orderId = createOrder(sku.id, vendor.id)

        // Act: try to ship a PENDING order (must be CONFIRMED first)
        mockMvc.perform(
            post("/api/orders/$orderId/ship")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"trackingNumber":"TRK-BAD","carrier":"USPS"}""")
        )
            .andExpect(status().isBadRequest)
    }

    @Test
    fun `invalid transition - deliver on PENDING order returns 400`() {
        // Arrange
        val sku = createListedSku()
        val vendor = createActiveVendor()
        val orderId = createOrder(sku.id, vendor.id)

        // Act: try to deliver a PENDING order (must be SHIPPED first)
        mockMvc.perform(post("/api/orders/$orderId/deliver"))
            .andExpect(status().isBadRequest)
    }
}
