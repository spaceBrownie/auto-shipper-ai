package com.autoshipper.vendor

import com.fasterxml.jackson.databind.ObjectMapper
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.*
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class VendorEndpointIntegrationTest {

    companion object {
        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:16-alpine")
            .withDatabaseName("autoshipper_test")
            .withUsername("autoshipper")
            .withPassword("autoshipper")

        @JvmStatic
        @DynamicPropertySource
        fun configureDatasource(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.flyway.url", postgres::getJdbcUrl)
            registry.add("spring.flyway.user", postgres::getUsername)
            registry.add("spring.flyway.password", postgres::getPassword)
        }
    }

    @Autowired
    lateinit var mockMvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    private fun registerVendor(name: String = "Test Vendor", email: String = "test@vendor.com"): String {
        val result = mockMvc.perform(
            post("/api/vendors")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"$name","contactEmail":"$email"}""")
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andReturn()

        val body = objectMapper.readTree(result.response.contentAsString)
        return body["id"].asText()
    }

    @Test
    fun `POST vendors registers a new vendor in PENDING status`() {
        mockMvc.perform(
            post("/api/vendors")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"name":"Acme Corp","contactEmail":"acme@test.com"}""")
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.name").value("Acme Corp"))
            .andExpect(jsonPath("$.contactEmail").value("acme@test.com"))
            .andExpect(jsonPath("$.status").value("PENDING"))
            .andExpect(jsonPath("$.checklist.slaConfirmed").value(false))
    }

    @Test
    fun `GET vendors by id returns vendor details`() {
        val vendorId = registerVendor()

        mockMvc.perform(get("/api/vendors/$vendorId"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.id").value(vendorId))
            .andExpect(jsonPath("$.name").value("Test Vendor"))
    }

    @Test
    fun `GET vendors by id returns 404 for unknown id`() {
        mockMvc.perform(get("/api/vendors/00000000-0000-0000-0000-000000000000"))
            .andExpect(status().isNotFound)
    }

    @Test
    fun `PATCH checklist updates checklist items`() {
        val vendorId = registerVendor()

        mockMvc.perform(
            patch("/api/vendors/$vendorId/checklist")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"slaConfirmed":true,"defectRateDocumented":true}""")
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.checklist.slaConfirmed").value(true))
            .andExpect(jsonPath("$.checklist.defectRateDocumented").value(true))
            .andExpect(jsonPath("$.checklist.scalabilityConfirmed").value(false))
    }

    @Test
    fun `POST activate fails when checklist incomplete`() {
        val vendorId = registerVendor()

        mockMvc.perform(post("/api/vendors/$vendorId/activate"))
            .andExpect(status().isUnprocessableEntity)
            .andExpect(jsonPath("$.error").value("VENDOR_NOT_ACTIVATED"))
    }

    @Test
    fun `POST activate succeeds when checklist complete`() {
        val vendorId = registerVendor()

        mockMvc.perform(
            patch("/api/vendors/$vendorId/checklist")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{
                    "slaConfirmed":true,
                    "defectRateDocumented":true,
                    "scalabilityConfirmed":true,
                    "fulfillmentTimesConfirmed":true,
                    "refundPolicyConfirmed":true
                }"""
                )
        )
            .andExpect(status().isOk)

        mockMvc.perform(post("/api/vendors/$vendorId/activate"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("ACTIVE"))
    }

    @Test
    fun `POST score computes reliability score`() {
        val vendorId = registerVendor()

        mockMvc.perform(
            post("/api/vendors/$vendorId/score")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """{
                    "onTimeRate": 95,
                    "defectRate": 2,
                    "avgResponseTimeHours": 8
                }"""
                )
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.overallScore").isNumber)
            .andExpect(jsonPath("$.onTimeRate").value(95))
            .andExpect(jsonPath("$.breachCount").value(0))
    }

    @Test
    fun `POST fulfillments records a fulfillment outcome`() {
        val vendorId = registerVendor()

        mockMvc.perform(
            post("/api/vendors/$vendorId/fulfillments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"orderId":"00000000-0000-0000-0000-000000000001","isViolation":false}""")
        )
            .andExpect(status().isCreated)
    }

    @Test
    fun `POST fulfillments records a violation`() {
        val vendorId = registerVendor()

        mockMvc.perform(
            post("/api/vendors/$vendorId/fulfillments")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"orderId":"00000000-0000-0000-0000-000000000002","isViolation":true,"violationType":"LATE_SHIPMENT"}""")
        )
            .andExpect(status().isCreated)
    }

    @Test
    fun `GET vendors lists all vendors`() {
        registerVendor("Vendor A", "a@test.com")
        registerVendor("Vendor B", "b@test.com")

        mockMvc.perform(get("/api/vendors"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.length()").value(org.hamcrest.Matchers.greaterThanOrEqualTo(2)))
    }
}
